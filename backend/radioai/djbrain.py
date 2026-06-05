import re
import json
from typing import Optional, Protocol
from radioai.models import Song

_HEBREW_WORDS_PER_SEC = 2.5

# A budget at/above this many words is "long" -> ask for richer content
# (a fact, a small story, an angle) instead of a one-line quip.
_RICH_BUDGET = 28

_SKIP_TOKEN = "SKIP"


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


# Lines the model sometimes leaks despite instructions: stage directions,
# option markers, English notes. We scrub these from the spoken output.
_DIRECTION_LINE = re.compile(
    r"^\s*(?:option|note|dj|host|intro|outro|aside|stage|"
    r"\d+[\.\)]|[-*•])\s*[:\-]?\s*", re.IGNORECASE)


def _is_skip(text: str) -> bool:
    if text is None:
        return True
    stripped = re.sub(r"[\s\.\!\?…\"'`*]+", "", text).upper()
    return stripped == _SKIP_TOKEN


def _clean(text: str) -> str:
    """Scrub LLM cruft so only spoken Hebrew remains: code fences, markdown,
    surrounding quotes, leaked stage directions / option lists, parentheticals
    that carry English notes, and stray latin letters."""
    if text is None:
        return ""
    t = text.strip()
    # strip code fences
    t = re.sub(r"```[a-zA-Z]*", " ", t)
    # if the model returned an option list / multiple lines, keep the first
    # substantive line.
    lines = [ln.strip() for ln in t.splitlines()]
    lines = [ln for ln in lines if ln]
    picked = []
    for ln in lines:
        ln2 = _DIRECTION_LINE.sub("", ln).strip()
        if ln2:
            picked.append(ln2)
    if picked:
        t = picked[0]
    else:
        t = t.replace("\n", " ")
    # drop parentheticals / brackets (usually leaked directions or English notes)
    t = re.sub(r"[\(\[\{][^\)\]\}]*[\)\]\}]", " ", t)
    # strip markdown emphasis and quote characters
    t = t.replace("**", " ").replace("*", " ").replace("_", " ").replace("#", " ")
    t = t.replace("“", " ").replace("”", " ")
    t = t.replace("‘", " ").replace("’", "'")
    t = t.replace('"', " ").replace("`", " ")
    # remove leaked latin words / stray english (keep Hebrew, digits, punctuation)
    t = re.sub(r"[A-Za-z]+", " ", t)
    # tidy whitespace and dangling punctuation
    t = re.sub(r"\s+([,\.\!\?…:;])", r"\1", t)
    t = re.sub(r"\s{2,}", " ", t).strip()
    t = t.strip(" -–—:;,")
    return t.strip()



def _extract_json_array(text: str):
    """Pull the first JSON array out of an LLM reply, tolerating code fences,
    prose, and trailing commentary. Tries the greedy match first, then the
    first balanced array if the greedy parse fails. Mirrors setlist.py so the
    whole codebase parses LLM JSON the same defensive way."""
    if text is None:
        raise ValueError("No JSON array found in LLM output")
    match = re.search(r"\[.*\]", text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(0))
        except (ValueError, json.JSONDecodeError):
            pass
    depth = 0
    start = -1
    for i, ch in enumerate(text):
        if ch == "[":
            if depth == 0:
                start = i
            depth += 1
        elif ch == "]" and depth > 0:
            depth -= 1
            if depth == 0 and start >= 0:
                chunk = text[start:i + 1]
                try:
                    return json.loads(chunk)
                except (ValueError, json.JSONDecodeError):
                    start = -1
                    continue
    raise ValueError("No JSON array found in LLM output")


class DJBrain:
    def __init__(self, client: LLMClient, persona: str):
        self.client = client
        self.persona = persona

    # ---- shared prompt fragments ----------------------------------------

    def _persona_line(self) -> str:
        return (
            f"אתה {self.persona}, שדרן רדיו ישראלי כריזמטי, שנון ומצחיק שמדבר כמו "
            f"בנאדם אמיתי ברדיו FM - חם, אנרגטי, זורם ואנושי."
        )

    def _depth_line(self, budget: int) -> str:
        """Short budget -> punchy one-liner. Long budget -> genuinely richer
        content (a cool fact / mini-story / interesting angle), never filler."""
        if budget >= _RICH_BUDGET:
            return (
                "יש לך זמן אוויר אמיתי כאן - אל תמתח משפט אחד, תן תוכן עשיר "
                "ומעניין: עובדה מגניבה על השיר או האמן, סיפור קטן, או זווית "
                "מקורית ומשעשעת. שתי-שלוש מחשבות שזורמות, לא מילוי סרק."
            )
        return "שורה זריזה אחת, קולעת ומצחיקה - בלי למתוח."

    def _wit_line(self) -> str:
        return (
            "תהיה חכם ומשעשע, ושאף לעשות משחק מילים שנון על שם השיר או שם האמן. "
            "אל תחזור על אותה פתיחה פעמיים - תפתיע, תהיה מגוון ואנושי."
        )

    def _naming_line(self) -> str:
        """Real DJs announce / back-announce the tracks. Tell the model to weave
        the outgoing and incoming song + artist names into the line naturally,
        with wordplay where it fits - but to vary it, not name them formulaically
        every single time."""
        return (
            "כמו שדרן אמיתי, שזור את שם השיר ושם האמן בתוך המשפט בטבעיות - "
            "הכרז על השיר שעולה ו/או 'סגור' את השיר שהרגע התנגן (back-announce), "
            "רצוי עם קריצה או משחק מילים על שם השיר/האמן. אבל אל תעשה את זה "
            "בכל פעם באותה צורה ולא תמיד - תהיה מגוון, לפעמים רק מזכיר שם אחד, "
            "לפעמים את שניהם, ולפעמים סתם זורם - שזה יישמע אנושי ולא רובוטי."
        )

    def _format_line(self, budget: int) -> str:
        return (
            f"דבר בעברית מדוברת בלבד (סלנג ישראלי בסדר גמור), עד {budget} מילים. "
            f"אסור לחרוג מ-{budget} מילים וחובה לסיים במשפט שלם. החזר אך ורק את "
            f"הטקסט המדובר עצמו - בלי אנגלית, בלי מרכאות, בלי כוכביות או עיצוב, "
            f"בלי הסברים, בלי רשימות ובלי אפשרויות."
        )

    def _skip_line(self) -> str:
        return (
            "אם אין כרגע שום דבר באמת שנון, מעניין או רלוונטי להגיד כאן - עדיף "
            "שתשתוק. במקרה כזה החזר בדיוק את המילה SKIP (ותו לא)."
        )

    def _maybe_skip(self, budget: int, allow_skip: bool) -> str:
        return ("\n" + self._skip_line() + "\n") if allow_skip else ""

    # ---- per-beat prompts -----------------------------------------------

    def _prompt(self, prev: Optional[Song], nxt: Song, budget: int,
                allow_skip: bool = False) -> str:
        prev_line = (f'השיר שהרגע התנגן: "{prev.title}" של {prev.artist}.'
                     if prev else "זו פתיחת השידור.")
        return (
            f"{self._persona_line()}\n"
            f"{prev_line}\n"
            f'עכשיו עומד להתנגן: "{nxt.title}" של {nxt.artist}.\n'
            f"כתוב קישור מדובר אל השיר הבא. {self._depth_line(budget)}\n"
            f"{self._naming_line()}\n"
            f"{self._wit_line()}\n"
            f"{self._maybe_skip(budget, allow_skip)}"
            f"{self._format_line(budget)}"
        )

    def _weather_prompt(self, nxt: Song, ctx, budget: int,
                        allow_skip: bool = False) -> str:
        return (
            f"{self._persona_line()}\n"
            f"השעה {ctx.time_str}, {ctx.part_of_day}. מזג האוויר עכשיו: {ctx.weather}.\n"
            f'השיר הבא: "{nxt.title}" של {nxt.artist}.\n'
            f"שלב את השעה/מזג האוויר וזרום אל השיר הבא. {self._depth_line(budget)}\n"
            f"{self._wit_line()}\n"
            f"{self._maybe_skip(budget, allow_skip)}"
            f"{self._format_line(budget)}"
        )

    def _news_prompt(self, nxt: Song, headline: str, budget: int,
                     allow_skip: bool = False) -> str:
        return (
            f"{self._persona_line()}\n"
            f'כותרת חדשות עכשווית: "{headline}".\n'
            f'השיר הבא: "{nxt.title}" של {nxt.artist}.\n'
            f"הזכר בקצרה את הכותרת בניסוח שלך (לא מילה במילה) והמשך לשיר. "
            f"{self._depth_line(budget)}\n"
            f"{self._wit_line()}\n"
            f"{self._maybe_skip(budget, allow_skip)}"
            f"{self._format_line(budget)}"
        )

    def _topic_prompt(self, nxt: Song, topic: str, headline: str, budget: int,
                      allow_skip: bool = False) -> str:
        return (
            f"{self._persona_line()} אתה אוהב את הנושא '{topic}'.\n"
            f'כותרת עדכנית בנושא {topic}: "{headline}".\n'
            f'השיר הבא: "{nxt.title}" של {nxt.artist}.\n'
            f"הזכר את החדשה בנושא {topic} בניסוח שלך וזרום לשיר הבא. "
            f"{self._depth_line(budget)}\n"
            f"{self._wit_line()}\n"
            f"{self._maybe_skip(budget, allow_skip)}"
            f"{self._format_line(budget)}"
        )

    # ---- output shaping --------------------------------------------------

    def _finish(self, text: str, budget: int) -> str:
        text = _clean(text)
        words = text.split()
        if len(words) <= budget:
            return text
        capped = " ".join(words[:budget])
        matches = list(re.finditer(r"[.!?…]", capped))
        if matches:
            return capped[: matches[-1].end()].strip()
        return capped

    def _refine(self, line: str, budget: int) -> str:
        """Optional cheap second pass to sharpen the line. Robust: any failure
        or empty/garbage result falls back to the original line."""
        try:
            prompt = (
                f"{self._persona_line()}\n"
                f'הנה שורת קישור שכתבת: "{line}".\n'
                f"חדד אותה: יותר שנונה וטבעית, רצוי עם משחק מילים על שם השיר/אמן, "
                f"עד {budget} מילים, משפט שלם. החזר רק את השורה המשופרת בעברית "
                f"מדוברת, בלי מרכאות, אנגלית, עיצוב או הסברים."
            )
            out = self._finish(self.client.complete(prompt), budget)
            if out and out.strip():
                return out
        except Exception:
            pass
        return line

    # ---- public API ------------------------------------------------------

    def write_intro(self, prev: Optional[Song], nxt: Song, seconds: float) -> str:
        budget = words_for_seconds(seconds)
        text = self.client.complete(self._prompt(prev, nxt, budget))
        return self._finish(text, budget)

    def write_break(self, prev: Optional[Song], nxt: Song, beat: str, ctx,
                    seconds: float, topic: Optional[str] = None,
                    allow_skip: bool = False) -> Optional[str]:
        budget = words_for_seconds(seconds)
        if beat == "weather" and ctx.weather:
            prompt = self._weather_prompt(nxt, ctx, budget, allow_skip)
        elif beat == "news" and ctx.general_headline:
            prompt = self._news_prompt(nxt, ctx.general_headline, budget,
                                       allow_skip)
        elif beat == "topic" and topic and ctx.topic_headlines.get(topic):
            prompt = self._topic_prompt(nxt, topic, ctx.topic_headlines[topic],
                                        budget, allow_skip)
        else:
            # song beat / fallback -> witty handoff (honors allow_skip too)
            prompt = self._prompt(prev, nxt, budget, allow_skip)
        raw = self.client.complete(prompt)
        if allow_skip and _is_skip(raw):
            return None
        return self._finish(raw, budget)


    # ---- two-host banter -------------------------------------------------

    def _banter_prompt(self, ctx, budget: int,
                       topic: Optional[str] = None) -> str:
        """A short two-person Hebrew bit: two distinct personalities riffing on
        the time / weather / news / topic, to make the drive feel like a fun,
        interactive two-man podcast - but SHORT and high quality."""
        bits = []
        if getattr(ctx, "time_str", None):
            bits.append(f"השעה {ctx.time_str}")
        if getattr(ctx, "part_of_day", None):
            bits.append(str(ctx.part_of_day))
        if getattr(ctx, "weather", None):
            bits.append(f"מזג אוויר: {ctx.weather}")
        if getattr(ctx, "general_headline", None):
            bits.append(f"כותרת: {ctx.general_headline}")
        if topic:
            head = {}
            try:
                head = ctx.topic_headlines or {}
            except Exception:
                head = {}
            if head.get(topic):
                bits.append(f"בנושא {topic}: {head[topic]}")
            else:
                bits.append(f"נושא לדבר עליו: {topic}")
        ctx_line = " | ".join(bits) if bits else "אין הקשר מיוחד כרגע."
        return (
            f"{self._persona_line()}\n"
            f"זה קטע קצר של שני שדרנים באולפן רדיו ישראלי - דו-שיח מצחיק "
            f"ושנון, כמו פודקאסט של שניים. שדרן A הוא אתה: חם, מוביל ומזמין. "
            f"שדרן B הוא שותף לאולפן: שנון, חד וקצת קונדס. הם מפנגים זה את זה.\n"
            f"הקשר נוכחי: {ctx_line}\n"
            f"כתוב חילופי דברים קצרים: 2 עד 5 תורות לסירוגין (A ואז B ואז A...), "
            f"מתחילים ב-A. או בדיחה קצרה, או עובדה מעניינת, או פינג-פונג משעשע. "
            f"קצר ולעניין - אל תאריך, אל תמלא זמן אוויר סתם. "
            f"סך הכל עד {budget} מילים בכל התורות יחד.\n"
            f"{self._wit_line()}\n"
            f"דבר בעברית מדוברת בלבד, בלי אנגלית, בלי מרכאות, בלי כוכביות "
            f"או עיצוב, בלי הוראות במה. החזר אך ורק מערך JSON תקין בצורה הזו "
            f'בדיוק:\n[{{"speaker":"A","text":"..."}},'
            f'{{"speaker":"B","text":"..."}}]'
        )

    def write_banter(self, ctx, seconds: float = 12.0,
                     topic: Optional[str] = None) -> list:
        """A short two-host Hebrew exchange (2-5 alternating turns A/B). Returns
        a list of (speaker, text) tuples; speaker is "A" (the persona/main host)
        or "B" (co-host), alternating, cleaned to pure spoken Hebrew, with the
        total words across all turns held within the budget for `seconds`.

        Parses the model's JSON defensively. If nothing usable comes back, it
        degrades gracefully to a single [("A", <one cleaned host line>)]."""
        budget = words_for_seconds(seconds)
        raw = self.client.complete(self._banter_prompt(ctx, budget, topic))

        turns: list = []
        try:
            data = _extract_json_array(raw)
        except Exception:
            data = None

        if isinstance(data, list):
            expected = "A"
            for item in data:
                if not isinstance(item, dict):
                    continue
                spk = str(item.get("speaker", "")).strip().upper()
                if spk not in ("A", "B"):
                    # tolerate names/roles -> infer by strict alternation
                    spk = expected
                text = _clean(item.get("text", ""))
                if not text:
                    continue
                # enforce strict alternation starting at A; drop a repeated
                # speaker so the back-and-forth always reads cleanly.
                if turns and spk == turns[-1][0]:
                    spk = "A" if turns[-1][0] == "B" else "B"
                if not turns and spk != "A":
                    spk = "A"
                turns.append((spk, text))
                expected = "B" if spk == "A" else "A"

        # keep total words within budget (trim whole turns from the end)
        if turns and budget >= 0:
            kept: list = []
            used = 0
            for spk, text in turns:
                w = text.split()
                if not w:
                    continue
                if used + len(w) <= budget:
                    kept.append((spk, text))
                    used += len(w)
                else:
                    room = budget - used
                    if room > 0:
                        clipped = self._finish(text, room)
                        if clipped.strip():
                            kept.append((spk, clipped))
                            used += len(clipped.split())
                    break
            turns = kept

        if turns:
            return turns

        # FALLBACK: degrade to one host line.
        line = self._finish(raw, budget)
        if not line.strip():
            line = self._finish(_clean(raw), budget)
        return [("A", line)]

