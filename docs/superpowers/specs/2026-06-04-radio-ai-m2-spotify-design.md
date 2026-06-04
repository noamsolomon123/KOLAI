# Radio AI — M2: Spotify-Driven Setlists — Design

**Date:** 2026-06-04
**Status:** Approved design (pre-implementation)
**Builds on:** M1 (core-mix vertical slice, complete)

## 1. Goal

Replace M1's hardcoded demo setlist with songs derived from the listener's real
**Spotify taste**. Pull their top tracks + top artists, let Gemini curate a
flowing radio setlist grounded in that taste, and feed it into the existing M1
render pipeline (fetch → analyze → mix → witty Hebrew DJ → voice → show.mp3).

## 2. Scope

- **In:** Spotify OAuth + taste fetch, LLM setlist curation, version matching via
  Spotify track durations, integration into `render_show`, offline fallback.
- **Out (later milestones):** generate-ahead engine + HLS + web player (M4),
  deep mixing/mashups (M5), learned taste over time, news/weather/topics (M3).
- Output is still a single rendered `show.mp3` (+ `show_script.txt`), as in M1.

## 3. Key decisions

| Decision | Choice |
|----------|--------|
| Taste source | Spotify Web API: **medium-term top tracks (~20) + top artists (~10)** |
| OAuth scope | `user-top-read` (read-only) |
| Setlist strategy | **LLM-curated** (Gemini), grounded in the real taste profile; may include related/deeper cuts |
| Show size | **~6 songs**, witty DJ talkover before song 1 and every ~2 songs |
| Version matching | Attach Spotify **exact duration** to known top tracks → duration-aware fetcher locks the correct original (no manual pinning) |
| Credentials | Spotify app **"KOLAI"** (dev mode). `SPOTIFY_CLIENT_ID/SECRET/REDIRECT_URI` in gitignored `.env` |
| Redirect URI | `http://127.0.0.1:5173` (must match the app registration exactly) |
| Caching | Taste profile cached to `cache/taste.json`; OAuth token cached by spotipy |
| Fallback | No Spotify creds → fall back to the M1 hardcoded demo setlist with a warning |

Spotify caveat: we use only `top tracks`/`top artists` (still supported). We avoid
the endpoints Spotify deprecated for new apps (recommendations, audio-features).

## 4. Architecture

Two new components feed the existing M1 pipeline; nothing else changes.

```
Spotify OAuth → TasteService → TasteProfile → SetlistPlanner(Gemini) → [Song]
   → (M1) AudioFetcher → AudioAnalyzer → MixPlanner → DJBrain → VoiceRenderer
   → MixRenderer → show.mp3 + show_script.txt
```

| Component | Job | In → Out |
|-----------|-----|----------|
| **TasteService** (`spotipy`) | OAuth + fetch taste, cache to disk | creds → `TasteProfile` |
| **SetlistPlanner** (`GeminiClient`) | Curate a flowing setlist grounded in taste | `TasteProfile`, n → `list[Song]` |

**Data shapes:**
- `TasteProfile`: `top_tracks: list[TasteTrack]`, `top_artists: list[str]`
- `TasteTrack`: `title: str`, `artist: str`, `duration_s: float`
- Output `Song` (existing dataclass): `title`, `artist`, `duration_s` (attached for
  tracks that match a taste track; else `None`), `query` (unused here).

## 5. TasteService (Spotify)

- Uses `spotipy.Spotify` with `SpotifyOAuth(client_id, client_secret,
  redirect_uri, scope="user-top-read", cache_path=cache/.spotify-token)`.
- First run opens the browser once for consent; spotipy captures the redirect on
  the loopback port (`5173`) and caches the token; later runs are silent.
- `get_profile()`:
  - `current_user_top_tracks(limit=20, time_range="medium_term")` →
    `TasteTrack(title=track name, artist=first artist, duration_s=duration_ms/1000)`
  - `current_user_top_artists(limit=10, time_range="medium_term")` → artist names
  - Caches the resulting `TasteProfile` to `cache/taste.json`; `get_profile(use_cache=True)`
    reads the file if present (so iterating on the planner/render doesn't re-hit Spotify).
- The pure parsing (Spotify JSON → `TasteProfile`) is split into a testable
  function `parse_profile(top_tracks_json, top_artists_json)` so tests mock the
  raw API payloads, not the network.

## 6. SetlistPlanner (Gemini)

- Builds a prompt embedding the taste profile (track titles+artists, artist list)
  and asks for an **ordered ~6-song setlist** with an energy arc, returned as
  **strict JSON**: `[{"title": "...", "artist": "..."}, ...]`.
- Grounding: instruct the model to prefer the listener's own tracks and closely
  related songs by the same/adjacent artists, and to return only real songs.
- Parsing (`parse_setlist(json_text, taste) -> list[Song]`), pure & tested:
  - Robust JSON extraction (tolerate code fences / extra prose).
  - **Duration attach:** if a returned (title, artist) case-insensitively matches a
    `TasteTrack`, set `Song.duration_s` from Spotify for accurate fetching.
  - **Dedupe** by (title, artist); cap to requested `n`.
  - If parsing fails entirely, raise a clear error (render falls back to demo set).

## 7. render_show integration

- New helper `build_setlist(cfg) -> list[Song]`:
  - If Spotify creds present: `TasteService(cfg).get_profile()` →
    `SetlistPlanner(GeminiClient(...)).plan(profile, n=6)`.
  - Else: log a warning and return the M1 hardcoded demo `SETLIST`.
- DJ talkover placement generalized: talkover before the **1st** song and on
  every ~2nd transition thereafter (witty Hebrew DJ from M1, unchanged).
- A song that fails to fetch/analyze is **skipped** (logged), not fatal.
- Everything else (Algieba voice + style, segue, silence-trim, script saving,
  3-key rotation) is reused unchanged.

## 8. Config additions

`.env` (gitignored): `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`,
`SPOTIFY_REDIRECT_URI` (default `http://127.0.0.1:5173`). `Config.from_env()` gains
these fields; `spotipy` added to dependencies.

## 9. Testing & success

- **TDD (unit):**
  - `parse_profile`: Spotify JSON → `TasteProfile` (title/artist/duration, first-artist handling, empty lists).
  - `parse_setlist`: JSON (with/without code fences) → `Song[]`; duration attach from taste; dedupe; cap to n; malformed input raises.
  - `build_setlist` fallback: no creds → demo setlist (mocked).
- **Integration (golden-ear):** real OAuth once, render a show from real taste;
  verify the setlist reflects the listener's taste, versions are correct, DJ stays witty.
- **Success:** a ~6-song show built from the user's actual Spotify taste, correct
  song versions, witty Hebrew DJ every ~2 songs, plays end-to-end.

## 10. Risks

- **Redirect URI mismatch** → must be exactly `http://127.0.0.1:5173` (registered in KOLAI). 
- **Dev-mode allow-list:** only the app owner's account works until extended (fine — it's the owner's).
- **LLM hallucination:** mitigated by grounding + duration matching + skip-on-fail.
- **OAuth needs a browser once** (loopback capture on port 5173); token cached after.
- **Headless/secret hygiene:** secret was shown in a screenshot; optional rotate later.
