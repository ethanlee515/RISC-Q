# Standalone SpinalHDL AOD engine

The `aod` Mill module contains the tone generator and its tests.
Its native interfaces separate backpressured coefficient loading from posted
runtime writes. See [INTERFACE.md](INTERFACE.md) for ports, register offsets,
loading/settling requirements, launch latency and status semantics. It needs no
external AOD checkout, hand-written HDL, implementation selector, or AOD-specific
environment settings. The implementation and all DUTs are SpinalHDL components.

`AodTop` is the canonical frontend, with immutable queued movements using RISC-Q's shared
`TimedQueue`, with first-sample timestamps, explicit schedule errors and automatic
reset/settling between pulsed jobs. See [SCHEDULING.md](SCHEDULING.md) for the
posted staging/fire API, pacing, setup lock and RF-off interval semantics.

`AodHostAdapter` adds a host TileLink setup window across independent clocks,
acknowledges coefficient writes after destination-memory completion, and supplies
an AOD reset/flush command plus minimal optional diagnostic readback. See
[HOST.md](HOST.md) for its map, reset/loading sequence and resource options.

The main RISC-Q module also provides [AodSoc](../docs/soc/AodSoc.md): one
RISC-V CPU with host program/setup windows, posted AOD writes, shared time,
result forwarding, and a CPU-in-the-loop regression. Its outputs are still native
complex samples; board RFDC conversion and implementation timing remain separate.
The standalone module and tests below do not require a SoC/firmware build.

From the RISC-Q checkout, run:

```bash
./mill --no-server aod.test
```

Requirements are RISC-Q's pinned Scala/SpinalHDL toolchain, Verilator 5, the normal
SpinalSim C++ toolchain, and Python 3 with NumPy for profile/calibration generation
and FFT analysis. No firmware compiler, Vivado, board, or SoC build is needed.

To run a subset:

```bash
./mill --no-server aod.test.testOnly riscq.aod.AodTests -- 'riscq.aod.AodTests.{pulsed,cw}'
./mill --no-server aod.test.testOnly riscq.aod.AodModelTests
./mill --no-server aod.test.testOnly riscq.aod.ScheduledTests
./mill --no-server aod.test.testOnly riscq.aod.HostTests
```

Generate the default 32-tone, two-channel engine with packaged coefficients:

```bash
./mill --no-server aod.runMain riscq.aod.AodGenerate out/aod-rtl
```

The output directory defaults to `out/aod-rtl` when omitted. For custom
coefficients, pass `<rom-directory> <output-directory>` instead; the directory
must contain `aod_unit.mem` and `aod_seed.mem`. Keep the generated initialization
files beside the generated Verilog when consuming it with other tools.

Scala callers can instantiate `new AodTop(AodConfig.default)` or construct an
`AodConfig` with explicit coefficient paths and geometry. The default tables are
classpath resources, extracted for elaboration; they also work when packaged in
a jar. Tests load their fixtures and numerical tools from local classpath
resources. Simulator builds, generated profiles, captures and Python logs go to
ignored `aod/simWorkspace/run-*/` directories, isolated per test JVM.

## Implementation

| Component | Purpose |
| --- | --- |
| `AodTone` | Writable profiles, forward differences, frequency scaling, phase accumulation, amplitude processing, pulsed/CW control |
| `AodCordic` | Buffered rotation-mode CORDIC with gain retained and a pipelined valid tag |
| `AodGcal` | Frequency-indexed lookup, signed interpolation and gain/application clamps |
| `AodBank` | Configuration distribution, phase scan, registered sum tree and saturation |
| `AodRound` | Final converter quantization, saturation and MSB alignment |
| `AodRegisters` | One posted address/data write per clock; runtime configuration and command strobes |
| `AodCoefficientLoader` | Atomic ready/valid setup writes, with enforced broadcast drain spacing |
| `AodEngine` | Bus-independent banks, triggers, counters, snapshot events, sticky status and I/Q packing |
| `AodTop` | Staged posted writes, immutable timed jobs, reset/settle/launch/drain lifecycle, setup locking and schedule status |
| `AodHostAdapter` | Host setup bus/clock crossing, completed-write acknowledgments, reset/flush and optional packed diagnostics |

`AodConfig` exposes tone/channel count, rate limits, trajectory geometry,
calibration depth, fanout and physical DAC precision. Arithmetic uses ACC=32,
UW=30, NDIFF=3, XW=20, AMP_FRAC=16, CALW=16, CAL_FRAC=15, DAC_BITS=16 and a
22-stage buffered CORDIC. Tables already compensate CORDIC gain. The contract is
one complex sample per clock, packed as `{sin,cos}` in two 16-bit slots. The
optional historical repetition into additional output slots is omitted.

Clock and active-high synchronous reset are explicit pins. Reset flushes queued
and active work; arithmetic pipelines and coefficient memories retain selective
reset behavior. Calibration initializes to unity. Profile rows use independent
beat-addressed memory banks, including beat 3's alias to the final slice.

`cmd` is a posted `Flow` with a 16-bit byte address and 32-bit data, matching
RISC-Q's `Put` shape without a SoC dependency. `setup` is a separate `Stream`
carrying the table, axis, row, slice and data in one transaction; backpressure
enforces eight-clock spacing around the existing table broadcast pipeline.
`AodTop` locks setup while jobs are outstanding and handles reset/settling
before each timed launch. It exposes queue/lifecycle/error status as `schedule`.
Status and phase-snapshot events are native outputs; `dac_valid` tags each
axis's final converter words. The historical AXI wrapper, readback mirror RAMs,
sentinel reads and decode aliases are removed.

SoC address allocation and CPU/link wiring, converter
rate adaptation and finish-and-hold remain separate work. Generated port and
hierarchy names require an audit of Vivado constraints before synthesis; FPGA
timing and analog behavior have not been validated.

## Tests and numerical fixtures

`AodTests` has twenty functional/interface cases. `AodModelTests` adds four cases;
`ScheduledTests` adds five scheduler cases, and `HostTests` adds five adapter
cases. There
is one implementation under test, without a duplicate legacy-implementation
suite.

| Cases | Coverage |
| --- | --- |
| `cordic` | 256 phases, I/Q accuracy against trigonometry with gain retained |
| `profiles` | Linear/minimum-jerk preparation, cubic endpoints at all five decimations and fit-error bound |
| `pulsed` | 20,000 preserved waveform samples within four DAC LSB, arbitrary signed deltas, exact endpoints, rate clamp and decimated durations |
| `indices` | Runtime identity trajectory, adopted indices at decimation 1/2/4/8/16 |
| `cw` | Exact phase advancement, no valid gaps, 22-clock reload dwell, 6669-clock period, solved periodicity and identical I/Q sweeps |
| `gcal256`, `gcal128`, `gcal128TwoCurves` | Unity, impulse addresses, signed clamps, both interpolation slopes, zoom, bypass and independent curves |
| `registers` | Canonical-top consecutive posted writes, tone fields, unmapped writes, masks, ratio/decimation staging and ignored legacy controls |
| `engineControl` | Direct native engine ports: counters, snapshots, clipping/clear, triggers, pending/dropped requests, soft reset and four-tone launch latency |
| `gcalSetup` | Both channels and all per-tone copies, profile/calibration isolation, live lookup and bypass through native setup |
| `coefficientLoading` | Continuously offered writes under backpressure, interleaved trajectory/envelope/calibration writes, every row/slice and tone copy, reset stalls, drain and memory persistence |
| `profilesThroughSetup` | Load-to-waveform behavior: mute envelope, change trajectory, restore exact converter trace after reset, synchronized axes, two-tone timestamped launches and repeated shots |
| `registerGeometry` | Native decoder with one/two axes and 2/33/64 tones, including high mask words and absent-axis isolation |
| `gcalGenerated128`, `gcalGenerated256` | Synthetic calibration sweep, 4004 exact integer comparisons at each depth |
| `spectrumCw`, `spectrumNegative`, `spectrumComb`, `spectrumChirp` | Final converter words, eight-tone native-engine launch latency, spur/image bounds, eight-tone locations/levels and linear/minimum-jerk chirp ripple |
| `roundingExhaustive` | All signed 16-bit inputs at 14/16-bit precision and all 8-bit inputs at 2-bit precision; samples, saturation and reset |
| `cordicStreaming` | 20,000 consecutive random signed I/Q inputs, phases and tags against an integer model; exact 24-clock latency and phase residual |
| `gcalStreaming` | 20,000 random clocks at each of two geometries against a stateful memory/lookup model, including writes, signed slopes, clamps and changing configuration |
| `runtimeProfiles` | Rewritten trajectory/envelope tables, positive/negative envelope slopes, beat aliases, non-unity calibration, signed displacement, decimation and disabled tone |
| `immutableJobs` | Exact scheduled output versus native-port engine traces, both-axis snapshots, active/queued staging isolation and repeated decimated jobs |
| `errorsAndReset` | Full pending budget, overflow, reset flush, insufficient lead, invalid parameters, order and overlapping-job rejection |
| `setupLockAndLateDispatch` | Backpressured setup through queue/playback/drain, setup conflict and overdue job discard |
| `wrapAndMinimumLead` | Exact first sample at the minimum enqueue lead across 32-bit timestamp wrap, four tones and one axis |
| `snapshotStorageGeometry` | Banked snapshot elaboration at 32/64 tones with both axes, queue depths 1/3/4 |
| `uploadCompletion` | Faster-host/faster-DSP clocks, actual destination memories, drain before acknowledgment, reset duration and table persistence |
| `backpressureAndDeniedAccess` | Eight outstanding source IDs, full CDC/response queues, stable stalled responses, ordered writes and invalid accesses |
| `playbackLockAndFlush` | Upload waits through playback, per-run reset flushes pending jobs, time/tables survive and the next run works |
| `optionalDiagnostics` | Reported and minimal builds, error visibility versus unchanged rejection, one-axis/128-entry gain-map bounds |
| `adapterRtl` | Default-geometry adapter RTL with diagnostic ports/readback enabled and disabled |

The streaming models are test-only Scala arithmetic. CORDIC angles are computed
from `atan` independently of the implementation's integer constant table. The
calibration model tracks its own memory image and lookup/application timing.
Rounding expectations use integer arithmetic and check the saturation flag too.

The runtime-profile case checks a SHA-256 trace over all 54,070 clocks, including
I/Q samples, valid, busy/done, accumulated phase, dropped launches, live increment,
phase and trajectory state. This trace was captured with per-clock live SV
comparisons enabled before those bindings were removed. Its field encoding and
expected digest are recorded in `runtime-profile.properties`.

The coefficient tables, original 20,000-sample waveform vector, geometry and
synthetic sweep are local resources. `provenance.json` records their source
revision/paths and original hashes, together with the provenance of the four
Python numerical tools. Those tools retain the established generation and FFT
checks; the profile generator was adapted to accept explicit local source tables
and no longer copies a Verilog configuration header. The sample vector predates
the current CORDIC, hence its historical four-LSB tolerance; all integer-model
and trace checks require exact agreement.

The original standalone behavior was checked against live SV before removing
that dependency. The retained Scala CORDIC attribution identifies its QubiC
origin. Provenance is historical context and imposes no checkout dependency.

Simulation uses seed 42 and zero startup values for otherwise uninitialized
registers, matching the established regression contract. Configure while idle,
allow distribution to settle and keep tables/configuration stable during a shot.
The inherited decimated-pulse idle-state behavior can affect the next trajectory;
use an engine soft reset between independent decimated shots (automated by
`AodTop`), as documented in
[INTERFACE.md](INTERFACE.md). The new upload/restore comparison uses this explicit
reset contract; the preserved block-level integer trace remains unchanged.
Randomized-startup robustness, formal exhaustive equivalence, default-size FPGA
timing and physical calibration are outside this suite. The calibration sweep
is synthetic; spectral tests use two/four/eight-tone geometries, not a board.

Validated on 2026-10-18 with Verilator 5.020:

- `./mill --no-server aod.test`: **34 passed, 0 failed**, including twenty
  functional/interface scenarios, four unchanged model/trace cases and five
  scheduler cases plus five host-adapter cases. Log: `out/aod-tests.log`.
- Default 32-tone/two-channel canonical-top generation succeeds using the
  packaged profile. Log: `out/aod-generate.log`.
- Generated RTL passes Verilator `--lint-only -Wall -Wno-DECLFILENAME
  -Wno-UNUSEDSIGNAL --top-module AodTop AodTop.v` from its output directory, with
  no diagnostics. Exclusions cover generated multi-module filenames and unused
  signals/slices. Log: `out/aod-lint.log`.
- Host adapter RTL in `out/aod-host-rtl/{reported,minimal}` is generated by
  `HostTests.adapterRtl`; both variants pass the same lint flags plus
  `-Wno-SYNCASYNCNET` (the library FIFO's reset synchronizers assert
  asynchronously while its transaction logic resets synchronously), with
  `--top-module AodHostAdapter AodHostAdapter.v`. Logs:
  `out/aod-host-{reported,minimal}-lint.log`.
- The main RISC-Q build compiles against the shared timing module, and
  `./mill --no-server runMain riscq.dsp.pulse.sim.TimedQueueSim` passes all
  scenarios with both head-stage settings. Log: `out/aod-scheduled-queue.log`.
