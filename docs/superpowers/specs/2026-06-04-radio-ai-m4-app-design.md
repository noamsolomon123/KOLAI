# Radio AI — M4: Spotify-style Liquid-Glass App — Design

**Date:** 2026-06-05
**Status:** Autonomous design (user asleep; decisions made per the standing goal —
"build the APK app in the style of Spotify with liquid glass design"). Documented
assumptions noted; to be reviewed on wake.

## 1. Goal

A beautiful mobile app — **Spotify-style layout, "liquid glass" (glassmorphism)
aesthetic** — that plays the user's personal Radio AI station: the rendered show
audio with live now-playing (current song / DJ talk / mashup), the setlist, and
playback controls. Packaged toward an Android **APK** for the OnePlus 15.

## 2. Architecture (thin client + backend)

The phone can't run the Python DSP/ML stack, so the app is a **thin client** to a
**backend that serves the already-rendered show + metadata**. Same model as the
whole project: heavy work server-side, phone just streams + displays.

```
[Python backend / FastAPI]                     [Mobile app — React/Vite, liquid glass]
  GET /api/show   -> show.json (segments)  -->   Now-Playing UI synced to audio time
  GET /api/audio  -> stream show.mp3 (range) -->  <audio> playback + seek
  POST /api/generate -> (re)render in background
  serves built frontend (static)
```

## 3. Decisions

| Area | Choice |
|------|--------|
| Backend | **FastAPI** (`radioai/server.py`) — serves audio (HTTP range), `show.json` metadata, optional regenerate, and the built frontend static files |
| Show metadata | `render_show` also writes `cache/show.json`: ordered **segments** with `start_s`/`end_s`, type (`song`/`talkover`/`mashup`), title/artist, and DJ text — so the app shows live now-playing |
| Frontend | **Vite + React + TypeScript**, liquid-glass Spotify aesthetic; plays `/api/audio`, syncs now-playing to `/api/show` segments by `audio.currentTime` |
| Artwork | No album art available → per-track **vibrant generated gradient** (hash title→hue) as the "cover" |
| Hebrew | RTL-aware text; Hebrew titles/DJ lines render correctly |
| Packaging | **Capacitor** Android wrapper → APK; also a valid **PWA** (installable on the phone) as the guaranteed-working fallback if the Android SDK can't be set up headlessly |
| Hosting (dev) | Backend runs on the PC; phone connects over LAN (same Wi-Fi) or the app points at a configurable base URL |

## 4. Components

| Unit | Responsibility |
|------|----------------|
| `radioai/showmeta.py` | Pure `build_segments(events) -> list[dict]` + `write_show_json(path, meta)`; consumed by render to emit `cache/show.json` |
| `render_show.py` | Track each segment's start time while assembling the timeline; emit `show.json` |
| `radioai/server.py` | FastAPI app: `/api/show`, `/api/audio` (range), `/api/generate`, static frontend mount |
| `frontend/` | Vite React app: `NowPlaying`, `Setlist`, `MiniPlayer`, `GlassCard`, gradient cover, playback hook synced to segments |
| `frontend` design system | Liquid-glass tokens: deep animated gradient bg, translucent blurred cards (`backdrop-filter`), specular borders, springy motion, Spotify-like layout |

## 5. show.json shape
```json
{
  "station": "רדיו AI",
  "dj": "רדיו AI",
  "duration_s": 1346.5,
  "generated_at": "<iso>",
  "segments": [
    {"type": "song", "title": "...", "artist": "...", "start_s": 0.0, "end_s": 210.3},
    {"type": "talkover", "beat": "weather", "text": "...", "start_s": 210.3, "end_s": 214.1},
    {"type": "song", "title": "...", "artist": "...", "start_s": 214.1, "end_s": 405.0}
  ]
}
```
The app finds the active segment via `start_s <= currentTime < end_s`.

## 6. UI (liquid glass, Spotify-style)

- **Background:** full-screen animated gradient mesh that shifts with the current
  track's color; heavy blur layers for depth.
- **Now-Playing (main):** large rounded gradient "cover", track title + artist
  (Hebrew RTL), a glass **DJ chip** that lights up during talkover/mashup
  ("🎙️ רדיו AI מדבר" + the line), play/pause, scrubber with segment ticks, prev/next
  (seek to segment).
- **Setlist sheet:** glass cards listing upcoming songs; tap to seek.
- **Mini-player:** persistent bottom glass bar (cover thumb, title, play/pause, progress).
- **Motion:** springy press states, glass cards with subtle parallax/specular sheen.

## 7. Testing

- **Backend (pytest):** `build_segments` pure (ordered, non-overlapping, correct
  start/end), `write_show_json` round-trips; `/api/show` returns the json; `/api/audio`
  honors a Range request (TestClient).
- **Frontend:** a smoke test (component renders; segment-finder picks the right
  segment for a given time) via vitest; manual visual check in the browser.
- **Integration:** run backend, load the app, play, watch now-playing update; verify
  on a phone browser over LAN. APK build verified if the Android toolchain is present.

## 8. Risks / assumptions

- **APK toolchain:** building a signed APK needs the Android SDK/JDK/Gradle, which may
  not be installed headlessly. Plan: deliver a polished, installable **PWA** + a ready
  **Capacitor** project; attempt the APK build best-effort and document the one command
  to finish it.
- **Now-playing accuracy** depends on `show.json` timings matching the rendered audio
  (crossfades make boundaries approximate — acceptable).
- **LAN access:** the phone reaches the PC backend over Wi-Fi; base URL configurable.
- This is a single rendered show (generate-ahead streaming engine is future work);
  `POST /api/generate` re-renders on demand.
