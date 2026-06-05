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


from radioai.config import Config
from radioai.taste import TasteService


class _FakeSpotify:
    def __init__(self):
        self.calls = 0

    def current_user_top_tracks(self, limit, time_range):
        self.calls += 1
        return {"items": [{"name": "Song A", "artists": [{"name": "Artist A"}],
                           "duration_ms": 123000}]}

    def current_user_top_artists(self, limit, time_range):
        return {"items": [{"name": "Artist A"}]}


def _cfg(tmp_path):
    return Config(gemini_api_keys=[], llm_model="m", tts_model="m", tts_voice="v",
                  cache_dir=str(tmp_path), spotify_client_id="x",
                  spotify_client_secret="y", spotify_redirect_uri="http://127.0.0.1:5173")


def test_get_profile_fetches_and_caches(tmp_path):
    fake = _FakeSpotify()
    svc = TasteService(_cfg(tmp_path), client=fake)
    profile = svc.get_profile(use_cache=False)
    assert profile.top_tracks[0].title == "Song A"
    assert abs(profile.top_tracks[0].duration_s - 123.0) < 0.001
    import os
    assert os.path.exists(os.path.join(str(tmp_path), "taste.json"))


def test_get_profile_uses_cache(tmp_path):
    fake = _FakeSpotify()
    svc = TasteService(_cfg(tmp_path), client=fake)
    svc.get_profile(use_cache=False)         # writes cache, calls=1
    again = svc.get_profile(use_cache=True)  # should read cache, no new call
    assert fake.calls == 1
    assert again.top_tracks[0].title == "Song A"


def test_get_profile_force_refresh_bypasses_cache(tmp_path):
    import json, os
    from radioai.taste import TasteService
    class Cfg:
        cache_dir = str(tmp_path)
        spotify_client_id = "x"; spotify_client_secret = "y"; spotify_redirect_uri = "z"
    os.makedirs(tmp_path, exist_ok=True)
    with open(os.path.join(tmp_path, "taste.json"), "w", encoding="utf-8") as f:
        json.dump({"top_tracks": [], "top_artists": ["CACHED"]}, f)
    class FakeSpotify:
        def __init__(self): self.calls = 0
        def current_user_top_tracks(self, **k):
            self.calls += 1
            return {"items": [{"name": "Live", "artists": [{"name": "Net"}], "duration_ms": 1000}]}
        def current_user_top_artists(self, **k):
            return {"items": [{"name": "FRESH"}]}
    fake = FakeSpotify()
    svc = TasteService(Cfg(), client=fake)
    assert svc.get_profile().top_artists == ["CACHED"]   # cache hit
    assert fake.calls == 0
    fresh = svc.get_profile(use_cache=False)              # force refresh
    assert fresh.top_artists == ["FRESH"]
    assert fake.calls == 1

