# Scheduled pulsed movements

`AodTop` is the standalone runtime frontend intended for later RISC-Q
integration. It accepts posted `cmd` writes, backpressured coefficient `setup`,
and an external 32-bit `time`. It instantiates the same `TimedQueue` used by
RISC-Q, shared through the `timing` Mill module. There is no SoC address allocation,
bus adapter, firmware driver, clock crossing or converter adapter in this pass.
`AodEngine` remains available for direct waveform control and engine tests;
there is one production runtime top.

## Setup, stage, enqueue, play

1. With no queued or active job, upload trajectory, envelope and calibration
   coefficients through `setup`. Keep the payload stable until `valid && ready`.
   Deassert `setup.valid` after the final transfer and wait for `setup_busy` low.
2. Write the next movement's tone fields, masks, amplitudes, ratio/decimation and
   calibration settings at the offsets in [INTERFACE.md](INTERFACE.md).
3. Write the desired first-sample timestamp to **`0x4100`**, then write anything
   to **`0x0000`** to enqueue. Fire captures all staged parameters across both
   axes and the timestamp in one immutable FIFO entry. Consecutive posted writes
   are supported. Fire does not increment the staged timestamp automatically.
4. Continue staging or queuing later movements while the current one plays.
   Parameter writes affect neither active nor already queued movements.

Snapshots use a ring of memories banked by parameter field. The timed FIFO
stores a slot reference and timestamp; dispatch captures the selected snapshot
into active registers before releasing its slot. This keeps wide tone arrays
out of the timing comparator/head pipeline and supports the full 64-tone
geometry without changing SpinalHDL's signal-width limits.

The historical `0x08` control register is reserved/ignored: no immediate go,
CW, external trigger, software reset or manual snapshot can bypass scheduling.
Each movement is pulsed and reloads its captured per-tone `phase0`. Frequency
words use the existing sample-clock convention. Program all fields before first
use, including fields belonging to disabled tones; tone arrays have no reset.

Coefficient tables are shared setup state, **not copied into FIFO entries**.
The scheduled frontend holds `setup.ready` low while any job is queued,
preparing, playing or draining, including its automatic phase snapshot. Thus an
upload can be offered during playback and will wait until quiescent. While a
setup write is offered or draining, fires are rejected with `setupConflict`.
Setup has priority over a simultaneous fire when quiescent. Finish uploading
before submitting the next batch. Normal eight-clock write spacing still applies.

## Timing and pacing

`time` must increment by one per AOD clock, including across wraparound. Reset
does not reset this external counter. Timestamp comparisons use signed modular
differences; submit near-future timestamps less than `2^31` clocks ahead, in
strictly increasing order while any jobs remain outstanding.

`startTime` identifies the **first valid packed output sample of this component**,
not the analog RF arrival. Board-side converter/interpolation/mixer latency must
be added or calibrated by the eventual adapter. Both axes start together.

For `N = tones`, the exported timing constants are:

| Constant | AOD clocks |
| --- | --- |
| Engine soft reset | 8 |
| Configuration settling | `N + 8` |
| Queue dispatch to first sample, `leadTime(p)` | `69 + N + ceil(log2(N))` |
| Required fire-to-start margin, `minimumEnqueueLead(p)` | `leadTime(p) + 3` |

The default 32-tone geometry therefore needs a 106-clock dispatch lead and at
least 109 clocks from fire acceptance to first sample. These margins are relative
to the native posted frontend; add upstream CPU/link latency before using them in
software. The shared queue dispatches early to capture configuration, soft reset
the tone banks, settle their distribution pipelines, and launch at a fixed time.
The reset preserves tables and avoids the inherited decimated-pulse idle-state
drift described in [INTERFACE.md](INTERFACE.md). Waveform arithmetic is unchanged.

Jobs must be separated enough for the preceding waveform, converter pipeline,
and phase snapshot to finish **before the next job's preparation begins** at
`nextStart - leadTime`. Jobs that violate this rule are dropped with `overlap`;
they are never deferred to a later start. A conservative sufficient start-to-start
spacing after a job with ratio `R`, for any legal decimation, is exported as
`conservativeStartSpacing(p, R)`:

```
moveSteps * R + leadTime(p) + channels * (tones + 8) + 64
```

This deliberately overestimates the required spacing, especially with decimation;
it is not a measured minimum sustainable cadence. Waiting for `schedule.idle`,
then choosing a fresh timestamp with enough enqueue lead is another safe way to
run independent shots. Gapless movement sequencing and finish-and-hold need a
different engine contract and are deferred.

The default FIFO budget is **four pending jobs**, configurable by `queueDepth`,
plus one dispatched active job. The head pipeline counts toward that budget.
`cmd` is a posted `Flow`, so there is no backpressure on runtime stores. Software
must pace writes to respect capacity and lead time. Later RISC-Q firmware can
reuse `wait_until` (the time-comparator write followed by a blocking read); no
new wait instruction is needed. Reusing superconducting pulse-train cadence
constants without measuring the AOD path is unsupported.

## Silence and experimental meaning

Outside valid pulsed output, the scheduled frontend drives **zero** to both
packed outputs. It does not repeat the last movement, continue an endpoint tone,
or hold an atom at its destination. Between an A→B pulse and a later B→C pulse,
there is an RF-off interval. The experiment must arrange suitable confinement,
transfer to another trap, or another deliberate protocol during that interval.
Enqueuing a B→B job still has the pulsed preparation/drain gap; it does not
implement continuous trapping. `phase0` is supplied independently for each pulse;
the scheduler does not infer physical phase continuity across off intervals.

## Status, errors and reset

`schedule.queued` counts pending entries. `active` includes preparation, playback,
sample drain and snapshot drain. An empty FIFO therefore does not imply silence
or availability for setup. `idle` means no pending/active job and no setup write
draining; an offered setup transfer can still be accepted while idle.
`can_enqueue` reports capacity and setup availability; it does not validate the
staged timestamp or parameters. These are native observation ports, not a bus
readback ABI. The future SoC integration must choose a reporting route.

`schedule.errors` contains sticky flags:

| Bit | Name | Effect |
| --- | --- | --- |
| 0 | `overflow` | Fire rejected because the pending budget/FIFO is full |
| 1 | `late` | Fire rejected for insufficient lead or a timestamp outside the future half-window; an overdue dispatch is also discarded |
| 2 | `overlap` | Queued job discarded because a preceding job is still active at its preparation deadline |
| 3 | `invalid` | Fire rejected unless `rateMin <= ratio <= ratioMax` and `1 <= dec <= decMax` |
| 4 | `order` | Fire rejected unless its timestamp follows the last accepted outstanding job |
| 5 | `setupConflict` | Fire rejected while setup is offered or draining |

Several flags can be raised by one malformed fire. Writing `0x18` clears these
and the engine's existing clearable sticky flags; a simultaneous new fault wins.
`accepted`, `started`, `completed` are wrapping 32-bit counters: accepted counts
FIFO insertions, started counts engine launches, completed counts fully drained
jobs. A later overlap/late discard increments no started/completed counter.
Existing engine status and phase-snapshot events remain available separately.

Active-high synchronous `rst` flushes the FIFO, cancels active work, clears
scheduler flags/counters, and forces scheduled output to zero. Tables survive
reset; interrupted uploads must be redone. Reprogram staging after reset. A time
jump can discard an overdue queued job; changing time during active playback is
outside the timing contract and does not retime its waveform.

## Standalone checks

```
./mill --no-server aod.test.testOnly riscq.aod.ScheduledTests
./mill --no-server aod.runMain riscq.aod.AodGenerate out/aod-rtl
```

The scheduler tests compare complete final-output bursts against native-port
`AodEngine`, including signed displacement, decimation, repeated shots and both axes.
They also check staging isolation, exact timestamps/minimum lead, wraparound,
overflow, invalid/order/late/overlap rejection, reset flushing, setup locking,
snapshot-slot reuse, snapshot completeness, and zeros throughout off intervals.
Elaboration covers 32/64-tone two-axis snapshots, non-power-of-two queue depth,
and a single-slot queue. FPGA timing closure
and RF behavior remain board-integration work.
