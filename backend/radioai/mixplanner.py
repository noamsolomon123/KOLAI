from radioai.models import TrackAnalysis, Transition
from radioai.scoring import compatibility
from radioai.keys import camelot_relation

_BEATMATCH_MIN = 0.75
_CROSSFADE_MIN = 0.45
_TALKOVER_SECONDS = 4.0
_BLEND_SECONDS = 8.0
_SHORT_BLEND_SECONDS = 3.0


def choose_transition(prev: TrackAnalysis, nxt: TrackAnalysis,
                      has_dj: bool) -> Transition:
    """Decide how to move from `prev` into `nxt`.

    has_dj=True means a DJ slot sits at this boundary -> talk over the
    instrumental. Otherwise pick a blend by compatibility.
    """
    if has_dj:
        return Transition(type="talkover", duration_s=_TALKOVER_SECONDS)

    score = compatibility(prev, nxt)
    if score >= _BEATMATCH_MIN:
        relation = camelot_relation(prev.key_camelot, nxt.key_camelot)
        dur = 12.0 if relation in ("same", "relative") else _BLEND_SECONDS
        return Transition(type="beatmatch", duration_s=dur)
    if score >= _CROSSFADE_MIN:
        return Transition(type="crossfade", duration_s=_BLEND_SECONDS)
    return Transition(type="crossfade", duration_s=_SHORT_BLEND_SECONDS)