import re
from typing import Optional, Protocol
from radioai.models import Song

_HEBREW_WORDS_PER_SEC = 2.5


def words_for_seconds(seconds: float) -> int:
    return int(seconds * _HEBREW_WORDS_PER_SEC)


class LLMClient(Protocol):
    def complete(self, prompt: str) -> str: ...


class GeminiClient:
    """LLMClient over the Gemini API. Rotates across multiple API keys on
    failure (e.g. rate-limit) to extend the free-tier quota."""

    def __init__(self, api_keys: list[str], model: str, clients=None):
        self._model = model
        self._idx = 0
        if clients is not None:
            self._clients = list(clients)
        else:
            from google import genai
            self._clients = [genai.Client(api_key=k) for k in api_keys]
        if not self._clients:
            raise ValueError("GeminiClient needs at least one API key/client")

    def complete(self, prompt: str) -> str:
        errors = []
        for _ in range(len(self._clients)):
            client = self._clients[self._idx]
            try:
                resp = client.models.generate_content(
                    model=self._model, contents=prompt)
                return resp.text
            except Exception as e:  # rate-limit/transient -> rotate to next key
                errors.append(repr(e))
                self._idx = (self._idx + 1) % len(self._clients)
        raise RuntimeError(f"All Gemini keys failed: {errors}")


class DJBrain:
    def __init__(self, client: LLMClient, persona: str):
        self.client = client
        self.persona = persona

    def _prompt(self, prev: Optional[Song], nxt: Song, budget: int) -> str:
        prev_line = (f'השיר שהרגע התנגן: "{prev.title}" של {prev.artist}.'
                     if prev else "זו פתיחת השידור.")
        return (
            f"אתה {self.persona}, שדרן רדיו ישראלי כריזמטי, שנון ומצחיק שמדבר כמו "
            f"בנאדם אמיתי ברדיו FM — חם, אנרגטי וזורם.\n"
            f"{prev_line}\n"
            f'עכשיו עומד להתנגן: "{nxt.title}" של {nxt.artist}.\n'
            f"כתוב משפט קישור אחד קצר, שנון ומצחיק בעברית מדוברת (סלנג ישראלי בסדר "
            f"גמור), עד {budget} מילים, שמכין את המאזינים לשיר הבא.\n"
            f"תהיה חכם ומשעשע — אם אפשר, שחק עם שם השיר או שם האמן (משחק מילים) "
            f"כדי שזה ירגיש כמו שדרן אמיתי שכיף לשמוע.\n"
            f"החזר אך ורק את המשפט עצמו לאמירה בקול — בלי רשימות, בלי אפשרויות, "
            f"בלי מרכאות, בלי כותרות ובלי הסברים."
        )

    def write_intro(self, prev: Optional[Song], nxt: Song, seconds: float) -> str:
        budget = words_for_seconds(seconds)
        text = self.client.complete(self._prompt(prev, nxt, budget)).strip()
        words = text.split()
        if len(words) <= budget:
            return text
        # Over budget: keep up to `budget` words, then cut back to the last
        # complete sentence so the DJ never stops mid-thought.
        capped = " ".join(words[:budget])
        matches = list(re.finditer(r"[.!?…]", capped))
        if matches:
            return capped[: matches[-1].end()].strip()
        return capped