from radioai.models import TrackAnalysis
from radioai.mixplanner import choose_transition


def _track(bpm, key, energy):
    return TrackAnalysis(path="x", duration_s=180, bpm=bpm, beat_times=[],
                         key_camelot=key, energy=energy, intro_end_s=8,
                         outro_start_s=170, vocal_onset_s=12)


def test_dj_slot_forces_talkover():
    a = _track(120, "8A", 0.6)
    b = _track(120, "8A", 0.6)
    t = choose_transition(a, b, has_dj=True)
    assert t.type == "talkover"


def test_high_compat_beatmatch():
    a = _track(120, "8A", 0.6)
    b = _track(122, "8A", 0.6)
    t = choose_transition(a, b, has_dj=False)
    assert t.type == "beatmatch"


def test_medium_compat_crossfade():
    a = _track(120, "8A", 0.6)
    b = _track(132, "11A", 0.6)  # ~10% tempo, clashing key -> medium
    t = choose_transition(a, b, has_dj=False)
    assert t.type == "crossfade"


def test_low_compat_cut():
    a = _track(120, "8A", 0.9)
    b = _track(180, "11A", 0.1)
    t = choose_transition(a, b, has_dj=False)
    assert t.type == "cut"
