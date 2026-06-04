import os
from dataclasses import dataclass
from dotenv import load_dotenv

load_dotenv()


@dataclass
class Config:
    gemini_api_keys: list[str]
    llm_model: str
    tts_model: str
    tts_voice: str
    cache_dir: str

    @classmethod
    def from_env(cls) -> "Config":
        raw = [
            os.environ.get("GEMINI_API_KEY", ""),
            os.environ.get("GEMINI_API_KEY_2", ""),
            os.environ.get("GEMINI_API_KEY_3", ""),
        ]
        keys = [k for k in raw if k]
        return cls(
            gemini_api_keys=keys,
            llm_model=os.environ.get("GEMINI_LLM_MODEL", "gemini-3.1-flash-lite-preview"),
            tts_model=os.environ.get("GEMINI_TTS_MODEL", "gemini-3.1-flash-tts-preview"),
            tts_voice=os.environ.get("GEMINI_TTS_VOICE", "Puck"),
            cache_dir=os.environ.get("CACHE_DIR", "./cache"),
        )
