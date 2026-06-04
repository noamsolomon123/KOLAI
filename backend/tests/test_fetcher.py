from radioai.models import Song
from radioai.fetcher import pick_best_candidate


def test_prefers_duration_close_match():
    song = Song(title="A", artist="B", duration_s=200.0)
    candidates = [
        {"id": "1", "title": "A - B (Live)", "duration": 360},
        {"id": "2", "title": "A - B (Official Audio)", "duration": 202},
        {"id": "3", "title": "A - B remix", "duration": 150},
    ]
    best = pick_best_candidate(song, candidates)
    assert best["id"] == "2"


def test_penalizes_live_versions():
    song = Song(title="A", artist="B", duration_s=200.0)
    candidates = [
        {"id": "live", "title": "A - B (Live at X)", "duration": 201},
        {"id": "audio", "title": "A - B (Audio)", "duration": 205},
    ]
    best = pick_best_candidate(song, candidates)
    assert best["id"] == "audio"


def test_no_reference_duration_uses_keyword_score():
    song = Song(title="A", artist="B")  # no duration
    candidates = [
        {"id": "x", "title": "A - B reaction", "duration": 600},
        {"id": "y", "title": "A - B official audio", "duration": 200},
    ]
    best = pick_best_candidate(song, candidates)
    assert best["id"] == "y"


def test_returns_none_for_empty():
    song = Song(title="A", artist="B")
    assert pick_best_candidate(song, []) is None
