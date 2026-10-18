#!/usr/bin/env python3
"""gen_gcal_table.py -- build an aod_gcal efficiency table from a measured sweep.

An AOD's diffraction efficiency varies with drive frequency, so a tweezer changes
brightness as it travels. aod_gcal corrects that with a table of the correction
against ABSOLUTE frequency, read by each tone at its own live frequency and
interpolated between entries. This builds that table.

THE ENTRY COUNT MUST MATCH THE BITSTREAM and the two builds differ: the production
rearrangement build ships 128 per axis, the standalone tone generator 256. Pass
--entries to match, and check it against the hardware with `tonegen.py gcal`, which
reads the depth out of GCAL_INFO rather than assuming one.

THE TABLE IS YOURS, NOT HARVARD'S. Their .npz proves the SHAPE and the MAGNITUDE of
the problem -- 1.96x across 75-116 MHz on X, non-monotonic, peaking at 93 MHz -- on
THEIR crystal and THEIR optics. Every AOD needs its own sweep. The Harvard path here
exists so the mechanism can be exercised end to end against real numbers before the
bench sweep exists, and so the two can be compared afterwards.

TWO INPUTS
----------
  --sweep FILE        the normal path. Two columns, "freq_MHz power" -- the measured
                      optical power (or first-order diffracted power, any linear
                      unit) at CONSTANT drive amplitude, swept across the band. Blank
                      lines and #-comments ignored.

  --harvard NPZ       X_POWER_CORRECTION.npz / Y_POWER_CORRECTION.npz. These are
                      correction(frequency, nominal_power) on a 400 x 1000 grid. A
                      rank-1 SVD of the rows that carry any frequency dependence
                      gives correction(f,p) = g(f)*h(p) to ~1.2% rms, and g is the
                      frequency profile this table wants.

                      NOTE ON THAT FILE, BECAUSE IT IS NOT OBVIOUS AND IT MATTERS:
                      only p <= 0.4004 carries frequency information at all. Above
                      it the array is EXACTLY the identity, correction == p, for
                      every frequency -- 601 of its 1000 rows. Those rows are
                      excluded here; including them drags g toward flat and quietly
                      halves the correction.

WHAT COMES OUT
--------------
A .mem file of --entries lines, one 32-bit hex word each: {slope[31:16], value[15:0]}, both
Q1.15 with 32768 = 1.0. `value` is the drive multiplier for that frequency and
`slope` is the step to the next entry, which is what lets the hardware interpolate.

DIRECTION OF THE CORRECTION, STATED EXPLICITLY. The table ATTENUATES: it is
normalised so its largest entry is 1.0, at the LEAST efficient frequency in the band.
Uniform trap depth can only be reached by pulling every efficient frequency DOWN to
the worst one, because there is no headroom above full drive. Anything else would
ask the converter for more than full scale and clip flat -- which is not a subtle
failure but it is an invisible one, since the spectrum still looks like a comb.
"""
import argparse, json, os, sys

import numpy as np

ACC = 32
TABN = 128          # default = GCAL_TABN in the shipped build (halved from 256
                    # for routing -- see cxp_host_fe_top.v). --entries overrides,
                    # and the count is recorded in the .json so the loader
                    # cannot disagree


# =====================================================================
# THE CURVE
# =====================================================================
def g_from_sweep(path):
    """Measured power vs frequency at constant drive. The honest input."""
    rows = []
    for ln in open(path):
        ln = ln.split("#")[0].strip()
        if ln:
            a = ln.replace(",", " ").split()
            rows.append((float(a[0]), float(a[1])))
    if len(rows) < 8:
        sys.exit("%s: need at least 8 sweep points, found %d" % (path, len(rows)))
    rows.sort()
    f = np.array([r[0] for r in rows])
    p = np.array([r[1] for r in rows])
    if (p <= 0).any():
        sys.exit("%s: non-positive power at %.3f MHz -- the correction would divide by it"
                 % (path, f[p <= 0][0]))
    return f, p / p.max()


def g_from_harvard(path):
    """Rank-1 SVD of correction(f, p), restricted to the rows that mean anything."""
    z = np.load(path)
    for k in ("correction_array", "frequencies", "nominal_power"):
        if k not in z:
            sys.exit("%s: not a Harvard correction table (no '%s')" % (path, k))
    C, f, p = z["correction_array"], z["frequencies"], z["nominal_power"]

    # Keep only rows that differ from the identity. See the note in the docstring:
    # above p ~ 0.4 the array is correction == p at every frequency and carries no
    # frequency information whatsoever.
    live = np.abs(C - p[:, None]).max(axis=1) > 1e-6
    live &= p > 0
    if live.sum() < 8:
        sys.exit("%s: only %d rows carry frequency dependence" % (path, live.sum()))

    U, s, Vt = np.linalg.svd(C[live], full_matrices=False)
    rank1 = s[0] ** 2 / (s ** 2).sum()
    sign = np.sign(Vt[0].mean())
    g = Vt[0] * sign
    resid = C[live] - np.outer(U[:, 0] * s[0] * sign, g)
    rms = np.sqrt((resid ** 2).mean()) / C[live].mean()

    print("  harvard : %d/%d power rows carry frequency dependence (p <= %.4f)"
          % (live.sum(), len(p), p[live].max()))
    print("  rank-1  : %.4f%% of energy, %.2f%% rms residual" % (100 * rank1, 100 * rms))
    if g.min() <= 0:
        sys.exit("  the dominant singular vector changes sign -- not a separable "
                 "efficiency curve, refusing to build a table from it")
    return f, g / g.max()


# =====================================================================
# PLACEMENT
# =====================================================================
def pick_zoom(f_lo, f_hi, fclk, want=None):
    """span = fclk / 2^(zoom+1). Take the TIGHTEST span that still covers the band.

    Tighter is better and the reason is resolution, not range: the table is a fixed
    256 entries, so halving the span halves the bin width and, because the hardware
    interpolates, quarters the error. MEASURED on the Harvard X curve, worst case
    over the band:  100 MHz span -> 0.359%,  50 MHz -> 0.130%,  25 MHz -> does not
    cover it. Choosing the span is therefore a real accuracy decision and not a
    formality, which is why it is computed rather than defaulted.
    """
    need = f_hi - f_lo
    best = None
    for z in range(4):
        span = fclk / 2.0 ** (z + 1)
        if span >= need:
            best = (z, span)
    if best is None:
        sys.exit("band is %.2f MHz wide; the widest table span is %.2f MHz (zoom 0)"
                 % (need, fclk / 2.0))
    if want is not None:
        span = fclk / 2.0 ** (want + 1)
        if span < need:
            sys.exit("--zoom %d spans %.2f MHz, too narrow for a %.2f MHz band"
                     % (want, span, need))
        best = (want, span)
    return best


def word_of(f_mhz, fclk):
    """Signed baseband frequency -> phase word, mod 2^ACC. Same convention as
    tonegen.py: a negative frequency is simply the two's-complement word, and the
    hardware's `inc - base` subtract is mod-2^ACC too, so the two agree for tones on
    either side of the NCO with no sign extension anywhere."""
    return int(round(f_mhz * (2 ** ACC) / fclk)) % (2 ** ACC)


# =====================================================================
# QUANTISE
# =====================================================================
def build(f, g, base_mhz, span, fclk, nco, TABN=TABN):
    """Resample onto the table grid and quantise to {slope, value} in Q1.15."""
    grid = base_mhz + np.arange(TABN) * span / TABN

    # OUTSIDE THE MEASURED BAND THE CURVE IS HELD, NOT EXTRAPOLATED. np.interp does
    # that by construction. Extrapolating a non-monotonic efficiency curve past its
    # last measured point is inventing data, and it would be inventing it exactly
    # where a tone has wandered somewhere nobody characterised.
    gg = np.interp(np.clip(grid, f[0], f[-1]), f, g)

    # ATTENUATE TO THE WORST FREQUENCY IN THE BAND. corr = g_min/g is <= 1 by
    # construction, so no entry ever asks for more than full drive. See the
    # docstring for why the other normalisation is not available.
    g_min = gg.min()
    corr = g_min / gg
    val = np.clip(np.round(corr * 32768), 0, 65535).astype(np.int64)

    dlt = np.zeros(TABN, dtype=np.int64)
    dlt[:-1] = val[1:] - val[:-1]
    # Entry TABN-1 keeps slope 0: the hardware clamps the index there and must not
    # extrapolate off the end of the table.

    if (np.abs(dlt) > 32767).any():
        sys.exit("a slope of %d does not fit 16 bits signed -- the curve is too steep "
                 "for %d entries over %.2f MHz. Use a tighter --zoom."
                 % (np.abs(dlt).max(), TABN, span))

    words = ((dlt.astype(np.uint64) & 0xFFFF) << 16) | (val.astype(np.uint64) & 0xFFFF)

    # ---- WHAT THE HARDWARE WILL ACTUALLY DO, against the source curve -------
    # This reproduces the RTL's arithmetic rather than the intent: the quantised
    # entries, the 8-bit sub-bin fraction, and the linear blend between adjacent
    # entries. It is the number to quote, because it includes the table's own
    # rounding and not just its resolution.
    fine = np.linspace(max(f[0], base_mhz), min(f[-1], base_mhz + span), 20000)
    x = (fine - base_mhz) / span * TABN
    i0 = np.clip(np.floor(x).astype(int), 0, TABN - 2)
    frac = np.floor(np.clip(x - i0, 0, 1) * 256) / 256.0      # the RTL's FRACW=8
    hw = (val[i0] + (val[i0 + 1] - val[i0]) * frac) / 32768.0
    ideal = g_min / np.interp(fine, f, g)                     # same normalisation
    err = np.abs(hw / ideal - 1)

    return words, val, dict(
        max_err_pct=100 * err.max(),
        rms_err_pct=100 * np.sqrt((err ** 2).mean()),
        span_mhz=span, bin_khz=1000 * span / TABN,
        base_word=word_of(base_mhz - nco, fclk),
        atten_min=float(corr.min()), atten_max=float(corr.max()),
        covered_lo=float(fine[0]), covered_hi=float(fine[-1]))


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--sweep", help="measured 'freq_MHz power' file")
    src.add_argument("--harvard", help="X_POWER_CORRECTION.npz style file")
    ap.add_argument("--out", required=True, help="output .mem path")
    ap.add_argument("--fclk", type=float, default=400.0,
                    help="fabric clock MHz. Use the NOMINAL 400.0, not a measured "
                         "value: 400.0 is what the RFDC's NCO assumes, and a "
                         "J10-locked analyser cannot see absolute frequency anyway")
    ap.add_argument("--nco", type=float, required=True,
                    help="RFDC fine-mixer carrier MHz -- what tonegen.py nco was set to")
    ap.add_argument("--zoom", type=int, choices=range(4),
                    help="force the span (fclk/2^(zoom+1)); default is the tightest "
                         "that covers the band, which is also the most accurate")
    ap.add_argument("--entries", type=int, default=TABN, choices=[64, 128, 256, 512],
                    help="table entries PER AXIS. MUST match the bitstream: the "
                         "production build (cxp_host_fe_top.v .GCAL_TABN) ships 128, the "
                         "standalone tone generator ships 256. An oversized table loads "
                         "with no error and its halves alias over each other.")
    ap.add_argument("--base", type=float,
                    help="force the frequency of table entry 0 (MHz); default centres "
                         "the measured band in the span")
    a = ap.parse_args()

    print("reading  : %s" % (a.sweep or a.harvard))
    f, g = g_from_sweep(a.sweep) if a.sweep else g_from_harvard(a.harvard)
    print("  band    : %.2f .. %.2f MHz, efficiency varies %.3fx (peak at %.2f MHz)"
          % (f[0], f[-1], g.max() / g.min(), f[np.argmax(g)]))

    zoom, span = pick_zoom(f[0], f[-1], a.fclk, a.zoom)
    base = a.base if a.base is not None else (f[0] + f[-1]) / 2.0 - span / 2.0

    # A tone at the table's base must be inside Nyquist once the NCO is subtracted,
    # or the phase word wraps and the whole table lands somewhere else. Say so here
    # rather than let it surface as a mysteriously mis-corrected axis.
    for edge in (base, base + span):
        if abs(edge - a.nco) >= a.fclk / 2.0:
            sys.exit("table edge %.2f MHz is %.2f MHz from the %.2f MHz NCO, past "
                     "Nyquist for a %.3f MHz clock" % (edge, edge - a.nco, a.nco, a.fclk))

    words, val, m = build(f, g, base, span, a.fclk, a.nco, a.entries)

    with open(a.out, "w") as fh:
        for w in words:
            fh.write("%08X\n" % w)

    reg = dict(zoom=zoom, base_word=m["base_word"], base_mhz=base, span_mhz=span,
               nco_mhz=a.nco, fclk_mhz=a.fclk, entries=a.entries)
    with open(os.path.splitext(a.out)[0] + ".json", "w") as fh:
        json.dump(reg, fh, indent=2)

    print("table    : %s  (%d entries per axis)" % (a.out, a.entries))
    print("  span    : %.3f MHz from %.3f MHz, %.1f kHz per entry (zoom %d)"
          % (span, base, m["bin_khz"], zoom))
    print("  base    : phase word 0x%08X  (%.3f MHz baseband against a %.3f MHz NCO)"
          % (m["base_word"], base - a.nco, a.nco))
    print("  attenuation %.4f .. %.4f  -- the table pulls efficient frequencies DOWN"
          % (m["atten_min"], m["atten_max"]))
    print("  ERROR against the source curve over %.2f-%.2f MHz: max %.3f%%, rms %.3f%%"
          % (m["covered_lo"], m["covered_hi"], m["max_err_pct"], m["rms_err_pct"]))
    # TWO LOADERS, because there are two bitstreams and they do not share a
    # transport: the production build reaches the table through an AXI GPIO
    # (load_gcal.py), the tone generator through its own register file.
    print("\ndownload with (on the board):")
    if a.entries == 256:
        print("  python3 tonegen.py gcal --x %s --nco %.3f   # tone generator (256)"
              % (a.out, a.nco))
    else:
        print("  python3 load_gcal.py --x %s --nco %.3f      # production build (128)"
              % (a.out, a.nco))
    print("  (--y loads the other axis; both loaders check --entries against the")
    print("   bitstream, so a table built for the wrong build is refused, not aliased)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
