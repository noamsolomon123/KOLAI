# Radio AI — Endless Engine — Design

**Date:** 2026-06-05
**Status:** Approved by user (rolling block queue + continuous Spotify taste learning).

## 1. Goal

Turn Radio AI from a single finite ~20-minute `show.mp3` into a **never-ending
personalized station** that plays until the user stops it, and that **keeps
re-learning the user's taste from Spotify** as it runs. Delivery model: a
**rolling block queue** — the backend renders blocks ahead of playback and the
app plays them back-to-back so it feels continuous, while keeping the existing
now-playing / cover / up-next / scrub UI.

## 2. Why this shape

- The phone stays a thin client; heavy DSP/LLM/TTS stays on the PC backend.
- A block is a normal MP3 + JSON — robust, resumable, reuses the entire existing
  fetch → analyze → mix → Gemini-TTS pipeline. No new streaming infra.
- Rendering a ~10-minute block takes ~1–2 minutes, far less than its playtime, so
  buffering one block ahead keeps playback gapless after the first block.

## 3. Architecture

```
[ StationEngine (background thread) ]
   RollingPlanner.next_songs(n, seed)  ──► picks next songs (taste refreshed, no repeats)
   BlockRenderer.render(songs, prev)   ──► cache/blocks/block_{n}.mp3 + block_{n}.json
   keeps blocks [current .. current+BUFFER] rendered ahead
            │
[ FastAPI ]  POST /api/station/start
             GET  /api/station/block/{n}        (audio, HTTP Range)
             GET  /api/station/block/{n}/meta   (segments + talk + index, or 202 while rendering)
            │
[ App ]  useStation: play block n, prefetch n+1, swap on ended → endless; "tuning in…" on block 0
```

## 4. Components

| Unit | Responsibility |
|------|----------------|
| `radioai/planner_rolling.py` (`RollingPlanner`) | Endless song selection. Holds play history; excludes recently played (last ~50). Refreshes Spotify taste every `REFRESH_EVERY` songs or after `REFRESH_TTL_S`, then asks Gemini (via existing `SetlistPlanner`) for the next songs that flow from a continuity seed. |
| `radioai/taste.py` (extend) | `get_profile(force_refresh=False)` — bypass the `cache/taste.json` cache when forced, so the station re-reads Spotify over time. |
| `radioai/setlist.py` (extend) | `SetlistPlanner.plan(profile, n, exclude=None, seed=None)` — exclude recent titles and bias continuity from a seed track. |
| `radioai/block_renderer.py` (`BlockRenderer`) | Pure-ish render of one block: given `songs` + optional `prev_track` (last track of the previous block, for the opening transition), produce `(audio: np.ndarray, meta: dict)`. Extracted from `render_show.main`'s per-transition loop; reuses `AudioFetcher`, `analyze`, `choose_transition`, `DJBrain`, `VoiceRenderer`, `mixrenderer`, `DJContext`, `showmeta`. Mashups stay disabled. |
| `radioai/station.py` (`StationEngine`) | Owns the queue + background worker thread. `start()`, `ensure_ahead()`, `get_block_path(n)` (renders/waits if needed), `get_block_meta(n)`. Passes each block's last track as the next block's `prev_track` for continuity. |
| `radioai/server.py` (extend) | Station endpoints (start / block audio / block meta); a process-wide singleton `StationEngine` started lazily on first `start`. |
| `frontend` `useStation` hook + UI | Start station, fetch block 0 meta+audio (poll while 202 → "tuning in…"), play; prefetch block n+1; on `ended` advance with a second `<audio>` element so there is no stop. Now-playing/up-next/scrub operate on the current block's meta. |

## 5. Block + meta shape

`cache/blocks/block_{n}.json`:
```json
{
  "index": 3,
  "duration_s": 612.4,
  "segments": [ {"type":"song","title":"...","artist":"...","start_s":0.0,"end_s":210.3}, ... ],
  "talk": [ {"beat":"weather","text":"...","start_s":205.0,"end_s":210.0}, ... ],
  "next_index": 4
}
```
Block 0 begins with no `prev_track` (cold start). Block n>0 opens with a real
transition from block n−1's last track.

## 6. Continuous Spotify learning

- `RollingPlanner` tracks `songs_since_refresh` and `last_refresh_monotonic`.
- Before planning the next batch, if `songs_since_refresh >= REFRESH_EVERY (5)`
  **or** elapsed `> REFRESH_TTL_S (600)`, it calls `TasteService.get_profile(force_refresh=True)`
  and resets the counters. Otherwise it reuses the in-memory profile.
- Effect: songs the user plays/likes on Spotify during the session progressively
  shift the station. Cadence is capped to respect Spotify rate limits.

## 7. No-repeat policy

- Planner keeps `history` (ordered titles/ids). New picks exclude the last
  `NO_REPEAT_WINDOW (50)` titles. If Spotify/Gemini can't supply enough fresh
  songs, it relaxes the window rather than stalling (logs the relaxation).

## 8. Buffering + lifecycle

- `BUFFER_AHEAD = 1` fully-rendered block beyond the current index.
- First listen: app calls `start`, polls block 0 meta until ready ("tuning in…"),
  then plays. Background worker renders block 1 during block 0.
- Stop = app stops playback; the engine can idle (stop rendering further ahead
  once `current + BUFFER` is satisfied; it advances when the app reports/fetches
  the next index).
- Single global station for the POC (no per-user sessions).

## 9. Testing

- `RollingPlanner`: refresh cadence triggers force-refresh at the boundary;
  history-exclusion removes recent titles; relaxes window when starved (fakes for
  TasteService + SetlistPlanner).
- `setlist.plan`: passes `exclude`/`seed` into the prompt; `parse_setlist` still
  works.
- `BlockRenderer`: with fakes (fetch returns a short sine wav, stub DJ/voice),
  renders N songs into audio + non-overlapping ordered segments; opening
  transition present when `prev_track` given.
- `StationEngine`: with a fake BlockRenderer, `get_block_path(n)` renders missing
  blocks, keeps `BUFFER_AHEAD`, threads continuity (prev_track) correctly, and is
  thread-safe (no double render of the same index).
- Server: TestClient — `start` returns ok; `block/{n}/meta` returns 202 then 200;
  `block/{n}` honors Range (206) once ready (inject a fake engine).
- Frontend: vitest — `useStation` advances index on `ended`; "tuning in" while
  block 0 not ready; segment-finder picks the right segment within a block.

## 10. Risks / assumptions

- **Free-quota burn:** endless play = continuous Gemini LLM + TTS. 3-key rotation
  helps; a long session may still exhaust the free tier. Acceptable for POC; note
  to user. Mitigation later: fewer DJ breaks per block, cache TTS.
- **Disk growth:** new songs accumulate in `cache/`. Repeats are avoided so the
  library grows. Add cache pruning later (out of scope now).
- **First-block latency:** cold start downloads + TTS can take a minute; covered
  by "tuning in…". Subsequent blocks are pre-rendered.
- **Block seam:** a tiny gap can occur swapping audio elements; usually masked by
  the DJ break at block start. Web Audio gapless is a later polish.
- **Single global station:** fine for one user; multi-listener would need sessions
  (out of scope).
```
