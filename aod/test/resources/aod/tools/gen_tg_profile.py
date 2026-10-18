#!/usr/bin/env python3
"""gen_tg_profile.py -- trajectory and envelope ROMs for the standalone tone generator.

Writes aod_unit.mem (the sweep SHAPE) and aod_seed.mem (the amplitude ENVELOPE) for
aod_tg_tone1. Both are $readmemh defaults AND runtime-writable over AXI (rom_sel 5 and
6), so a profile change is a register write, not a rebuild -- see tonegen.py --profile.

    gen_tg_profile.py --out roms_tg_linear  --profile linear
    gen_tg_profile.py --out roms_tg_minjerk --profile minjerk --src path/to/minjerk


WHY LINEAR IS THE DEFAULT, AND WHY IT IS NOT WHAT THE PRODUCTION BUILD SHIPS
===========================================================================
The production trajectory is a minimum-jerk spline: zero velocity at both ends,
~3.5x mean velocity at the midpoint. That is exactly right for MOVING AN ATOM --
it is the profile that does not shake the tweezer -- and exactly wrong for
MEASURING A SIGNAL CHAIN.

A chirp on a min-jerk profile has a BOWED spectrum. The tone dwells near the
endpoints and races through the middle, so energy piles up at the edges and
thins in the centre: horns at both ends, ~8-24 dB of sag between them. That is
measured behaviour on this hardware, not theory. Sweep a bowed chirp through a
filter you are trying to characterise and you cannot tell the filter's response
from the chirp's own shape.

A LINEAR ramp spends equal time at every frequency, so its spectrum is flat and
whatever structure you see belongs to the thing under test. That is what a bench
instrument wants, so it is the default here. Both profiles are shipped; the
production one is available with --profile minjerk for measurements that need to
reproduce what the rearrangement build actually emits.


THE ENDPOINT, AND WHY THIS SCRIPT NEEDS TO KNOW `dec`
====================================================
aod_tg_tone1 lands EXACTLY on f_end because the trajectory terminates at exactly
2**UW and the scale path computes (d_word * 2**UW) >>> UW == d_word. That
identity holds only if the terminal value sits on the trajectory index the
hardware actually ADOPTS at the end of the sweep -- and that index depends on
`dec`, because decimation advances the difference chain `dec` times per slow step
and the last slow step therefore lands on a different count.

The minimum-jerk profile never had to care: it decelerates to a stop, so its last
~630 samples are ALL exactly 2**UW and any endpoint in that region gives the same
answer. A linear ramp has no flat tail -- its value changes by a full step every
sample -- so landing one index early is a real frequency error.

ADOPTED_INDEX below is MEASURED, by AodTests.indices, which loads a
trajectory whose value IS its own index and reads the adopted one straight out of
inc_live. It is not derived, because deriving it means modelling the two PRIME
emits, the emit-leads-by-one rule and the ceil() in the end-of-move compare, and
getting any of the three wrong produces a plausible number that is quietly wrong.
The bench runs in the regression suite, so the table is re-checked, not trusted.

    --dec N        make the endpoint exact for that decimation (default 1)
    --universal    flat FINAL SEGMENT instead, valid for every dec -- but the
                   1.57% dwell it costs is a real spur on the chirp spectrum, so
                   it is not the default. Use it only when one ROM has to serve
                   several decimations and endpoint accuracy matters more than
                   spectral flatness.


THE ENVELOPE IS FLAT, AND AT FULL SCALE
=======================================
The production envelope ramps up and down (it is an amplitude profile for picking
an atom up and putting it down) and peaks at HALF the rotator's full scale. A
bench instrument wants neither: constant amplitude, so a chirp's power does not
change as it sweeps, and full scale, so the host owns the whole dynamic range
through amp_scale rather than losing the top 6 dB to a ROM constant.

Per-tone amplitude still interpolates cal_a -> cal_b across the sweep; set them
equal for constant amplitude, unequal for a deliberate ramp. That is the knob for
amplitude shaping, not this ROM.

NOTE ON HEADROOM: N tones at full scale sum to N x full scale and the limiter
will clip flat. That is what amp_scale exists for, and what the clip counter
exists to report. The ROM deliberately does NOT pre-divide by N -- the right
divisor depends on the phase set's crest factor, which is a runtime choice.
"""
import argparse, json, math, os, shutil, sys

# MEASURED by AodTests.indices on the geometry below. Re-run that
# bench if MOVE_STEPS / NSEG / SEG_LEN ever change -- the numbers do not scale.
ADOPTED_INDEX = {1: 6250, 2: 6248, 4: 6240, 8: 6230, 16: 6208}
ADOPTED_GEOM  = (6250, 64, 98)          # (MOVE_STEPS, NSEG, SEG_LEN)


def cordic_gain(nstage):
    """Growth of an unnormalised CORDIC rotation. aod_tg_tone1 instantiates cordicg
    with NORMALIZE=0 and CG_NSTAGE=22, so the seed must be pre-divided by this."""
    g = 1.0
    for i in range(nstage):
        g *= math.sqrt(1.0 + 2.0 ** (-2 * i))
    return g


def emit_unit(path, rom, acc, ndiff):
    """One row per segment, fields packed LSB-first: field j occupies
    urow[j*ACC +: ACC], so it is written LAST in the hex string."""
    w = acc // 4
    with open(path, "w") as fh:
        for row in rom:
            fh.write("".join("%0*x" % (w, row[ndiff - 1 - j] & ((1 << acc) - 1))
                             for j in range(ndiff)) + "\n")


def emit_seed(path, rows, aw):
    """srow = {astep, aacc}: aacc is srow[0 +: AW] so it is written LAST."""
    w = (aw + 3) // 4
    m = (1 << aw) - 1
    with open(path, "w") as fh:
        for aacc, astep in rows:
            fh.write("%0*x%0*x\n" % (w, astep & m, w, aacc & m))


def build_linear(uw, acc, ndiff, nseg, seg_len, move_steps, end_index):
    """U(m) = m * 2**UW / end_index, as one linear piece per segment.

    d[0] is reloaded from ROM at every segment boundary, so rounding error cannot
    accumulate across the move -- only within a segment, where it is bounded by
    SEG_LEN * 0.5 = 49 LSB of 2**30, i.e. 4.6e-8 of the span. The segment holding
    the endpoint gets its d[0] back-solved so the terminal value is EXACT.
    """
    if ndiff < 2:
        sys.exit("linear needs NDIFF >= 2 (d[0] value + d[1] step)")
    target = 1 << uw
    d1 = int(round(target / float(end_index)))
    s_end = end_index // seg_len
    off = end_index - s_end * seg_len
    if s_end >= nseg:
        sys.exit("endpoint index %d falls past the last segment (%d)" % (end_index, nseg))

    rom = []
    for s in range(nseg):
        base = s * seg_len
        d0 = int(round(base * target / float(end_index)))
        if s == s_end:
            # Back-solve so d0 + off*d1 == 2**UW to the last bit. Shifts this one
            # segment by at most SEG_LEN/2 LSB, which is invisible, and buys an
            # endpoint that is exact rather than nearly right.
            d0 = target - off * d1
        rom.append([d0, d1] + [0] * (ndiff - 2))
    return rom, d1, s_end, off


def build_cubic(uw, acc, ndiff, nseg, seg_len, move_steps, end_index):
    """U(m) = target * (3t^2 - 2t^3), t = m/end_index -- "smoothstep".

    ZERO VELOCITY AT BOTH ENDS, which is the point: a linear ramp starts and stops
    the tone instantaneously, and on an AOD that is the thing that shakes a
    tweezer. This is the cheapest curve that does not.

    WHERE IT SITS, because the tree already ships the two profiles either side of
    it and picking blind is how the wrong one gets used:

        linear    constant velocity, discontinuous at both ends. The FLATTEST
                  chirp spectrum (0.59 dB ripple vs min-jerk's 3.03) and
                  therefore the right choice for MEASURING a signal chain.
        cubic     zero velocity at the ends, non-zero ACCELERATION -- the jerk is
                  a step at t=0 and t=1.
        minjerk   the production quintic: zero velocity AND acceleration at both
                  ends. Strictly smoother than this, and what the rearrangement
                  build actually moves atoms with. Its cost is a BOWED spectrum:
                  it dwells at the endpoints and races through the middle.

    So cubic is a middle: smoother than linear for moving something real, and
    less spectrally bowed than min-jerk. If the question is "which one moves an
    atom without losing it", the answer is min-jerk, not this.

    HOW IT FITS IN A ROM THAT ONLY CARRIES QUADRATICS. The difference chain is
    NDIFF=3 deep, so within a segment the hardware walks
        u(k) = d0 + k*d1 + k(k-1)/2*d2
    -- an exact quadratic and nothing more. A cubic cannot be one of those. It
    does not need to be: d[] is RELOADED FROM ROM at every segment boundary, so
    the curve is piecewise quadratic over NSEG=64 pieces and the error never
    accumulates across a boundary -- only within one segment, where it is bounded
    by the cubic's own third difference.

    The three coefficients are fitted by interpolating U at the segment's START,
    MIDPOINT and END rather than by taking Newton differences at the start.
    Same cost, same ROM, and it is worth roughly 4x: differences taken at the
    start are exact there and drift monotonically across the segment, while a
    three-point fit puts the error where it belongs, in the interior. The
    achieved number is MEASURED by replaying the chain (see the report), not
    predicted here.
    """
    if ndiff < 3:
        sys.exit("cubic needs NDIFF >= 3 (value + step + curvature); this build has %d"
                 % ndiff)
    target = 1 << uw

    def U(m):
        # HELD, NOT CONTINUED, PAST THE ENDPOINT. 3t^2-2t^3 turns over at t=1 and
        # DECREASES after it, so extending the polynomial would run the frequency
        # BACKWARDS past the end of the move. Nothing downstream would report it;
        # the tone would simply start coming back.
        if m >= end_index:
            return float(target)
        t = m / float(end_index)
        return target * (3.0 * t * t - 2.0 * t * t * t)

    rom = []
    for s in range(nseg):
        base = s * seg_len
        if base >= end_index:
            rom.append([target] + [0] * (ndiff - 1))     # flat tail
            continue
        # THE FIT NODES MUST ALL LIE IN THE LIVE PART OF THE SEGMENT, and the one
        # segment that STRADDLES the endpoint is where that stops being automatic.
        # U is held flat past end_index (see above), so on that segment a node at
        # k = SEG_LEN sits on the clamp rather than on the curve, and it drags the
        # quadratic across the whole live span: MEASURED, that one segment fitted
        # to 16698 LSB while every other segment fitted to 398. Clamping the fit
        # span to the live part fixes it, and the samples past end_index that the
        # fit then ignores are never emitted -- the sweep has already ended.
        h = min(seg_len, end_index - base)
        a = h // 2
        if a < 1:                       # a 1-sample tail: nothing to fit, hold it
            rom.append([int(round(U(base)))] + [0] * (ndiff - 1))
            continue
        u0, um, uh = U(base), U(base + a), U(base + h)
        # u(k) = d0 + k*d1 + k(k-1)/2*d2, solved at k = 0, a, h
        #   um - u0 = a*d1 + a(a-1)/2*d2
        #   uh - u0 = h*d1 + h(h-1)/2*d2
        ca, cb = a * (a - 1) / 2.0, h * (h - 1) / 2.0
        det = a * cb - h * ca
        d1 = ((um - u0) * cb - (uh - u0) * ca) / det
        d2 = (a * (uh - u0) - h * (um - u0)) / det
        rom.append([int(round(u0)), int(round(d1)), int(round(d2))]
                   + [0] * (ndiff - 3))

    # THE ENDPOINT IS BACK-SOLVED EXACT, exactly as build_linear does and for the
    # same reason: a move that ends a few LSB short puts every tone a few LSB off
    # its destination frequency, forever, and the error is invisible in a
    # spectrum. Shifting one segment's d0 by the fit residual is not.
    s_end, off = divmod(end_index, seg_len)
    if s_end >= nseg:
        sys.exit("endpoint index %d falls past the last segment (%d)" % (end_index, nseg))
    d1, d2 = rom[s_end][1], rom[s_end][2]
    rom[s_end][0] = target - off * d1 - (off * (off - 1) // 2) * d2
    return rom, s_end, off


def build_universal(uw, acc, ndiff, nseg, seg_len, move_steps):
    """Linear over segments 0..NSEG-2, FLAT over the last segment.

    Every adopted index for every supported dec (6208..6250) lies inside the last
    segment (which starts at 6174), so the endpoint is exact for all of them. The
    cost is a dwell over 1.57% of the sweep, which puts a real line on an
    otherwise flat chirp spectrum -- hence not the default.
    """
    target = 1 << uw
    end_index = (nseg - 1) * seg_len          # the ramp finishes here
    d1 = int(round(target / float(end_index)))
    rom = []
    for s in range(nseg - 1):
        d0 = int(round(s * seg_len * target / float(end_index)))
        rom.append([d0, d1] + [0] * (ndiff - 2))
    rom.append([target] + [0] * (ndiff - 1))  # flat: value target, step 0
    return rom, d1, end_index


def simulate(rom, ndiff, nseg, seg_len, acc):
    """Replay the hardware's difference chain exactly, so the report below states
    what the RTL will produce rather than what the maths intended."""
    m = (1 << acc) - 1
    us, d = [], list(rom[0])
    for seg in range(nseg):
        for k in range(seg_len):
            us.append(d[0])
            if k == seg_len - 1:
                d = list(rom[seg + 1]) if seg + 1 < nseg else list(d)
            else:
                for j in range(ndiff - 1):
                    d[j] = (d[j] + d[j + 1]) & m
    return us


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True)
    ap.add_argument("--profile", choices=("linear", "cubic", "minjerk"), default="linear")
    ap.add_argument("--src", required=True,
                    help="minimum-jerk coefficient directory containing aod_unit.mem and aod_seed.mem")
    ap.add_argument("--dec", type=int, default=1,
                    help="decimation the endpoint must be exact for (linear and cubic)")
    ap.add_argument("--universal", action="store_true",
                    help="flat final segment: exact for every dec, at the cost of a spur")
    ap.add_argument("--envelope", choices=("flat", "copy"), default="flat")
    ap.add_argument("--envelope-scale", type=float, default=1.0,
                    help="fraction of rotator full scale for the flat envelope")
    ap.add_argument("--acc", type=int, default=32)
    ap.add_argument("--uw", type=int, default=30)
    ap.add_argument("--ndiff", type=int, default=3)
    ap.add_argument("--nseg", type=int, default=64)
    ap.add_argument("--seglen", type=int, default=98)
    ap.add_argument("--move-steps", type=int, default=6250)
    ap.add_argument("--xw", type=int, default=20)
    ap.add_argument("--amp-frac", type=int, default=16)
    ap.add_argument("--cordic-nstage", type=int, default=22)
    a = ap.parse_args()

    ACC, UW, ND, NSEG, SL, MS = a.acc, a.uw, a.ndiff, a.nseg, a.seglen, a.move_steps
    AW = a.xw + a.amp_frac
    os.makedirs(a.out, exist_ok=True)

    # ---- trajectory ------------------------------------------------------
    if a.profile == "minjerk":
        src = os.path.join(a.src, "aod_unit.mem")
        if not os.path.exists(src):
            sys.exit("no %s -- --src must point at a normalised ROM set" % src)
        shutil.copy(src, os.path.join(a.out, "aod_unit.mem"))
        rom = None
        note = "copied from %s" % src
    elif a.universal:
        rom, d1, end_index = build_universal(UW, ACC, ND, NSEG, SL, MS)
        emit_unit(os.path.join(a.out, "aod_unit.mem"), rom, ACC, ND)
        note = "linear, flat final segment, exact for every dec"
    elif a.profile == "cubic":
        if (MS, NSEG, SL) != ADOPTED_GEOM:
            sys.exit("ADOPTED_INDEX was measured for MOVE_STEPS/NSEG/SEG_LEN=%s, not %s.\n"
                     "Re-run AodTests.indices on the new geometry and update "
                     "the table -- the indices do NOT scale." % (ADOPTED_GEOM, (MS, NSEG, SL)))
        if a.dec not in ADOPTED_INDEX:
            sys.exit("no measured endpoint index for dec=%d (have %s). Add it with "
                     "AodTests.indices rather than interpolating."
                     % (a.dec, sorted(ADOPTED_INDEX)))
        end_index = ADOPTED_INDEX[a.dec]
        rom, s_end, off = build_cubic(UW, ACC, ND, NSEG, SL, MS, end_index)
        emit_unit(os.path.join(a.out, "aod_unit.mem"), rom, ACC, ND)
        note = "cubic smoothstep 3t^2-2t^3, endpoint exact at index %d (dec=%d)" % (end_index, a.dec)
    else:
        if (MS, NSEG, SL) != ADOPTED_GEOM:
            sys.exit("ADOPTED_INDEX was measured for MOVE_STEPS/NSEG/SEG_LEN=%s, not %s.\n"
                     "Re-run AodTests.indices on the new geometry and update "
                     "the table -- the indices do NOT scale." % (ADOPTED_GEOM, (MS, NSEG, SL)))
        if a.dec not in ADOPTED_INDEX:
            sys.exit("no measured endpoint index for dec=%d (have %s). Add it with "
                     "AodTests.indices rather than interpolating."
                     % (a.dec, sorted(ADOPTED_INDEX)))
        end_index = ADOPTED_INDEX[a.dec]
        rom, d1, s_end, off = build_linear(UW, ACC, ND, NSEG, SL, MS, end_index)
        emit_unit(os.path.join(a.out, "aod_unit.mem"), rom, ACC, ND)
        note = "linear, endpoint exact at index %d (dec=%d)" % (end_index, a.dec)

    # ---- envelope --------------------------------------------------------
    gain = cordic_gain(a.cordic_nstage)
    if a.envelope == "copy":
        src = os.path.join(a.src, "aod_seed.mem")
        if not os.path.exists(src):
            sys.exit("no %s" % src)
        shutil.copy(src, os.path.join(a.out, "aod_seed.mem"))
        full = None
        env_note = "copied from %s" % src
    else:
        # Two LSB of margin below the rotator's own full scale: the CORDIC gain is
        # irrational and the last bit of the seed is not worth a clipped sample.
        full = int((2 ** (a.xw - 1) - 1) / gain) - 2
        full = int(round(full * a.envelope_scale))
        if full <= 0 or (full << a.amp_frac) >= (1 << AW):
            sys.exit("envelope seed %d does not fit in AW=%d" % (full, AW))
        emit_seed(os.path.join(a.out, "aod_seed.mem"),
                  [(full << a.amp_frac, 0)] * NSEG, AW)
        env_note = "flat, seed %d (%.3f of full scale)" % (full, a.envelope_scale)


    meta = dict(profile=a.profile, universal=bool(a.universal), dec=a.dec,
                acc=ACC, uw=UW, ndiff=ND, nseg=NSEG, seg_len=SL, move_steps=MS,
                xw=a.xw, amp_frac=a.amp_frac, cordic_nstage=a.cordic_nstage,
                cordic_gain=gain, envelope=a.envelope,
                envelope_seed=full, envelope_scale=a.envelope_scale,
                trajectory_note=note)
    with open(os.path.join(a.out, "tg_profile.json"), "w") as fh:
        json.dump(meta, fh, indent=2, sort_keys=True)

    # ---- report, from a REPLAY of the chain rather than from the intent ----
    print("wrote %s" % a.out)
    print("  trajectory : %s" % note)
    print("  envelope   : %s   (cordic gain %.6f)" % (env_note, gain))
    if rom is not None:
        us = simulate(rom, ND, NSEG, SL, ACC)
        target = 1 << UW
        if not a.universal:
            got = us[end_index]
            print("  endpoint   : u[%d] = 0x%X, target 0x%X, error %d LSB"
                  % (end_index, got, target, got - target))
            if got != target:
                sys.exit("ENDPOINT IS NOT EXACT -- refusing to ship this ROM")
        else:
            bad = [i for i in ADOPTED_INDEX.values() if us[i] != target]
            print("  endpoint   : exact at every adopted index %s"
                  % sorted(ADOPTED_INDEX.values()) if not bad else "MISMATCH at %s" % bad)
            if bad:
                sys.exit("ENDPOINT IS NOT EXACT at %s -- refusing to ship this ROM" % bad)
        # DEVIATION FROM THE CURVE THIS PROFILE IS TRYING TO BE, not from a line.
        # Reporting "linearity" for a cubic would score it against a shape it is
        # deliberately not, and the number would look like a catastrophe.
        n = end_index
        if a.profile == "cubic":
            def ideal(i):
                t = i / float(n)
                return target * (3.0 * t * t - 2.0 * t * t * t)
            what = "cubic fit "
        else:
            def ideal(i):
                return i * target / float(n)
            what = "linearity "
        worst = max(abs(us[i] - ideal(i)) for i in range(n + 1))
        print("  %s: worst deviation %.1f LSB of 2^%d = %.2e of span"
              % (what, worst, UW, worst / float(target)))
    print("  files      : aod_unit.mem, aod_seed.mem, tg_profile.json")


main()
