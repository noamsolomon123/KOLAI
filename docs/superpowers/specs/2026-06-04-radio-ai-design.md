# Radio AI — POC Design

**Date:** 2026-06-04
**Status:** Approved design (pre-implementation)
**Author:** brainstorming session

## 1. Vision

Radio AI is a personal, always-on radio station with a real-feeling AI DJ. The
"wow" is the whole experience working together: an expressive, human-sounding DJ;
music that fits the listener perfectly; seamless mixing and flow between songs;
and talk that is genuinely personalized. No single piece alone is enough — they
combine into something that feels like a real radio station made just for you.

**Primary context of use:** in the car (drive-time radio).

**Mixing philosophy:** classic radio is the *base* — the DJ does not talk after
every song. Whenever the DJ is silent and the tracks are compatible, the engine
blends DJ-style (beat-matched crossfades) and mashup-style transitions. The more
compatible the tracks, the deeper the blend. The more seamless mixing, the better.

## 2. Scope

This document covers a **proof of concept (POC)**.

- **Platform:** web app now; Android **APK** is the eventual target. The backend
  pipeline is designed to transfer to the APK unchanged — only the frontend swaps.
- **Music source:** audio is fetched via **`yt-dlp`** for the POC. Real music
  licensing is explicitly a *later* concern (the owner will pay/license for
  production); it is **not** a POC blocker.
- **DJ language:** **Hebrew.**
- **Music scope:** **mixed** — both Hebrew/Israeli and international tracks
  (realistic Israeli radio).

## 3. Key technical decisions

| Decision | Choice | Why |
|----------|--------|-----|
| Engine model | **Generate-ahead** (rolling chunks, ~2–3 segments ahead) | Feels live, no real-time complexity, backend reusable for APK |
| Audio source | `yt-dlp` (+ ffmpeg) | Gives raw waveform → real beat-matching & mashups (DRM streaming cannot) |
| Taste source | **Spotify** (top/saved artists & tracks, playlists, recently-played) | Spotify is the taste brain; yt-dlp is the audio source |
| Audio analysis | `librosa` (local) | Spotify deprecated `audio-features`/`recommendations`/previews for new apps (Nov 2024); we must compute BPM/key locally — needed for beat-matching anyway |
| Setlist expansion | **LLM** (not Spotify recommendations) | Spotify recommendations endpoint restricted for new apps |
| DJ voice | **ElevenLabs** (Hebrew, multilingual) | Most expressive → best for a lively, real-feeling radio personality; supports custom/cloned voices |
| DJ brain / scripts | LLM (Claude — strong Hebrew) | Natural Hebrew patter, persona consistency, length budgeting |
| Streaming | **HLS** (segments + `.m3u8`), `hls.js` player | Standard, gapless, APK-friendly |

## 4. Architecture

A **Python backend** does all heavy work; a **thin web player** is the frontend.
Each backend unit has one job and is independently testable and swappable.

| # | Component | Job | In → Out |
|---|-----------|-----|----------|
| 1 | **TasteService** | Read Spotify taste | auth → taste profile (top/saved artists & tracks, playlists, recently-played) |
| 2 | **SetlistPlanner** (LLM) | Taste + session context → ordered setlist with an energy arc | taste + context → list of {title, artist} |
| 3 | **AudioFetcher** (yt-dlp) | Get & normalize the actual audio, cached | {title, artist} → audio file |
| 4 | **AudioAnalyzer** (librosa) | BPM, key, beatgrid, energy envelope, intro/outro regions, vocal-onset | audio file → analysis (cached) |
| 5 | **MixPlanner** | Decide each transition + DJ-slot placement | analyzed tracks + talk slots → timeline |
| 6 | **NewsProvider** | Fresh headlines: general + user topics (deduped) | topics → snippets |
| 7 | **DJBrain** (LLM) | Write Hebrew DJ scripts (intros/outros, banter, time/weather, news) | context → Hebrew scripts |
| 8 | **VoiceRenderer** (ElevenLabs) | Voice the scripts in Hebrew with matching energy | script → voice audio |
| 9 | **MixRenderer** | Render audio: crossfades, beat-match, mashup, duck music under DJ | timeline + audio + voice → mixed segment |
| 10 | **StreamServer** (FastAPI/HLS) | Keep a buffer of segments ahead, serve the stream | segments → HTTP stream |
| 11 | **WebPlayer** | Connect Spotify, set topics/city/persona, play, now-playing | — |

## 5. The generate-ahead engine (Producer loop)

A Producer loop always keeps the stream buffer ~2–3 segments ahead of playback,
so slow work (download, analysis, TTS) happens off the critical path:

```
loop while listening:
  if buffer has < N segments ahead:
     1. SetlistPlanner picks the next 1–2 songs (taste + already-played + energy arc)
     2. AudioFetcher + AudioAnalyzer        (cache hit if seen before)
     3. MixPlanner decides the transition into them + whether a DJ slot goes here
     4. if DJ slot: NewsProvider → DJBrain writes Hebrew script → VoiceRenderer voices it
     5. MixRenderer renders the segment (music + transition + ducked DJ talk)
     6. push segment → StreamServer buffer
  WebPlayer consumes segments gaplessly (HLS)
```

This loop is the part that carries over unchanged to the Android APK.

## 6. Mixing engine

**Analysis (per track, cached):** tempo/BPM, beat times (beatgrid), musical key,
energy envelope, intro/outro instrumental regions, and **vocal-onset point**.

**Transition decision (MixPlanner):** score compatibility on tempo ratio, key
(**Camelot wheel** — adjacent/relative keys blend well), and energy continuity,
then choose:

- **Talk-over** (radio base): DJ over an instrumental intro/outro, music ducked
  ~**-7 dB**. Signature move: *"talk up to the vocal"* — the DJ's last word lands
  right as singing begins (using the detected vocal-onset point).
- **Beat-matched crossfade:** align beatgrids, nudge tempo to match, EQ bass-swap,
  blend over 8–16 bars. Used when the DJ is silent and tracks are compatible.
- **Mashup:** overlay one track's vocal region over another's instrumental when
  key + tempo align. Opportunistic. POC ships a simpler "extended blend /
  double-drop" first; **full mashup is a stretch goal.**
- **Clean / quick cut** when nothing blends.

**Rule encoding the vision:** classic radio pacing is the base (DJ every few
songs); whenever the DJ is silent and compatibility is high → beat-match/mashup;
the more compatible, the deeper the blend.

## 7. DJ brain & talk

**DJBrain (LLM):** assembles context — prev/next song + artist facts, the user's
taste highlights, time of day, weather (by city), the user's favorite topics, and
fresh news. Writes a consistent **DJ persona** (name, style, energy) in natural
Hebrew, **sized to the available instrumental gap** (target seconds → word budget).

**Talk content (all enabled):**
- Song & artist intros/outros (back-announce, hype next, fun facts)
- Personalized banter (references the user's taste, time of day, drive vibes)
- Time / weather / "drive" chatter
- News / headlines — general **and** per the user's **favorite topics**
  (NewsProvider pulls and dedupes so items never repeat)

**VoiceRenderer (ElevenLabs):** Hebrew, energy matched to the moment. **Code-
switching** (English song titles inside Hebrew speech) is specifically tested.

## 8. Tech stack & project structure

**Backend:** Python 3.11+, FastAPI. `spotipy` (taste), `yt-dlp` + `ffmpeg`
(audio), `librosa`/`numpy`/`soundfile` (analysis), `pedalboard`/`pyrubberband`
(crossfade, EQ, time-stretch), `elevenlabs` (TTS), Anthropic SDK (LLM), a
search/news API (e.g. Tavily or NewsAPI) + a weather API. **Streaming:** HLS
segments + `.m3u8`, `hls.js`. **State:** filesystem cache (audio + analysis) +
SQLite (history/metadata). Keys in `.env`.

**Frontend:** minimal Vite app — screens: *connect Spotify*, *settings* (topics,
city, DJ persona), *player* (play + now-playing). Swaps for the APK; backend
untouched.

```
radioai/
  backend/  app/(FastAPI)  services/(taste, setlist, fetcher, analyzer, mixplanner,
            news, djbrain, voice, mixrenderer, stream)  engine/(producer loop)
            persona/  cache/  tests/
  frontend/ (Vite + hls.js)
  docs/
```

## 9. Testing & success criteria

- **TDD on deterministic parts:** Camelot key logic, compatibility scoring,
  talk-length budgeting, vocal-onset timing math, HLS segmenting.
- **Listening (golden-ear) gate** for creative parts — render a fixed sample
  setlist and *listen*; this is the real quality bar, not an assert.
- **Integration:** end-to-end produce several segments → verify a continuous,
  gapless, playable HLS stream.

**"Wow" success criteria:**
1. Transitions sound intentional, not jarring.
2. Hebrew DJ sounds natural, personalized, and on-topic.
3. Talk-up-to-vocal timing lands.
4. News is fresh and relevant (general + user topics).
5. Plays continuously without stutter.

## 10. Known risks / unknowns

- **Spotify dev mode:** the owner must add themselves to the app's user allowlist;
  OAuth redirect must be configured.
- **yt-dlp track matching:** risk of grabbing a live/wrong version — match on
  title + artist **and** duration-close-to-Spotify, prefer "official audio."
- **Beat-match artifacts:** extreme tempo-stretch sounds bad — POC may restrict
  deep blends to tempo-compatible tracks.
- **Hebrew TTS edge cases:** numbers and English code-switching — test explicitly.
- **Legality:** `yt-dlp` downloads are POC-only; real licensing comes later
  (owner will pay/license for production).

## 11. Out of scope (POC)

- True real-time (on-the-fly) engine — generate-ahead is sufficient.
- Learned taste profile that adapts from likes/skips over time.
- The Android APK itself (backend is designed for it; app build is later).
- Production music licensing.
