from radioai.models import TrackAnalysis
from radioai.scoring import compatibility


def _track(bpm, key, energy):
    return TrackAnalysis(path="x", duration_s=180, bpm=bpm, beat_times=[],
                         key_camelot=key, energy=energy, intro_end_s=8,
                         outro_start_s=170, vocal_onset_s=12)


def test_identical_tracks_score_high():
    a = _track(120, "8A", 0.6)
    b = _track(120, "8A", 0.6)
    assert compatibility(a, b) > 0.9


def test_far_tempo_scores_low():
    a = _track(120, "8A", 0.6)
    b = _track(170, "8A", 0.6)  # 41% faster
    assert compatibility(a, b) < 0.4


def test_incompatible_key_lowers_score():
    a = _track(120, "8A", 0.6)
    compatible = compatibility(a, _track(120, "8B", 0.6))   # relative key
    clashing = compatibility(a, _track(120, "11A", 0.6))    # distant key
    assert compatible > clashing


def test_score_is_bounded():
    a = _track(120, "8A", 0.6)
    b = _track(200, "11A", 0.0)
    s = compatibility(a, b)
    assert 0.0 <= s <= 1.0
