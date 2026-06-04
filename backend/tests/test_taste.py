from radioai.taste import TasteTrack, TasteProfile, parse_profile

_TOP_TRACKS = {
    "items": [
        {"name": "Tudo Bom", "artists": [{"name": "Static & Ben El"},
                                          {"name": "J Balvin"}], "duration_ms": 200000},
        {"name": "Million Dollar", "artists": [{"name": "Noa Kirel"}], "duration_ms": 180500},
    ]
}
_TOP_ARTISTS = {"items": [{"name": "Static & Ben El"}, {"name": "Omer Adam"}]}


def test_parse_profile_tracks():
    p = parse_profile(_TOP_TRACKS, _TOP_ARTISTS)
    assert isinstance(p, TasteProfile)
    assert len(p.top_tracks) == 2
    t = p.top_tracks[0]
    assert t.title == "Tudo Bom"
    assert t.artist == "Static & Ben El"   # first artist only
    assert abs(t.duration_s - 200.0) < 0.001


def test_parse_profile_artists():
    p = parse_profile(_TOP_TRACKS, _TOP_ARTISTS)
    assert p.top_artists == ["Static & Ben El", "Omer Adam"]


def test_parse_profile_empty():
    p = parse_profile({"items": []}, {"items": []})
    assert p.top_tracks == []
    assert p.top_artists == []


def test_parse_profile_missing_artist_is_blank():
    p = parse_profile({"items": [{"name": "X", "artists": [], "duration_ms": 1000}]},
                      {"items": []})
    assert p.top_tracks[0].artist == ""
    assert abs(p.top_tracks[0].duration_s - 1.0) < 0.001
