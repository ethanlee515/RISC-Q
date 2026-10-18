"""Native-output AodSoc map and setup helpers. Read the manifest emitted with RTL.

This shares the C compiler/startup and Driver seam with superconducting RISC-Q;
it does not use its channel DSL, RFDC configuration or SocSpec.
"""
from __future__ import annotations

import json
import time
from pathlib import Path
from types import SimpleNamespace

from riscq.build import Image, compile_c
from riscq.driver import Driver


class AodMap:
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
                  "RQ_AOD": c["aod"], "RQ_HOSTWIN_BASE": c["host_window"], "RQ_AOD_TONES": m["tones"], "RQ_AOD_AXES": m["axes"],
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

    def load(self, image: Image):
        self.stop()
        if image.entry != 0x80000000 or len(image.data) > self.map.mem_bytes:
            raise ValueError("image does not fit this AodSoc")
        # Full words, including the final padded word: setup/CPU-RAM paths are word-oriented.
        data = image.data + bytes((-len(image.data)) % 4)
        for i in range(0, len(data), 4):
            self.driver.write32(self.map.host["program"] + i, int.from_bytes(data[i:i + 4], "little"))

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
