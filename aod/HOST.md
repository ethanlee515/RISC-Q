# Host setup adapter

`AodHostAdapter` connects a 32-bit TileLink host window to `AodTop.setup` across
independent host/DSP clocks. RISC-Q already bridges the board's host AXI bus to
TileLink; this adapter belongs downstream of that bridge. It does not introduce
another waveform top or modify the runtime posted-write interface. The
[AodSoc shell](../docs/soc/AodSoc.md) now supplies the global address allocation,
CPU/link wiring and host software helpers. This page describes the standalone
adapter's relative interface.

The adapter uses Spinal's `tilelink.FifoCc` for requests and responses. Address,
payload and transaction identity cross together. Reads capture their entire
32-bit result in the DSP domain before returning it; multi-bit diagnostic values
are not crossed with independent synchronizers.

## Relative host address map

All offsets are relative to the adapter's 64 KiB window. Accesses are aligned,
full 32-bit words: TileLink Get or PutFullData, size 2, full byte mask. Bursts,
partial/sub-word accesses, unmapped operations and coefficient readback are
denied without modifying tables or reset state. Source and size are preserved
in responses, including denied responses. Profile beat 3 aliases beat 2, matching
the native engine; reserved rows and nonexistent axes are denied.

| Offset | Access | Meaning |
| --- | --- | --- |
| `0x0000` | Read | Capabilities: version 1 at bits 31:24, axis count at 17:16, tone count at 15:8, diagnostic readback enabled at bit 0 |
| `0x0004` | Read/write | Write 1 to reset/flush AOD for eight DSP clocks; write 0 has no effect; other values denied. Read returns reset level |
| `0x0008` | Read | State: setup busy bit 0, active bit 1, idle bit 2, can enqueue bit 3, AOD reset bit 4 |
| `0x000c` | Read | Optional diagnostic word; reads zero when disabled |
| `0x1000 + row*16 + beat*4` | Write | Trajectory coefficient slice |
| `0x2000 + row*16 + beat*4` | Write | Envelope coefficient slice |
| `0x3000 + axis*0x400 + entry*4` | Write | Gain calibration word |

Rows must be below `segments`; gain entries below `gcalEntries`; axes below
`channels`. `AodHostMap.profile` and `.calibration` compute these offsets.
Table layout/data encoding are specified in [INTERFACE.md](INTERFACE.md).

## Completion and backpressure

A coefficient write is acknowledged **after** the native setup transfer and
after `setup_busy` deasserts. A successful TileLink response therefore means
the broadcast write has reached every destination memory copy. Acceptance into
the host clock-crossing FIFO is not completion. The adapter processes requests
in order and holds responses stable under backpressure. A later read is executed
after earlier requests have completed in the DSP domain.

Use host device-memory ordering and wait for write completion/an ordered
readback before releasing the CPU. The eventual AXI/host driver must preserve
this contract; a locally posted CPU store alone does not establish completion.
There is no separate upload FIFO-empty heuristic or software delay estimate.

While any AOD movement is queued/active/draining, a coefficient request waits
for `AodTop.setup.ready`; it cannot change the tables mid-shot. Host transactions
can stall for this reason. Intended preprocessing keeps the runtime CPU stopped
and its posted link quiescent, so no new movements interfere with loading.

## Reset and run sequence

Wire `host_clk/host_rst` to the system host domain and `dsp_clk/dsp_rst` to the
system DSP domain. Assert both system resets together at initialization; allow
both clocks to run through reset and synchronization. These resets initialize
the bus CDC machinery. An interrupted transaction/upload is not preserved.

Wire `adapter.io.aod_reset` to `aod.io.rst`, and connect the adapter's setup
stream and state inputs to the corresponding `AodTop` ports. Host reset requests
reset the AOD engine/scheduler **downstream of the bus CDC**, leaving its FIFO
pointers and the external DSP time counter running. The reset write responds
only after the eight-clock reset has finished. It flushes queued/active jobs,
clears staging and diagnostics as specified by `AodTop`, and preserves uploaded
tables. Tone arrays remain write-before-use. Reset commands execute in host
request order; they do not overtake an earlier pending upload.

Follow the existing RISC-Q convention of stopping the CPU separately from the
host/DSP setup machinery:

1. Hold the CPU in reset and let its posted command link become quiescent.
2. Write 1 to the AOD reset register and wait for its response.
3. Upload coefficients if needed; wait for completed responses/readback.
4. Release the CPU. Its program stages movement parameters and timestamps.

Do not connect AOD reset to the *held* CPU-reset level: setup must remain usable
while the CPU is stopped. The adapter supplies the AOD side of this sequence;
the future SoC shell supplies CPU reset and link-quiescence sequencing. Loaded
tables can be reused across runs. After a system reset interrupts an upload,
repeat the upload before use.

## Minimal, optional diagnostics

The diagnostic word contains native scheduler error bits at 5:0, clipping at
9:8, configuration errors at 11:10, dropped-go at 12 and decimation-clamped at
13. Unused axis bits and reserved bits read zero. Scheduler errors are sticky;
decimation-clamped is a level. Reads do not clear flags. The runtime clear at
`AodRegisterMap.clearSticky` retains its existing semantics; AOD reset also
clears sticky diagnostics. State/diagnostic reads reflect their DSP execution
cycle, not necessarily the later host response cycle.

`withDiagnostics = false` omits diagnostic input ports and packing at
elaboration; the capability bit clears and the diagnostic register reads zero.
This does not change command acceptance, overlap/late rejection, reset, upload
completion or waveform behavior. Unconnected native diagnostic/counter outputs
may then be trimmed by synthesis. Actual resource/timing savings must be
measured: this switch does not remove the scheduler's essential checks.

No CPU event stream, interrupt handling, counter transport or phase-snapshot
readback RAM is added. These can be introduced if an experiment needs them.
Keep diagnostic transport optional if utilization or routing becomes limiting;
do not assume it is the timing bottleneck without implementation evidence.

## Verification

```
./mill --no-server aod.test.testOnly riscq.aod.HostTests
```

The composition fixture connects the actual canonical top, adapter and replicated
table memories. Tests use both faster-host and faster-DSP clocks, check every
accepted write against destination memories after its response, delayed
responses, multiple outstanding source IDs, upstream backpressure, denied
accesses, playback exclusion, reset flushing/table persistence, and diagnostic
readback enabled/disabled. Runtime stores in this fixture are test stimulus;
there is no RISC-V/board integration or timing-closure claim in this round.

`HostTests.adapterRtl` generates default-geometry adapter RTL in
`out/aod-host-rtl/{reported,minimal}`. Lint each `AodHostAdapter.v` with
`--lint-only -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL -Wno-SYNCASYNCNET
--top-module AodHostAdapter`. The extra reset-net exclusion covers the library
FIFO's asynchronous reset-assertion synchronizers alongside synchronous
transaction/pointer reset logic.
