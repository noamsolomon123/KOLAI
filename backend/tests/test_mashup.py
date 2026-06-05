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
    assert abs(float(np.mean(out[: window_n])) - 0.44) < 0.05
    assert abs(float(np.mean(out[window_n + SR: window_n + 2 * SR])) - 0.7) < 0.05
    assert abs(len(out) - SR * 30) < SR * 0.2
    assert float(np.max(np.abs(out))) <= 1.0001


def test_build_mashup_empty_raises():
    from radioai.mashup import build_mashup
    import pytest
    with pytest.raises(ValueError):
        build_mashup(np.zeros(0, dtype=np.float32), np.ones(SR, dtype=np.float32),
                     np.ones(SR, dtype=np.float32), 120, 120, [0.0])


def test_build_mashup_picks_loud_vocal_section():
    from radioai.mashup import build_mashup
    from radioai.mixrenderer import SR
    bpm = 120
    window_n = int(8 * 4 * 60 / bpm * SR)  # 16s
    # vocals: QUIET first window, LOUD second window
    voc = np.concatenate([
        np.full(window_n, 0.05, dtype=np.float32),
        np.full(window_n, 0.5, dtype=np.float32),
    ])
    inst = np.zeros(SR * 40, dtype=np.float32)   # silent bed (isolate the acapella)
    full = np.zeros(SR * 40, dtype=np.float32)
    out = build_mashup(voc, inst, full, bpm, bpm, [0.0], bars=8)
    # mashup window must reflect the LOUD vocal (~0.5), not the quiet outro/intro (~0.05)
    assert float(np.mean(np.abs(out[:window_n]))) > 0.3


def test_build_mashup_picks_loud_middle_not_tail():
    from radioai.mashup import build_mashup
    from radioai.mixrenderer import SR
    bpm = 120
    window_n = int(8 * 4 * 60 / bpm * SR)
    voc = np.concatenate([
        np.full(window_n, 0.05, dtype=np.float32),   # quiet head
        np.full(window_n, 0.5, dtype=np.float32),    # LOUD middle
        np.full(window_n, 0.05, dtype=np.float32),   # quiet tail (outro) <- old code used this
    ])
    inst = np.zeros(SR * 60, dtype=np.float32)
    full = np.zeros(SR * 60, dtype=np.float32)
    out = build_mashup(voc, inst, full, bpm, bpm, [0.0], bars=8)
    assert float(np.mean(np.abs(out[:window_n]))) > 0.3   # must find the loud MIDDLE


def test_vocal_forward_gains_boosts_quiet_vocal():
    from radioai.mashup import _vocal_forward_gains
    rng = np.random.default_rng(0)
    bed = (rng.standard_normal(40000).astype(np.float32)) * 0.3
    acap = (rng.standard_normal(40000).astype(np.float32)) * 0.12
    bg, ag = _vocal_forward_gains(bed, acap)
    br = float(np.sqrt(np.mean(bed ** 2)))
    ar = float(np.sqrt(np.mean(acap ** 2)))
    assert ar * ag > br * bg          # vocal ends up on top of the bed


def test_vocal_forward_gains_silent_bed_keeps_vocal():
    from radioai.mashup import _vocal_forward_gains
    bed = np.zeros(2000, dtype=np.float32)
    acap = np.full(2000, 0.2, dtype=np.float32)
    bg, ag = _vocal_forward_gains(bed, acap)
    assert ag == 1.0                  # nothing to compete with -> no boost


def test_same_recording_true_for_identical():
    from radioai.mashup import same_recording
    from radioai.mixrenderer import SR
    rng = np.random.default_rng(1)
    x = rng.standard_normal(SR * 30).astype(np.float32) * 0.3
    assert same_recording(x, x.copy()) is True


def test_same_recording_false_for_different():
    from radioai.mashup import same_recording
    from radioai.mixrenderer import SR
    rng = np.random.default_rng(2)
    a = rng.standard_normal(SR * 30).astype(np.float32) * 0.3
    b = rng.standard_normal(SR * 30).astype(np.float32) * 0.3
    assert same_recording(a, b) is False
