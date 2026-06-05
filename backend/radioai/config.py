import os
from dataclasses import dataclass, field
from dotenv import load_dotenv

load_dotenv()


@dataclass
class Config:
    gemini_api_keys: list[str]
    llm_model: str
    tts_model: str
    tts_voice: str
    cache_dir: str
    spotify_client_id: str
    spotify_client_secret: str
    spotify_redirect_uri: str
    city: str = ""
    topics: list[str] = field(default_factory=list)
    mashups_enabled: bool = False

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
            spotify_client_id=os.environ.get("SPOTIFY_CLIENT_ID", ""),
            spotify_client_secret=os.environ.get("SPOTIFY_CLIENT_SECRET", ""),
            spotify_redirect_uri=os.environ.get("SPOTIFY_REDIRECT_URI",
                                                "http://127.0.0.1:5173"),
            city=os.environ.get("CITY", ""),
            topics=[t.strip() for t in os.environ.get("TOPICS", "").split(",")
                    if t.strip()],
            mashups_enabled=os.environ.get("MASHUPS_ENABLED", "false").lower()
            in ("true", "1", "yes"),
        )
