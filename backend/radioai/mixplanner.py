from radioai.models import TrackAnalysis, Transition
from radioai.scoring import compatibility

_BEATMATCH_MIN = 0.75
_CROSSFADE_MIN = 0.45
_TALKOVER_SECONDS = 4.0
_BLEND_SECONDS = 8.0


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
        return Transition(type="beatmatch", duration_s=_BLEND_SECONDS)
    if score >= _CROSSFADE_MIN:
        return Transition(type="crossfade", duration_s=_BLEND_SECONDS)
    return Transition(type="cut", duration_s=0.0)

