# AOD reference regression

Run from the RISC-Q checkout:

```bash
./mill --no-server aod.test
```

This independent Mill module reuses RISC-Q's pinned Scala, SpinalHDL, Verilator,
and uTest infrastructure. It does not compile or connect the RISC-Q SoC and needs
no firmware compiler, Vivado, or board. Requirements are the normal SpinalSim
C++ toolchain, Verilator 5, and Python 3 with NumPy for profile generation and FFT
analysis. Scala drives the HDL and captures results; the original Python
generators and analysis tools remain the numerical oracles. A nonzero Python
exit status fails the Mill test. This avoids translating both the hardware and
its numerical checks at the same time and adds no Python dependency beyond what
the profile checks already required.
`--no-server` is optional; it also avoids stale Mill daemon state.

To run one case or a subset:

```bash
./mill --no-server aod.test.testOnly riscq.aod.AodReferenceTests -- 'riscq.aod.AodReferenceTests.cw'
./mill --no-server aod.test.testOnly riscq.aod.AodReferenceTests -- 'riscq.aod.AodReferenceTests.{pulsed,cw}'
./mill --no-server aod.test.testOnly riscq.aod.AodReferenceTests -- 'riscq.aod.AodReferenceTests.{gcalGenerated128,gcalGenerated256,spectrumCw,spectrumNegative,spectrumComb,spectrumChirp}'
```

By default the reference is the sibling `../AODToneGeneration` checkout. After
moving it, use an absolute path:

```bash
AOD_REFERENCE_ROOT=/path/to/AODToneGeneration ./mill --no-server aod.test
```

The reference RTL and fixture memories are read directly from that checkout.
Generated profiles, simulator builds, and generator logs go under the ignored
`aod/simWorkspace/` directory. No tracked reference tables are regenerated.
The starting reference revision is `1defd33dd6f1a25d932ecf4b7ba5ea4fff72c476`;
RISC-Q was at `289d921f9f612ec5c32aacbb65438f04b7716dcb` when this suite was added.

The stimulus and assertions live in Scala and run through
`SimConfig.compile(...).doSim(...)`. BlackBoxes instantiate the original
SystemVerilog. The two adapters in `rtl/` only expose read-only observations that
the original benches access hierarchically. They contain no test stimulus,
assertions, or replacement datapath behavior. Clock and active-high synchronous
reset are explicit pins; drivers change inputs with clock low and sample settled
outputs after rising edges. AXI waits and other polling loops have bounds.

Simulation uses `--x-initial 0` to match the original standalone Verilator
runner's startup state. SpinalSim otherwise randomizes uninitialized registers;
the reference has unreset write-enable distribution stages that can overwrite
initialized calibration entries during reset under that mode. Random-startup
robustness is therefore outside this regression's equivalence contract. The
reference RTL is preserved, including this behavior.

The index diagnostic also preserves the original inter-shot cadence (eight
clocks after observing idle, then six setup clocks). At decimation greater than
one, the reference can keep advancing segment-position state while idle; a
longer wait changes later adopted-index measurements. The test records the
original stimulus and its published values, rather than imposing a new
idle-state policy.

| Scala test | Original coverage |
| --- | --- |
| `cordic` | `tb_cordicg`: 256 phases, gain retained, error below 64 datapath LSB; checks both I and Q |
| `profiles` | Both Python generation groups: linear/minjerk preparation, cubic exact endpoints at dec=1,2,4,8,16, fit deviation at most 6000 LSB |
| `pulsed` | `tb_aod_tg_tone1`: 20,000 golden samples within 4 DAC LSB, eight arbitrary deltas, exact endpoints, ratio 4/7 clamp equivalence, exact decimated durations |
| `indices` | `tb_aod_tg_index`: runtime identity trajectory, adopted indices at all five decimations |
| `cw` | `tb_aod_tg_cw`: continuous phase and valid stream, 22-clock dwell, measured period, modular periodicity solve, identical I/Q sweeps |
| `gcal256`, `gcal128`, `gcal128TwoCurves` | All three `tb_aod_gcal` configurations: unity, impulse addressing, signed clamp/wrap, both interpolation slopes, zoom, bypass, independent curves |
| `registers` | `tb_aod_tg_regs`: all tone fields, invalid accesses, capabilities, masks, configuration status, counter, sweep/phase snapshot, pulsed silence, clipping/W1C, triggers, queued/dropped launches, soft reset |
| `gcalAxi` | `tb_aod_tg_gcal`: all entries and both channels, per-tone copies, selector isolation, trajectory/envelope preservation, configuration distribution, live lookup and bypass |
| `gcalGenerated128`, `gcalGenerated256` | `tb_aod_gcal_real` plus `check_gcal_real.py`: generated calibration tables from the supplied synthetic sweep, 4000 frequency offsets plus four clamp/wrap cases, exact model agreement |
| `spectrumCw` | `tb_aod_tg_spectrum` and `tg_spectrum.py`: final packed converter words for a +20 MHz tone; worst spur below -70 dBc and mirrored image below -40 dBc |
| `spectrumNegative` | Final packed converter words for a -20 MHz tone; mirrored image below -40 dBc |
| `spectrumComb` | Eight enabled tones from -20 to +20 MHz; every peak within 0.05 MHz of its target and level spread below 1.5 dB |
| `spectrumChirp` | Repeating -10 to +10 MHz sweeps using linear and minimum-jerk trajectories; linear ripple at most 3 dB and less than minimum-jerk ripple |

There are sixteen uTest cases: the original nine simulation runs, the two Python
groups combined into `profiles`, and six cases for the two additional bench
families. All nine SV testbench files in the reference checkout now have their
functional scenarios represented. Failed Scala assertions and failed Python
checks fail the Mill command.
Compared with the original benches, the adopted indices are asserted rather than
only printed; calibration initialization and replication cover both tones on both
channels; and the AXI driver checks read and write responses. The additional
benches' dump-completion messages alone do not count as a pass: their Python
comparisons must also pass. The comb analyzer originally only reports levels;
Scala additionally asserts peak locations and a spread bound that allows for
FFT-window scalloping. Chirp comparison additionally asserts that the linear
profile is flatter than minimum-jerk, rather than just printing the comparison.

Spectral tests record 65,536 raw converter words per capture after startup has
settled and require that the capture did not clip. They exercise summation,
16-to-14-bit rounding, and final I/Q packing. The linear chirp table is generated
for decimation 16, matching the chirp stimulus. The supplied minimum-jerk table
is used for the comparison; it is not required to have a flat spectrum.

The calibration test uses `examples/example_sweep.txt`, which is explicitly
synthetic. The original `gen_gcal_table.py` produces both the table and its
placement metadata; Scala loads it into the BlackBox, sweeps frequency, and
passes the captured results to the original `check_gcal_real.py`. Both signed
slope directions are required in the fixture. This proves consistency of the
generated table and the fixed-point RTL interpretation without needing the
absent measured calibration files. It does not validate a physical crystal's
calibration. Dumps and analysis logs are under `aod/simWorkspace/calibration/`
and `aod/simWorkspace/spectrum/`.

The tone adapter pins the shipped 6250-step, 64-row, 98-step geometry and 16-bit
DAC format. The profile test checks these fixture assumptions explicitly. The
register tests use two and four tones per channel on two channels, while spectral
tests use eight tones on one channel, as in the original benches. They do not test
default-size FPGA timing or analog behavior. GUI/CLI/agent behavior, Vivado build
gates, board measurements, exhaustive parameter coverage, and randomized-startup
robustness remain outside this suite.

This establishes executable expectations before translating the datapath. Later
SpinalHDL implementations can replace individual bindings while retaining the
same drivers and assertions. Golden-vector tolerance is historical (the vector
predates a CORDIC change); future direct RTL-versus-Spinal comparisons should aim
for exact integer equality. No scheduling, bus bridge, converter adaptation, or
finish-and-hold behavior is added here.

Validated on 2026-10-18 with Verilator 5.020 and seed 42:

- Original `run_tests.sh`: 11 groups passed, 0 failed, with generated tables
  redirected outside the reference checkout.
- `./mill --no-server aod.test`: 16 cases passed, 0 failed (about 70 seconds
  with cached dependencies and simulator builds on the development machine).
- Golden waveform: 20,000 samples, maximum deviation 2 DAC LSB against the
  preserved vector (allowed 4). CORDIC: maximum error 8 of its 20-bit LSB.
- CW: period 6669 clocks, reload dwell 22 clocks, adjacent captures of 6668
  I/Q samples bit identical; the final run checked 93,235 exact phase advances.
- Generated calibration tables: 4004/4004 outputs bit exact at each of 128 and
  256 entries, including the added clamp/wrap cases.
- Final converter-word spectra: worst CW spur -74.5 dBc; mirrored image
  -82.4 dBc for both frequency signs; eight-tone level spread 0.75 dB;
  linear chirp ripple 0.83 dB versus minimum-jerk ripple 2.91 dB.
- In a temporary copy of the reference, disabling calibration writes made both
  the module and AXI calibration tests fail. Reloading phase during CW restart
  made the phase-continuity assertion fail. The combined mutation run returned
  a nonzero exit status with all three selected cases failing as intended.
- Additional mutations in a temporary reference copy: swapping final I/Q lanes
  failed `spectrumNegative`; treating a negative interpolation slope as unsigned
  failed `gcalGenerated128` with 1085 mismatched points. Both failures propagated
  from the original Python checkers to the Scala runner and Mill exit status.

Local logs include `out/aod-test.log`, `out/aod-analysis-tests.log`,
`out/aod-mutations.log`, `out/aod-analysis-mutations.log`, and
`out/aod-analysis-restored.log`; generated files and logs are not tracked.
