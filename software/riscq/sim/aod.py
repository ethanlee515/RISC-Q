"""Interactive native-output AodSoc simulation; no board/IP address required."""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import importlib.metadata
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time

import numpy as np

from riscq.driver.cosim import CosimDriver, _to_bytes
from riscq.sim.server import _tail, stop

ROOT = Path(__file__).resolve().parents[3]


def preflight() -> dict[str, str]:
    """Check the notebook toolchain before starting a potentially long build."""
    tools = ("java", "verilator", "make", "g++", "riscv64-unknown-elf-clang",
             "riscv64-unknown-elf-objcopy", "riscv64-unknown-elf-nm", "riscv64-unknown-elf-objdump")
    found = {name: shutil.which(name) for name in tools}
    missing = [name for name, path in found.items() if path is None]
    if missing:
        raise RuntimeError("missing native tools on PATH: " + ", ".join(missing) +
                           "; see docs/software/aod.md for setup")
    for name in ("cocotb", "numpy", "Pyro5"):
        try:
            found[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            raise RuntimeError("sync the notebook environment: uv sync --project software --extra notebook --locked") from None
    if found["cocotb"] != "1.8.1":
        raise RuntimeError("this notebook backend is tested with cocotb 1.8.1; use the locked notebook environment")
    found["python"] = sys.executable
    return found


@dataclass
class Capture:
    """Native samples: iq[clock, axis, I/Q] int16, valid[clock, axis].

    I is cos (low 16 bits); Q is sin (high 16 bits). t0 is the exact first
    sample's shared DSP timestamp. All clocks, including RF-off gaps, are kept.
    """
    t0: int
    iq: np.ndarray
    valid: np.ndarray

    @property
    def times(self):
        return (np.arange(len(self.iq), dtype=np.uint64) + self.t0) & 0xffffffff

    @property
    def complex(self):
        return self.iq[..., 0].astype(float) + 1j * self.iq[..., 1].astype(float)


class _Extras:
    def __init__(self, proxy):
        self._proxy = proxy

    def get_params(self) -> str:
        return self._proxy.get_params()

    def advance(self, clocks: int):
        self._proxy.advance(int(clocks))

    def capture_arm(self, clocks: int) -> int:
        """Capture this many DSP clocks starting on the next CPU reset release.

        Arm while stopped, before AodSession.start(). Host setup/interactive
        pauses do not consume this window. CPU initialization and RF-off gaps do.
        """
        return self._proxy.capture_arm(int(clocks))

    def capture_get(self, handle: int) -> Capture:
        t0, n, axes, iq, valid = self._proxy.capture_get(int(handle))
        return Capture(int(t0), np.frombuffer(_to_bytes(iq), dtype="<i2").reshape(n, axes, 2).copy(),
                       np.frombuffer(_to_bytes(valid), dtype=np.uint8).reshape(n, axes).astype(bool))

    def shutdown(self):
        self._proxy.shutdown()


class AodSimDriver(CosimDriver):
    def __init__(self, uri):
        super().__init__(uri)
        self.sim = _Extras(self._proxy)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        stop(self)


def _source_digest() -> str:
    h = hashlib.sha256()
    for directory in ("src/riscq", "aod/src", "aod/resources", "timing/src"):
        for path in sorted((ROOT / directory).rglob("*")):
            if path.is_file():
                h.update(path.relative_to(ROOT).as_posix().encode())
                h.update(path.read_bytes())
    h.update((ROOT / "build.mill").read_bytes())
    return h.hexdigest()


def _ensure_rtl(build_dir: Path) -> Path:
    rtl = build_dir / "rtl"
    digest = _source_digest()
    stamp = rtl / ".source.sha"
    if not (stamp.exists() and stamp.read_text() == digest and (rtl / "AodSoc.v").exists()
            and (rtl / "aod-soc.json").exists()):
        cmd = [str(ROOT / "mill"), "--no-server", "runMain", "riscq.soc.GenAodSoc", str(rtl), "--sim"]
        result = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
        (build_dir / "elaboration.log").write_text(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError("AOD RTL generation failed; see " + str(build_dir / "elaboration.log"))
        stamp.write_text(digest)
    return rtl


def start(build_dir: str | Path | None = None) -> AodSimDriver:
    preflight()
    build_dir = Path(build_dir or ROOT / "software/build/aod-notebook").resolve()
    build_dir.mkdir(parents=True, exist_ok=True)
    uri_file = build_dir / "cosim.uri"
    uri_file.unlink(missing_ok=True)
    log_path = build_dir / "cosim.log"
    with log_path.open("wb") as log:
        proc = subprocess.Popen([sys.executable, "-m", "riscq.sim.aod", str(build_dir)],
                                stdout=log, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 600
    try:
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise RuntimeError(f"AOD simulator exited ({proc.returncode}):\n{_tail(log_path)}")
            if uri_file.exists() and uri_file.read_text().startswith("PYRO:"):
                driver = AodSimDriver(uri_file.read_text().strip())
                driver._proc = proc
                return driver
            time.sleep(0.1)
        raise TimeoutError(f"AOD simulation startup exceeded 600 seconds:\n{_tail(log_path)}")
    except BaseException:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill(); proc.wait()
        raise


def _main(build_dir: Path):
    from cocotb.runner import get_runner
    # Embedded Python gets sys.path, not setuptools' editable-install meta finder.
    sys.path.insert(0, str(ROOT / "software"))
    rtl = _ensure_rtl(build_dir)
    sim = build_dir / "sim_build"
    sim.mkdir(exist_ok=True)
    h = hashlib.sha256()
    for path in sorted(rtl.glob("*")):
        if path.suffix in (".v", ".bin"):
            h.update(path.name.encode()); h.update(path.read_bytes())
    h.update(importlib.metadata.version("cocotb").encode())
    # The binary links to this environment's cocotb VPI library via an absolute
    # rpath; moving/recreating the environment must invalidate that executable.
    h.update(sys.executable.encode())
    h.update(sys.version.encode())
    h.update(subprocess.check_output(["verilator", "--version"]))
    digest = h.hexdigest()
    stamp = sim / ".build.sha"
    runner = get_runner("verilator")
    if not (stamp.exists() and stamp.read_text() == digest and (sim / "AodSoc").exists()):
        runner.build(verilog_sources=[rtl / "AodSoc.v"], hdl_toplevel="AodSoc", build_dir=sim,
                     always=True, build_args=["-Wno-fatal", "--x-initial", "0", "--threads", "1"])
        stamp.write_text(digest)
    for path in rtl.glob("*.bin"):
        shutil.copy(path, sim)
    runner.test(hdl_toplevel="AodSoc", hdl_toplevel_lang="verilog", test_module="riscq.sim.aod_bench",
                build_dir=sim, test_dir=sim,
                extra_env={"RISCQ_AOD_MANIFEST": str(rtl / "aod-soc.json"),
                           "RISCQ_AOD_URI": str(build_dir / "cosim.uri")})


if __name__ == "__main__":
    _main(Path(sys.argv[1]).resolve())
