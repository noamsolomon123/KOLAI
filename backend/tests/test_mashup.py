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
