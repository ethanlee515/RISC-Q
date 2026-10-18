"""AodSoc manifest/firmware and host setup contract; RTL gate is AodSocCpuSim."""
from pathlib import Path

import pytest

from riscq.aod import AodMap, AodSession

ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def mapping():
    return AodMap.load(ROOT / "configs/aod-sim.json")


def test_compile_movement_train(mapping):
    image = mapping.compile((ROOT / "examples/aod_moves.c").read_text())
    assert image.entry == image.symbols["_start"][0] == 0x80000000
    assert 0 < len(image.data) < mapping.mem_bytes - 1024
    assert image.symbols["starts"][1] == 7 * 4
    with pytest.raises(ValueError, match="one CPU"):
        mapping.gen_header(1)


class WordDriver:
    """Word-only PS MMIO, with hardware reset-completion/state behavior."""
    host_base = 0x840000000

    def __init__(self, m):
        self.m = m
        self.words = {}
        self.writes = []
        self.running = True
        self.state = 2

    def write32(self, addr, value):
        assert addr % 4 == 0 and 0 <= value < 1 << 32
        m = self.m
        if addr == m.host["control"] + m.host["cpu_reset"]:
            self.running = not value
        if addr == m.host["setup"] + m.setup["reset"]:
            assert not self.running, "CPU must stop before AOD flush"
            self.state = 4
        self.words[addr] = value
        self.writes.append((addr, value))

    def read32(self, addr):
        m = self.m
        if addr == m.host["control"] + m.host["stopped"]:
            return int(not self.running)
        if addr == m.host["setup"] + m.setup["state"]:
            return self.state
        return self.words.get(addr, 0)


def test_host_setup_and_word_packing(mapping):
    d = WordDriver(mapping)
    session = AodSession(d, mapping)
    rows = [(0x12345678 << 64) | (0xfedcba98 << 32) | i
            for i in range(mapping.manifest["segments"])]
    with pytest.raises(RuntimeError, match="stop/flush"):
        session.profile("trajectory", rows)
    session.stop()
    session.profile("trajectory", rows)
    base = mapping.host["setup"] + mapping.setup["trajectory"]
    for i, word in enumerate(rows):
        actual = sum(d.words[base + 16 * i + 4 * b] << (32 * b) for b in range(3))
        assert actual == word
    with pytest.raises(ValueError, match="72-bit"):
        session.profile("envelope", rows)
    session.start()
    assert d.running
    assert d.words[mapping.host["control"] + mapping.host["host_base_hi"]] == 0x80000008
    d.words[mapping.host["control"] + mapping.host["done"]] = 1
    d.state = 2
    with pytest.raises(TimeoutError):
        session.wait_done(timeout=0)
    d.state = 4
    session.wait_done(timeout=0)
