"""Cocotb/Pyro host for AodSoc: all program/setup accesses are front-door AXI.

Only the simulation observation port sampleTime and native output ports are
sampled. No RISC-V instruction emulation or waveform replacement is involved.
"""
from __future__ import annotations
import json
import os
from pathlib import Path
import queue
import threading

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import ClockCycles, FallingEdge, Timer
import numpy as np
import Pyro5.api
import serpent

from riscq.sim.axi import AxiMaster, _host_window_slave

HOST_BASE = 0x840000000


class Request:
    def __init__(self, op, args):
        self.op, self.args = op, args
        self.done = threading.Event()
        self.result = self.error = None


@Pyro5.api.expose
class Server:
    def __init__(self, requests, manifest):
        self.requests, self.manifest = requests, manifest

    def _submit(self, op, *args):
        request = Request(op, args)
        self.requests.put(request)
        if not request.done.wait(120):
            raise RuntimeError(f"AOD simulation did not service {op!r} within 120 s")
        if request.error is not None:
            raise RuntimeError(request.error)
        return request.result

    def get_params(self):
        return self.manifest

    def get_host_base(self):
        return HOST_BASE

    def read32(self, address):
        return self._submit("read32", int(address))

    def write32(self, address, value):
        return self._submit("write32", int(address), int(value))

    def read_block(self, address, size):
        return self._submit("read_block", int(address), int(size))

    def write_block(self, address, data):
        data = serpent.tobytes(data) if isinstance(data, dict) else bytes(data)
        return self._submit("write_block", int(address), data)

    def read_host(self, offset, size):
        return self._submit("read_host", int(offset), int(size))

    def advance(self, clocks):
        return self._submit("advance", int(clocks))

    def capture_arm(self, clocks):
        return self._submit("capture_arm", int(clocks))

    def capture_get(self, handle):
        return self._submit("capture_get", int(handle))

    def shutdown(self):
        return self._submit("shutdown")


class State:
    def __init__(self, manifest):
        self.m = manifest
        self.captures = {}
        self.next_handle = 0
        self.cpu_held = True
        self.host_mem = bytearray(1 << 24)

    def host_write(self, address, data, mask):
        offset = address - HOST_BASE
        if not 0 <= offset <= len(self.host_mem) - 4:
            raise RuntimeError("AOD result write outside the modeled PS buffer")
        for b in range(4):
            if mask >> b & 1:
                self.host_mem[offset + b] = data >> (8 * b) & 255


async def _collect(dut, capture, axes):
    n = capture["clocks"]
    iq = np.zeros((n, axes, 2), dtype="<i2")
    valid = np.zeros((n, axes), dtype=np.uint8)
    t0 = None
    for i in range(n):
        # Half a DSP period after the consuming edge, all registered outputs and
        # the observation timestamp have settled, independently of host clock.
        await FallingEdge(dut.io_dspClk)
        timestamp = int(dut.sampleTime.value)
        if t0 is None:
            t0 = timestamp
        if timestamp != (t0 + i) & 0xffffffff:
            raise RuntimeError("DSP sample timestamp skipped a clock")
        flags = int(dut.io_dac_valid.value)
        for axis, port in enumerate((dut.io_dac_x, dut.io_dac_y)[:axes]):
            raw = int(port.value)
            iq[i, axis, 0] = ((raw & 0xffff) + 0x8000) % 0x10000 - 0x8000
            iq[i, axis, 1] = ((raw >> 16) + 0x8000) % 0x10000 - 0x8000
            valid[i, axis] = flags >> axis & 1
    capture["result"] = (t0, n, axes, iq.tobytes(), valid.tobytes())


def _word(address):
    if address < 0 or address >= 1 << 32 or address % 4:
        raise ValueError("AXI address must be an aligned uint32")


async def _handle(dut, axi, st, op, args):
    if op == "read32":
        _word(args[0]); return await axi.read_word(args[0])
    if op == "write32":
        address, value = args
        _word(address)
        result = await axi.write_word(address, value)
        if address == st.m["host"]["control"] + st.m["host"]["cpu_reset"]:
            held = bool(value & 1)
            if st.cpu_held and not held:
                for capture in st.captures.values():
                    if capture["task"] is None:
                        capture["task"] = cocotb.start_soon(_collect(dut, capture, st.m["axes"]))
            st.cpu_held = held
        return result
    if op in ("read_block", "write_block"):
        address = args[0]
        _word(address)
        n = args[1] if op == "read_block" else len(args[1])
        if not 0 <= n <= 1 << 20:
            raise ValueError("invalid AXI block size")
        if op == "read_block":
            data = bytearray()
            for i in range(0, n, 4):
                data += (await axi.read_word(address + i)).to_bytes(4, "little")
            return bytes(data[:n])
        data = args[1]
        if n % 4:
            raise ValueError("AOD block writes must contain complete words")
        for i in range(0, n, 4):
            await _handle(dut, axi, st, "write32", (address + i, int.from_bytes(data[i:i+4], "little")))
        return None
    if op == "read_host":
        offset, n = args
        if offset < 0 or n < 0 or offset + n > len(st.host_mem):
            raise ValueError("host result range outside buffer")
        # Drain posted writes after CPU DONE; the generic board contract still
        # requires the caller to allow actual DDR completion on real hardware.
        await ClockCycles(dut.clk, 100)
        return bytes(st.host_mem[offset:offset+n])
    if op == "advance":
        if not 0 <= args[0] <= 1_000_000:
            raise ValueError("advance must be 0..1000000 DSP clocks")
        if args[0]:
            await ClockCycles(dut.io_dspClk, args[0])
        return None
    if op == "capture_arm":
        if not st.cpu_held:
            raise RuntimeError("arm a capture while CPU reset is held")
        n = args[0]
        if not 1 <= n <= 1_000_000 or len(st.captures) >= 4:
            raise ValueError("capture limit: 1..1000000 clocks, four outstanding handles")
        st.next_handle += 1
        st.captures[st.next_handle] = dict(clocks=n, task=None, result=None)
        return st.next_handle
    if op == "capture_get":
        capture = st.captures[args[0]]
        if capture["task"] is None:
            raise RuntimeError("release CPU reset to begin the armed capture")
        await capture["task"]
        st.captures.pop(args[0])
        return capture["result"]
    raise ValueError(f"unknown AOD sim operation {op}")


@cocotb.test()
async def aod_server(dut):
    text = Path(os.environ["RISCQ_AOD_MANIFEST"]).read_text()
    st = State(json.loads(text))
    dut.reset.value = 1; dut.io_dspRst.value = 1
    for signal in ("aw", "ar", "w"):
        getattr(dut, f"io_axi_{signal}_valid").value = 0
    dut.io_axi_b_ready.value = 0; dut.io_axi_r_ready.value = 0
    dut.io_hostMem_aw_ready.value = 0; dut.io_hostMem_w_ready.value = 0; dut.io_hostMem_b_valid.value = 0
    cocotb.start_soon(Clock(dut.clk, 10, units="ns").start())
    cocotb.start_soon(Clock(dut.io_dspClk, 2500, units="ps").start())
    cocotb.start_soon(_host_window_slave(dut, st))
    await Timer(200, units="ns")
    dut.reset.value = 0; dut.io_dspRst.value = 0
    await Timer(200, units="ns")
    axi = AxiMaster(dut)
    requests = queue.Queue()
    daemon = Pyro5.api.Daemon(host="127.0.0.1", port=0)
    uri = daemon.register(Server(requests, text), objectId="riscq.aod")
    thread = threading.Thread(target=daemon.requestLoop, daemon=True)
    thread.start()
    uri_file = Path(os.environ["RISCQ_AOD_URI"])
    temporary = uri_file.with_suffix(".tmp")
    temporary.write_text(str(uri)); os.replace(temporary, uri_file)
    try:
        while True:
            try:
                request = requests.get_nowait()
            except queue.Empty:
                await ClockCycles(dut.clk, 100)
                continue
            if request.op == "shutdown":
                request.done.set(); break
            try:
                request.result = await _handle(dut, axi, st, request.op, request.args)
            except Exception as error:
                request.error = f"{type(error).__name__}: {error}"
            request.done.set()
    finally:
        daemon.shutdown(); daemon.close()
