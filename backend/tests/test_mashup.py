from dataclasses import dataclass
from radioai.mashup import mashup_gate


@dataclass
class _TA:
    bpm: float
    key_camelot: str


def test_gate_passes_same_key_close_bpm():
    assert mashup_gate(_TA(120, "8A"), _TA(123, "8A")) is True


def test_gate_passes_relative_key():
    assert mashup_gate(_TA(120, "8A"), _TA(120, "8B")) is True


def test_gate_fails_far_bpm():
    assert mashup_gate(_TA(120, "8A"), _TA(140, "8A")) is False


def test_gate_fails_clashing_key():
    assert mashup_gate(_TA(120, "8A"), _TA(121, "11A")) is False


def test_gate_fails_adjacent_key():
    assert mashup_gate(_TA(120, "8A"), _TA(121, "9A")) is False


def test_gate_guards_zero_bpm():
    assert mashup_gate(_TA(0, "8A"), _TA(120, "8A")) is False


import numpy as np
from radioai.mixrenderer import SR


def test_build_mashup_overlay_then_full():
    from radioai.mashup import build_mashup
    bpm = 120
    voc = np.full(SR * 20, 0.3, dtype=np.float32)
    inst = np.full(SR * 30, 0.2, dtype=np.float32)
    full = np.full(SR * 30, 0.7, dtype=np.float32)
    beats = [0.0, 0.5, 1.0]
    out = build_mashup(voc, inst, full, bpm, bpm, beats, bars=8)
    window_n = int(8 * 4 * 60 / bpm * SR)
    assert abs(float(np.mean(out[: window_n])) - 0.5) < 0.05
    assert abs(float(np.mean(out[window_n + SR: window_n + 2 * SR])) - 0.7) < 0.05
    assert abs(len(out) - SR * 30) < SR * 0.2
    assert float(np.max(np.abs(out))) <= 1.0001


def test_build_mashup_empty_raises():
    from radioai.mashup import build_mashup
    import pytest
    with pytest.raises(ValueError):
        build_mashup(np.zeros(0, dtype=np.float32), np.ones(SR, dtype=np.float32),
                     np.ones(SR, dtype=np.float32), 120, 120, [0.0])
