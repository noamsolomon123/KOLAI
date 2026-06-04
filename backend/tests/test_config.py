from radioai.config import Config


def test_from_env_collects_keys(monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "k1")
    monkeypatch.setenv("GEMINI_API_KEY_2", "k2")
    monkeypatch.delenv("GEMINI_API_KEY_3", raising=False)
    monkeypatch.setenv("GEMINI_LLM_MODEL", "m-llm")
    monkeypatch.setenv("GEMINI_TTS_MODEL", "m-tts")
    monkeypatch.setenv("GEMINI_TTS_VOICE", "Zephyr")
    cfg = Config.from_env()
    assert cfg.gemini_api_keys == ["k1", "k2"]   # empties filtered out
    assert cfg.llm_model == "m-llm"
    assert cfg.tts_model == "m-tts"
    assert cfg.tts_voice == "Zephyr"


def test_from_env_defaults(monkeypatch):
    for v in ("GEMINI_API_KEY", "GEMINI_API_KEY_2", "GEMINI_API_KEY_3",
              "GEMINI_LLM_MODEL", "GEMINI_TTS_MODEL", "GEMINI_TTS_VOICE", "CACHE_DIR"):
        monkeypatch.delenv(v, raising=False)
    cfg = Config.from_env()
    assert cfg.gemini_api_keys == []
    assert cfg.llm_model == "gemini-3.1-flash-lite-preview"
    assert cfg.tts_model == "gemini-3.1-flash-tts-preview"
    assert cfg.tts_voice == "Puck"


def test_from_env_loads_spotify(monkeypatch):
    monkeypatch.setenv("SPOTIFY_CLIENT_ID", "cid")
    monkeypatch.setenv("SPOTIFY_CLIENT_SECRET", "csecret")
    monkeypatch.delenv("SPOTIFY_REDIRECT_URI", raising=False)
    cfg = Config.from_env()
    assert cfg.spotify_client_id == "cid"
    assert cfg.spotify_client_secret == "csecret"
    assert cfg.spotify_redirect_uri == "http://127.0.0.1:5173"  # default


def test_from_env_loads_city_and_topics(monkeypatch):
    monkeypatch.setenv("CITY", "Tel Aviv")
    monkeypatch.setenv("TOPICS", "technology, physics ,robotics,AI")
    cfg = Config.from_env()
    assert cfg.city == "Tel Aviv"
    assert cfg.topics == ["technology", "physics", "robotics", "AI"]  # trimmed, split


def test_from_env_topics_empty(monkeypatch):
    monkeypatch.delenv("TOPICS", raising=False)
    monkeypatch.delenv("CITY", raising=False)
    cfg = Config.from_env()
    assert cfg.topics == []
    assert cfg.city == ""


def test_from_env_mashups_enabled_default_and_off(monkeypatch):
    monkeypatch.delenv("MASHUPS_ENABLED", raising=False)
    assert Config.from_env().mashups_enabled is True       # default on
    monkeypatch.setenv("MASHUPS_ENABLED", "false")
    assert Config.from_env().mashups_enabled is False
    monkeypatch.setenv("MASHUPS_ENABLED", "0")
    assert Config.from_env().mashups_enabled is False