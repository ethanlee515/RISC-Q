# AOD SoC

`riscq.soc.AodSoc` is a separate top-level component: one existing `RiscvSoc`
CPU/RAM block, one paired X/Y `AodTop`, the existing host AXI→TileLink fabric,
and `AodHostAdapter` for setup/reset/status. It has no superconducting pulse,
demodulation, ADC, readout or cross-core hub blocks. The superconducting
`PulseTableSoc` remains available. `aod` still builds independently of the SoC;
the main module now depends on it.

This is the native complex-output SoC, not a board overlay. Its DSP clock target
is **400 MHz**, one complex sample per axis per clock. `dac_x` and `dac_y` retain
AOD's packed `{sin,cos}` output and `dac_valid` axis tags (see
[INTERFACE.md](../../aod/INTERFACE.md)). RFDC packing, interpolation/mixing,
converter latency, Vivado board wiring, synthesis utilization and timing closure
remain to be implemented/measured. Lowering a target clock does not establish
closure for the new datapath or make the existing RFDC settings compatible.

## Address spaces

Host addresses below are offsets into the externally mapped PS AXI aperture,
not ARM physical addresses. Use the emitted `aod-soc.json` for the exact build.
The default CPU RAM is 4096 × 32-bit words (16 KiB); larger power-of-two RAMs
are configurable up to the next host region.

| Host offset | Region |
| --- | --- |
| `0x00000000 .. memDepth*4-1` | CPU instruction/data RAM, read/write, usable while CPU reset is held |
| `0x00100000 .. 0x00100fff` | Host control block |
| `0x00200000 .. 0x0020ffff` | AOD setup/reset/status; relative map in [HOST.md](../../aod/HOST.md) |

| Host control offset | Meaning |
| --- | --- |
| `0x00` | Read/write CPU reset, bit 0; **powers up asserted** |
| `0x04` | Read-only STOPPED, bit 0; synchronized acknowledgment that CPU reset is asserted and runtime command delivery is gated/cleared |
| `0x48` | Host-result buffer physical base, low 32 bits |
| `0x4c` | Base bits 39:32 in bits 7:0, result forwarding enable in bit 31; initially disabled |
| `0x50` | Read-only sticky CPU DONE, bit 0; cleared by CPU reset |

| CPU address | Meaning |
| --- | --- |
| `0x00004000` | Time compare register |
| `0x00004008` | Blocking time-compare read used by `wait_until` |
| `0x00004010` | Set DONE (bit 0) |
| `0x0000bff8` | Shared time, registered CPU-local copy |
| `0x00010000 .. 0x0001ffff` | One posted AOD device; add [AodRegisterMap offsets](../../aod/INTERFACE.md) |
| `0x40000000 .. 0x40ffffff` | Existing 16-MiB write-only host-result window |
| `0x80000000 .. +memDepth*4-1` | Unified program/data RAM and boot vector |

Both axes belong to that **one** posted device. `fire` snapshots all staged
axis/tone fields and the explicit `startTime` together. Full-word CPU stores
are required. There is no CPU-side AOD readback or second event protocol;
minimal diagnostics are host-readable. `withDiagnostics=false` removes diagnostic
packing/input ports; the scheduler's essential checks remain.

The host fabric supports ordinary AXI strobed writes. Its AOD leaf translates
all-byte TileLink PutPartial writes into complete words, drops Spinal's cache
allocation hint for this uncached window, and supplies the full-word mask omitted
by the read-only bridge. Actual partial writes are denied by AOD without changing
tables. The adapter itself owns the setup CDC; that leaf stays in the host clock
domain. RAM loading uses the existing fabric CDC into the DSP clock domain.

## Time, pacing and reset

Shared time increments every DSP clock, wraps at 32 bits and is independent of
per-run CPU reset and AOD reset. Only system DSP reset restarts it. Native AOD
registers each output on a clock edge; the shell supplies that edge's upcoming
time value, so the **first output sample has exactly the programmed start time**.
The CPU reads its existing registered time copy. `wait_until` is the existing
compare-write/blocking-read operation, not a new instruction. Keep timestamps
within the signed 32-bit half-window and satisfy the
[scheduler lead, spacing and capacity rules](../../aod/SCHEDULING.md).

The manifest's `minimum_enqueue_lead` is measured from acceptance at AOD, not
from a C store. Allow for CPU execution, posted-store buffering, fabric and
`linkPipe` latency. There is no universal instruction-count bound for arbitrary
programs. The example uses generous margins and waits after each scheduled
start, so it never fills an arbitrarily long experiment into the finite FIFO.
AOD does not auto-advance its start time.

Setup/run sequence:

1. Write CPU reset = 1; poll STOPPED = 1. This gates runtime delivery and clears
   command-pipeline valid bits. It does **not** flush work already accepted by AOD.
2. Write 1 to `setup + 0x04`. The response follows AOD's eight-clock reset/flush;
   it clears queued/active movements, staging scalars and sticky status, forces
   output to zero, and preserves coefficient RAMs/program RAM/time. CDC pointers
   are not reset per run. Do not issue setup uploads before this stop/flush.
3. Load program RAM and trajectory/envelope/gain tables with aligned 32-bit host
   transactions. Each successful coefficient response means the actual memory
   write has drained. The host can safely backpressure; the CPU remains stopped.
4. Configure the result-buffer base/enable if used. Write CPU reset = 0 to boot.
   Firmware initializes every movement field (including disabled tones), fires
   timestamped jobs and paces the queue. Staging updates affect the next job.
5. Firmware waits for its final movement's conservative drain time, then returns;
   shared startup sets DONE. Host completion checks DONE **and** AOD idle. CPU DONE
   alone does not imply that a scheduled future movement has finished.
6. Stop/flush again before the next shot. Tables need not be reloaded. To abort
   pending/active jobs, use the same stop/flush sequence.

The result path uses RISC-Q's existing `HostWindowFunnel` and a DSP→host FIFO.
Like superconducting RISC-Q, its writes are posted beyond CPU DONE: allow them
to drain before reading results or changing buffer bases (see
[HostWindow.md](HostWindow.md)). Keep the same allocated buffer across runs.
System host/DSP resets initialize both sides of CDC FIFOs; independently resetting
one clock domain during operation is not a run reset protocol.

Outside active pulses both axes drive **zero**. This does not hold atoms at an
endpoint, repeat a trajectory, or provide gapless B→B holding. Experiments must
provide appropriate confinement/transfer during RF-off intervals. Each movement
uses the explicitly programmed `phase0`.

## Software and validation

Generate the default 32-tone, two-axis SoC and its matching manifest:

```sh
./mill --no-server runMain riscq.soc.GenAodSoc out/aod-soc-rtl
# Omit diagnostic readback while retaining essential scheduling guards:
./mill --no-server runMain riscq.soc.GenAodSoc out/aod-soc-minimal --minimal
```

`riscq.aod.AodMap` consumes that manifest and reuses `compile_c`, RISC-V startup,
soft arithmetic support and the unified-RAM linker layout. `riscq_aod.h` supplies
raw-code movement helpers and existing `now`/`wait_until`. The [AOD notebook workflow](../software/aod.md) now reuses the shared Python
kernel compiler with an AOD runtime header, named parameters/results and native
X/Y simulation capture. Superconducting ParamTables/calibration channels and the
board server/RFDC bring-up remain separate backends.

```python
from pathlib import Path
from riscq.aod import AodMap, AodSession

m = AodMap.load("out/aod-soc-rtl/aod-soc.json")
image = m.compile(Path("software/examples/aod_moves.c").read_text())
s = AodSession(driver, m)  # existing Driver seam; driver.host_base is its PS result buffer
s.load(image)              # stops/flushes first, writes program words
# Optional custom tables before starting:
# s.profile("trajectory", trajectory_rows)  # 96-bit packed rows
# s.profile("envelope", envelope_rows)      # 72-bit packed rows
# s.gains(axis, gain_words)                 # 32-bit entries
s.start()                  # configures the result buffer and releases CPU reset
s.wait_done()
status = s.diagnostics()    # zero if reporting was omitted
s.stop()
```

Supply a Driver for this AXI aperture with a stable result buffer; this example
does not turn the superconducting board driver into an AOD overlay loader.
Packaged minimum-jerk profiles are elaboration defaults. Gain RAM powers up to
unity; custom host uploads replace setup state until changed/system reload.

With the RISC-V LLVM toolchain on PATH, run:

```sh
./mill --no-server runMain riscq.soc.sim.AodSocCpuSim
./mill --no-server aod.test
cd software
PYTHONPATH=. uv run --no-project --with pytest python -m pytest tests/test_aod.py tests/test_build.py -q
```

`AodSocCpuSim` loads every instruction and coefficient through **host AXI** and
runs actual C firmware on the sole RISC-V runtime master, without a test bus tap
or memory backdoor. With a two-tone/two-axis build and a two-entry queue it runs
seven jobs twice, changing parameters during playback, in both host/DSP clock-rate
directions. Every complex X/Y sample is compared with independently configured
native `AodTop` jobs and every first-sample timestamp is checked. It also verifies
program readback, custom trajectory/envelope/axis gain uploads, zero off intervals,
result writes to modeled PS DDR, repeated shots without reloading tables,
monotonic time and aborting an enqueued job. The checked-in software sim manifest
is compared against the hardware map by this gate.

These are simulation/control-path checks, not RF hardware or 400-MHz closure
claims. Default-size RTL generation and lint are separate checks. Logs for this
round are `out/aod-soc-{cpu,generate,lint}.log`,
`out/aod-soc-regression.log` and `out/aod-software-tests.log`.

Validated: 34 standalone AOD tests, eight host/compiler tests, CPU integration
with both clock-rate directions, and default-size RTL generation/lint with
reporting enabled and omitted. A one-core superconducting SoC also elaborates
with the extracted common bus-IO helper.

The default/minimal lint command is:

```sh
verilator --lint-only -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL \
  -Wno-SYNCASYNCNET -Wno-WIDTHTRUNC --top-module AodSoc \
  out/aod-soc-rtl/AodSoc.v
```

The width-warning exclusion covers ten generated right shifts in CPU RAM,
SlaveFactory address decoding and Spinal's AXI aligners/compactors: each removes
known zero high bits after a constant shift. The CDC FIFO reset is intentionally
used for asynchronous assertion and synchronous logic (`SYNCASYNCNET`). Other
unsuppressed lint warnings remain fatal. Use the minimal output path to check
that build.
