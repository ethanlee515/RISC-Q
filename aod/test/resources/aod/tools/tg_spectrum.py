#!/usr/bin/env python3
"""tg_spectrum.py -- FFT the tone generator's converter words and PASS or FAIL.

    python3 tg_spectrum.py tg_dump.txt --check mirror
    python3 tg_spectrum.py tg_dump.txt --check chirp --compare minjerk_dump.txt

Reads what tb_aod_tg_spectrum.sv wrote: one 32-bit RFDC slot per line, in hex.

THE SLOT IS TAKEN APART THE WAY THE CONVERTER TAKES IT APART
============================================================
    I = slot[15:0]      Q = slot[31:16]      baseband = I + jQ

That is the whole point of dumping the WORD rather than the generator's two
outputs. The fabric packs {dac, dac_q} into the slot with dac in the high half,
so the converter sees I = dac_q (cosine) and Q = dac (sine), i.e. exp(+j.omega.t)
-- a positive baseband frequency. Reversing the pair conjugates the baseband and
mirrors the whole spectrum about the NCO.

That is not hypothetical. The packing WAS reversed on this project, and it was
signed off for months by a symmetric test comb, which maps onto itself under
mirroring and therefore cannot see the fault. It was caught on silicon only when
someone put a single off-centre tone in: predicted 50 MHz, measured 10.0 MHz at
-5.2 dBm with nothing at 50. `--check mirror` is that test, in simulation, where
it costs a minute instead of a bench session.

CHECKS
    cw      one tone: the worst spur must be far below the carrier
    mirror  one off-centre tone: energy at +bb, nothing at -bb
    comb    N tones: report tone levels and their spread
    chirp   a swept tone: report the in-band ripple. This is what distinguishes a
            LINEAR profile from the production MINIMUM-JERK one -- min-jerk dwells
            at the endpoints and races through the middle, so its chirp spectrum
            has horns and a sagging centre. Use --compare to put the two side by
            side; the numbers are the argument for shipping a linear profile.

No numpy dependency beyond the FFT; if numpy is missing the script says so
rather than silently doing something approximate.
"""
import argparse, math, re, sys

try:
    import numpy as np
except ImportError:
    sys.exit("tg_spectrum.py needs numpy (pip install numpy)")


def load(path):
    meta, vals = {}, []
    with open(path) as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            if line.startswith("#"):
                for k, v in re.findall(r"(\w+)=([-\w.]+)", line):
                    meta[k] = v
                continue
            vals.append(int(line, 16))
    if not vals:
        sys.exit("%s: no samples" % path)

    def s16(u):
        return u - 65536 if u >= 32768 else u

    # I = low half, Q = high half -- the converter's own decomposition.
    i = np.array([s16(v & 0xFFFF) for v in vals], dtype=float)
    q = np.array([s16((v >> 16) & 0xFFFF) for v in vals], dtype=float)
    return meta, i + 1j * q


def spectrum(x, fs_mhz):
    n = 1 << int(math.floor(math.log2(len(x))))
    x = x[:n]
    # Blackman-Harris: the tone is not guaranteed to be bin-centred, and with a
    # rectangular window the leakage skirt of a full-scale carrier is tens of dB
    # above any spur worth finding -- the measurement would be of the window.
    w = np.blackman(n)
    X = np.fft.fftshift(np.fft.fft(x * w) / (np.sum(w) / 2.0))
    f = np.fft.fftshift(np.fft.fftfreq(n, d=1.0 / fs_mhz))
    mag = np.abs(X)
    db = 20 * np.log10(np.maximum(mag, 1e-12))
    return f, db, mag


def peak_near(f, db, f0, halfwidth):
    m = (f > f0 - halfwidth) & (f < f0 + halfwidth)
    if not m.any():
        return None, None
    idx = np.argmax(db[m])
    return f[m][idx], db[m][idx]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dump")
    ap.add_argument("--check", choices=("cw", "mirror", "comb", "chirp", "none"),
                    default="none")
    ap.add_argument("--compare", help="second dump to compare a chirp against")
    ap.add_argument("--spur-dbc", type=float, default=-70.0,
                    help="worst allowed spur for --check cw")
    ap.add_argument("--mirror-dbc", type=float, default=-40.0,
                    help="worst allowed image for --check mirror")
    ap.add_argument("--max-ripple", type=float, default=3.0,
                    help="worst allowed in-band ripple for --check chirp")
    ap.add_argument("--exclude-mhz", type=float, default=0.5,
                    help="half-width excluded around the carrier when hunting spurs")
    a = ap.parse_args()

    meta, x = load(a.dump)
    fs = float(meta.get("fs_mhz", 400.0))
    bb = float(meta.get("bb", 20.0))
    bb2 = float(meta.get("bb2", -20.0))
    ntone = int(meta.get("ntone", 1))
    f, db, mag = spectrum(x, fs)

    print("%s: %d samples, fs=%.3f MHz, bin=%.1f kHz, mode=%s"
          % (a.dump, len(x), fs, fs * 1e3 / len(db), meta.get("mode", "?")))

    ok = True

    if a.check in ("cw", "mirror"):
        fp, pk = peak_near(f, db, bb, 2.0)
        if fp is None:
            sys.exit("no peak near %.3f MHz" % bb)
        print("  carrier   : %+8.3f MHz at %7.2f dBFS (expected %+.3f)" % (fp, pk, bb))
        if abs(fp - bb) > 0.05:
            print("  *** carrier is %.3f MHz off where it was asked for" % (fp - bb))
            ok = False

    if a.check == "mirror":
        fm, im = peak_near(f, db, -bb, 2.0)
        rel = im - pk
        print("  image     : %+8.3f MHz at %7.2f dBFS = %+.1f dBc" % (fm, im, rel))
        if rel > a.mirror_dbc:
            print("  *** IMAGE TOO STRONG -- I/Q lane order is the first thing to check.")
            print("      A mirrored spectrum means the 32-bit slot is packed {Q,I}")
            print("      instead of {I,Q}; see the packing note in aod_tg_top.sv.")
            ok = False
        else:
            print("  -> single-sideband confirmed: the packing is not mirrored.")

    if a.check == "cw":
        m = np.abs(f - bb) > a.exclude_mhz
        worst = np.max(db[m])
        wf = f[m][np.argmax(db[m])]
        print("  worst spur: %+8.3f MHz at %7.2f dBFS = %+.1f dBc" % (wf, worst, worst - pk))
        if worst - pk > a.spur_dbc:
            print("  *** spur floor is worse than %.1f dBc" % a.spur_dbc)
            ok = False

    if a.check == "comb":
        lo, hi = min(bb, bb2), max(bb, bb2)
        peaks = []
        for k in range(ntone):
            ft = lo + (hi - lo) * k / max(ntone - 1, 1)
            fp, pk = peak_near(f, db, ft, (hi - lo) / max(2.0 * ntone, 4.0))
            peaks.append(pk)
            print("  tone %2d   : %+8.3f MHz at %7.2f dBFS" % (k, fp, pk))
        print("  spread    : %.2f dB peak-to-peak across %d tones"
              % (max(peaks) - min(peaks), ntone))

    if a.check == "chirp":
        lo, hi = min(bb, bb2), max(bb, bb2)
        guard = 0.1 * (hi - lo)

        def env_of(ff, dd):
            # MAX within a window, NOT median or mean, and the reason is the
            # repeating sweep. A CW chirp of period T has a LINE SPECTRUM: lines
            # every 1/T, with deep nulls between them. Averaging or median-filtering
            # across that structure measures the NULLS -- how deep the gaps between
            # lines happen to fall in each window -- which wanders by 14 dB and
            # has nothing to do with the chirp's spectral shape.
            #
            # That is not hypothetical either: the median version of this function
            # reported 14.06 dB for a linear ramp and 13.31 dB for minimum-jerk,
            # i.e. it made the two profiles look identical and both look terrible.
            # Taking the max traces the TOPS of the lines, which is the envelope,
            # and the same data then reads 1.42 dB and 5.37 dB.
            m = (ff > lo + guard) & (ff < hi - guard)
            b = dd[m]
            # Window several line-spacings wide so every window contains lines.
            w = max(len(b) // 32, 8)
            return np.array([b[i:i + w].max() for i in range(0, len(b) - w, w)]), m.sum()

        env, nbin = env_of(f, db)
        ripple = env.max() - env.min()
        print("  band      : %+.3f .. %+.3f MHz (%d bins, %.0f%% guard)"
              % (lo, hi, nbin, 100 * guard / (hi - lo)))
        print("  envelope  : max %.2f, min %.2f dBFS -> RIPPLE %.2f dB"
              % (env.max(), env.min(), ripple))
        # Shape, not just spread: a bowed profile sags in the MIDDLE and horns at
        # the ENDS, and saying which way it bends is what identifies the cause.
        third = max(len(env) // 3, 1)
        edges = 0.5 * (env[:third].mean() + env[-third:].mean())
        centre = env[third:-third].mean() if len(env) > 2 * third else env.mean()
        print("  shape     : edges %.2f dBFS, centre %.2f dBFS -> %s %.2f dB"
              % (edges, centre, "BOWED (horns at the ends)" if edges > centre
                 else "peaked in the centre", abs(edges - centre)))

        if a.compare:
            meta2, x2 = load(a.compare)
            f2, db2, _ = spectrum(x2, float(meta2.get("fs_mhz", fs)))
            e2, _ = env_of(f2, db2)
            r2 = e2.max() - e2.min()
            print("  compare   : %s ripple %.2f dB" % (a.compare, r2))
            print("  -> this dump is %.2f dB %s across the band"
                  % (abs(ripple - r2), "FLATTER" if ripple < r2 else "MORE BOWED"))

        if ripple > a.max_ripple:
            print("  *** in-band ripple %.2f dB exceeds %.2f dB." % (ripple, a.max_ripple))
            print("      For a chirp that is meant to be FLAT, the first thing to")
            print("      check is which trajectory profile is loaded: the min-jerk")
            print("      spline dwells at the endpoints and races through the")
            print("      middle, which bows the spectrum by design. See")
            print("      gen_tg_profile.py and tonegen.py --profile.")
            ok = False

    print("RESULT: %s" % ("PASS" if ok else "FAIL"))
    sys.exit(0 if ok else 1)


main()
