from typing import Optional, Protocol
from radioai.models import Song

_HEBREW_WORDS_PER_SEC = 2.5


def words_for_seconds(seconds: float) -> int:
    return int(seconds * _HEBREW_WORDS_PER_SEC)


class LLMClient(Protocol):
    def complete(self, prompt: str) -> str: ...


class AnthropicClient:
    """Thin wrapper around the Anthropic SDK so DJBrain stays testable."""

    def __init__(self, api_key: str, model: str = "claude-opus-4-8"):
        import anthropic
        self._client = anthropic.Anthropic(api_key=api_key)
        self._model = model

    def complete(self, prompt: str) -> str:
        msg = self._client.messages.create(
            model=self._model,
            max_tokens=300,
            messages=[{"role": "user", "content": prompt}],
        )
        return msg.content[0].text


class DJBrain:
    def __init__(self, client: LLMClient, persona: str):
        self.client = client
        self.persona = persona

    def _prompt(self, prev: Optional[Song], nxt: Song, budget: int) -> str:
        prev_line = (f'השיר שהתנגן עכשיו: "{prev.title}" של {prev.artist}.'
                     if prev else "זו פתיחת השידור.")
        return (
            f"אתה שדרן רדיו ישראלי בשם {self.persona}, אנרגטי וטבעי.\n"
            f"{prev_line}\n"
            f'השיר הבא: "{nxt.title}" של {nxt.artist}.\n'
            f"כתוב קטע מעבר קצר בעברית, עד {budget} מילים, שמכריז על השיר הבא. "
            f"החזר רק את הטקסט המדובר, בלי הסברים."
        )

    def write_intro(self, prev: Optional[Song], nxt: Song, seconds: float) -> str:
        budget = words_for_seconds(seconds)
        text = self.client.complete(self._prompt(prev, nxt, budget)).strip()
        words = text.split()
        if len(words) > budget:
            text = " ".join(words[:budget])
        return text