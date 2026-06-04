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
