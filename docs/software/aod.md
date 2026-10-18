# AOD notebook workflow

Start with [examples/aod_moves.ipynb](../../examples/aod_moves.ipynb). It is the
AOD counterpart of `amplitude_sweep.ipynb`: a complete experiment compiled from
Python and executed by the actual RISC-V CPU in Verilator's AodSoc RTL simulation.
It requires no board, network board address, Vivado, bitstream or atom model.
Cocotb supplies host AXI transactions and clocks; it observes native complex X/Y
output. It does not emulate the CPU instructions or synthesize replacement waves.

## Environment

From the repository root:

```sh
# One-time setup; Python packages go into software/.venv, not the system Python.
uv sync --project software --extra notebook --locked
uv run --project software --extra notebook --locked jupyter lab examples/aod_moves.ipynb
```

Select the environment's Python 3 kernel. Launching Jupyter with this command
makes that environment available to the notebook and its simulator subprocess.
`software/.python-version` selects Python 3.12; `software/uv.lock` records exact
Python dependency versions. Normal `pip install -e software` still installs only
the board-compatible base dependencies. The `cal` extra remains separate; the AOD
waveform demo needs neither QuTiP nor SciPy.

Native tools are external prerequisites: Java 21, Verilator, make, g++, the
checked-in Mill launcher/submodules, and RISC-V LLVM/binutils commands
`riscv64-unknown-elf-{clang,objcopy,nm,objdump}` on PATH. For this workspace the
compiler tools are under:

```sh
export PATH="$HOME/.local/opt/riscv-llvm-2025.01.20/bin:$PATH"
```

Use the path to your own toolchain on another machine. `simulator.preflight()`
reports missing commands before the long build. Python packages cannot install
those native tools. A first Mill run may download its pinned JVM dependencies.
The tested native stack here is Java 21, Verilator 5.020 and clang 19.1.7.

The notebook extra pins **cocotb 1.8.1** for the installed Verilator 5.020 and
Python 3.12. Its [simulator requirements](https://docs.cocotb.org/en/v1.8.1/simulator_support.html#verilator)
include Verilator 5.006+, and its [release notes](https://docs.cocotb.org/en/v1.8.1/release_notes.html#cocotb-1-8-1-2023-10-06)
add Python 3.12 support. Newer cocotb runner APIs/Verilator requirements differ;
update and test this pin deliberately rather than installing an arbitrary latest
version beside the locked environment.

Execute headlessly, saving a separate executed copy under ignored `out/`:

```sh
uv run --project software --extra notebook --locked jupyter nbconvert \
  --to notebook --execute examples/aod_moves.ipynb \
  --output aod_moves.executed.ipynb --output-dir out \
  --ExecutePreprocessor.timeout=600
```

Jupyter kernels and the simulation driver use **localhost sockets**. Sandboxed
execution needs permission to create those sockets; ordinary local execution
needs no external network server. Configuration/cache directories can be placed
under `out/` using `JUPYTER_CONFIG_DIR`, `JUPYTER_RUNTIME_DIR`, `IPYTHONDIR` and
`MPLCONFIGDIR` if the home directory is read-only.

## What the notebook shows

The steps deliberately distinguish host preparation, host setup and experiment
execution:

1. Start the RTL simulator and consume its emitted `aod-soc.json` manifest.
2. Define a Python `@kernel` using AOD operations, integer control flow, named
   runtime parameters and a result array.
3. Compile using the shared RISC-Q frontend/IR/C emitter and display the actual
   generated C. Cross-compile/link it and display the exact ELF's RISC-V
   disassembly and image bytes. This is the image subsequently uploaded.
4. Stop/flush, load firmware, then upload the trajectory, amplitude envelope and
   per-axis gain tables through host AXI. Successful table responses mean the
   physical bank write has drained. Tables stay fixed during execution.
5. Set host parameters, arm capture, release CPU reset, and let the soft core
   schedule paired X/Y jobs through the posted-write timed queue.
6. Read named results; compare CPU-recorded times with actual output-valid
   transitions. Plot amplitude, instantaneous frequency and off intervals.
7. Stop/flush, modify only the initial phase parameter and repeat, reusing both
   firmware and coefficient tables. Shared time continues across runs.
8. Shut down the local simulator.

The interactive configuration uses **two tones per axis**, one enabled, and a
**two-entry pending queue**. It retains the production 6,250-step movement and
64×98 profile geometry, and the DSP clock runs at 400 MHz in the bench. This makes
interactive simulation cheaper than 32 parallel tones without replacing waveform
arithmetic. The normal `GenAodSoc` hardware default is still 32 tones per axis;
`GenAodSoc <dir> --sim` builds the observation wrapper used here.

The wrapper adds only `sampleTime`, wired to the shared DSP counter. Every
program/coefficient load still traverses the host AXI path. The CPU remains the
sole runtime writer. Captures sample stable native outputs half a DSP period
after the consuming edge, with `iq[clock, axis, I/Q]` signed int16 and a valid bit
per axis. I is the low-word cosine, Q is the high-word sine. Off clocks are kept,
not stitched away. No board converter latency is modeled.

## API and compiler contract

```python
from riscq.lang import Array, kernel
from riscq.aod import AodMap, AodSession, compile_kernel
from riscq.sim import aod as simulator
```

`riscq.aod.compile_kernel` reuses `riscq.lang`; it selects `riscq_aod.h` instead
of the superconducting runtime header. Supported parameters are `int` and
`Array`. Host-window output arrays are supported too. Superconducting ParamTables,
channel mappings, cross-core operations and the calibration DSL are not AOD APIs.

Only the marked function is compiled. The shared subset uses signed int32
arithmetic with wrapping C compilation, `range` loops, `while`, conditionals,
integer/array operations and positional intrinsic calls. Float/NumPy operations
stay on the host. For runtime negation use `0 - value` (the current shared
frontend only accepts unary `-` on constants). The compiler validates language
structure, not arbitrary programs' FIFO capacity or deadline safety.

AOD C-header intrinsics imported from `riscq.aod` are:

| Intrinsic | Purpose |
| --- | --- |
| `aod_init()` | Initialize every tone, including disabled tones; start with zero masks/amplitudes, unity calibration endpoints and gain lookup enabled |
| `aod_rate(ratio, decimation)` | Stage rate/decimation |
| `aod_axis(axis, mask_lo, mask_hi, amplitude)` | Stage tone enable masks and Q1.15 axis amplitude |
| `aod_tone(axis, tone, frequency, displacement, phase0, cal_a, cal_b)` | Stage raw tone codes; calibrated endpoint values are Q1.15 |
| `aod_fire_at(time)` | Stage an absolute timestamp and atomically enqueue paired X/Y parameters |
| `aod_start_spacing(ratio)` | Conservative sufficient separation between starts |
| `now()`, `wait_until(time)` | Existing shared time and comparator wait |

These names are compiler intrinsics; calling them directly in host Python raises
an error. `AodMap.frequency(hz)` produces signed int32 phase-increment bit patterns
for frequency/displacement strictly inside complex Nyquist; `phase(turns)` wraps
to a turn and uses the same bit-pattern convention. `amplitude(fraction)` encodes
a Q1.15 multiplier in [0,1], not absolute optical power. Result timestamps are
int32 storage: interpret them modulo `2**32`, as the notebook does.

`AodSession.load(program)` preserves the Program's parameter/result metadata.
`parameters(**values)` writes runtime scalar globals while stopped. `result(name)`
reads named arrays after completion. `stop()` holds CPU reset and flushes AOD;
RAM and coefficient tables survive. `Image.elf_path` and
`Image.disassemble("main")` expose locally compiled artifacts; images received
over the driver wire need not have a local ELF. Raw handwritten C and
`AodMap.compile(source)` remain supported.

## Scheduling, physics and hardware boundary

The example uses generous upstream enqueue lead, conservative start spacing and
`wait_until` pacing. It stages the next pulse while the current one is active,
exercising the immutable queued-parameter contract. It waits for final drain
before returning; CPU DONE alone is not a waveform-completion guarantee.
See [AodSoc](../soc/AodSoc.md) and [scheduling](../../aod/SCHEDULING.md) for the
full reset, capacity, signed timestamp-window and timing rules.

RF output is **zero between pulses**. A completed frequency sweep does not hold
an atom at its endpoint; another trap/transfer protocol must cover RF-off
intervals. Fresh phase is explicitly supplied per job. The notebook demonstrates
digital minimum-jerk frequency trajectories, not calibrated atom positions or a
quantum/optical dynamics simulation.

Board RFDC packing, interpolation/mixing, physical latency, board-driver/overlay
bring-up and 400-MHz synthesis/place-and-route closure remain separate milestones.
No existing superconducting board script is implicitly converted by this notebook.

## Validation and artifacts

The checked-in notebook includes the executed C, disassembly and plot. The first
elaboration/Verilator build is cached under `software/build/aod-notebook/`; RTL
cache keys include AOD/timing sources and coefficient resources, and simulator
build keys include the emitted RTL/init files, cocotb and Verilator versions.
One live simulator per build directory is supported. To use another concurrent
session, pass a different directory to `simulator.start(build_dir=...)`.

The notebook's last cell closes the simulator. If a cell fails during interactive
work, run that cleanup cell before restarting; `with simulator.start() as driver:`
is available for scripts/tests. Logs are `elaboration.log` and `cosim.log` in the
build directory. Refresh or clear notebook outputs before sharing if local build
paths and timestamps are unwanted; there are no board credentials in this demo.

```sh
uv run --project software --extra notebook --locked pytest software/tests/test_aod.py \
  software/tests/test_build.py software/tests/test_kernel.py software/tests/test_lang.py -q
uv run --project software --extra notebook --locked pytest software/tests/test_aod_cosim.py \
  software/tests/test_boot.py --cosim -q
```

The AOD co-sim test checks fixed frequencies against analytic phase increments,
a quarter-turn phase change against multiplication by `i`, exact start timestamps,
X/Y alignment, zero off intervals, error responses and parameter-only reruns.
Existing superconducting CPU boot tests exercise the extracted shared AXI/DDR
models and editable-install launcher path. Logs from this round live under
`out/aod-notebook-{unit,cosim-tests,execute}.log`.
