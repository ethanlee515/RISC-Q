#!/usr/bin/env python3
"""check_gcal_real.py -- compare tb_aod_gcal_real's RTL dump against the table.

tb_aod_gcal_real loads a REAL generated gcal table (rearrange/gcal/gcal_X.mem /
gcal_Y.mem), sweeps a tone across the whole addressable span and dumps what the
hardware produced. This script recomputes every sample with a bit-exact model of
aod_gcal's fixed-point datapath -- the SIGNED slope * unsigned frac product with
its arithmetic (floor) >>8, the neg/ovf clamps, and the final cal_now multiply
(unity here, so cal_eff IS g) -- and requires EXACT agreement.

That is the claim the bench header makes and nothing else checks: the table
GENERATOR's error figure models a datapath, and this is the proof the datapath
it models is the one that exists. The bench mentions a `gen_gcal_table.py
--selfcheck` mode which was never written; this checker is that comparison,
kept beside the bench it serves.

Usage: check_gcal_real.py gcal_rtl_dump.txt gcal_X.mem [--zoom 2]
Prints "RESULT: PASS" on exact agreement (run_aod_tests.sh gates on that).
"""
import argparse
import sys

ACC, CALW, CAL_FRAC, FRACW = 32, 16, 15, 8
UNITY = 1 << CAL_FRAC


def model(w, mem, tabn, zoom):
    """aod_gcal, bit for bit: address slice -> clamp -> interpolate -> apply."""
    idxw = tabn.bit_length() - 1                      # $clog2, tabn is 2^k
    top = 30 - zoom                                   # w[top -: IDXW] is the index
    neg = (w >> (ACC - 1)) & 1
    ovf_bits = w >> (top + 1) & ((1 << (30 - top)) - 1) if top < 30 else 0
    ovf = 1 if ovf_bits else 0
    if neg:
        idx, frac = 0, 0
    elif ovf:
        idx, frac = tabn - 1, 0
    else:
        idx = (w >> (top - idxw + 1)) & (tabn - 1)
        frac = (w >> (top - idxw + 1 - FRACW)) & ((1 << FRACW) - 1)
    ent = mem[idx]
    val = ent & 0xFFFF
    dlt = ent >> 16
    if dlt >= 0x8000:
        dlt -= 0x10000
    # gm = dlt * frac; g = val + (gm >>> FRACW), floor toward -inf, then clamp
    g = val + ((dlt * frac) >> FRACW)
    g = 0 if g < 0 else (0xFFFF if g > 0xFFFF else g)
    # cal_now is UNITY in the bench, so ce_s = (UNITY * g) >> CAL_FRAC = g exactly
    ce = (UNITY * g) >> CAL_FRAC
    return 0xFFFF if ce >> CALW else ce & 0xFFFF


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dump")
    ap.add_argument("mem")
    ap.add_argument("--zoom", type=int, default=2)
    a = ap.parse_args()

    mem = [int(l.split("//")[0].strip(), 16)
           for l in open(a.mem) if l.split("//")[0].strip()]
    tabn = len(mem)
    if tabn & (tabn - 1):
        print(f"RESULT: FAIL ({a.mem}: {tabn} entries, not a power of two)")
        return 1

    n = bad = 0
    worst = (0, 0, 0)                                 # |err|, w, line
    for line in open(a.dump):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        w, got = (int(x) for x in line.split())
        want = model(w, mem, tabn, a.zoom)
        n += 1
        if got != want:
            bad += 1
            if abs(got - want) > worst[0]:
                worst = (abs(got - want), w, n)
            if bad <= 5:
                print(f"    w={w}: rtl={got} model={want} (diff {got - want})")

    if n == 0:
        print("RESULT: FAIL (empty dump)")
        return 1
    print(f"    {n} sweep points, TABN={tabn}, zoom={a.zoom}: "
          f"{n - bad} bit-exact, {bad} mismatched"
          + (f" (worst |err|={worst[0]} at w={worst[1]})" if bad else ""))
    print("RESULT: PASS" if bad == 0 else "RESULT: FAIL")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
