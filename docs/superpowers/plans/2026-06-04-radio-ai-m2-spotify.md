# Radio AI — M2: Spotify-Driven Setlists — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the listener's setlist from their real Spotify taste — fetch top tracks/artists, let Gemini curate a ~6-song flowing setlist grounded in that taste, and feed it into the existing M1 render pipeline.

**Architecture:** Two new, independently-testable components — `TasteService` (spotipy OAuth + taste fetch, cached) and `SetlistPlanner` (Gemini, grounded in taste) — produce a `list[Song]` that the existing `render_show` pipeline consumes. Pure parsing functions are unit-tested with mocked API/LLM payloads; the network/OAuth and final render are exercised by a real golden-ear run. A hardcoded demo setlist remains as an offline fallback.

**Tech Stack:** Python 3.11+, `spotipy` (Spotify Web API + OAuth), existing `google-genai` (`GeminiClient`), `pytest`. Reuses all M1 modules unchanged.

---

## Environment notes (for every task)
- Work from `C:\dev\RadioAI\backend`.
- System `python` is 3.10 and will NOT work. Use the venv: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe` for all python/pytest commands.
- Prefer the PowerShell tool on Windows.
- Repo has `core.autocrlf=true` and a prior crash dropped commits. After each commit, VERIFY with `git diff --ignore-all-space --stat` that nothing real is left uncommitted; re-add/commit if so. Plain `git status`/`git diff` can mislead here.
- `.env` already contains `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`, `SPOTIFY_REDIRECT_URI=http://127.0.0.1:5173` (gitignored).
- End every commit message with a blank line then: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

## File Structure
```
backend/radioai/
  config.py          # MODIFY: add spotify_client_id/secret/redirect_uri
  taste.py           # CREATE: TasteTrack, TasteProfile, parse_profile, TasteService
  setlist.py         # CREATE: parse_setlist, SetlistPlanner
  render_show.py     # MODIFY: DEMO_SETLIST, build_setlist, DJ-every-2 placement, skip-on-fail
backend/tests/
  test_config.py     # MODIFY: assert spotify fields
  test_taste.py      # CREATE
  test_setlist.py    # CREATE
backend/pyproject.toml  # MODIFY: add spotipy
```

---

## Task 1: Add spotipy dependency + Spotify config fields

**Files:**
- Modify: `backend/pyproject.toml`
- Modify: `backend/radioai/config.py`
- Modify: `backend/tests/test_config.py`

- [ ] **Step 1: Add the failing test**

Append to `backend/tests/test_config.py`:
```python
def test_from_env_loads_spotify(monkeypatch):
    monkeypatch.setenv("SPOTIFY_CLIENT_ID", "cid")
    monkeypatch.setenv("SPOTIFY_CLIENT_SECRET", "csecret")
    monkeypatch.delenv("SPOTIFY_REDIRECT_URI", raising=False)
    cfg = Config.from_env()
    assert cfg.spotify_client_id == "cid"
    assert cfg.spotify_client_secret == "csecret"
    assert cfg.spotify_redirect_uri == "http://127.0.0.1:5173"  # default
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_config.py -v`
Expected: FAIL (`AttributeError: 'Config' object has no attribute 'spotify_client_id'`).

- [ ] **Step 3: Implement — overwrite `backend/radioai/config.py`**

```python
import os
from dataclasses import dataclass
from dotenv import load_dotenv

load_dotenv()


@dataclass
class Config:
    gemini_api_keys: list[str]
    llm_model: str
    tts_model: str
    tts_voice: str
    cache_dir: str
    spotify_client_id: str
    spotify_client_secret: str
    spotify_redirect_uri: str

    @classmethod
    def from_env(cls) -> "Config":
        raw = [
            os.environ.get("GEMINI_API_KEY", ""),
            os.environ.get("GEMINI_API_KEY_2", ""),
            os.environ.get("GEMINI_API_KEY_3", ""),
        ]
        keys = [k for k in raw if k]
        return cls(
            gemini_api_keys=keys,
            llm_model=os.environ.get("GEMINI_LLM_MODEL", "gemini-3.1-flash-lite-preview"),
            tts_model=os.environ.get("GEMINI_TTS_MODEL", "gemini-3.1-flash-tts-preview"),
            tts_voice=os.environ.get("GEMINI_TTS_VOICE", "Puck"),
            cache_dir=os.environ.get("CACHE_DIR", "./cache"),
            spotify_client_id=os.environ.get("SPOTIFY_CLIENT_ID", ""),
            spotify_client_secret=os.environ.get("SPOTIFY_CLIENT_SECRET", ""),
            spotify_redirect_uri=os.environ.get("SPOTIFY_REDIRECT_URI",
                                                "http://127.0.0.1:5173"),
        )
```

- [ ] **Step 4: Add spotipy to `backend/pyproject.toml`**

In the `dependencies = [ ... ]` list, add the line `"spotipy>=2.24",` (e.g. right after the `"google-genai>=2.8",` line). Then install it:
Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pip install "spotipy>=2.24"`
Expected: installs successfully.

- [ ] **Step 5: Run tests to confirm pass**

Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_config.py -v`
Expected: PASS (all config tests, including the new one).

- [ ] **Step 6: Commit**

```bash
git add backend/pyproject.toml backend/radioai/config.py backend/tests/test_config.py
git commit -m "feat: add spotipy dep and Spotify config fields"
```
Then verify clean: `git diff --ignore-all-space --stat` (re-add/commit if anything real remains).

---

## Task 2: Taste models + parse_profile

**Files:**
- Create: `backend/radioai/taste.py`
- Create: `backend/tests/test_taste.py`

- [ ] **Step 1: Write the failing test**

`backend/tests/test_taste.py`:
```python
from radioai.taste import TasteTrack, TasteProfile, parse_profile

_TOP_TRACKS = {
    "items": [
        {"name": "Tudo Bom", "artists": [{"name": "Static & Ben El"},
                                          {"name": "J Balvin"}], "duration_ms": 200000},
        {"name": "Million Dollar", "artists": [{"name": "Noa Kirel"}], "duration_ms": 180500},
    ]
}
_TOP_ARTISTS = {"items": [{"name": "Static & Ben El"}, {"name": "Omer Adam"}]}


def test_parse_profile_tracks():
    p = parse_profile(_TOP_TRACKS, _TOP_ARTISTS)
    assert isinstance(p, TasteProfile)
    assert len(p.top_tracks) == 2
    t = p.top_tracks[0]
    assert t.title == "Tudo Bom"
    assert t.artist == "Static & Ben El"   # first artist only
    assert abs(t.duration_s - 200.0) < 0.001


def test_parse_profile_artists():
    p = parse_profile(_TOP_TRACKS, _TOP_ARTISTS)
    assert p.top_artists == ["Static & Ben El", "Omer Adam"]


def test_parse_profile_empty():
    p = parse_profile({"items": []}, {"items": []})
    assert p.top_tracks == []
    assert p.top_artists == []


def test_parse_profile_missing_artist_is_blank():
    p = parse_profile({"items": [{"name": "X", "artists": [], "duration_ms": 1000}]},
                      {"items": []})
    assert p.top_tracks[0].artist == ""
    assert abs(p.top_tracks[0].duration_s - 1.0) < 0.001
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_taste.py -v`
Expected: FAIL (`ModuleNotFoundError: No module named 'radioai.taste'`).

- [ ] **Step 3: Write minimal implementation**

`backend/radioai/taste.py`:
```python
import os
import json
from dataclasses import dataclass


@dataclass
class TasteTrack:
    title: str
    artist: str
    duration_s: float


@dataclass
class TasteProfile:
    top_tracks: list[TasteTrack]
    top_artists: list[str]


def parse_profile(top_tracks_json: dict, top_artists_json: dict) -> TasteProfile:
    tracks = []
    for it in top_tracks_json.get("items", []):
        artists = it.get("artists", [])
        artist = artists[0].get("name", "") if artists else ""
        tracks.append(TasteTrack(
            title=it.get("name", ""),
            artist=artist,
            duration_s=(it.get("duration_ms", 0) or 0) / 1000.0,
        ))
    artist_names = [a.get("name", "") for a in top_artists_json.get("items", [])]
    return TasteProfile(top_tracks=tracks, top_artists=artist_names)
```

- [ ] **Step 4: Run tests to confirm pass**

Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_taste.py -v`
Expected: PASS (4 passed).

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/taste.py backend/tests/test_taste.py
git commit -m "feat: add taste models and parse_profile"
```
Verify clean: `git diff --ignore-all-space --stat`.

---

## Task 3: TasteService (fetch + cache)

**Files:**
- Modify: `backend/radioai/taste.py`
- Modify: `backend/tests/test_taste.py`

- [ ] **Step 1: Write the failing test**

Append to `backend/tests/test_taste.py`:
```python
from radioai.config import Config
from radioai.taste import TasteService


class _FakeSpotify:
    def __init__(self):
        self.calls = 0

    def current_user_top_tracks(self, limit, time_range):
        self.calls += 1
        return {"items": [{"name": "Song A", "artists": [{"name": "Artist A"}],
                           "duration_ms": 123000}]}

    def current_user_top_artists(self, limit, time_range):
        return {"items": [{"name": "Artist A"}]}


def _cfg(tmp_path):
    return Config(gemini_api_keys=[], llm_model="m", tts_model="m", tts_voice="v",
                  cache_dir=str(tmp_path), spotify_client_id="x",
                  spotify_client_secret="y", spotify_redirect_uri="http://127.0.0.1:5173")


def test_get_profile_fetches_and_caches(tmp_path):
    fake = _FakeSpotify()
    svc = TasteService(_cfg(tmp_path), client=fake)
    profile = svc.get_profile(use_cache=False)
    assert profile.top_tracks[0].title == "Song A"
    assert abs(profile.top_tracks[0].duration_s - 123.0) < 0.001
    # cache file written
    import os
    assert os.path.exists(os.path.join(str(tmp_path), "taste.json"))


def test_get_profile_uses_cache(tmp_path):
    fake = _FakeSpotify()
    svc = TasteService(_cfg(tmp_path), client=fake)
    svc.get_profile(use_cache=False)         # writes cache, calls=1
    again = svc.get_profile(use_cache=True)  # should read cache, no new call
    assert fake.calls == 1
    assert again.top_tracks[0].title == "Song A"
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_taste.py -v`
Expected: FAIL (`ImportError: cannot import name 'TasteService'`).

- [ ] **Step 3: Implement — append to `backend/radioai/taste.py`**

```python
def _profile_to_dict(p: TasteProfile) -> dict:
    return {
        "top_tracks": [{"title": t.title, "artist": t.artist,
                        "duration_s": t.duration_s} for t in p.top_tracks],
        "top_artists": p.top_artists,
    }


def _profile_from_dict(d: dict) -> TasteProfile:
    return TasteProfile(
        top_tracks=[TasteTrack(**t) for t in d.get("top_tracks", [])],
        top_artists=d.get("top_artists", []),
    )


class TasteService:
    """Reads Spotify taste (top tracks/artists), cached to disk. Inject a
    `client` (spotipy.Spotify-like) for tests; otherwise one is built lazily
    via OAuth from config."""

    SCOPE = "user-top-read"

    def __init__(self, config, client=None):
        self.config = config
        self._client = client
        self.cache_path = os.path.join(config.cache_dir, "taste.json")

    def _spotify(self):
        if self._client is None:
            from spotipy import Spotify
            from spotipy.oauth2 import SpotifyOAuth
            auth = SpotifyOAuth(
                client_id=self.config.spotify_client_id,
                client_secret=self.config.spotify_client_secret,
                redirect_uri=self.config.spotify_redirect_uri,
                scope=self.SCOPE,
                cache_path=os.path.join(self.config.cache_dir, ".spotify-token"),
                open_browser=True,
            )
            self._client = Spotify(auth_manager=auth)
        return self._client

    def get_profile(self, use_cache: bool = True) -> TasteProfile:
        if use_cache and os.path.exists(self.cache_path):
            with open(self.cache_path, "r", encoding="utf-8") as f:
                return _profile_from_dict(json.load(f))
        sp = self._spotify()
        tracks = sp.current_user_top_tracks(limit=20, time_range="medium_term")
        artists = sp.current_user_top_artists(limit=10, time_range="medium_term")
        profile = parse_profile(tracks, artists)
        os.makedirs(self.config.cache_dir, exist_ok=True)
        with open(self.cache_path, "w", encoding="utf-8") as f:
            json.dump(_profile_to_dict(profile), f, ensure_ascii=False, indent=2)
        return profile
```

- [ ] **Step 4: Run tests to confirm pass**

Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_taste.py -v`
Expected: PASS (6 passed total in the file).

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/taste.py backend/tests/test_taste.py
git commit -m "feat: add TasteService with Spotify fetch and disk cache"
```
Verify clean: `git diff --ignore-all-space --stat`.

---

## Task 4: parse_setlist

**Files:**
- Create: `backend/radioai/setlist.py`
- Create: `backend/tests/test_setlist.py`

- [ ] **Step 1: Write the failing test**

`backend/tests/test_setlist.py`:
```python
import pytest
from radioai.taste import TasteProfile, TasteTrack
from radioai.models import Song
from radioai.setlist import parse_setlist

_TASTE = TasteProfile(
    top_tracks=[TasteTrack(title="Million Dollar", artist="Noa Kirel", duration_s=180.5)],
    top_artists=["Noa Kirel", "Omer Adam"],
)


def test_parse_plain_json():
    text = '[{"title": "Tel Aviv", "artist": "Omer Adam"}, {"title": "Million Dollar", "artist": "Noa Kirel"}]'
    songs = parse_setlist(text, _TASTE, n=6)
    assert [s.title for s in songs] == ["Tel Aviv", "Million Dollar"]
    assert all(isinstance(s, Song) for s in songs)


def test_parse_tolerates_code_fence_and_prose():
    text = 'Sure! Here you go:\n```json\n[{"title": "Tel Aviv", "artist": "Omer Adam"}]\n```\nEnjoy.'
    songs = parse_setlist(text, _TASTE, n=6)
    assert songs[0].title == "Tel Aviv"


def test_duration_attached_for_known_track():
    text = '[{"title": "million dollar", "artist": "noa kirel"}, {"title": "Tel Aviv", "artist": "Omer Adam"}]'
    songs = parse_setlist(text, _TASTE, n=6)
    # case-insensitive match to a taste track -> Spotify duration attached
    assert abs(songs[0].duration_s - 180.5) < 0.001
    # unknown track -> no duration
    assert songs[1].duration_s is None


def test_dedupe_and_cap():
    text = ('[{"title": "A", "artist": "B"}, {"title": "A", "artist": "B"}, '
            '{"title": "C", "artist": "D"}, {"title": "E", "artist": "F"}]')
    songs = parse_setlist(text, _TASTE, n=2)
    assert [(s.title, s.artist) for s in songs] == [("A", "B"), ("C", "D")]


def test_malformed_raises():
    with pytest.raises(ValueError):
        parse_setlist("no json here at all", _TASTE, n=6)
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_setlist.py -v`
Expected: FAIL (`ModuleNotFoundError: No module named 'radioai.setlist'`).

- [ ] **Step 3: Write minimal implementation**

`backend/radioai/setlist.py`:
```python
import re
import json
from radioai.models import Song
from radioai.taste import TasteProfile


def _extract_json_array(text: str):
    match = re.search(r"\[.*\]", text, re.DOTALL)
    if not match:
        raise ValueError("No JSON array found in LLM output")
    return json.loads(match.group(0))


def parse_setlist(text: str, taste: TasteProfile, n: int = 6) -> list[Song]:
    data = _extract_json_array(text)
    durations = {(t.title.lower(), t.artist.lower()): t.duration_s
                 for t in taste.top_tracks}
    songs: list[Song] = []
    seen = set()
    for item in data:
        if not isinstance(item, dict):
            continue
        title = (item.get("title") or "").strip()
        artist = (item.get("artist") or "").strip()
        if not title or not artist:
            continue
        key = (title.lower(), artist.lower())
        if key in seen:
            continue
        seen.add(key)
        songs.append(Song(title=title, artist=artist,
                          duration_s=durations.get(key)))
        if len(songs) >= n:
            break
    if not songs:
        raise ValueError("SetlistPlanner produced no usable songs")
    return songs
```

- [ ] **Step 4: Run tests to confirm pass**

Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_setlist.py -v`
Expected: PASS (5 passed).

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/setlist.py backend/tests/test_setlist.py
git commit -m "feat: add parse_setlist (JSON parse, duration attach, dedupe, cap)"
```
Verify clean: `git diff --ignore-all-space --stat`.

---

## Task 5: SetlistPlanner.plan

**Files:**
- Modify: `backend/radioai/setlist.py`
- Modify: `backend/tests/test_setlist.py`

- [ ] **Step 1: Write the failing test**

Append to `backend/tests/test_setlist.py`:
```python
from radioai.setlist import SetlistPlanner


class _FakeLLM:
    def __init__(self, text):
        self._text = text
        self.last_prompt = None

    def complete(self, prompt: str) -> str:
        self.last_prompt = prompt
        return self._text


def test_planner_builds_songs_and_prompt_mentions_taste():
    llm = _FakeLLM('[{"title": "Tel Aviv", "artist": "Omer Adam"}]')
    planner = SetlistPlanner(client=llm)
    songs = planner.plan(_TASTE, n=6)
    assert songs[0].title == "Tel Aviv"
    # prompt should include the listener's taste so the LLM is grounded
    assert "Noa Kirel" in llm.last_prompt
    assert "Million Dollar" in llm.last_prompt
    assert "6" in llm.last_prompt  # requested count
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_setlist.py -v`
Expected: FAIL (`ImportError: cannot import name 'SetlistPlanner'`).

- [ ] **Step 3: Implement — append to `backend/radioai/setlist.py`**

```python
from typing import Protocol


class _LLM(Protocol):
    def complete(self, prompt: str) -> str: ...


class SetlistPlanner:
    """Curates a flowing radio setlist grounded in the listener's taste, via an
    LLM client (the project's GeminiClient)."""

    def __init__(self, client: "_LLM"):
        self.client = client

    def _prompt(self, taste: TasteProfile, n: int) -> str:
        tracks = "\n".join(f'- "{t.title}" — {t.artist}' for t in taste.top_tracks)
        artists = ", ".join(taste.top_artists)
        return (
            "You are a radio music director building a personal station for one "
            "listener. Here is their recent taste.\n\n"
            f"Top tracks:\n{tracks}\n\n"
            f"Top artists: {artists}\n\n"
            f"Curate a flowing {n}-song setlist with a natural energy arc. Prefer "
            "the listener's own tracks and closely related real songs by the same "
            "or adjacent artists. Only include real, well-known songs.\n"
            "Return ONLY a JSON array, no prose, in exactly this shape:\n"
            '[{"title": "...", "artist": "..."}, ...]'
        )

    def plan(self, taste: TasteProfile, n: int = 6) -> list[Song]:
        text = self.client.complete(self._prompt(taste, n))
        return parse_setlist(text, taste, n)
```

- [ ] **Step 4: Run tests to confirm pass**

Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_setlist.py -v`
Expected: PASS (6 passed).

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/setlist.py backend/tests/test_setlist.py
git commit -m "feat: add SetlistPlanner.plan (grounded Gemini setlist curation)"
```
Verify clean: `git diff --ignore-all-space --stat`.

---

## Task 6: Wire Spotify setlist into render_show + golden-ear

**Files:**
- Modify: `backend/radioai/render_show.py`
- Create: `backend/tests/test_render_setlist.py`

This adds `build_setlist(cfg)` (Spotify path + offline fallback), generalizes DJ
placement to every ~2 songs, and makes per-song fetch/analyze failures non-fatal.
The fallback is unit-tested; the full Spotify render is the golden-ear gate.

- [ ] **Step 1: Write the failing test (fallback path)**

`backend/tests/test_render_setlist.py`:
```python
from radioai.config import Config
from radioai.render_show import build_setlist, DEMO_SETLIST


def _cfg_no_spotify(tmp_path):
    return Config(gemini_api_keys=[], llm_model="m", tts_model="m", tts_voice="v",
                  cache_dir=str(tmp_path), spotify_client_id="",
                  spotify_client_secret="", spotify_redirect_uri="http://127.0.0.1:5173")


def test_build_setlist_falls_back_without_creds(tmp_path):
    songs = build_setlist(_cfg_no_spotify(tmp_path))
    assert songs == DEMO_SETLIST
    assert len(songs) >= 1
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_render_setlist.py -v`
Expected: FAIL (`ImportError: cannot import name 'build_setlist'` / `DEMO_SETLIST`).

- [ ] **Step 3: Implement — edit `backend/radioai/render_show.py`**

(a) Rename the existing `SETLIST = [...]` to `DEMO_SETLIST = [...]` (same 3 pinned Israeli hits — keep the exact `Song(...)` entries and their `query=` URLs).

(b) Add these imports near the top (with the other `from radioai...` imports):
```python
from radioai.taste import TasteService
from radioai.setlist import SetlistPlanner
```

(c) Add the `build_setlist` helper (above `def main()`):
```python
def build_setlist(cfg):
    """Build the setlist from Spotify taste; fall back to the demo set offline."""
    if cfg.spotify_client_id and cfg.spotify_client_secret:
        try:
            profile = TasteService(cfg).get_profile()
            planner = SetlistPlanner(
                client=GeminiClient(api_keys=cfg.gemini_api_keys,
                                    model=cfg.llm_model))
            songs = planner.plan(profile, n=6)
            if songs:
                return songs
        except Exception as e:
            print(f"[warn] Spotify setlist failed ({e}); using demo setlist")
    return DEMO_SETLIST
```

(d) In `main()`, replace the fetch/analyze loop so it uses `build_setlist` and skips songs that fail. Replace:
```python
    print("Fetching + analyzing...")
    tracks = []
    for song in SETLIST:
        path = fetcher.fetch(song)
        tracks.append((song, analyze(path), mx.load_mono(path)))
```
with:
```python
    setlist = build_setlist(cfg)
    print("Setlist:")
    for s in setlist:
        print(f"  - {s.title} — {s.artist}")

    print("Fetching + analyzing...")
    tracks = []
    for song in setlist:
        try:
            path = fetcher.fetch(song)
            tracks.append((song, analyze(path), mx.load_mono(path)))
        except Exception as e:
            print(f"  [skip] {song.title} — {song.artist}: {e}")
    if len(tracks) < 2:
        raise RuntimeError("Not enough playable songs to build a show")
```

(e) Generalize DJ placement: change the talkover trigger from `has_dj = (i == 1)` to:
```python
        has_dj = (i % 2 == 1)  # witty talkover every ~2 songs
```

Leave everything else (RADIO_STYLE, voice/style, segue crossfade, trim_silence,
dj_lines/show_script.txt writing, write_mp3) unchanged.

- [ ] **Step 4: Run tests to confirm pass**

Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_render_setlist.py -v`
Expected: PASS (1 passed). Then full suite:
Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest -q`
Expected: all pass (M1's 53 + new M2 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/render_show.py backend/tests/test_render_setlist.py
git commit -m "feat: build setlist from Spotify taste with offline fallback + DJ every ~2 songs"
```
Verify clean: `git diff --ignore-all-space --stat`.

- [ ] **Step 6: Golden-ear — first taste-driven render (real Spotify, one-time login)**

Clear stale audio so a fresh show is built, then run:
```
Remove-Item C:\dev\RadioAI\backend\cache\*.mp3 -Force -ErrorAction SilentlyContinue
Remove-Item C:\dev\RadioAI\backend\cache\voice\*.wav -Force -ErrorAction SilentlyContinue
Remove-Item C:\dev\RadioAI\backend\cache\taste.json -Force -ErrorAction SilentlyContinue
C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m radioai.render_show
```
On the FIRST run a browser opens for Spotify consent (redirect to `http://127.0.0.1:5173`); approve it. The token caches to `cache/.spotify-token` for next time.
Verify: `ffprobe -v error -show_entries format=duration -of csv=p=0 cache\show.mp3` (>0) and read `cache\show_script.txt`. Confirm the printed setlist reflects the listener's real taste and song versions are correct.

**Note:** OAuth needs a human to approve in the browser — if running head-less/in a subagent that cannot open a browser, STOP and hand back to the user to run this step interactively (e.g. via `! python -m radioai.render_show`).

---

## Self-Review

**Spec coverage:**
- Spotify OAuth + taste fetch (top tracks/artists, medium-term) → Task 3 ✓
- `parse_profile` (pure) → Task 2 ✓
- LLM-curated, grounded setlist → Task 5 ✓; parsing/duration-attach/dedupe/cap → Task 4 ✓
- Version matching via Spotify duration → Task 4 (duration attach) + existing fetcher ✓
- render integration, DJ every ~2 songs, skip-on-fail, offline fallback → Task 6 ✓
- Config + spotipy dep → Task 1 ✓
- Caching (taste.json + token) → Task 3 ✓
- Testing (mocked spotipy + LLM, fallback) → Tasks 2–6 ✓; golden-ear → Task 6 Step 6 ✓

**Placeholder scan:** No TBD/TODO; every code step has complete code; every run step has command + expected result. ✓

**Type consistency:** `TasteTrack`/`TasteProfile`/`parse_profile` (Task 2) used identically in Tasks 3–5. `TasteService(config, client=None).get_profile(use_cache)` consistent. `parse_setlist(text, taste, n)` and `SetlistPlanner(client).plan(taste, n)` consistent (Tasks 4–5). `build_setlist(cfg)`/`DEMO_SETLIST` consistent (Task 6). `GeminiClient(api_keys=, model=)` matches its M1 signature. `Song(title, artist, duration_s, query)` matches the existing dataclass. ✓
