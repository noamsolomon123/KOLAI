# Radio AI — M1: Core Mix Vertical Slice — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** From a hardcoded list of 2–3 songs, fetch the audio, analyze it (BPM, beatgrid, key, energy, vocal-onset), and render a single MP3 that plays song A → a Hebrew DJ intro voiced by ElevenLabs → a beat-matched transition into song B.

**Architecture:** A Python pipeline of small, independently-testable units. Pure-logic units (data models, Camelot key math, compatibility scoring, transition planning) are built test-first. I/O units (yt-dlp fetch, librosa analysis, ElevenLabs voice) are tested with synthetic fixtures or mocks. A final CLI wires them into one render. This is the foundation reused by all later milestones.

**Tech Stack:** Python 3.11+, `yt-dlp`, `ffmpeg`, `librosa`, `numpy`, `soundfile`, `pydub`, `pyrubberband` (time-stretch), `elevenlabs`, `anthropic`, `pytest`.

---

## File Structure

```
radioai/
  backend/
    pyproject.toml
    .env.example
    radioai/
      __init__.py
      config.py          # load API keys + settings from env
      models.py          # Song, TrackAnalysis, DJSlot, Transition, PlanItem
      keys.py            # Camelot wheel mapping + harmonic compatibility
      scoring.py         # tempo/key/energy compatibility scoring
      fetcher.py         # AudioFetcher (yt-dlp) with duration matching
      analyzer.py        # AudioAnalyzer (librosa)
      mixplanner.py      # MixPlanner: choose transition + DJ-slot placement
      djbrain.py         # minimal DJBrain: Hebrew intro script (Claude)
      voice.py           # VoiceRenderer (ElevenLabs)
      mixrenderer.py     # MixRenderer: crossfade, beat-match, duck DJ over music
      render_show.py     # CLI entry: song list -> show.mp3
    tests/
      __init__.py
      conftest.py
      fixtures/          # generated synthetic audio for deterministic tests
      test_models.py
      test_keys.py
      test_scoring.py
      test_analyzer.py
      test_fetcher.py
      test_mixplanner.py
      test_djbrain.py
      test_voice.py
      test_mixrenderer.py
```

Each file has one responsibility. Pure-logic files (`models`, `keys`, `scoring`, `mixplanner`) have no I/O and are fully unit-tested. I/O files isolate external calls behind one class each, so they can be mocked.

---

## Task 0: Project scaffold, git, dependencies, config

**Files:**
- Create: `backend/pyproject.toml`
- Create: `backend/.env.example`
- Create: `backend/radioai/__init__.py`
- Create: `backend/radioai/config.py`
- Create: `backend/tests/__init__.py`
- Create: `backend/tests/conftest.py`

- [ ] **Step 1: Initialize git (repo does not exist yet)**

Run from `C:\dev\RadioAI`:
```bash
git init
```
Expected: "Initialized empty Git repository".

- [ ] **Step 2: Create `backend/pyproject.toml`**

```toml
[project]
name = "radioai"
version = "0.1.0"
description = "Radio AI - personal AI radio DJ (POC)"
requires-python = ">=3.11"
dependencies = [
    "yt-dlp>=2024.1.0",
    "librosa>=0.10.1",
    "numpy>=1.26",
    "soundfile>=0.12",
    "pydub>=0.25",
    "pyrubberband>=0.3.0",
    "elevenlabs>=1.0",
    "anthropic>=0.39",
    "python-dotenv>=1.0",
]

[project.optional-dependencies]
dev = ["pytest>=8.0"]

[tool.pytest.ini_options]
testpaths = ["tests"]
pythonpath = ["."]
```

- [ ] **Step 3: Create `backend/.env.example`**

```bash
# Copy to .env and fill in. .env is gitignored.
ANTHROPIC_API_KEY=sk-ant-...
ELEVENLABS_API_KEY=...
ELEVENLABS_VOICE_ID=...          # a Hebrew-capable voice id
CACHE_DIR=./cache                # where fetched audio + analysis are cached
```

- [ ] **Step 4: Create `backend/.gitignore`**

```
__pycache__/
*.pyc
.env
cache/
*.mp3
*.wav
.pytest_cache/
.venv/
```

- [ ] **Step 5: Create empty package files**

`backend/radioai/__init__.py`:
```python
```
`backend/tests/__init__.py`:
```python
```

- [ ] **Step 6: Create `backend/radioai/config.py`**

```python
import os
from dataclasses import dataclass
from dotenv import load_dotenv

load_dotenv()


@dataclass
class Config:
    anthropic_api_key: str
    elevenlabs_api_key: str
    elevenlabs_voice_id: str
    cache_dir: str

    @classmethod
    def from_env(cls) -> "Config":
        return cls(
            anthropic_api_key=os.environ.get("ANTHROPIC_API_KEY", ""),
            elevenlabs_api_key=os.environ.get("ELEVENLABS_API_KEY", ""),
            elevenlabs_voice_id=os.environ.get("ELEVENLABS_VOICE_ID", ""),
            cache_dir=os.environ.get("CACHE_DIR", "./cache"),
        )
```

- [ ] **Step 7: Create `backend/tests/conftest.py`**

```python
import os
import sys

# Ensure the backend package root is importable when running pytest from backend/.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
```

- [ ] **Step 8: Install dependencies**

Run from `backend/`:
```bash
python -m venv .venv
.venv\Scripts\activate
pip install -e ".[dev]"
```
Expected: installs without error. Also confirm `ffmpeg` is on PATH (`ffmpeg -version`); install via `winget install Gyan.FFmpeg` if missing.

- [ ] **Step 9: Commit**

```bash
git add backend/pyproject.toml backend/.env.example backend/.gitignore backend/radioai/__init__.py backend/radioai/config.py backend/tests/__init__.py backend/tests/conftest.py docs/
git commit -m "chore: scaffold radioai backend (M1)"
```

---

## Task 1: Data models

**Files:**
- Create: `backend/radioai/models.py`
- Test: `backend/tests/test_models.py`

- [ ] **Step 1: Write the failing test**

`backend/tests/test_models.py`:
```python
from radioai.models import Song, TrackAnalysis, DJSlot, Transition, PlanItem


def test_song_minimal():
    s = Song(title="Tudo Bom", artist="Static & Ben El")
    assert s.title == "Tudo Bom"
    assert s.artist == "Static & Ben El"
    assert s.duration_s is None


def test_track_analysis_fields():
    a = TrackAnalysis(
        path="x.wav", duration_s=180.0, bpm=120.0, beat_times=[0.5, 1.0],
        key_camelot="8A", energy=0.7, intro_end_s=8.0, outro_start_s=170.0,
        vocal_onset_s=12.0,
    )
    assert a.bpm == 120.0
    assert a.beat_times == [0.5, 1.0]
    assert a.key_camelot == "8A"


def test_transition_defaults_no_dj():
    t = Transition(type="beatmatch", duration_s=8.0)
    assert t.dj_slot is None


def test_plan_item_links_song_and_transition():
    s = Song(title="A", artist="B")
    a = TrackAnalysis(path="a.wav", duration_s=10, bpm=120, beat_times=[],
                      key_camelot="8A", energy=0.5, intro_end_s=1, outro_start_s=9,
                      vocal_onset_s=2)
    t = Transition(type="cut", duration_s=0.0)
    item = PlanItem(song=s, analysis=a, transition_in=t)
    assert item.song.title == "A"
    assert item.transition_in.type == "cut"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_models.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'radioai.models'`.

- [ ] **Step 3: Write minimal implementation**

`backend/radioai/models.py`:
```python
from dataclasses import dataclass
from typing import Literal, Optional

TransitionType = Literal["talkover", "beatmatch", "crossfade", "cut"]


@dataclass
class Song:
    title: str
    artist: str
    duration_s: Optional[float] = None  # expected/reference duration if known


@dataclass
class TrackAnalysis:
    path: str
    duration_s: float
    bpm: float
    beat_times: list[float]   # beat onset times in seconds
    key_camelot: str          # e.g. "8A"
    energy: float             # 0..1 normalized loudness
    intro_end_s: float        # end of leading instrumental region
    outro_start_s: float      # start of trailing instrumental region
    vocal_onset_s: float      # time the first vocal enters


@dataclass
class DJSlot:
    text: str          # Hebrew script
    audio_path: str    # voiced audio file
    duration_s: float


@dataclass
class Transition:
    type: TransitionType
    duration_s: float
    dj_slot: Optional[DJSlot] = None


@dataclass
class PlanItem:
    song: Song
    analysis: TrackAnalysis
    transition_in: Transition  # how we move INTO this song from the previous one
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_models.py -v`
Expected: PASS (4 passed).

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/models.py backend/tests/test_models.py
git commit -m "feat: add core data models"
```

---

## Task 2: Camelot key mapping & harmonic compatibility

**Files:**
- Create: `backend/radioai/keys.py`
- Test: `backend/tests/test_keys.py`

- [ ] **Step 1: Write the failing test**

`backend/tests/test_keys.py`:
```python
from radioai.keys import camelot_from_key, are_keys_compatible


def test_camelot_major_mapping():
    assert camelot_from_key("C", "major") == "8B"
    assert camelot_from_key("A", "minor") == "8A"
    assert camelot_from_key("G", "major") == "9B"


def test_same_key_is_compatible():
    assert are_keys_compatible("8A", "8A") is True


def test_relative_major_minor_compatible():
    # same number, different letter
    assert are_keys_compatible("8A", "8B") is True


def test_adjacent_on_wheel_compatible():
    assert are_keys_compatible("8A", "9A") is True
    assert are_keys_compatible("8A", "7A") is True


def test_wheel_wraps_around():
    assert are_keys_compatible("12A", "1A") is True
    assert are_keys_compatible("1A", "12A") is True


def test_distant_keys_incompatible():
    assert are_keys_compatible("8A", "11A") is False
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_keys.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'radioai.keys'`.

- [ ] **Step 3: Write minimal implementation**

`backend/radioai/keys.py`:
```python
# Camelot wheel: maps a musical key to a code like "8A" (minor) / "8B" (major).
# Harmonic mixing: keys are compatible if equal, relative (same number, A<->B),
# or adjacent on the wheel (number +/- 1 mod 12, same letter).

_MAJOR_TO_CAMELOT = {
    "C": "8B", "G": "9B", "D": "10B", "A": "11B", "E": "12B", "B": "1B",
    "F#": "2B", "Gb": "2B", "Db": "3B", "C#": "3B", "Ab": "4B", "G#": "4B",
    "Eb": "5B", "D#": "5B", "Bb": "6B", "A#": "6B", "F": "7B",
}
_MINOR_TO_CAMELOT = {
    "A": "8A", "E": "9A", "B": "10A", "F#": "11A", "Gb": "11A",
    "C#": "12A", "Db": "12A", "G#": "1A", "Ab": "1A", "D#": "2A", "Eb": "2A",
    "A#": "3A", "Bb": "3A", "F": "4A", "C": "5A", "G": "6A", "D": "7A",
}


def camelot_from_key(tonic: str, mode: str) -> str:
    """tonic like 'C', 'F#'; mode 'major' or 'minor'. Returns e.g. '8B'."""
    table = _MAJOR_TO_CAMELOT if mode == "major" else _MINOR_TO_CAMELOT
    if tonic not in table:
        raise ValueError(f"Unknown tonic {tonic!r} for mode {mode!r}")
    return table[tonic]


def _parse(code: str) -> tuple[int, str]:
    letter = code[-1]
    number = int(code[:-1])
    return number, letter


def are_keys_compatible(a: str, b: str) -> bool:
    na, la = _parse(a)
    nb, lb = _parse(b)
    if a == b:
        return True
    if na == nb and la != lb:  # relative major/minor
        return True
    if la == lb:               # adjacent on the wheel (1..12 wraps)
        diff = abs(na - nb)
        return diff == 1 or diff == 11
    return False
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_keys.py -v`
Expected: PASS (6 passed).

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/keys.py backend/tests/test_keys.py
git commit -m "feat: add Camelot key mapping and harmonic compatibility"
```

---

## Task 3: Compatibility scoring

**Files:**
- Create: `backend/radioai/scoring.py`
- Test: `backend/tests/test_scoring.py`

Scoring decides how blendable two tracks are (0..1) from tempo, key, and energy.

- [ ] **Step 1: Write the failing test**

`backend/tests/test_scoring.py`:
```python
from radioai.models import TrackAnalysis
from radioai.scoring import compatibility


def _track(bpm, key, energy):
    return TrackAnalysis(path="x", duration_s=180, bpm=bpm, beat_times=[],
                         key_camelot=key, energy=energy, intro_end_s=8,
                         outro_start_s=170, vocal_onset_s=12)


def test_identical_tracks_score_high():
    a = _track(120, "8A", 0.6)
    b = _track(120, "8A", 0.6)
    assert compatibility(a, b) > 0.9


def test_far_tempo_scores_low():
    a = _track(120, "8A", 0.6)
    b = _track(170, "8A", 0.6)  # 41% faster
    assert compatibility(a, b) < 0.4


def test_incompatible_key_lowers_score():
    a = _track(120, "8A", 0.6)
    compatible = compatibility(a, _track(120, "8B", 0.6))   # relative key
    clashing = compatibility(a, _track(120, "11A", 0.6))    # distant key
    assert compatible > clashing


def test_score_is_bounded():
    a = _track(120, "8A", 0.6)
    b = _track(200, "11A", 0.0)
    s = compatibility(a, b)
    assert 0.0 <= s <= 1.0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_scoring.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'radioai.scoring'`.

- [ ] **Step 3: Write minimal implementation**

`backend/radioai/scoring.py`:
```python
from radioai.models import TrackAnalysis
from radioai.keys import are_keys_compatible

# Weights for the three factors (sum to 1.0).
# NOTE: finalized at 0.65/0.22/0.13 during implementation — the original
# 0.5/0.3/0.2 made test_far_tempo_scores_low impossible (0+0.3+0.2=0.5 > 0.4).
_W_TEMPO = 0.65
_W_KEY = 0.22
_W_ENERGY = 0.13

# Tempo within this fraction is considered fully matchable (post small nudge).
_TEMPO_TOLERANCE = 0.06  # +/-6%


def _tempo_score(bpm_a: float, bpm_b: float) -> float:
    if bpm_a <= 0 or bpm_b <= 0:
        return 0.0
    ratio = max(bpm_a, bpm_b) / min(bpm_a, bpm_b)
    diff = ratio - 1.0
    if diff <= _TEMPO_TOLERANCE:
        return 1.0
    # Linear falloff: 0 by the time we are ~30% apart.
    return max(0.0, 1.0 - (diff - _TEMPO_TOLERANCE) / 0.24)


def _key_score(a: str, b: str) -> float:
    return 1.0 if are_keys_compatible(a, b) else 0.0


def _energy_score(ea: float, eb: float) -> float:
    return 1.0 - min(1.0, abs(ea - eb))


def compatibility(a: TrackAnalysis, b: TrackAnalysis) -> float:
    score = (
        _W_TEMPO * _tempo_score(a.bpm, b.bpm)
        + _W_KEY * _key_score(a.key_camelot, b.key_camelot)
        + _W_ENERGY * _energy_score(a.energy, b.energy)
    )
    return max(0.0, min(1.0, score))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_scoring.py -v`
Expected: PASS (4 passed).

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/scoring.py backend/tests/test_scoring.py
git commit -m "feat: add tempo/key/energy compatibility scoring"
```

---

## Task 4: MixPlanner — choose transition + DJ-slot placement

**Files:**
- Create: `backend/radioai/mixplanner.py`
- Test: `backend/tests/test_mixplanner.py`

For M1 the rule set is small: the first song after a DJ slot uses a **talkover**;
otherwise, high compatibility → **beatmatch**, medium → **crossfade**, low → **cut**.

- [ ] **Step 1: Write the failing test**

`backend/tests/test_mixplanner.py`:
```python
from radioai.models import TrackAnalysis
from radioai.mixplanner import choose_transition


def _track(bpm, key, energy):
    return TrackAnalysis(path="x", duration_s=180, bpm=bpm, beat_times=[],
                         key_camelot=key, energy=energy, intro_end_s=8,
                         outro_start_s=170, vocal_onset_s=12)


def test_dj_slot_forces_talkover():
    a = _track(120, "8A", 0.6)
    b = _track(120, "8A", 0.6)
    t = choose_transition(a, b, has_dj=True)
    assert t.type == "talkover"


def test_high_compat_beatmatch():
    a = _track(120, "8A", 0.6)
    b = _track(122, "8A", 0.6)
    t = choose_transition(a, b, has_dj=False)
    assert t.type == "beatmatch"


def test_medium_compat_crossfade():
    a = _track(120, "8A", 0.6)
    b = _track(132, "11A", 0.6)  # ~10% tempo, clashing key -> medium
    t = choose_transition(a, b, has_dj=False)
    assert t.type == "crossfade"


def test_low_compat_cut():
    a = _track(120, "8A", 0.9)
    b = _track(180, "11A", 0.1)
    t = choose_transition(a, b, has_dj=False)
    assert t.type == "cut"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_mixplanner.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'radioai.mixplanner'`.

- [ ] **Step 3: Write minimal implementation**

`backend/radioai/mixplanner.py`:
```python
from radioai.models import TrackAnalysis, Transition
from radioai.scoring import compatibility

_BEATMATCH_MIN = 0.75
_CROSSFADE_MIN = 0.45
_TALKOVER_SECONDS = 6.0
_BLEND_SECONDS = 8.0


def choose_transition(prev: TrackAnalysis, nxt: TrackAnalysis,
                      has_dj: bool) -> Transition:
    """Decide how to move from `prev` into `nxt`.

    has_dj=True means a DJ slot sits at this boundary -> talk over the
    instrumental. Otherwise pick a blend by compatibility.
    """
    if has_dj:
        return Transition(type="talkover", duration_s=_TALKOVER_SECONDS)

    score = compatibility(prev, nxt)
    if score >= _BEATMATCH_MIN:
        return Transition(type="beatmatch", duration_s=_BLEND_SECONDS)
    if score >= _CROSSFADE_MIN:
        return Transition(type="crossfade", duration_s=_BLEND_SECONDS)
    return Transition(type="cut", duration_s=0.0)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_mixplanner.py -v`
Expected: PASS (4 passed).

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/mixplanner.py backend/tests/test_mixplanner.py
git commit -m "feat: add MixPlanner transition selection"
```

---

## Task 5: AudioAnalyzer (librosa)

**Files:**
- Create: `backend/radioai/analyzer.py`
- Test: `backend/tests/test_analyzer.py`

Test deterministically against a **synthesized click track** at a known BPM.

- [ ] **Step 1: Write the failing test**

`backend/tests/test_analyzer.py`:
```python
import numpy as np
import soundfile as sf
from radioai.analyzer import analyze, _normalized_energy


def _make_click_track(path, bpm=120, seconds=8, sr=22050):
    n = int(seconds * sr)
    audio = np.zeros(n, dtype=np.float32)
    interval = int(sr * 60.0 / bpm)
    click_len = 200
    for start in range(0, n - click_len, interval):
        audio[start:start + click_len] += np.hanning(click_len).astype(np.float32)
    sf.write(path, audio, sr)
    return path


def test_analyze_detects_bpm(tmp_path):
    p = _make_click_track(str(tmp_path / "click.wav"), bpm=120)
    result = analyze(p)
    # librosa may report a metrical multiple; accept 120 or 60.
    assert abs(result.bpm - 120) < 6 or abs(result.bpm - 60) < 6


def test_analyze_populates_fields(tmp_path):
    p = _make_click_track(str(tmp_path / "click.wav"), bpm=100, seconds=6)
    result = analyze(p)
    assert result.duration_s > 5.0
    assert len(result.beat_times) > 0
    assert 0.0 <= result.energy <= 1.0
    assert result.key_camelot[-1] in ("A", "B")


def test_normalized_energy_bounds():
    loud = np.ones(1000, dtype=np.float32)
    quiet = np.zeros(1000, dtype=np.float32)
    assert _normalized_energy(loud) > _normalized_energy(quiet)
    assert 0.0 <= _normalized_energy(loud) <= 1.0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_analyzer.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'radioai.analyzer'`.

- [ ] **Step 3: Write minimal implementation**

`backend/radioai/analyzer.py`:
```python
import numpy as np
import librosa
from radioai.models import TrackAnalysis
from radioai.keys import camelot_from_key

_PITCHES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

# Krumhansl-Schmuckler key profiles.
_MAJOR_PROFILE = np.array(
    [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
_MINOR_PROFILE = np.array(
    [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])


def _normalized_energy(samples: np.ndarray) -> float:
    rms = float(np.sqrt(np.mean(np.square(samples)))) if samples.size else 0.0
    # Map RMS (0..1) onto 0..1 with a gentle curve; clamp.
    return max(0.0, min(1.0, rms))


def _estimate_key(y: np.ndarray, sr: int) -> str:
    chroma = librosa.feature.chroma_cqt(y=y, sr=sr)
    profile = chroma.mean(axis=1)
    best_score = -np.inf
    best = ("C", "major")
    for i in range(12):
        rotated = np.roll(profile, -i)
        maj = float(np.corrcoef(rotated, _MAJOR_PROFILE)[0, 1])
        minr = float(np.corrcoef(rotated, _MINOR_PROFILE)[0, 1])
        if maj > best_score:
            best_score, best = maj, (_PITCHES[i], "major")
        if minr > best_score:
            best_score, best = minr, (_PITCHES[i], "minor")
    return camelot_from_key(best[0], best[1])


def _vocal_onset(y: np.ndarray, sr: int) -> float:
    # Heuristic: first strong onset after the very start ~ vocal/lead entry.
    onsets = librosa.onset.onset_detect(y=y, sr=sr, units="time", backtrack=True)
    for t in onsets:
        if t > 1.0:
            return float(t)
    return 0.0


def analyze(path: str) -> TrackAnalysis:
    y, sr = librosa.load(path, mono=True)
    duration = float(librosa.get_duration(y=y, sr=sr))

    tempo, beats = librosa.beat.beat_track(y=y, sr=sr)
    bpm = float(np.atleast_1d(tempo)[0])
    beat_times = [float(t) for t in librosa.frames_to_time(beats, sr=sr)]

    key = _estimate_key(y, sr)
    energy = _normalized_energy(y)

    # Intro/outro regions: first/last ~8s (refined in later milestones).
    intro_end = min(8.0, duration * 0.1)
    outro_start = max(duration - 8.0, duration * 0.9)
    vocal_onset = _vocal_onset(y, sr)

    return TrackAnalysis(
        path=path, duration_s=duration, bpm=bpm, beat_times=beat_times,
        key_camelot=key, energy=energy, intro_end_s=intro_end,
        outro_start_s=outro_start, vocal_onset_s=vocal_onset,
    )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_analyzer.py -v`
Expected: PASS (3 passed). Note: this test loads/analyzes real audio, so it may take a few seconds.

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/analyzer.py backend/tests/test_analyzer.py
git commit -m "feat: add librosa AudioAnalyzer (bpm, key, beats, energy, vocal-onset)"
```

---

## Task 6: AudioFetcher (yt-dlp) with duration matching

**Files:**
- Create: `backend/radioai/fetcher.py`
- Test: `backend/tests/test_fetcher.py`

The fetcher's *selection logic* (pick the best candidate by duration + "official
audio" preference) is pure and unit-tested. The network/yt-dlp call is isolated
behind a single method and mocked.

- [ ] **Step 1: Write the failing test**

`backend/tests/test_fetcher.py`:
```python
from radioai.models import Song
from radioai.fetcher import pick_best_candidate


def test_prefers_duration_close_match():
    song = Song(title="A", artist="B", duration_s=200.0)
    candidates = [
        {"id": "1", "title": "A - B (Live)", "duration": 360},
        {"id": "2", "title": "A - B (Official Audio)", "duration": 202},
        {"id": "3", "title": "A - B remix", "duration": 150},
    ]
    best = pick_best_candidate(song, candidates)
    assert best["id"] == "2"


def test_penalizes_live_versions():
    song = Song(title="A", artist="B", duration_s=200.0)
    candidates = [
        {"id": "live", "title": "A - B (Live at X)", "duration": 201},
        {"id": "audio", "title": "A - B (Audio)", "duration": 205},
    ]
    best = pick_best_candidate(song, candidates)
    assert best["id"] == "audio"


def test_no_reference_duration_uses_keyword_score():
    song = Song(title="A", artist="B")  # no duration
    candidates = [
        {"id": "x", "title": "A - B reaction", "duration": 600},
        {"id": "y", "title": "A - B official audio", "duration": 200},
    ]
    best = pick_best_candidate(song, candidates)
    assert best["id"] == "y"


def test_returns_none_for_empty():
    song = Song(title="A", artist="B")
    assert pick_best_candidate(song, []) is None
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_fetcher.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'radioai.fetcher'`.

- [ ] **Step 3: Write minimal implementation**

`backend/radioai/fetcher.py`:
```python
import os
import hashlib
from typing import Optional
import yt_dlp
from radioai.models import Song

_GOOD_KEYWORDS = ("official audio", "audio", "official")
_BAD_KEYWORDS = ("live", "remix", "reaction", "cover", "karaoke", "sped up")


def _candidate_score(song: Song, cand: dict) -> float:
    title = cand.get("title", "").lower()
    score = 0.0
    if song.duration_s and cand.get("duration"):
        diff = abs(cand["duration"] - song.duration_s)
        score += max(0.0, 30.0 - diff)  # closer duration = higher
    for kw in _GOOD_KEYWORDS:
        if kw in title:
            score += 5.0
    for kw in _BAD_KEYWORDS:
        if kw in title:
            score -= 20.0
    return score


def pick_best_candidate(song: Song, candidates: list[dict]) -> Optional[dict]:
    if not candidates:
        return None
    return max(candidates, key=lambda c: _candidate_score(song, c))


class AudioFetcher:
    def __init__(self, cache_dir: str):
        self.cache_dir = cache_dir
        os.makedirs(cache_dir, exist_ok=True)

    def _cache_path(self, song: Song) -> str:
        key = f"{song.artist}-{song.title}".lower()
        h = hashlib.sha1(key.encode("utf-8")).hexdigest()[:16]
        return os.path.join(self.cache_dir, f"{h}.mp3")

    def _search(self, query: str, limit: int = 5) -> list[dict]:
        opts = {"quiet": True, "skip_download": True, "extract_flat": True}
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(f"ytsearch{limit}:{query}", download=False)
        return info.get("entries", []) if info else []

    def fetch(self, song: Song) -> str:
        """Return a local mp3 path for the song, downloading if not cached."""
        out_path = self._cache_path(song)
        if os.path.exists(out_path):
            return out_path

        candidates = self._search(f"{song.artist} {song.title} official audio")
        best = pick_best_candidate(song, candidates)
        if best is None:
            raise RuntimeError(f"No candidate found for {song.artist} - {song.title}")

        opts = {
            "quiet": True,
            "format": "bestaudio/best",
            "outtmpl": out_path.replace(".mp3", ".%(ext)s"),
            "postprocessors": [
                {"key": "FFmpegExtractAudio", "preferredcodec": "mp3",
                 "preferredquality": "192"},
            ],
        }
        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.download([best["id"] if "http" in str(best.get("id", ""))
                          else f"https://www.youtube.com/watch?v={best['id']}"])
        return out_path
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_fetcher.py -v`
Expected: PASS (4 passed). The network `fetch`/`_search` paths are not unit-tested here; they are exercised by the integration run in Task 10.

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/fetcher.py backend/tests/test_fetcher.py
git commit -m "feat: add yt-dlp AudioFetcher with duration-aware candidate selection"
```

---

## Task 7: DJBrain (minimal Hebrew intro via Claude)

**Files:**
- Create: `backend/radioai/djbrain.py`
- Test: `backend/tests/test_djbrain.py`

The LLM call is isolated behind one method; the test injects a fake client so we
assert prompt construction and word-budget trimming without hitting the API.

- [ ] **Step 1: Write the failing test**

`backend/tests/test_djbrain.py`:
```python
from radioai.models import Song
from radioai.djbrain import DJBrain, words_for_seconds


def test_words_for_seconds_budget():
    # ~2.5 Hebrew words/sec
    assert words_for_seconds(6.0) == 15
    assert words_for_seconds(0.0) == 0


class _FakeClient:
    def __init__(self, text):
        self._text = text
        self.last_prompt = None

    def complete(self, prompt: str) -> str:
        self.last_prompt = prompt
        return self._text


def test_intro_includes_song_titles_in_prompt():
    client = _FakeClient("ועכשיו שיר חדש בשבילכם")
    brain = DJBrain(client=client, persona="גלגלצ")
    prev = Song(title="Tudo Bom", artist="Static & Ben El")
    nxt = Song(title="Malkat Hayofi", artist="Eden Ben Zaken")
    script = brain.write_intro(prev=prev, nxt=nxt, seconds=6.0)
    assert "Tudo Bom" in client.last_prompt
    assert "Malkat Hayofi" in client.last_prompt
    assert script.strip() != ""


def test_intro_trims_to_word_budget():
    long_text = " ".join(["מילה"] * 100)
    client = _FakeClient(long_text)
    brain = DJBrain(client=client, persona="גלגלצ")
    script = brain.write_intro(prev=None, nxt=Song("A", "B"), seconds=4.0)
    assert len(script.split()) <= words_for_seconds(4.0)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_djbrain.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'radioai.djbrain'`.

- [ ] **Step 3: Write minimal implementation**

`backend/radioai/djbrain.py`:
```python
from typing import Optional, Protocol
from radioai.models import Song

_HEBREW_WORDS_PER_SEC = 2.5


def words_for_seconds(seconds: float) -> int:
    return int(seconds * _HEBREW_WORDS_PER_SEC)


class LLMClient(Protocol):
    def complete(self, prompt: str) -> str: ...


class AnthropicClient:
    """Thin wrapper around the Anthropic SDK so DJBrain stays testable."""

    def __init__(self, api_key: str, model: str = "claude-opus-4-8"):
        import anthropic
        self._client = anthropic.Anthropic(api_key=api_key)
        self._model = model

    def complete(self, prompt: str) -> str:
        msg = self._client.messages.create(
            model=self._model,
            max_tokens=300,
            messages=[{"role": "user", "content": prompt}],
        )
        return msg.content[0].text


class DJBrain:
    def __init__(self, client: LLMClient, persona: str):
        self.client = client
        self.persona = persona

    def _prompt(self, prev: Optional[Song], nxt: Song, budget: int) -> str:
        prev_line = (f'השיר שהתנגן עכשיו: "{prev.title}" של {prev.artist}.'
                     if prev else "זו פתיחת השידור.")
        return (
            f"אתה שדרן רדיו ישראלי בשם {self.persona}, אנרגטי וטבעי.\n"
            f"{prev_line}\n"
            f'השיר הבא: "{nxt.title}" של {nxt.artist}.\n'
            f"כתוב קטע מעבר קצר בעברית, עד {budget} מילים, שמכריז על השיר הבא. "
            f"החזר רק את הטקסט המדובר, בלי הסברים."
        )

    def write_intro(self, prev: Optional[Song], nxt: Song, seconds: float) -> str:
        budget = words_for_seconds(seconds)
        text = self.client.complete(self._prompt(prev, nxt, budget)).strip()
        words = text.split()
        if len(words) > budget:
            text = " ".join(words[:budget])
        return text
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_djbrain.py -v`
Expected: PASS (3 passed).

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/djbrain.py backend/tests/test_djbrain.py
git commit -m "feat: add minimal Hebrew DJBrain with word-budget trimming"
```

---

## Task 8: VoiceRenderer (ElevenLabs)

**Files:**
- Create: `backend/radioai/voice.py`
- Test: `backend/tests/test_voice.py`

The ElevenLabs call is isolated; the test injects a fake synth and asserts a
`DJSlot` is produced with the written bytes and a measured duration.

- [ ] **Step 1: Write the failing test**

`backend/tests/test_voice.py`:
```python
import os
import numpy as np
import soundfile as sf
from radioai.voice import VoiceRenderer


class _FakeSynth:
    """Returns 1 second of silence as wav bytes for any text."""
    def synth(self, text: str) -> bytes:
        import io
        buf = io.BytesIO()
        sf.write(buf, np.zeros(22050, dtype=np.float32), 22050, format="WAV")
        return buf.getvalue()


def test_render_creates_djslot(tmp_path):
    r = VoiceRenderer(synth=_FakeSynth(), out_dir=str(tmp_path))
    slot = r.render("שלום עולם")
    assert os.path.exists(slot.audio_path)
    assert slot.text == "שלום עולם"
    assert abs(slot.duration_s - 1.0) < 0.1
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_voice.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'radioai.voice'`.

- [ ] **Step 3: Write minimal implementation**

`backend/radioai/voice.py`:
```python
import os
import hashlib
from typing import Protocol
import soundfile as sf
from radioai.models import DJSlot


class Synth(Protocol):
    def synth(self, text: str) -> bytes: ...


class ElevenLabsSynth:
    """Thin wrapper around the ElevenLabs SDK; returns wav/mp3 bytes."""

    def __init__(self, api_key: str, voice_id: str,
                 model: str = "eleven_multilingual_v2"):
        from elevenlabs.client import ElevenLabs
        self._client = ElevenLabs(api_key=api_key)
        self._voice_id = voice_id
        self._model = model

    def synth(self, text: str) -> bytes:
        audio = self._client.text_to_speech.convert(
            voice_id=self._voice_id,
            model_id=self._model,
            text=text,
            output_format="mp3_44100_128",
        )
        return b"".join(audio)


class VoiceRenderer:
    def __init__(self, synth: Synth, out_dir: str):
        self.synth = synth
        self.out_dir = out_dir
        os.makedirs(out_dir, exist_ok=True)

    def render(self, text: str) -> DJSlot:
        data = self.synth.synth(text)
        h = hashlib.sha1(text.encode("utf-8")).hexdigest()[:16]
        path = os.path.join(self.out_dir, f"dj_{h}.wav")
        with open(path, "wb") as f:
            f.write(data)
        info = sf.info(path)
        return DJSlot(text=text, audio_path=path, duration_s=float(info.duration))
```

Note: ElevenLabs returns mp3 bytes; `soundfile` reads mp3 via libsndfile ≥1.1.
If `sf.info` fails on mp3 in your environment, set `output_format="pcm_44100"`
in `ElevenLabsSynth.synth` and write a `.wav`. The fake synth already uses wav,
so the unit test is unaffected.

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_voice.py -v`
Expected: PASS (1 passed).

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/voice.py backend/tests/test_voice.py
git commit -m "feat: add ElevenLabs VoiceRenderer producing DJSlot"
```

---

## Task 9: MixRenderer — beat-match, crossfade, duck DJ over music

**Files:**
- Create: `backend/radioai/mixrenderer.py`
- Test: `backend/tests/test_mixrenderer.py`

Audio DSP is tested on synthetic signals where the math is verifiable
(crossfade length, ducking attenuation, equal-power sum).

- [ ] **Step 1: Write the failing test**

`backend/tests/test_mixrenderer.py`:
```python
import numpy as np
from radioai.mixrenderer import (
    equal_power_crossfade, duck, time_stretch_to_bpm, SR,
)


def test_crossfade_output_length():
    sr = SR
    a = np.ones(sr * 3, dtype=np.float32)  # 3s
    b = np.ones(sr * 3, dtype=np.float32)  # 3s
    out = equal_power_crossfade(a, b, overlap_s=1.0)
    # total = 3 + 3 - 1 overlap = 5s
    assert abs(len(out) - sr * 5) <= 2


def test_crossfade_is_continuous_no_clipping():
    sr = SR
    a = np.ones(sr * 2, dtype=np.float32) * 0.8
    b = np.ones(sr * 2, dtype=np.float32) * 0.8
    out = equal_power_crossfade(a, b, overlap_s=1.0)
    assert np.max(np.abs(out)) <= 1.0001


def test_duck_attenuates_music_under_voice():
    sr = SR
    music = np.ones(sr * 4, dtype=np.float32) * 0.8
    voice = np.ones(sr * 2, dtype=np.float32) * 0.1  # low so combined < original
    out = duck(music, voice, start_s=1.0, attenuation_db=-7.0)
    # During the voiced region music should be quieter than before it.
    before = np.max(np.abs(out[: int(0.5 * sr)]))
    during = np.max(np.abs(out[int(1.5 * sr) : int(2.5 * sr)]))
    assert during < before


def test_time_stretch_changes_length_toward_target():
    sr = SR
    audio = np.random.uniform(-0.3, 0.3, sr * 4).astype(np.float32)
    stretched = time_stretch_to_bpm(audio, src_bpm=120, dst_bpm=140)
    # faster target -> shorter audio
    assert len(stretched) < len(audio)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_mixrenderer.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'radioai.mixrenderer'`.

- [ ] **Step 3: Write minimal implementation**

`backend/radioai/mixrenderer.py`:
```python
import numpy as np
import soundfile as sf
import librosa
import pyrubberband as pyrb

SR = 44100  # working sample rate


def load_mono(path: str) -> np.ndarray:
    y, _ = librosa.load(path, sr=SR, mono=True)
    return y.astype(np.float32)


def equal_power_crossfade(a: np.ndarray, b: np.ndarray,
                          overlap_s: float) -> np.ndarray:
    n = int(overlap_s * SR)
    n = min(n, len(a), len(b))
    if n <= 0:
        return np.concatenate([a, b])
    t = np.linspace(0, 1, n, dtype=np.float32)
    fade_out = np.cos(t * np.pi / 2)   # equal-power
    fade_in = np.cos((1 - t) * np.pi / 2)
    head = a[:-n]
    mixed = a[-n:] * fade_out + b[:n] * fade_in
    tail = b[n:]
    out = np.concatenate([head, mixed, tail])
    return np.clip(out, -1.0, 1.0)


def duck(music: np.ndarray, voice: np.ndarray, start_s: float,
         attenuation_db: float = -7.0, ramp_s: float = 0.3) -> np.ndarray:
    out = music.copy()
    start = int(start_s * SR)
    end = min(len(out), start + len(voice))
    gain = 10 ** (attenuation_db / 20.0)
    ramp = int(ramp_s * SR)
    # ramp down
    for i in range(start, min(start + ramp, end)):
        f = (i - start) / max(1, ramp)
        out[i] *= (1.0 - f) + f * gain
    # steady duck
    out[min(start + ramp, end):end] *= gain
    # overlay voice
    vlen = end - start
    out[start:end] += voice[:vlen]
    return np.clip(out, -1.0, 1.0)


def time_stretch_to_bpm(audio: np.ndarray, src_bpm: float,
                        dst_bpm: float) -> np.ndarray:
    if src_bpm <= 0 or dst_bpm <= 0:
        return audio
    rate = dst_bpm / src_bpm   # >1 = faster = shorter
    try:
        return pyrb.time_stretch(audio, SR, rate).astype(np.float32)
    except Exception:
        # rubberband CLI binary may be absent (e.g. Windows); fall back to
        # librosa's pure-Python phase vocoder (lower quality, no dependency).
        return librosa.effects.time_stretch(audio, rate=rate).astype(np.float32)


def write_mp3(path: str, audio: np.ndarray) -> None:
    wav_path = path.replace(".mp3", ".wav")
    sf.write(wav_path, np.clip(audio, -1.0, 1.0), SR)
    import subprocess
    subprocess.run(
        ["ffmpeg", "-y", "-i", wav_path, "-b:a", "192k", path],
        check=True, capture_output=True,
    )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_mixrenderer.py -v`
Expected: PASS (4 passed).

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/mixrenderer.py backend/tests/test_mixrenderer.py
git commit -m "feat: add MixRenderer DSP (crossfade, duck, time-stretch)"
```

---

## Task 10: render_show CLI — wire the vertical slice end-to-end

**Files:**
- Create: `backend/radioai/render_show.py`
- Test: manual integration (golden-ear)

This assembles the pipeline into one runnable command and is the **golden-ear**
quality gate. No unit test — it requires real API keys and produces audio to
*listen* to.

- [ ] **Step 1: Write the CLI**

`backend/radioai/render_show.py`:
```python
"""Render a 2-3 song show with one Hebrew DJ intro into a single MP3.

Usage:
    python -m radioai.render_show
"""
import os
import numpy as np
from radioai.config import Config
from radioai.models import Song
from radioai.fetcher import AudioFetcher
from radioai.analyzer import analyze
from radioai.mixplanner import choose_transition
from radioai.djbrain import DJBrain, AnthropicClient
from radioai.voice import VoiceRenderer, ElevenLabsSynth
from radioai import mixrenderer as mx

# Hardcoded setlist for M1 (replaced by Spotify+LLM in M2).
SETLIST = [
    Song(title="Tudo Bom", artist="Static & Ben El Tavori"),
    Song(title="Hofim", artist="Idan Raichel"),
    Song(title="Malkat Hayofi", artist="Eden Ben Zaken"),
]


def main() -> None:
    cfg = Config.from_env()
    fetcher = AudioFetcher(cache_dir=cfg.cache_dir)
    brain = DJBrain(
        client=AnthropicClient(api_key=cfg.anthropic_api_key),
        persona="רדיו AI",
    )
    voice = VoiceRenderer(
        synth=ElevenLabsSynth(api_key=cfg.elevenlabs_api_key,
                              voice_id=cfg.elevenlabs_voice_id),
        out_dir=os.path.join(cfg.cache_dir, "voice"),
    )

    print("Fetching + analyzing...")
    tracks = []
    for song in SETLIST:
        path = fetcher.fetch(song)
        tracks.append((song, analyze(path), mx.load_mono(path)))

    timeline = tracks[0][2]  # first song audio
    for i in range(1, len(tracks)):
        prev_song, prev_an, _ = tracks[i - 1]
        song, an, audio = tracks[i]

        # Put a DJ slot before the 2nd song only (M1 keeps it simple).
        has_dj = (i == 1)
        t = choose_transition(prev_an, an, has_dj=has_dj)
        print(f"  {prev_song.title} -> {song.title}: {t.type}")

        if t.type == "talkover":
            script = brain.write_intro(prev=prev_song, nxt=song, seconds=t.duration_s)
            print(f"    DJ: {script}")
            slot = voice.render(script)
            dj_audio = mx.load_mono(slot.audio_path)
            # Duck DJ over the previous outro, then hard-join into next song.
            duck_start = max(0.0, len(timeline) / mx.SR - slot.duration_s - 1.0)
            timeline = mx.duck(timeline, dj_audio, start_s=duck_start)
            timeline = np.concatenate([timeline, audio])
        elif t.type == "beatmatch":
            stretched = mx.time_stretch_to_bpm(audio, an.bpm, prev_an.bpm)
            timeline = mx.equal_power_crossfade(timeline, stretched, overlap_s=t.duration_s)
        elif t.type == "crossfade":
            timeline = mx.equal_power_crossfade(timeline, audio, overlap_s=t.duration_s)
        else:  # cut
            timeline = np.concatenate([timeline, audio])

    out = os.path.join(cfg.cache_dir, "show.mp3")
    mx.write_mp3(out, timeline)
    print(f"Done -> {out}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Create `.env` from the example and fill in real keys**

Copy `backend/.env.example` to `backend/.env` and set `ANTHROPIC_API_KEY`,
`ELEVENLABS_API_KEY`, `ELEVENLABS_VOICE_ID` (a Hebrew-capable voice).

- [ ] **Step 3: Run the full render**

Run from `backend/`:
```bash
python -m radioai.render_show
```
Expected: prints fetch/transition/DJ lines and writes `cache/show.mp3`.

- [ ] **Step 4: Golden-ear check (the real quality gate)**

Listen to `cache/show.mp3` and verify against the success criteria:
1. Song A plays, then the Hebrew DJ speaks clearly over the tail (music ducked).
2. The DJ intro is natural Hebrew and names the next song/artist.
3. The transition into song B is smooth (beat-matched/crossfaded, not jarring).
4. No clipping/stutter; the file plays start to finish.

If quality is off, the likely tuning points are: `_TALKOVER_SECONDS`/`_BLEND_SECONDS`
in `mixplanner.py`, ducking `attenuation_db` in `render_show.py`, and the DJ
prompt in `djbrain.py`. Adjust and re-run.

- [ ] **Step 5: Commit**

```bash
git add backend/radioai/render_show.py
git commit -m "feat: add render_show CLI wiring the M1 vertical slice end-to-end"
```

---

## Self-Review

**Spec coverage (M1 subset):**
- yt-dlp audio source → Task 6 ✓
- librosa analysis (BPM/key/beats/energy/vocal-onset) → Task 5 ✓
- Camelot harmonic logic → Task 2 ✓
- Compatibility scoring → Task 3 ✓
- Transition decision (talkover/beatmatch/crossfade/cut) → Task 4 ✓
- ElevenLabs Hebrew voice → Task 8 ✓
- LLM Hebrew DJ script + length budgeting → Task 7 ✓
- Mixing (crossfade, beat-match, duck) → Task 9 ✓
- End-to-end render + golden-ear gate → Task 10 ✓
- *Deferred to later milestones (correctly out of M1 scope):* Spotify taste (M2),
  setlist planning (M2), news/weather/topics/persona (M3), generate-ahead engine +
  HLS + web player (M4), mashups + EQ bass-swap + Camelot-driven blends (M5).

**Placeholder scan:** No TBD/TODO; every code step has complete code; every run
step has a command + expected result. ✓

**Type consistency:** `Song`, `TrackAnalysis`, `DJSlot`, `Transition`, `PlanItem`
defined in Task 1 and used consistently. `analyze()`, `choose_transition(prev,
nxt, has_dj)`, `DJBrain.write_intro(prev, nxt, seconds)`, `VoiceRenderer.render`,
`equal_power_crossfade`, `duck`, `time_stretch_to_bpm`, `SR` all match across
tasks. ✓
