# Radio AI — M3: Full DJ Brain (time, weather, news, topics) — Design

**Date:** 2026-06-04
**Status:** Approved design (pre-implementation)
**Builds on:** M1 (core mix + witty Hebrew DJ) and M2 (Spotify-driven setlists), both complete.

## 1. Goal

Give the DJ real-world things to talk about beyond song intros: time-of-day/drive
chatter, current weather, general news headlines, and fresh news/banter on the
listener's favorite topics. Each DJ break stays short and witty (per the brevity
rules tuned in M2), and varies by rotating a "beat" so it never feels repetitive.

## 2. Scope

- **In:** Clock/time-of-day, weather (Open-Meteo), news (Google News RSS, general
  + per favorite-topic), a `DJContext` bundle, beat rotation across DJ breaks, and
  a `DJBrain.write_break` that composes a short witty Hebrew line per beat.
- **Out (later):** generate-ahead engine + web player (M4), deep mixing (M5),
  learned taste, multi-day news memory.
- Output remains a single rendered `show.mp3` (+ `show_script.txt`).

## 3. Key decisions

| Decision | Choice |
|----------|--------|
| Content types | time-of-day, weather, general news, favorite-topic news (all four) |
| Weather source | **Open-Meteo** — free, **no API key** (geocoding + current forecast) |
| News source | **Google News RSS** — free, **no API key**, Hebrew (`hl=he&gl=IL&ceid=IL:he`) |
| City / topics | `CITY=Tel Aviv`, `TOPICS=technology,physics,robotics,AI` in `.env` |
| Talk variety | One **beat** per DJ break, rotating: song-intro → weather/time → favorite-topic → general-news |
| Brevity | Reuse M2 rules: short (~4s/≤10-word) witty lines, deep duck, DJ finishes before next song |
| Dependencies | `requests` (already present via spotipy) + stdlib `xml.etree` — **no new deps** |
| HTTP isolation | Thin fetch methods + **pure parser functions** (unit-tested with mock payloads) |
| Resilience | Any failed fetch → that beat falls back to a song-intro; the show never crashes |

## 4. Architecture

New components feed the existing render pipeline; the DJ break now picks a beat and
composes from `DJContext`.

```
DJContext.build(cfg)  ──┬─ Clock        → time string + part-of-day
                        ├─ WeatherService → temp + Hebrew condition (Open-Meteo)
                        └─ NewsService    → general headline + {topic: headline} (Google News RSS)
render_show: for each DJ break → beat = rotation[k]
   → DJBrain.write_break(prev, nxt, beat, ctx, seconds) → VoiceRenderer → duck (M2 talkover)
```

| File | Responsibility |
|------|----------------|
| `radioai/clock.py` | `now_parts(dt) -> (time_str, part_of_day)` (pure; dt injected) |
| `radioai/weather.py` | `WeatherService` + pure `parse_weather`, `weather_code_to_hebrew` |
| `radioai/news.py` | `NewsService` + pure `parse_news_rss`, `google_news_url` |
| `radioai/djcontext.py` | `DJContext` dataclass + `build(cfg, ...)` (deps injectable) |
| `radioai/djbrain.py` | ADD `write_break(prev, nxt, beat, ctx, seconds)` (keep `write_intro`) |
| `radioai/render_show.py` | beat rotation + call `write_break`; build `DJContext` once |
| `radioai/config.py` | ADD `city`, `topics: list[str]` |

## 5. Data shapes & sources

- **Clock:** `now_parts(dt)` → `("21:40", "ערב")`. Part-of-day buckets:
  בוקר (5–11), צהריים (12–16), ערב (17–21), לילה (22–4). dt passed in (testable).
- **WeatherService** (Open-Meteo, no key):
  - geocode: `https://geocoding-api.open-meteo.com/v1/search?name={city}&count=1` → lat/lon
  - forecast: `https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&current=temperature_2m,weather_code`
  - `parse_weather(json) -> WeatherNow(temp_c: float, code: int)`;
    `weather_code_to_hebrew(code) -> str` (e.g. 0→"שמיים בהירים", 61→"גשם").
- **NewsService** (Google News RSS, no key):
  - `google_news_url(query=None) -> str` (general top stories if `query is None`,
    else `/rss/search?q={query}`), always `hl=he&gl=IL&ceid=IL:he`.
  - `parse_news_rss(xml_text) -> list[str]` (item titles, in order).
  - `NewsService.headlines(query=None, limit=1)` fetches + parses; `top_for_topics(topics)`
    returns `{topic: first_headline}` (skips topics that error/return nothing).
- **DJContext** (dataclass): `time_str`, `part_of_day`, `weather: str | None`,
  `general_headline: str | None`, `topic_headlines: dict[str, str]`.
  `DJContext.build(cfg, *, clock_dt=None, weather=None, news=None)` — deps injectable
  for tests; on any sub-fetch error it leaves that field `None`/empty (logged).

## 6. Beats (variety + brevity)

`BEATS = ["song", "weather", "topic", "news"]`, cycled across the DJ breaks
(`beat = BEATS[k % len(BEATS)]`, k = break index). `render_show` assigns a beat per
break; `DJBrain.write_break` dispatches:
- `song` → existing `write_intro` (witty song handoff).
- `weather` → short line using `time_str`/`part_of_day` + `weather`, then teases next song.
- `topic` → pick the next topic in rotation; short witty line referencing its headline, then next song.
- `news` → short line referencing `general_headline`, then next song.

If the needed context for a beat is missing (e.g. `weather is None`), `write_break`
falls back to the `song` beat so a break is never empty. All lines obey the M2
brevity/wit constraints (one short Hebrew sentence, ≤ budget words, complete sentence).

## 7. render_show integration

- Build `DJContext` once after config load (before the song loop).
- Keep DJ placement (every ~2 songs) and the M2 talkover audio (deep duck, DJ
  finishes before segue, trim silence, script saving).
- Track a break index; assign `beat = BEATS[k % 4]`; call `write_break`.
- `show_script.txt` records each line with its beat label.

## 8. Testing & success

- **TDD (unit):** `now_parts` buckets; `parse_weather` + `weather_code_to_hebrew`;
  `parse_news_rss` (mock RSS XML); `google_news_url` (general vs topic, Hebrew params);
  beat rotation; `write_break` prompt per beat (mock LLM — assert weather/headline/topic
  text is in the prompt) + fallback-to-song when context missing; `DJContext.build`
  with injected fakes (and graceful None on failure).
- **Integration (golden-ear):** real render — confirm the DJ mentions a real Tel Aviv
  weather line, a real tech/AI/physics/robotics headline, time-of-day, and song intros,
  all short and witty.
- **Success:** a show where DJ breaks vary across song-intro / weather / topic / news,
  each short, witty, accurate, and clearly audible.

## 9. Risks

- **Google News RSS** is unofficial; format can change → `parse_news_rss` tolerant,
  failures fall back to song beat. Headlines are Hebrew (matches the DJ).
- **Open-Meteo** geocoding ambiguity → take first result for the city; failure → skip weather.
- **Freshness/time:** render-time `now` is used (fine for a pre-rendered show).
- **Topic headline quality:** Google News may return marketing/odd titles → the DJ
  paraphrases briefly rather than reading verbatim (prompt instructs a natural mention).
