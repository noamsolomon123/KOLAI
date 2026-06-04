from radioai.config import Config
from radioai.render_show import build_setlist, DEMO_SETLIST


def _cfg_no_spotify(tmp_path):
    return Config(gemini_api_keys=[], llm_model="m", tts_model="m", tts_voice="v",
                  cache_dir=str(tmp_path), spotify_client_id="",
                  spotify_client_secret="", spotify_redirect_uri="http://127.0.0.1:5173")


def test_build_setlist_falls_back_without_creds(tmp_path):
    songs = build_setlist(_cfg_no_spotify(tmp_path))
    assert songs == DEMO_SETLIST
    assert len(songs) >= 1
