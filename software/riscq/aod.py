"""Native-output AodSoc map and setup helpers. Read the manifest emitted with RTL.

This shares the C compiler/startup and Driver seam with superconducting RISC-Q;
it does not use its channel DSL, RFDC configuration or SocSpec.
"""
from __future__ import annotations

import json
import math
import operator
import time
from pathlib import Path
from types import SimpleNamespace

from riscq.build import Image, Program, compile_c
from riscq.driver import Driver


class AodMap:
    HOSTWIN_BYTES = 1 << 24

    def __init__(self, manifest: dict):
        if manifest.get("kind") != "aod" or manifest.get("version") != 1:
            raise ValueError("expected AodSoc manifest version 1")
        self.manifest = manifest
        self.params = SimpleNamespace(name=manifest["name"], with_mul=manifest["with_mul"])
        self.mem_bytes = manifest["mem_depth"] * 4
        self.host = manifest["host"]
        self.setup = manifest["setup"]
        self.cpu = manifest["cpu"]
        self.registers = manifest["registers"]

    @classmethod
    def load(cls, path: str | Path) -> AodMap:
        return cls(json.loads(Path(path).read_text()))

    def gen_header(self, core: int = 0) -> str:
        if core != 0:
            raise ValueError("AodSoc has one CPU")
        c, r, m = self.cpu, self.registers, self.manifest
        values = {"RQ_MEM_BASE": 0x80000000, "RQ_MEM_BYTES": self.mem_bytes,
                  "RQ_CTRL_TIME": c["time"], "RQ_CTRL_TIME_CMP": c["time_cmp"],
                  "RQ_CTRL_WAIT_TIME_CMP": c["wait_time_cmp"], "RQ_CTRL_DONE": c["done"],
                  "RQ_HOSTWIN": c["host_window"], "RQ_AOD": c["aod"],
                  "RQ_HOSTWIN_BASE": c["host_window"], "RQ_AOD_TONES": m["tones"],
                  "RQ_AOD_AXES": m["axes"],
                  "RQ_AOD_QUEUE_DEPTH": m["queue_depth"], "RQ_AOD_LINK_PIPE": m["link_pipe"],
                  "RQ_AOD_MINIMUM_ENQUEUE_LEAD": m["minimum_enqueue_lead"],
                  "RQ_AOD_MOVE_STEPS": m["move_steps"], "RQ_AOD_LEAD_TIME": m["lead_time"],
                  "RQ_AOD_RATIO_MIN": m["ratio_min"], "RQ_AOD_RATIO_MAX": m["ratio_max"],
                  "RQ_AOD_DEC_MAX": m["dec_max"]}
        values.update({f"RQ_AOD_{k.upper()}": v for k, v in r.items() if isinstance(v, int)})
        lines = ["/* Generated from AodSoc RTL manifest. */", "#ifndef RISCQ_MAP_H", "#define RISCQ_MAP_H"]
        lines += [f"#define {name} 0x{value:x}" for name, value in values.items()]
        # Explicit addresses from the hardware decoder: no duplicated tone-layout formula.
        lines += ["#ifndef __ASSEMBLER__", "#include <stdint.h>",
                  f"static const uint32_t rq_aod_tones[{m['axes']}][{m['tones']}][4] = {{"]
        lines += ["  {" + ", ".join("{" + ", ".join(hex(v) for v in tone) + "}" for tone in axis) + "},"
                  for axis in r["tone"]]
        lines += ["};", "static const uint32_t rq_aod_channels[] = {" +
                  ", ".join(hex(v) for v in r["channel"]) + "};", "#endif", "#endif", ""]
        return "\n".join(lines)

    def gen_linker(self) -> str:
        # Shared unified-RAM layout/startup contract, including the 1-KiB stack reserve.
        from riscq.map import ram_linker
        return ram_linker(self.mem_bytes)

    def compile(self, source: str) -> Image:
        header = (Path(__file__).resolve().parent.parent / "fw" / "riscq_aod.h").read_text()
        return compile_c(source, self, extra_headers={"riscq_aod.h": header})

    def frequency(self, hz: float) -> int:
        """Signed int32 bit pattern of a Q0.32 phase increment; |f| < fs/2."""
        rate = self.manifest["dsp_hz"]
        if not math.isfinite(hz) or not -rate / 2 < hz < rate / 2:
            raise ValueError("frequency/displacement must be strictly inside complex Nyquist")
        return _int32(round(hz / rate * (1 << 32)))

    def phase(self, turns: float) -> int:
        """Initial phase in turns, wrapped to one turn and encoded for an int32 kernel."""
        if not math.isfinite(turns):
            raise ValueError("phase must be finite")
        return _int32(round((turns % 1) * (1 << 32)))

    def amplitude(self, fraction: float) -> int:
        """Q1.15 amplitude multiplier, not a promise of absolute optical/DAC power."""
        if not math.isfinite(fraction) or not 0 <= fraction <= 1:
            raise ValueError("amplitude fraction must be in [0, 1]")
        return round(fraction * 32768)

    def start_spacing(self, ratio: int) -> int:
        ratio = operator.index(ratio)
        m = self.manifest
        if not m["ratio_min"] <= ratio <= m["ratio_max"]:
            raise ValueError("ratio outside this build's supported range")
        return m["move_steps"] * ratio + m["lead_time"] + m["axes"] * (m["tones"] + 8) + 64


def _int32(word: int) -> int:
    return (int(word) + (1 << 31)) % (1 << 32) - (1 << 31)


def read_profile(path: str | Path) -> list[int]:
    """Read the packed hexadecimal rows of an AOD .mem profile."""
    words = []
    for line in Path(path).read_text().splitlines():
        words.extend(int(word, 16) for word in line.split("//", 1)[0].split())
    return words


def compile_kernel(k, mapping: AodMap, **bindings) -> Program:
    """Reuse RISC-Q's typed Python kernel/IR/C compiler with AOD intrinsics.

    Bind integer constants and Array sizes as usual. Unbound integers are host
    parameters. RF ParamTables and cross-core operations do not belong to AodSoc.
    """
    from riscq.lang.kernel import Kernel, KernelCompileError
    from riscq.lang import compile_kernel as shared_compile
    # Validate peripheral-specific annotations before the shared resolver tries
    # to look up superconducting channels/event sinks in an AOD map.
    import inspect
    from riscq.lang import Array
    if not isinstance(k, Kernel):
        raise KernelCompileError("compile_kernel needs an @kernel function")
    if any(t not in (int, Array) for name, t in inspect.get_annotations(k.fn, eval_str=True).items()
           if name != "return"):
        raise KernelCompileError("AOD kernels support int and Array parameters")
    header = (Path(__file__).resolve().parent.parent / "fw" / "riscq_aod.h").read_text()
    return shared_compile(k, mapping, include=[("riscq_aod.h", header)],
                          runtime_header="riscq_aod.h", **bindings)


def _kernel_only(*args):
    raise RuntimeError("AOD intrinsics execute in compiled @kernel firmware")


# By-name calls are lowered by the shared frontend to these C-header functions.
# Importing them documents the kernel API and catches accidental host execution.
aod_init = aod_rate = aod_axis = aod_tone = aod_fire_at = aod_start_spacing = _kernel_only
now = wait_until = _kernel_only


class AodSession:
    """Host setup in word transactions; CPU reset and AOD flush are distinct.

    Addresses are relative to the AXI aperture, as in the existing Driver API.
    Coefficient tables must be loaded only while stopped and flushed.
    """
    def __init__(self, driver: Driver, mapping: AodMap):
        self.driver, self.map = driver, mapping

    def _wait(self, predicate, timeout: float):
        deadline = time.monotonic() + timeout
        while not predicate():
            if time.monotonic() >= deadline:
                raise TimeoutError("AodSoc host wait timed out")
            time.sleep(0.001)

    def stop(self, timeout: float = 5):
        d, m = self.driver, self.map
        d.write32(m.host["control"] + m.host["cpu_reset"], 1)
        self._wait(lambda: d.read32(m.host["control"] + m.host["stopped"]) & 1, timeout)
        # Successful response means the AOD reset has completed. It preserves RAM/tables/time.
        d.write32(m.host["setup"] + m.setup["reset"], 1)

    def load(self, image: Image | Program):
        self.stop()
        self.program = image if isinstance(image, Program) else None
        image = image.image if self.program is not None else image
        if image.entry != 0x80000000 or len(image.data) > self.map.mem_bytes:
            raise ValueError("image does not fit this AodSoc")
        # Full words, including the final padded word: setup/CPU-RAM paths are word-oriented.
        data = image.data + bytes((-len(image.data)) % 4)
        for i in range(0, len(data), 4):
            self.driver.write32(self.map.host["program"] + i, int.from_bytes(data[i:i + 4], "little"))

    def _symbol_address(self, name: str, index: int = 0) -> int:
        if getattr(self, "program", None) is None:
            raise RuntimeError("load a compiled Program before accessing named parameters/results")
        addr, size = self.program.image.symbols[name]
        if index < 0 or 4 * (index + 1) > size:
            raise ValueError("symbol index outside its allocated storage")
        offset = addr - self.program.image.entry + 4 * index
        if offset < 0 or offset + 4 > self.map.mem_bytes:
            raise ValueError("symbol is outside program RAM")
        return self.map.host["program"] + offset

    def parameters(self, **values):
        self._require_stopped()
        updates = []
        for name, value in values.items():
            if getattr(self, "program", None) is None or name not in self.program.params:
                raise ValueError(f"{name!r} is not a runtime kernel parameter")
            word = operator.index(value)
            if not -(1 << 31) <= word < 1 << 32:
                raise ValueError("parameter must fit an int32/uint32 word")
            updates.append((self._symbol_address(name), word & 0xffffffff))
        for address, word in updates:
            self.driver.write32(address, word)

    def result(self, name: str) -> list[int]:
        if getattr(self, "program", None) is None or name not in self.program.arrays:
            raise ValueError(f"{name!r} is not a kernel result array")
        if name in self.program.host_arrays:
            offset, count = self.program.host_arrays[name]
            raw = self.driver.read_host(offset, count * 4)
            return [int.from_bytes(raw[i:i+4], "little", signed=True) for i in range(0, len(raw), 4)]
        return [_int32(self.driver.read32(self._symbol_address(name, i)))
                for i in range(self.program.arrays[name])]

    def _require_stopped(self):
        m = self.map
        if not self.driver.read32(m.host["control"] + m.host["stopped"]) & 1:
            raise RuntimeError("stop/flush the SoC before changing coefficients")
        if self.driver.read32(m.host["setup"] + m.setup["state"]) & 0x7 != 0x4:
            raise RuntimeError("AOD must be idle before changing coefficients")

    def profile(self, table: str, rows):
        """Upload packed profile rows: 96-bit trajectory or 72-bit envelope."""
        if table not in ("trajectory", "envelope"):
            raise ValueError("unknown profile table")
        rows = list(rows)
        width = 96 if table == "trajectory" else 72
        if len(rows) != self.map.manifest["segments"] or any(not 0 <= v < 1 << width for v in rows):
            raise ValueError(f"expected one {width}-bit word per profile row")
        self._require_stopped()
        base = self.map.host["setup"] + self.map.setup[table]
        for row, word in enumerate(rows):
            for beat in range(3):
                self.driver.write32(base + row * 16 + beat * 4, (word >> (beat * 32)) & 0xffffffff)

    def gains(self, axis: int, words):
        words = list(words)
        if not 0 <= axis < self.map.manifest["axes"] or len(words) != self.map.manifest["gain_entries"]:
            raise ValueError("invalid gain table geometry")
        if any(not 0 <= v < 1 << 32 for v in words):
            raise ValueError("gain entries must be uint32 words")
        self._require_stopped()
        base = self.map.host["setup"] + self.map.setup["gain"] + axis * 0x400
        for i, word in enumerate(words):
            self.driver.write32(base + i * 4, word)

    def start(self):
        self._require_stopped()
        d, m = self.driver, self.map
        base = d.host_base
        if base % 4 or not 0 <= base <= (1 << 40) - (1 << 24):
            raise ValueError("host result buffer must fit the 40-bit PS address space")
        d.write32(m.host["control"] + m.host["host_base_lo"], base & 0xffffffff)
        d.write32(m.host["control"] + m.host["host_base_hi"], (base >> 32) | (1 << 31))
        self.driver.write32(self.map.host["control"] + self.map.host["cpu_reset"], 0)

    def wait_done(self, timeout: float = 5):
        d, m = self.driver, self.map
        self._wait(lambda: (d.read32(m.host["control"] + m.host["done"]) & 1) and
                   (d.read32(m.host["setup"] + m.setup["state"]) & 0x7 == 0x4), timeout)

    def diagnostics(self) -> int:
        return self.driver.read32(self.map.host["setup"] + self.map.setup["diagnostics"])
