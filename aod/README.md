# Standalone SpinalHDL AOD engine

The `aod` Mill module contains the tone generator and its tests. It needs no
external AOD checkout, hand-written HDL, implementation selector, or AOD-specific
environment settings. The implementation and all DUTs are SpinalHDL components.

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
| `AodRegisters` | Standalone AXI register transport and readback |
| `AodTop` | Triggers, counters, snapshots, sticky status and I/Q packing |

`AodConfig` exposes tone/channel count, rate limits, trajectory geometry,
calibration depth, fanout and physical DAC precision. Arithmetic uses ACC=32,
UW=30, NDIFF=3, XW=20, AMP_FRAC=16, CALW=16, CAL_FRAC=15, DAC_BITS=16 and a
22-stage buffered CORDIC. Tables already compensate CORDIC gain. The contract is
one complex sample per clock, packed as `{sin,cos}` in two 16-bit slots. The
optional historical repetition into additional output slots is omitted.

Clock and active-high synchronous reset are explicit pins. Reset only touches
the state specified by the engine; arithmetic pipelines and coefficient memories
have no reset. Calibration initializes to unity. Profile rows use independent
beat-addressed memory banks, including beat 3's alias to the final slice.

The control/status bundles separate register transport from waveform banks.
The AXI interface retains the established address/data overlap requirement,
ignored byte strobes/protection bits, decode aliases and response timing.
Native RISC-Q posted writes, scheduled launch, converter rate adaptation and
finish-and-hold remain separate integration work. Generated port and hierarchy
names require an audit of Vivado constraints before synthesis; FPGA timing and
analog behavior have not been validated.

## Tests and numerical fixtures

`AodTests` has sixteen functional cases. `AodModelTests` adds four cases; there
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
| `registers` | Tone fields, invalid accesses, masks, capabilities, configuration status, counters, snapshots, silence, clipping/W1C, triggers, pending/dropped launches and soft reset |
| `gcalAxi` | Both channels and all per-tone copies, selector isolation, profile preservation, live calibration and bypass |
| `gcalGenerated128`, `gcalGenerated256` | Synthetic calibration sweep, 4004 exact integer comparisons at each depth |
| `spectrumCw`, `spectrumNegative`, `spectrumComb`, `spectrumChirp` | Final converter words, spur/image bounds, eight-tone locations/levels and linear/minimum-jerk chirp ripple |
| `roundingExhaustive` | All signed 16-bit inputs at 14/16-bit precision and all 8-bit inputs at 2-bit precision; samples, saturation and reset |
| `cordicStreaming` | 20,000 consecutive random signed I/Q inputs, phases and tags against an integer model; exact 24-clock latency and phase residual |
| `gcalStreaming` | 20,000 random clocks at each of two geometries against a stateful memory/lookup model, including writes, signed slopes, clamps and changing configuration |
| `runtimeProfiles` | Rewritten trajectory/envelope tables, positive/negative envelope slopes, beat aliases, non-unity calibration, signed displacement, decimation and disabled tone |

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
Randomized-startup robustness, formal exhaustive equivalence, default-size FPGA
timing and physical calibration are outside this suite. The calibration sweep
is synthetic; spectral tests use two/four/eight-tone geometries, not a board.

Validated on 2026-10-18 with Verilator 5.020:

- `./mill --no-server aod.test`: **20 passed, 0 failed**, including all sixteen
  functional scenarios and four model/trace cases. Log: `out/aod-standalone-tests.log`.
- Default 32-tone/two-channel generation succeeds using the packaged profile.
  Tests and generation also pass with obsolete external-directory settings
  deliberately pointing at nonexistent paths. Log: `out/aod-standalone-generate.log`.
- Generated RTL passes Verilator `--lint-only -Wall -Wno-DECLFILENAME
  -Wno-UNUSEDSIGNAL --top-module AodTop AodTop.v` from its output directory, with
  no diagnostics. Exclusions cover generated multi-module filenames and unused
  signals/slices. Log: `out/aod-standalone-lint.log`.
