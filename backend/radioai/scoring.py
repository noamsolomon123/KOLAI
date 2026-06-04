from radioai.models import TrackAnalysis
from radioai.keys import are_keys_compatible

# Weights for the three factors (sum to 1.0).
# Tempo is dominant so that a ~41% BPM gap produces a total below 0.4
# even when key and energy are identical (0.22 + 0.13 = 0.35 < 0.4).
_W_TEMPO = 0.65
_W_KEY = 0.22
_W_ENERGY = 0.13

# Tempo within this fraction is considered fully matchable (post small nudge).
_TEMPO_TOLERANCE = 0.06  # +/-6%


def _tempo_score(bpm_a: float, bpm_b: float) -> float:
    if bpm_a <= 0 or bpm_b <= 0:
        return 0.0
    ratio = max(bpm_a, bpm_b) / min(bpm_a, bpm_b)
    diff = ratio - 1.0
    if diff <= _TEMPO_TOLERANCE:
        return 1.0
    # Linear falloff: 0 by the time we are ~30% apart.
    return max(0.0, 1.0 - (diff - _TEMPO_TOLERANCE) / 0.24)


def _key_score(a: str, b: str) -> float:
    return 1.0 if are_keys_compatible(a, b) else 0.0


def _energy_score(ea: float, eb: float) -> float:
    return 1.0 - min(1.0, abs(ea - eb))


def compatibility(a: TrackAnalysis, b: TrackAnalysis) -> float:
    score = (
        _W_TEMPO * _tempo_score(a.bpm, b.bpm)
        + _W_KEY * _key_score(a.key_camelot, b.key_camelot)
        + _W_ENERGY * _energy_score(a.energy, b.energy)
    )
    return max(0.0, min(1.0, score))
