# Standalone AOD interface

`AodTop` is the canonical standalone component. It composes staged posted
register writes, the shared RISC-Q timed queue, snapshot storage, coefficient
loading and `AodEngine`. There are no AXI/TileLink ports or SoC dependencies.
The separate [host adapter](HOST.md) adds a backpressured TileLink setup window,
clock crossing, reset control and optional minimal diagnostic readback.
See [SCHEDULING.md](SCHEDULING.md) for timing constants, queue capacity/pacing,
errors, reset behavior and the RF-off intervals between pulsed movements.

All ports use `clk`; `rst` is synchronous, active-high. `time` is an externally
supplied 32-bit counter incrementing every AOD clock. Coefficient memories and
arithmetic registers retain the engine's selective reset behavior. Reset flushes
queued/active work and forces the top's packed sample outputs to zero.

## Setup: coefficient loading

`setup` is a Spinal `Stream[AodCoefficientWrite]`. A transfer occurs on a rising
edge with both `valid` and `ready` high. The producer must hold `valid` and the
entire payload stable until that edge. There is no separate data-staging write.

| Field | Meaning |
| --- | --- |
| `table` | 0: trajectory; 1: envelope; 2: gain calibration |
| `channel` | Axis 0/1 for gain calibration; ignored for trajectory/envelope |
| `address` | Profile row or calibration entry index, **not** a byte address |
| `beat` | Profile slice 0/1/2, least-significant 32 bits first; 3 aliases slice 2; ignored for calibration |
| `data` | 32-bit coefficient word; the envelope's final slice uses only 8 bits |

Trajectory rows are 96 bits (three 32-bit differences); envelope rows are
72 bits (two packed 36-bit amplitude/slope values). Profiles broadcast to all
tones on both axes. Calibration entries are 32 bits and broadcast to all tones
on the selected axis. Legal addresses are below `segments` or `gcalEntries`,
respectively. Reserved table values and out-of-range payloads are unsupported.

The loader preserves the bank's held-enable write distribution. Accepted writes
are separated by at least **8 AOD clocks**; backpressure enforces this spacing.
`setup.ready` is also low during reset or while any movement is queued,
preparing, playing or draining, including its phase snapshot. `setup_busy` stays
high while an accepted write drains through the broadcast pipeline. Acceptance
is not completion of every memory copy. Finish the upload, deassert
`setup.valid`, and wait for `setup_busy` low before submitting movements.
`AodHostAdapter` preserves this handshake and acknowledges only after drain.
AXI/TileLink protocols remain outside the standalone waveform component.

Tables are shared by all jobs and are not part of the per-movement snapshot.
The top enforces setup/playback exclusion. An offered write waits until all
outstanding jobs drain; a simultaneous fire is rejected with `setupConflict`.
Reset interrupting an upload may leave partial tables; upload again before use.
Reset after a completed upload preserves the tables.

## Runtime: posted staging and fire

`cmd` is a Spinal `Flow[AodWrite]`: valid, a 16-bit byte address and 32-bit data.
Every valid rising edge delivers a full-word write, including adjacent edges.
There is no acknowledgement, ready or synchronous read path. This matches
RISC-Q's posted `Put` shape. Unmapped/misaligned writes have no effect.

| Byte offset | Write |
| --- | --- |
| `0x00` | Fire: capture the staged movement and timestamp into the queue; data ignored |
| `0x4100` | Stage the next movement's first-sample timestamp |
| `0x08` | Reserved/ignored; no immediate control bypass |
| `0x0c` | Ratio |
| `0x10` | Decimation |
| `0x18` | Clear scheduler errors and engine clipping/dropped-go sticky flags |
| `0x8000 + axis*0x4000 + field*0x800 + tone*4` | Tone field 0: starting frequency, 1: signed displacement, 2: initial phase, 3: packed calibration endpoints |
| `0xa800 + axis*0x4000 + 0/4` | Tone-enable bits 0–31 / 32–63 (unimplemented bits ignored) |
| `0xa800 + axis*0x4000 + 8` | Amplitude scale |
| `0xa800 + axis*0x4000 + 20` | Calibration enable at bit 0, zoom at bits 2:1 |
| `0xa800 + axis*0x4000 + 24` | Calibration base frequency |

Parameter writes update staging only. Fire atomically captures the timestamp
and all movement fields across both axes; later writes cannot change active or
queued jobs. Each job reloads its captured `phase0`. The top handles bank soft
reset and settling automatically; software does not issue a go or reset command.

## Outputs and reporting

`schedule` exposes queued count, active/idle/capacity, sticky scheduling errors
and accepted/started/completed counters. Empty FIFO is not the same as idle:
active includes preparation, sample drain and automatic phase-snapshot drain.
See the scheduling contract for exact definitions and malformed-job handling.

`status` exposes engine busy, clipping, configuration errors, sweep/trigger/fabric
counters and snapshot activity. `pa_we` marks a phase event with `pa_ch`,
`pa_addr` (tone) and `pa_data`; capture events on their valid edge. There is no
internal readback RAM or event backpressure. The eventual SoC adapter must route
status/events explicitly. Native engine busy excludes some lifecycle phases;
use `schedule` for top-level job/setup availability.

`dac_x`/`dac_y` pack `{sin,cos}` in two 16-bit slots with physical-DAC rounding
and MSB alignment. `dac_valid` tags each axis's final packed output on the same
clock. This is one complex sample per AOD clock, with no converter backpressure.
The top drives zero outside valid output. `startTime` refers to the first valid
sample here; downstream RFDC/interpolation/mixer latency remains board work.

## Direct engine use

`AodEngine` is the lower-level waveform component, not a second runtime top.
It takes native `AodControl` and coefficient-event ports. Its pulsed/CW, trigger,
phase snapshot, pending-go and selective-reset behavior is unchanged. Tests of
these controls drive its ports directly, independently of the scheduled API.

Direct users must keep parameters/tables stable during playback and enforce
coefficient spacing themselves (or use `AodCoefficientLoader`). A fresh pulsed
go reloads `phase0`; CW restarts preserve phase. The pending-go bit stores only
a request, not a second movement snapshot. Before launch, allow configuration
distribution to settle. `AodEngine.launchLatency(p)` is
`52 + ceil(log2(tones))` clocks from an idle, settled native `control.go`
acceptance edge to the first final valid word; pending/trigger timing differs.

The inherited decimated-pulse engine can update trajectory work state while
idle. Direct engine users need soft reset for at least eight clocks between
independent shots, then a `tones + 8` settling guard. The canonical top automates
this sequence. Direct CW users should not reset between automatic sweeps.

Global SoC wiring/address allocation, firmware ABI, converter integration and FPGA
constraints/timing closure remain future work. Simulation and lint do not
establish board timing or RF behavior.
