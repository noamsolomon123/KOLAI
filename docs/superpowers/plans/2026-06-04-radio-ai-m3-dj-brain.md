# Radio AI — M3: Full DJ Brain — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the DJ real-world things to say — time-of-day, weather (Open-Meteo), and news (Google News RSS: general + favorite topics) — woven into short, witty Hebrew breaks that rotate a "beat" so they vary and stay brief.

**Architecture:** Small isolated units — `clock`, `WeatherService`, `NewsService` — each with pure parsers (unit-tested with mock payloads) behind thin injectable HTTP. A `DJContext` bundles them; `DJBrain.write_break` composes a short line per beat; `render_show` rotates beats across DJ breaks. Any fetch failure falls back to a song intro. No new dependencies (uses `requests` via spotipy + stdlib `xml.etree`).

**Tech Stack:** Python 3.11+, `requests`, `xml.etree.ElementTree`, existing `GeminiClient`, `pytest`.

---

## Environment notes (every task)
- Work from `C:\dev\RadioAI\backend`. Use venv python for ALL commands: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe`
- Run tests with that interpreter, e.g. `...\.venv\Scripts\python.exe -m pytest tests/<file> -v`.
- Prefer the PowerShell tool. Hebrew strings are UTF-8 — write directly. If the Write/Edit tool errors on Hebrew, use `[System.IO.File]::WriteAllText(path, text)` via PowerShell (UTF-8).
- Repo has `core.autocrlf=true` and a prior crash dropped commits. After each commit, VERIFY with `git diff --ignore-all-space --stat` that nothing real is uncommitted; re-add/commit if so. Plain `git status`/`git diff` can mislead.
- `.env` already has `CITY=Tel Aviv` and `TOPICS=technology,physics,robotics,AI`.
- End every commit message with a blank line then: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

## File Structure
```
backend/radioai/
  config.py        # MODIFY: add city: str, topics: list[str]
  clock.py         # CREATE: now_parts(dt)
  weather.py       # CREATE: WeatherNow, parse_weather, weather_code_to_hebrew, WeatherService
  news.py          # CREATE: google_news_url, parse_news_rss, NewsService
  djcontext.py     # CREATE: DJContext + build()
  djbrain.py       # MODIFY: extract _finish, add write_break + beat prompts
  render_show.py   # MODIFY: build DJContext, beat_for_break, rotate beats, write_break
backend/tests/
  test_config.py        # MODIFY
  test_clock.py         # CREATE
  test_weather.py       # CREATE
  test_news.py          # CREATE
  test_djcontext.py     # CREATE
  test_djbrain.py       # MODIFY (write_break tests)
  test_render_beats.py  # CREATE (beat_for_break)
```

---

## Task 1: Config city + topics

**Files:** Modify `backend/radioai/config.py`, `backend/tests/test_config.py`

- [ ] **Step 1: Append failing test to `backend/tests/test_config.py`**
```python
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
```

- [ ] **Step 2: Run it; confirm FAIL** (`AttributeError: ... 'city'`).
Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_config.py -v`

- [ ] **Step 3: Edit `backend/radioai/config.py`**
Change the imports line `from dataclasses import dataclass` to:
```python
from dataclasses import dataclass, field
```
Add these two fields at the END of the `Config` dataclass (after `spotify_redirect_uri`):
```python
    city: str = ""
    topics: list[str] = field(default_factory=list)
```
In `from_env`, add these two arguments to the `return cls(...)` call (after `spotify_redirect_uri=...`):
```python
            city=os.environ.get("CITY", ""),
            topics=[t.strip() for t in os.environ.get("TOPICS", "").split(",")
                    if t.strip()],
```
(Defaults mean existing tests that build `Config(...)` directly without these still work.)

- [ ] **Step 4: Run tests; confirm PASS.** Then full suite `...python.exe -m pytest -q` (was 67 → now 69).

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/config.py backend/tests/test_config.py
git commit -m "feat: add city and topics config for M3"
```
Verify `git diff --ignore-all-space --stat` clean.

---

## Task 2: Clock — now_parts

**Files:** Create `backend/radioai/clock.py`, `backend/tests/test_clock.py`

- [ ] **Step 1: Write `backend/tests/test_clock.py`**
```python
from datetime import datetime
from radioai.clock import now_parts


def test_time_string():
    ts, _ = now_parts(datetime(2026, 6, 4, 21, 40))
    assert ts == "21:40"


def test_parts_of_day():
    assert now_parts(datetime(2026, 6, 4, 8, 0))[1] == "בוקר"
    assert now_parts(datetime(2026, 6, 4, 14, 0))[1] == "צהריים"
    assert now_parts(datetime(2026, 6, 4, 19, 0))[1] == "ערב"
    assert now_parts(datetime(2026, 6, 4, 2, 0))[1] == "לילה"
```

- [ ] **Step 2: Run it; confirm FAIL** (`ModuleNotFoundError: radioai.clock`).

- [ ] **Step 3: Write `backend/radioai/clock.py`**
```python
from datetime import datetime


def now_parts(dt: datetime) -> tuple[str, str]:
    """Return (HH:MM, Hebrew part-of-day) for the given datetime."""
    time_str = dt.strftime("%H:%M")
    h = dt.hour
    if 5 <= h <= 11:
        part = "בוקר"
    elif 12 <= h <= 16:
        part = "צהריים"
    elif 17 <= h <= 21:
        part = "ערב"
    else:
        part = "לילה"
    return time_str, part
```

- [ ] **Step 4: Run tests; confirm PASS.** Full suite → 71 passed.

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/clock.py backend/tests/test_clock.py
git commit -m "feat: add clock now_parts (time + Hebrew part-of-day)"
```
Verify `git diff --ignore-all-space --stat` clean.

---

## Task 3: WeatherService (Open-Meteo)

**Files:** Create `backend/radioai/weather.py`, `backend/tests/test_weather.py`

- [ ] **Step 1: Write `backend/tests/test_weather.py`**
```python
from radioai.weather import (
    WeatherNow, parse_weather, weather_code_to_hebrew, WeatherService,
)


def test_parse_weather():
    w = parse_weather({"current": {"temperature_2m": 24.3, "weather_code": 0}})
    assert isinstance(w, WeatherNow)
    assert abs(w.temp_c - 24.3) < 0.001
    assert w.code == 0


def test_weather_code_to_hebrew_known_and_unknown():
    assert "בהיר" in weather_code_to_hebrew(0)
    assert weather_code_to_hebrew(999) == "מזג אוויר משתנה"


def test_for_city_builds_summary():
    def fake_get(url, params=None):
        if "geocoding" in url:
            return {"results": [{"latitude": 32.07, "longitude": 34.78}]}
        return {"current": {"temperature_2m": 24.3, "weather_code": 0}}

    svc = WeatherService(get_json=fake_get)
    summary = svc.for_city("Tel Aviv")
    assert "24" in summary           # rounded temp
    assert "מעלות" in summary
    assert "בהיר" in summary         # code 0 -> שמיים בהירים


def test_for_city_missing_raises():
    def fake_get(url, params=None):
        return {"results": []}
    import pytest
    with pytest.raises(ValueError):
        WeatherService(get_json=fake_get).for_city("Nowhere")
```

- [ ] **Step 2: Run it; confirm FAIL** (`ModuleNotFoundError: radioai.weather`).

- [ ] **Step 3: Write `backend/radioai/weather.py`**
```python
from dataclasses import dataclass
import requests

_GEO_URL = "https://geocoding-api.open-meteo.com/v1/search"
_FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

# WMO weather codes -> short Hebrew description.
_CODE_HE = {
    0: "שמיים בהירים", 1: "בהיר ברובו", 2: "מעונן חלקית", 3: "מעונן",
    45: "ערפל", 48: "ערפל", 51: "טפטוף קל", 53: "טפטוף", 55: "טפטוף חזק",
    61: "גשם קל", 63: "גשם", 65: "גשם חזק", 71: "שלג קל", 73: "שלג",
    75: "שלג כבד", 80: "ממטרים", 81: "ממטרים", 82: "ממטרים עזים",
    95: "סופת רעמים", 96: "סופת רעמים", 99: "סופת רעמים",
}


@dataclass
class WeatherNow:
    temp_c: float
    code: int


def weather_code_to_hebrew(code: int) -> str:
    return _CODE_HE.get(code, "מזג אוויר משתנה")


def parse_weather(forecast_json: dict) -> WeatherNow:
    cur = forecast_json.get("current", {})
    return WeatherNow(temp_c=float(cur.get("temperature_2m", 0.0)),
                      code=int(cur.get("weather_code", 0)))


class WeatherService:
    """Current weather for a city via Open-Meteo (free, no API key). Inject
    `get_json(url, params)` for tests; defaults to a real HTTP GET."""

    def __init__(self, get_json=None):
        self._get = get_json or self._http_get

    def _http_get(self, url, params=None):
        r = requests.get(url, params=params, timeout=10)
        r.raise_for_status()
        return r.json()

    def for_city(self, city: str) -> str:
        geo = self._get(_GEO_URL, {"name": city, "count": 1})
        results = geo.get("results") or []
        if not results:
            raise ValueError(f"City not found: {city}")
        lat = results[0]["latitude"]
        lon = results[0]["longitude"]
        fc = self._get(_FORECAST_URL, {
            "latitude": lat, "longitude": lon,
            "current": "temperature_2m,weather_code",
        })
        w = parse_weather(fc)
        return f"{round(w.temp_c)} מעלות, {weather_code_to_hebrew(w.code)}"
```

- [ ] **Step 4: Run tests; confirm PASS (4).** Full suite → 75 passed.

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/weather.py backend/tests/test_weather.py
git commit -m "feat: add Open-Meteo WeatherService with Hebrew conditions"
```
Verify `git diff --ignore-all-space --stat` clean.

---

## Task 4: NewsService (Google News RSS)

**Files:** Create `backend/radioai/news.py`, `backend/tests/test_news.py`

- [ ] **Step 1: Write `backend/tests/test_news.py`**
```python
from radioai.news import google_news_url, parse_news_rss, NewsService

_RSS = """<?xml version="1.0"?>
<rss version="2.0"><channel>
<title>feed</title>
<item><title>כותרת ראשונה</title><link>http://a</link></item>
<item><title>כותרת שנייה</title><link>http://b</link></item>
</channel></rss>"""


def test_google_news_url_general_is_hebrew():
    url = google_news_url()
    assert "hl=he" in url and "gl=IL" in url
    assert "/search" not in url


def test_google_news_url_topic_query():
    url = google_news_url("AI")
    assert "/search?q=AI" in url
    assert "hl=he" in url


def test_parse_news_rss_titles_in_order():
    titles = parse_news_rss(_RSS)
    assert titles == ["כותרת ראשונה", "כותרת שנייה"]


def test_headlines_limit():
    svc = NewsService(get_text=lambda url: _RSS)
    assert svc.headlines("AI", limit=1) == ["כותרת ראשונה"]


def test_top_for_topics_skips_failures():
    def flaky(url):
        if "physics" in url.lower():
            raise RuntimeError("boom")
        return _RSS
    svc = NewsService(get_text=flaky)
    out = svc.top_for_topics(["AI", "physics"])
    assert out["AI"] == "כותרת ראשונה"
    assert "physics" not in out
```

- [ ] **Step 2: Run it; confirm FAIL** (`ModuleNotFoundError: radioai.news`).

- [ ] **Step 3: Write `backend/radioai/news.py`**
```python
import xml.etree.ElementTree as ET
from urllib.parse import quote
import requests

_BASE = "https://news.google.com/rss"
_TAIL = "hl=he&gl=IL&ceid=IL:he"


def google_news_url(query: str | None = None) -> str:
    if query:
        return f"{_BASE}/search?q={quote(query)}&{_TAIL}"
    return f"{_BASE}?{_TAIL}"


def parse_news_rss(xml_text: str) -> list[str]:
    root = ET.fromstring(xml_text)
    titles = []
    for item in root.iter("item"):
        t = item.find("title")
        if t is not None and t.text:
            titles.append(t.text.strip())
    return titles


class NewsService:
    """Headlines from Google News RSS (free, no API key, Hebrew). Inject
    `get_text(url)` for tests; defaults to a real HTTP GET."""

    def __init__(self, get_text=None):
        self._get = get_text or self._http_get

    def _http_get(self, url):
        r = requests.get(url, timeout=10)
        r.raise_for_status()
        return r.text

    def headlines(self, query: str | None = None, limit: int = 1) -> list[str]:
        xml = self._get(google_news_url(query))
        return parse_news_rss(xml)[:limit]

    def top_for_topics(self, topics) -> dict:
        out = {}
        for topic in topics:
            try:
                hs = self.headlines(topic, limit=1)
                if hs:
                    out[topic] = hs[0]
            except Exception:
                continue
        return out
```

- [ ] **Step 4: Run tests; confirm PASS (5).** Full suite → 80 passed.

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/news.py backend/tests/test_news.py
git commit -m "feat: add Google News RSS NewsService (general + per-topic)"
```
Verify `git diff --ignore-all-space --stat` clean.

---

## Task 5: DJContext

**Files:** Create `backend/radioai/djcontext.py`, `backend/tests/test_djcontext.py`

- [ ] **Step 1: Write `backend/tests/test_djcontext.py`**
```python
from datetime import datetime
from radioai.config import Config
from radioai.djcontext import DJContext


def _cfg():
    return Config(gemini_api_keys=[], llm_model="m", tts_model="m", tts_voice="v",
                  cache_dir=".", spotify_client_id="", spotify_client_secret="",
                  spotify_redirect_uri="x", city="Tel Aviv",
                  topics=["AI", "physics"])


class _FakeWeather:
    def for_city(self, city):
        return "24 מעלות, שמיים בהירים"


class _FakeNews:
    def headlines(self, query=None, limit=1):
        return ["חדשות כלליות"]

    def top_for_topics(self, topics):
        return {t: f"כותרת על {t}" for t in topics}


def test_build_populates_all(  ):
    ctx = DJContext.build(_cfg(), clock_dt=datetime(2026, 6, 4, 19, 0),
                          weather=_FakeWeather(), news=_FakeNews())
    assert ctx.time_str == "19:00"
    assert ctx.part_of_day == "ערב"
    assert ctx.weather == "24 מעלות, שמיים בהירים"
    assert ctx.general_headline == "חדשות כלליות"
    assert ctx.topic_headlines["AI"] == "כותרת על AI"


class _BoomWeather:
    def for_city(self, city):
        raise RuntimeError("no net")


def test_build_is_resilient_to_weather_failure():
    ctx = DJContext.build(_cfg(), clock_dt=datetime(2026, 6, 4, 19, 0),
                          weather=_BoomWeather(), news=_FakeNews())
    assert ctx.weather is None              # failure -> None, no crash
    assert ctx.general_headline == "חדשות כלליות"
```

- [ ] **Step 2: Run it; confirm FAIL** (`ModuleNotFoundError: radioai.djcontext`).

- [ ] **Step 3: Write `backend/radioai/djcontext.py`**
```python
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional
from radioai.clock import now_parts
from radioai.weather import WeatherService
from radioai.news import NewsService


@dataclass
class DJContext:
    time_str: str
    part_of_day: str
    weather: Optional[str]
    general_headline: Optional[str]
    topic_headlines: dict = field(default_factory=dict)

    @classmethod
    def build(cls, cfg, *, clock_dt=None, weather=None, news=None) -> "DJContext":
        dt = clock_dt or datetime.now()
        time_str, part = now_parts(dt)
        ws = weather if weather is not None else WeatherService()
        ns = news if news is not None else NewsService()

        weather_str = None
        try:
            if cfg.city:
                weather_str = ws.for_city(cfg.city)
        except Exception:
            weather_str = None

        general = None
        try:
            hs = ns.headlines(None, limit=1)
            general = hs[0] if hs else None
        except Exception:
            general = None

        topics = {}
        try:
            topics = ns.top_for_topics(cfg.topics)
        except Exception:
            topics = {}

        return cls(time_str=time_str, part_of_day=part, weather=weather_str,
                   general_headline=general, topic_headlines=topics)
```

- [ ] **Step 4: Run tests; confirm PASS (2).** Full suite → 82 passed.

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/djcontext.py backend/tests/test_djcontext.py
git commit -m "feat: add DJContext bundling time, weather, and news"
```
Verify `git diff --ignore-all-space --stat` clean.

---

## Task 6: DJBrain.write_break + beats

**Files:** Modify `backend/radioai/djbrain.py`, `backend/tests/test_djbrain.py`

- [ ] **Step 1: Append failing tests to `backend/tests/test_djbrain.py`**
```python
from radioai.djcontext import DJContext


def _ctx(weather=None, general=None, topics=None):
    return DJContext(time_str="19:00", part_of_day="ערב", weather=weather,
                     general_headline=general, topic_headlines=topics or {})


def test_write_break_weather_uses_context():
    client = _FakeClient("ערב טוב, 24 מעלות, שיר בדרך!")
    brain = DJBrain(client=client, persona="רדיו AI")
    ctx = _ctx(weather="24 מעלות, שמיים בהירים")
    out = brain.write_break(prev=None, nxt=Song("A", "B"), beat="weather",
                            ctx=ctx, seconds=4.0)
    assert "24 מעלות" in client.last_prompt
    assert "ערב" in client.last_prompt
    assert out.strip() != ""


def test_write_break_topic_uses_headline():
    client = _FakeClient("חדשות מהעולם הטכנולוגי, ועכשיו שיר!")
    brain = DJBrain(client=client, persona="רדיו AI")
    ctx = _ctx(topics={"AI": "פריצת דרך חדשה ב-AI"})
    out = brain.write_break(prev=None, nxt=Song("A", "B"), beat="topic",
                            ctx=ctx, seconds=4.0, topic="AI")
    assert "פריצת דרך חדשה ב-AI" in client.last_prompt
    assert "AI" in client.last_prompt


def test_write_break_news_uses_general():
    client = _FakeClient("כותרת חמה, ומיד מוזיקה!")
    brain = DJBrain(client=client, persona="רדיו AI")
    ctx = _ctx(general="כותרת חדשותית חשובה")
    out = brain.write_break(prev=None, nxt=Song("A", "B"), beat="news",
                            ctx=ctx, seconds=4.0)
    assert "כותרת חדשותית חשובה" in client.last_prompt


def test_write_break_falls_back_to_song_when_context_missing():
    client = _FakeClient("ברוכים הבאים, שיר ראשון!")
    brain = DJBrain(client=client, persona="רדיו AI")
    ctx = _ctx(weather=None)  # weather beat requested but no weather
    brain.write_break(prev=None, nxt=Song("A", "B"), beat="weather",
                      ctx=ctx, seconds=4.0)
    # fell back to write_intro's song prompt (prev=None marker)
    assert "זו פתיחת השידור" in client.last_prompt
```

- [ ] **Step 2: Run it; confirm FAIL** (`AttributeError: ... 'write_break'`).
Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_djbrain.py -v`

- [ ] **Step 3: Edit `backend/radioai/djbrain.py`**

(a) Refactor the trim logic out of `write_intro` into a shared `_finish` method, and add `write_break` + beat-prompt helpers. Replace the existing `write_intro` method with these methods (keep `_prompt`, `words_for_seconds`, `LLMClient`, `GeminiClient` as they are):
```python
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
            f"כותרת חדשות עכשווית: \"{headline}\".\n"
            f'השיר הבא: "{nxt.title}" של {nxt.artist}.\n'
            f"כתוב משפט אחד קצר בעברית מדוברת, עד {budget} מילים, שמזכיר בקצרה "
            f"ובחן את הכותרת (בניסוח שלך, לא מילה במילה) וממשיך לשיר. אסור לחרוג "
            f"מ-{budget} מילים, משפט שלם.\n"
            f"החזר רק את המשפט, בלי מרכאות והסברים."
        )

    def _topic_prompt(self, nxt: Song, topic: str, headline: str, budget: int) -> str:
        return (
            f"אתה {self.persona}, שדרן רדיו ישראלי שנון שאוהב את הנושא '{topic}'.\n"
            f"כותרת עדכנית בנושא {topic}: \"{headline}\".\n"
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
```

- [ ] **Step 4: Run tests; confirm PASS.** Run `...python.exe -m pytest tests/test_djbrain.py -v` (existing + 4 new). Then full suite `...python.exe -m pytest -q` → 86 passed.

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/djbrain.py backend/tests/test_djbrain.py
git commit -m "feat: add DJBrain.write_break with weather/news/topic beats + fallback"
```
Verify `git diff --ignore-all-space --stat` clean.

---

## Task 7: render_show integration + beat rotation

**Files:** Modify `backend/radioai/render_show.py`, create `backend/tests/test_render_beats.py`

- [ ] **Step 1: Write `backend/tests/test_render_beats.py`**
```python
from radioai.render_show import beat_for_break, BEATS


def test_beats_rotate():
    assert BEATS[0] == "song"
    seq = [beat_for_break(k) for k in range(len(BEATS) + 1)]
    assert seq[:len(BEATS)] == BEATS
    assert seq[len(BEATS)] == BEATS[0]   # wraps around
```

- [ ] **Step 2: Run it; confirm FAIL** (`ImportError: ... beat_for_break`).

- [ ] **Step 3: Edit `backend/radioai/render_show.py`**

(a) Add imports with the other `from radioai...` imports:
```python
from radioai.djcontext import DJContext
```

(b) Add the beat constants + helper near the other module constants (after `_SEGUE_S`/`DUCK_DB`):
```python
BEATS = ["song", "weather", "topic", "news"]


def beat_for_break(k: int) -> str:
    return BEATS[k % len(BEATS)]
```

(c) In `main()`, after `cfg = Config.from_env()` and after `voice`/`brain` are created, build the context once (before the song loop):
```python
    ctx = DJContext.build(cfg)
    print(f"Context: {ctx.time_str} {ctx.part_of_day} | weather={ctx.weather} | "
          f"topics={list(ctx.topic_headlines)}")
    topic_list = list(ctx.topic_headlines.keys())
    break_k = 0
    topic_k = 0
```
(Place these right before the `timeline = tracks[0][2]` line. `dj_lines = []` already exists — keep it.)

(d) Replace the talkover branch body so it picks a beat and uses `write_break`. The talkover branch becomes:
```python
        if t.type == "talkover":
            beat = beat_for_break(break_k)
            break_k += 1
            topic = None
            if beat == "topic" and topic_list:
                topic = topic_list[topic_k % len(topic_list)]
                topic_k += 1
            script = brain.write_break(prev=prev_song, nxt=song, beat=beat,
                                       ctx=ctx, seconds=t.duration_s, topic=topic)
            print(f"    DJ [{beat}]: {script}")
            dj_lines.append(f"[{beat}: {prev_song.title} -> {song.title}]\n{script}")
            slot = voice.render(script)
            dj_audio = mx.trim_silence(mx.load_mono(slot.audio_path))
            dj_dur = len(dj_audio) / mx.SR
            duck_start = max(0.0, len(timeline) / mx.SR - dj_dur - _SEGUE_S - 0.3)
            timeline = mx.duck(timeline, dj_audio, start_s=duck_start,
                               attenuation_db=DUCK_DB)
            timeline = mx.equal_power_crossfade(timeline, audio, overlap_s=_SEGUE_S)
```
Leave the beatmatch/crossfade/cut branches and the show_script writing unchanged.

- [ ] **Step 4: Run tests; confirm PASS.** `...python.exe -m pytest tests/test_render_beats.py -v` (1). Then full suite `...python.exe -m pytest -q` → 87 passed. Import check: `...python.exe -c "import radioai.render_show"` → no error.

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/render_show.py backend/tests/test_render_beats.py
git commit -m "feat: rotate DJ beats (song/weather/topic/news) in render with live context"
```
Verify `git diff --ignore-all-space --stat` clean.

- [ ] **Step 6: Golden-ear render (real weather + news; Spotify taste cached → no browser)**
Clear stale audio so a fresh show is built (KEEP cache/taste.json and cached song mp3s):
```
Remove-Item C:\dev\RadioAI\backend\cache\show.mp3 -Force -ErrorAction SilentlyContinue
Remove-Item C:\dev\RadioAI\backend\cache\voice\*.wav -Force -ErrorAction SilentlyContinue
C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m radioai.render_show
```
Verify `ffprobe -v error -show_entries format=duration -of csv=p=0 cache\show.mp3` (>0) and print `cache\show_script.txt` (Get-Content -Encoding utf8 -Raw). Confirm DJ breaks vary across beats and include a real Tel Aviv weather line, a real tech/AI/physics/robotics headline, and time-of-day — all short and witty. (If offline, beats fall back to song intros — that's the resilience path.)

---

## Self-Review

**Spec coverage:**
- Time-of-day → Task 2 (`now_parts`) ✓
- Weather (Open-Meteo, no key) → Task 3 ✓
- News general + per-topic (Google News RSS) → Task 4 ✓
- DJContext bundle + resilient build → Task 5 ✓
- write_break per beat + fallback → Task 6 ✓
- Beat rotation + render integration + script labels → Task 7 ✓
- Config city/topics → Task 1 ✓
- No new deps (requests + xml.etree) ✓; golden-ear → Task 7 Step 6 ✓

**Placeholder scan:** No TBD/TODO; every code step has full code; every run step has command + expected count. ✓

**Type consistency:** `now_parts(dt)->(str,str)` (T2) used by `DJContext.build` (T5). `WeatherService(get_json=).for_city(city)->str` (T3) used by build + write_break ctx.weather. `NewsService(get_text=).headlines(query,limit)`/`top_for_topics(topics)` (T4) used by build. `DJContext(time_str, part_of_day, weather, general_headline, topic_headlines)` (T5) consumed by `write_break`/beat prompts (T6) and render (T7). `write_break(prev, nxt, beat, ctx, seconds, topic=None)` (T6) called identically in render (T7). `beat_for_break`/`BEATS` (T7) match their test. `Config` city/topics (T1) read in build (T5) and render (T7). `_finish` shared by write_intro/write_break (T6) — write_intro behavior unchanged so M1/M2 djbrain tests still pass. ✓
