import numpy as np
from radioai.keys import camelot_relation
from radioai.mixrenderer import SR, time_stretch_to_bpm, start_on_beat

_MAX_BPM_RATIO = 1.06


def mashup_gate(prev, nxt) -> bool:
    """True only when a clean acapella-over-next mashup is feasible:
    same/relative key (no pitch shift) AND BPM within ~6% (gentle stretch)."""
    if prev.bpm <= 0 or nxt.bpm <= 0:
        return False
    if camelot_relation(prev.key_camelot, nxt.key_camelot) not in ("same", "relative"):
        return False
    ratio = max(prev.bpm, nxt.bpm) / min(prev.bpm, nxt.bpm)
    return ratio <= _MAX_BPM_RATIO
