# Standalone AOD interface

`AodTop` has no AXI or TileLink ports and does not instantiate RISC-Q SoC
components. It composes `AodRegisters`, `AodCoefficientLoader` and `AodEngine`.
The waveform arithmetic and coefficient formats are unchanged.

All ports use `clk`. `rst` is active-high and synchronous. The explicit clock
and selective reset behavior of the arithmetic blocks is preserved: reset does
not clear coefficient memories or every arithmetic register. The normal shell
is `AodTop`; direct `AodEngine` users must supply stable `AodControl` values and
correctly spaced coefficient events themselves.

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
The shell also holds ready low during hard or software soft reset.
`setup_busy` stays high while the last accepted write drains. Acceptance means
the loader has taken responsibility for a write, not that every memory copy has
already been updated. Finish the upload, deassert `setup.valid`, and wait for
`config_ready` before launching. A future host adapter must preserve the
ready/valid handshake and cross clock domains explicitly; AXI/TileLink bus
protocols are not present inside this standalone module.

Setup is supported only with the waveform engine idle, sample pipelines drained,
CW stopped, software soft reset clear, external triggers disabled, and no
launch pending/in flight. Do not issue launches or soft reset during upload.
`setup.ready` controls write spacing; it is not an assertion that these playback
preconditions hold. Concurrent table modification/playback is unspecified. Reset
interrupting an upload may leave partially updated tables; upload again before
use. Reset after a completed upload preserves the tables.

## Runtime: posted register writes

`cmd` is a Spinal `Flow[AodWrite]`: `valid`, a **16-bit byte address** and 32-bit
`data`. Every valid rising edge delivers one write, including adjacent valid
edges. There is no acknowledgment, ready signal or synchronous read path. This
matches the shape of RISC-Q's posted `Put` without depending on its SoC module.
Only aligned, full-word writes are supported. Unmapped and misaligned writes
have no effect; historical address aliases, AXI responses and sentinel reads
are removed.

| Byte offset | Write |
| --- | --- |
| `0x08` | Control: bit 0 go, 1 CW, 2 soft reset, 3 trigger enable, 4 trigger polarity, 5 phase snapshot |
| `0x0c` | Ratio |
| `0x10` | Decimation |
| `0x18` | Clear clipping and dropped-go sticky flags |
| `0x8000 + axis*0x4000 + field*0x800 + tone*4` | Tone field 0: starting frequency, 1: signed displacement, 2: initial phase, 3: packed calibration endpoints |
| `0xa800 + axis*0x4000 + 0/4` | Tone-enable bits 0–31 / 32–63 (unimplemented bits ignored) |
| `0xa800 + axis*0x4000 + 8` | Amplitude scale |
| `0xa800 + axis*0x4000 + 20` | Calibration enable at bit 0, zoom at bits 2:1 |
| `0xa800 + axis*0x4000 + 24` | Calibration base frequency |

Control writes replace all level bits together. Go, snapshot and sticky clear
are one-clock strobes; consecutive command beats represent consecutive requests.
Keep configuration stable during playback. The pending-go bit stores a request,
not a snapshot of another movement's configuration. CW restarts retain phase;
fresh pulsed launches reload `phase0`. Clearing CW finishes the current sweep.
Finish-and-hold is not implemented.

`config_ready` is a conservative launch prerequisite: register distribution has
settled, the coefficient loader has drained, no setup write is being offered,
and soft reset is clear. Configuration writes restart a `tones + 8` clock guard;
soft reset holds that guard loaded, so its release also settles. Other
control/clear-sticky strobes do not restart it. It does not mean idle or reserve queue space.
The existing bank-level early-ratio-launch rejection/`cfg_err` behavior remains;
the shell does not queue an early launch until configuration becomes ready.

The inherited tone implementation leaves decimation work state active after a
decimated pulsed sweep; its idle-state segment updates can affect the next
trajectory. This predates the native interface and is intentionally unchanged.
For reproducible independent shots with decimation greater than one, assert
software soft reset for at least 8 clocks, clear it, then wait for `config_ready`
before launching. This preserves loaded tables and tone configuration. Do not
reset between automatic CW sweeps: their phase-continuous behavior is preserved.

## Outputs and future integration

`status` exposes per-axis busy, clipping, configuration errors and sweep counts;
trigger/fabric counters; snapshot activity; decimation clamping; and dropped-go
status. `pa_we` marks one phase-snapshot event with `pa_ch`, `pa_addr` (tone) and
`pa_data`. Consumers capture each event on its valid edge. There is no internal
snapshot readback RAM or flow control on these events. A later SoC adapter must
choose the reporting/readback route explicitly.

`dac_x`/`dac_y` retain `{sin,cos}` packing in two 16-bit slots, with physical-DAC
rounding/MSB alignment. Each `dac_valid` bit tags that axis's packed output on
the same clock. These are one complex sample per AOD clock; valid is an
observation tag, not converter backpressure. Pulsed output drains to zero; CW
keeps continuous sample validity through reload dwell. Output values outside
valid retain the historical pipeline behavior and should not be interpreted as
valid waveform samples.

For an idle engine with configuration settled, the first valid converter word
arrives **`53 + ceil(log2(tones))` clocks after the edge accepting a posted go**
(`AodTop.idleLaunchLatency`). The registered runtime decode contributes one
clock. Tests assert this at two/four/eight tones, for pulsed and CW launch.
Pending busy-time go requests and asynchronous external triggers have different
timing contracts; this is not an absolute timestamp scheduler.

No timestamp queue, host bus adapter, event-sink wiring, firmware ABI, clock
crossing or RFDC rate/mixer adapter is added here. The eventual scheduler must
target the chosen output reference point and account for engine plus converter
latency. Simulation and RTL lint do not establish FPGA timing closure. In
particular, audit synthesis hierarchy/constraints after the `AodEngine` extraction;
the coefficient broadcast's held-write behavior remains relevant to multicycle
constraints.
