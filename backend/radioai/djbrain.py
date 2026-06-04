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
            f"כתוב משפט קישור אחד קצר וקולע מאוד בעברית מדוברת (סלנג ישראלי בסדר "
            f"גמור) — שורה זריזה אחת בלבד, עד {budget} מילים (עדיף פחות). אסור לחרוג "
            f"מ-{budget} מילים, וחובה לסיים במשפט שלם.\n"
            f"תהיה חכם ומשעשע — אם אפשר, שחק עם שם השיר או שם האמן (משחק מילים).\n"
            f"החזר אך ורק את המשפט עצמו לאמירה בקול — בלי רשימות, בלי אפשרויות, "
            f"בלי מרכאות, בלי כותרות ובלי הסברים."
        )

    def _finish(self, text: str, budget: int) -> str:
        text = text.strip()
        words = text.split()
        if len(words) <= budget:
            return text
        capped = " ".join(words[:budget])
        matches = list(re.finditer(r"[.!?…]", capped))
        if matches:
            return capped[: matches[-1].end()].strip()
        return capped

    def write_intro(self, prev: Optional[Song], nxt: Song, seconds: float) -> str:
        budget = words_for_seconds(seconds)
        text = self.client.complete(self._prompt(prev, nxt, budget))
        return self._finish(text, budget)

    def _weather_prompt(self, nxt: Song, ctx, budget: int) -> str:
        return (
            f"אתה {self.persona}, שדרן רדיו ישראלי שנון וקליל.\n"
            f"השעה {ctx.time_str}, {ctx.part_of_day}. מזג האוויר עכשיו: {ctx.weather}.\n"
            f'השיר הבא: "{nxt.title}" של {nxt.artist}.\n'
            f"כתוב משפט אחד קצר וקולע בעברית מדוברת, עד {budget} מילים, שמשלב את "
            f"השעה/מזג האוויר וזורם אל השיר הבא. אסור לחרוג מ-{budget} מילים, משפט שלם.\n"
            f"החזר רק את המשפט, בלי מרכאות והסברים."
        )

    def _news_prompt(self, nxt: Song, headline: str, budget: int) -> str:
        return (
            f"אתה {self.persona}, שדרן רדיו ישראלי שנון.\n"
            f'כותרת חדשות עכשווית: "{headline}".\n'
            f'השיר הבא: "{nxt.title}" של {nxt.artist}.\n'
            f"כתוב משפט אחד קצר בעברית מדוברת, עד {budget} מילים, שמזכיר בקצרה "
            f"את הכותרת (בניסוח שלך, לא מילה במילה) וממשיך לשיר. אסור לחרוג "
            f"מ-{budget} מילים, משפט שלם.\n"
            f"החזר רק את המשפט, בלי מרכאות והסברים."
        )

    def _topic_prompt(self, nxt: Song, topic: str, headline: str, budget: int) -> str:
        return (
            f"אתה {self.persona}, שדרן רדיו ישראלי שנון שאוהב את הנושא '{topic}'.\n"
            f'כותרת עדכנית בנושא {topic}: "{headline}".\n'
            f'השיר הבא: "{nxt.title}" של {nxt.artist}.\n'
            f"כתוב משפט אחד קצר וקולע בעברית מדוברת, עד {budget} מילים, שמזכיר את "
            f"החדשה בנושא {topic} (בניסוח שלך) וזורם לשיר הבא. אסור לחרוג מ-{budget} "
            f"מילים, משפט שלם.\n"
            f"החזר רק את המשפט, בלי מרכאות והסברים."
        )

    def write_break(self, prev: Optional[Song], nxt: Song, beat: str, ctx,
                    seconds: float, topic: Optional[str] = None) -> str:
        budget = words_for_seconds(seconds)
        if beat == "weather" and ctx.weather:
            prompt = self._weather_prompt(nxt, ctx, budget)
        elif beat == "news" and ctx.general_headline:
            prompt = self._news_prompt(nxt, ctx.general_headline, budget)
        elif beat == "topic" and topic and ctx.topic_headlines.get(topic):
            prompt = self._topic_prompt(nxt, topic, ctx.topic_headlines[topic], budget)
        else:
            return self.write_intro(prev, nxt, seconds)  # song beat / fallback
        return self._finish(self.client.complete(prompt), budget)