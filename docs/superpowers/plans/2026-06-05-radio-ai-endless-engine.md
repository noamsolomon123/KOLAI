# Endless Engine Implementation Plan

> **For agentic workers:** Execute task-by-task (subagent-driven). Backend files
> under `backend/radioai` and `backend/tests` must be edited via python/PowerShell
> patch scripts (the Edit/Write tools are hook-blocked there). After every commit,
> `git ls-files` to confirm tracking (commits in this repo intermittently fail to
> persist). Venv python: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe`. Tests:
> `...python.exe -m pytest -q`. Mashups stay DISABLED.

**Goal:** Make Radio AI a never-ending station (rolling block queue) that keeps
re-learning the user's Spotify taste.

**Architecture:** A background `StationEngine` keeps blocks rendered ahead of
playback; a `RollingPlanner` chooses endless, non-repeating, taste-refreshed
songs; FastAPI serves blocks sequentially; the app plays them back-to-back.

**Tech stack:** Python (FastAPI, numpy, existing render pipeline), React/Vite.

---

## Task 1: Taste force-refresh + setlist exclude/seed

**Files:** Modify `backend/radioai/taste.py`, `backend/radioai/setlist.py`; Test `backend/tests/test_taste.py`, `backend/tests/test_setlist.py`.

- Add `TasteService.get_profile(force_refresh: bool = False)`: when `force_refresh`, skip reading `cache/taste.json` and re-fetch from Spotify, then overwrite the cache. Default path unchanged.
- Extend `SetlistPlanner.plan(profile, n, exclude=None, seed=None)`: `exclude` (list of recently-played "Title — Artist" strings) and `seed` (a continuity track) are injected into the Gemini prompt ("avoid these; flow naturally from <seed>"). `parse_setlist` unchanged. Keep backward compatibility (both optional).
- Tests: forced refresh bypasses cache (fake spotify client called even when cache file exists); `plan` includes exclude/seed text in the prompt it builds (inspect via a fake client capturing the prompt) and still returns parsed songs.

## Task 2: RollingPlanner

**Files:** Create `backend/radioai/planner_rolling.py`; Test `backend/tests/test_planner_rolling.py`.

- `RollingPlanner(taste_service, setlist_planner, refresh_every=5, refresh_ttl_s=600, no_repeat_window=50, clock=<monotonic fn>)`.
- State: `history: list[str]`, `_profile`, `_songs_since_refresh`, `_last_refresh`.
- `next_songs(n, seed=None) -> list[Song]`: refresh profile (force) when `_songs_since_refresh >= refresh_every` or `clock() - _last_refresh > refresh_ttl_s` (and on first call); call `setlist_planner.plan(profile, n, exclude=last no_repeat_window of history, seed=seed)`; append picks to history; bump counters; return picks. If planner returns fewer than n fresh songs, relax exclusion (log) rather than stall.
- Tests (fakes for taste + planner, injectable clock): first call refreshes; no refresh before threshold; refresh at the `refresh_every` boundary; TTL expiry triggers refresh; history-exclusion passed to planner; relax-when-starved returns something.

## Task 3: BlockRenderer

**Files:** Create `backend/radioai/block_renderer.py`; Test `backend/tests/test_block_renderer.py`. Refactor `backend/radioai/render_show.py` to reuse it (keep `render_show` working).

- Extract the per-transition assembly loop from `render_show.main` into
  `BlockRenderer(fetcher, brain, voice, cfg, ctx_provider).render(songs, index, prev_track=None) -> (audio: np.ndarray, meta: dict)`.
  - Fetch+analyze each song (skip failures). With `prev_track` (an already-analyzed `(song, analysis, audio, path)` tuple) the first transition bridges from it; without it (block 0) start cold on songs[0].
  - Reuse `choose_transition`, DJ talkover (DUCK), beatmatch (bass-swap), crossfade, cut; mashups disabled. Build segments+talk via `showmeta.build_segments`. Return audio + meta dict `{index, duration_s, segments, talk}` and the block's last analyzed track (for continuity).
- `render_show.main` becomes: build one block from the setlist via BlockRenderer, write `show.mp3`/`show.json` (back-compat preserved).
- Tests with fakes (fetcher returns a short sine wav per song; stub brain/voice returning silence): renders ≥2 songs into audio; segments ordered, non-overlapping, count matches; `prev_track` produces an opening transition (longer audio than songs alone); a failing fetch is skipped.

## Task 4: StationEngine

**Files:** Create `backend/radioai/station.py`; Test `backend/tests/test_station.py`.

- `StationEngine(planner, block_renderer, songs_per_block=3, buffer_ahead=1, blocks_dir)`.
- Background worker (a daemon `threading.Thread`) keeps blocks `[current .. current+buffer_ahead]` rendered. `start()` idempotent. `get_block_meta(n)` returns meta or `None` if still rendering. `get_block_path(n)` returns the mp3 path, rendering synchronously (with a per-index lock) if not present. `advance(n)` sets current index (called when the app fetches block n). Continuity: pass block n−1's last track as block n's `prev_track`.
- Thread-safety: a lock per index so an index never renders twice; the worker and a direct `get_block_path` cooperate.
- Tests with a fake BlockRenderer (records calls; returns tiny audio+meta): `get_block_path(0)` renders block 0; worker pre-renders block 1; continuity (prev_track) threaded; requesting the same index twice renders once; `advance` moves the buffer window.

## Task 5: Server station endpoints

**Files:** Modify `backend/radioai/server.py`; Test `backend/tests/test_server.py`.

- Process-wide lazy `StationEngine` singleton (built from `Config.from_env()` wiring: AudioFetcher, DJBrain, VoiceRenderer, RollingPlanner(TasteService, SetlistPlanner), BlockRenderer).
- `POST /api/station/start` → ensure engine started; `{ "ok": true, "block": 0 }`.
- `GET /api/station/block/{n}/meta` → 200 meta when ready, else 202 `{ "status": "rendering" }`. Calls `advance(n)`.
- `GET /api/station/block/{n}` → audio with HTTP Range (206), or 202 while rendering.
- Tests (inject a fake engine via app state/dependency): start ok; meta 202→200; audio Range 206 once ready.

## Task 6: Frontend — endless playback (DO LAST, after the visual-polish agent finishes)

**Files:** Create `frontend/src/hooks/useStation.ts`; Modify `frontend/src/App.tsx` and now-playing/up-next to read block meta; Test `frontend/src/__tests__/useStation.test.ts(x)`.

- `useStation`: POST `start`; fetch block 0 meta (poll while 202 → expose `tuning` state); load audio `/api/station/block/0`; play. Maintain `currentIndex`; prefetch block `n+1` meta+audio; on `ended`, swap to a second `<audio>` element already loaded with block n+1 (gapless), increment index, prefetch n+2. Now-playing/up-next/scrub use the current block's segments.
- Keep the visual components from the polish agent; only wire data/state. "Tuning in…" uses the polished StateCard.
- Tests (vitest, mock fetch/audio): advances index on `ended`; shows tuning while 202; segment finder picks the active segment within a block.

## Task 7: Rebuild + end-to-end (DO LAST)

- `cd frontend; npm run build`. Rebuild APK: `cd frontend/android; $env:JAVA_HOME='C:\Program Files\Android\Android Studio3\jbr'; $env:ANDROID_HOME='C:\Users\noams\AppData\Local\Android\Sdk'; .\gradlew.bat assembleDebug --no-daemon`; copy to `C:\dev\RadioAI\RadioAI-debug.apk`.
- Manual: run backend on 8800, open app, confirm block 0 "tuning in" then plays, block 1 continues with no stop, now-playing/up-next update per block.
- Full pytest suite green.
```
