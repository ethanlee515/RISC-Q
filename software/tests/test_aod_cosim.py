"""Real CPU/AXI AOD gate: digital frequency/phase, timestamp and rerun invariants."""
import json
import numpy as np
import pytest

from riscq.lang import Array, kernel
from riscq.aod import (AodMap, AodSession, compile_kernel, aod_init, aod_rate,
                       aod_axis, aod_tone, aod_fire_at, aod_start_spacing, now, wait_until)
from riscq.sim import aod as simulator

pytestmark = pytest.mark.cosim


@kernel
def constant_tones(starts: Array, phase: int):
    aod_init()
    aod_rate(8, 1)
    aod_axis(0, 1, 0, 12000)
    aod_axis(1, 1, 0, 12000)
    # Exactly fs/32 and fs/16; the expected frequencies are analytic.
    aod_tone(0, 0, 134217728, 0, phase, 32768, 32768)
    aod_tone(1, 0, 268435456, 0, phase, 32768, 32768)
    t = now() + 4096
    starts[0] = t
    aod_fire_at(t)
    wait_until(t + aod_start_spacing(8) + 512)


def test_native_frequency_phase_and_table_preserving_rerun():
    with simulator.start() as driver:
        m = AodMap(json.loads(driver.sim.get_params()))
        session = AodSession(driver, m)
        program = compile_kernel(constant_tones, m, starts=Array(1))
        session.load(program)
        # Word-level malformed accesses must produce a host error and no hang.
        with pytest.raises(RuntimeError, match="AXI write denied"):
            driver.write32(m.host["setup"] + 0x80, 0)
        shots = []
        for phase in (0, m.phase(0.25)):
            session.parameters(phase=phase)
            handle = driver.sim.capture_arm(65000)
            session.start()
            session.wait_done(timeout=120)
            cap = driver.sim.capture_get(handle)
            start = session.result('starts')[0] & 0xffffffff
            assert session.diagnostics() == 0
            session.stop()
            assert np.array_equal(cap.valid[:, 0], cap.valid[:, 1])
            on = np.flatnonzero(np.diff(cap.valid[:, 0].astype(int), prepend=0) == 1)
            assert len(on) == 1 and cap.times[on[0]] == start
            assert np.all(cap.iq[~cap.valid] == 0)
            z = cap.complex
            good = cap.valid[1:] & cap.valid[:-1] & (np.abs(z[1:]) > 1500) & (np.abs(z[:-1]) > 1500)
            f = np.angle(z[1:] * np.conj(z[:-1])) * m.manifest["dsp_hz"] / (2 * np.pi)
            for axis, expected in enumerate((12.5e6, 25e6)):
                assert abs(np.mean(f[good[:, axis], axis]) - expected) < 1000
            shots.append((start, z[on[0]+5000:on[0]+25000]))
        assert shots[1][0] > shots[0][0]
        # A quarter-turn phase update multiplies the complex signal by i.
        assert np.max(np.abs(shots[1][1] - 1j * shots[0][1])) < 12
