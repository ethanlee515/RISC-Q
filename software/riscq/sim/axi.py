"""Shared single-word host AXI BFM used by the SC and AOD co-simulations."""
from collections import deque
from cocotb.triggers import RisingEdge, FallingEdge

AXI_TIMEOUT = 100_000

class AxiMaster:
    """Single-beat AXI4 master (len=0, size=2, INCR, strb=0xF); AW/W handshakes independent,
    B/R awaited. Proven mechanics from the old cosim driver."""

    def __init__(self, dut):
        self.dut = dut
        self.clk = dut.clk

    async def _await_ready(self, valid, ready, what: str):
        valid.value = 1
        for _ in range(AXI_TIMEOUT):
            await RisingEdge(self.clk)
            if ready.value == 1:
                valid.value = 0
                return
        raise RuntimeError(f"AXI {what} handshake timeout after {AXI_TIMEOUT} cycles")

    def _addr_phase(self, prefix: str, addr: int):
        d = self.dut
        getattr(d, f"io_axi_{prefix}_payload_addr").value = addr & 0xFFFFFFFF
        for field, value in (("id", 0), ("region", 0), ("len", 0), ("size", 2),
                             ("burst", 1), ("lock", 0), ("cache", 0), ("qos", 0), ("prot", 0)):
            getattr(d, f"io_axi_{prefix}_payload_{field}").value = value

    async def write_word(self, addr: int, data: int) -> None:
        d = self.dut
        self._addr_phase("aw", addr)
        d.io_axi_w_payload_data.value = data & 0xFFFFFFFF
        d.io_axi_w_payload_strb.value = 0xF
        d.io_axi_w_payload_last.value = 1
        d.io_axi_b_ready.value = 1
        await self._await_ready(d.io_axi_aw_valid, d.io_axi_aw_ready, "AW")
        await self._await_ready(d.io_axi_w_valid, d.io_axi_w_ready, "W")
        for _ in range(AXI_TIMEOUT):
            if d.io_axi_b_valid.value == 1:
                break
            await RisingEdge(self.clk)
        else:
            raise RuntimeError(f"AXI B response timeout at addr {addr:#x}")
        response = int(d.io_axi_b_payload_resp.value)
        d.io_axi_b_ready.value = 0
        if response != 0:
            raise RuntimeError(f"AXI write denied at {addr:#x}")

    async def read_word(self, addr: int) -> int:
        d = self.dut
        self._addr_phase("ar", addr)
        d.io_axi_r_ready.value = 1
        await self._await_ready(d.io_axi_ar_valid, d.io_axi_ar_ready, "AR")
        for _ in range(AXI_TIMEOUT):
            if d.io_axi_r_valid.value == 1:
                break
            await RisingEdge(self.clk)
        else:
            raise RuntimeError(f"AXI R response timeout at addr {addr:#x}")
        response = int(d.io_axi_r_payload_resp.value)
        data = int(d.io_axi_r_payload_data.value) & 0xFFFFFFFF
        d.io_axi_r_ready.value = 0
        if response != 0:
            raise RuntimeError(f"AXI read denied at {addr:#x}")
        return data



def _int_or_zero(sig) -> int:
    """A port value as an int, or 0 while it is still X (pre-reset)."""
    try:
        return int(sig.value)
    except ValueError:
        return 0


async def _host_window_slave(dut, st) -> None:
    """Model of `S_AXI_HP0_FPD`: accept the host window's write-only AXI master, answer `b`, and
    store every beat into `st.host_mem` at `addr - HOST_BASE` under its `wstrb`.
    Single-beat writes with one ID, so `aw` and `w` pair in issue order.

    Timing: runs on the falling edge and *commits* that cycle's `ready` there — AXI holds `valid`
    and its payload until the handshake, so "valid now && I drive ready now" IS the transfer at the
    coming rising edge. It **sleeps on `aw_valid`** whenever the funnel is idle, so the per-cycle
    python costs nothing between shots (the same reason `_adc_stimulus` free-runs in coarse chunks).

    `ready` is held high rather than randomised: back-pressure on this port is signed off in
    SpinalSim by `HostWindowFunnelSim`/`HostWindowCpuSim` (random ready stalls, golden memory), and
    stalling here would only slow the co-sim down.
    """
    clk = dut.clk
    awq: deque = deque()
    wq: deque = deque()
    pending_b = 0
    dut.io_hostMem_b_payload_id.value = 0
    dut.io_hostMem_b_payload_resp.value = 0
    dut.io_hostMem_aw_ready.value = 1
    dut.io_hostMem_w_ready.value = 1
    dut.io_hostMem_b_valid.value = 0
    while True:
        if not (_int_or_zero(dut.io_hostMem_aw_valid) or _int_or_zero(dut.io_hostMem_w_valid)
                or pending_b or awq or wq):
            await RisingEdge(dut.io_hostMem_aw_valid)   # idle: wake only when a write starts
            continue
        await FallingEdge(clk)
        if _int_or_zero(dut.io_hostMem_aw_valid):
            awq.append(_int_or_zero(dut.io_hostMem_aw_payload_addr))
        if _int_or_zero(dut.io_hostMem_w_valid):
            wq.append((_int_or_zero(dut.io_hostMem_w_payload_data),
                       _int_or_zero(dut.io_hostMem_w_payload_strb)))
        while awq and wq:
            addr = awq.popleft()
            data, strb = wq.popleft()
            st.host_write(addr, data, strb)
            pending_b += 1
        if pending_b and _int_or_zero(dut.io_hostMem_b_ready):
            dut.io_hostMem_b_valid.value = 1
            pending_b -= 1
        else:
            dut.io_hostMem_b_valid.value = 0

