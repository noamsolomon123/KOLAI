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


def test_penalizes_foreign_language_version():
    song = Song(title="Tudo Bom", artist="Static & Ben El", duration_s=200.0)
    candidates = [
        {"id": "eng", "title": "Tudo Bom (English Version)", "duration": 201, "view_count": 5_000_000},
        {"id": "orig", "title": "Static & Ben El - Tudo Bom (Official)", "duration": 199, "view_count": 80_000_000},
    ]
    assert pick_best_candidate(song, candidates)["id"] == "orig"


def test_view_count_breaks_ties():
    song = Song(title="A", artist="B", duration_s=200.0)
    candidates = [
        {"id": "low", "title": "A - B official audio", "duration": 200, "view_count": 1_000},
        {"id": "high", "title": "A - B official audio", "duration": 200, "view_count": 50_000_000},
    ]
    assert pick_best_candidate(song, candidates)["id"] == "high"


def test_song_query_override_field():
    s = Song(title="A", artist="B", query="B A")
    assert s.query == "B A"
