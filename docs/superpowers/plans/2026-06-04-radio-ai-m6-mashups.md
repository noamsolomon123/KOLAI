# Radio AI — M6: Mashups via Stem Separation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** When two adjacent songs are highly compatible (same/relative key + BPM within ~6%), perform an acapella-over-next mashup — the outgoing song's Demucs-isolated vocal rides the incoming song's instrumental for ~8 bars, then resolves to the full incoming track. Opportunistic, cached, with graceful fallback to the M5 beatmatch.

**Architecture:** Pure logic (`mashup_gate`, `build_mashup`, `stem_cache_paths`) is TDD-tested; Demucs is isolated behind an injectable `separate_fn` (mocked in tests); render-time decides a mashup when a beatmatch pair passes the gate and `MASHUPS_ENABLED`. All backend-side.

**Tech Stack:** Python 3.11+, `demucs` (PyTorch) for separation, numpy, soundfile, existing `mixrenderer`/`keys`. rubberband already installed.

---

## Environment notes (every task)
- Work from `C:\dev\RadioAI\backend`. Use venv python for ALL commands: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe`
- Prefer the PowerShell tool. If Write/Edit errors on content, use `[System.IO.File]::WriteAllText`.
- Repo `core.autocrlf=true` + prior crashes dropped commits. After each commit VERIFY `git diff --ignore-all-space --stat` is clean; re-add/commit if real changes remain; if files OTHER than yours show changed, do NOT touch them — report it.
- End commit messages with a blank line then: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- Baseline full suite: 98 passed.

## File Structure
```
backend/radioai/
  config.py     # MODIFY: mashups_enabled: bool
  mashup.py     # CREATE: mashup_gate, build_mashup
  stems.py      # CREATE: stem_cache_paths, StemSeparator
  render_show.py# MODIFY: carry path per track; beatmatch->mashup when gated; StemSeparator
backend/tests/
  test_config.py   # MODIFY
  test_mashup.py   # CREATE
  test_stems.py    # CREATE
```

---

## Task 1: Config mashups_enabled

**Files:** Modify `backend/radioai/config.py`, `backend/tests/test_config.py`

- [ ] **Step 1: Append failing test to `backend/tests/test_config.py`**
```python
def test_from_env_mashups_enabled_default_and_off(monkeypatch):
    monkeypatch.delenv("MASHUPS_ENABLED", raising=False)
    assert Config.from_env().mashups_enabled is True       # default on
    monkeypatch.setenv("MASHUPS_ENABLED", "false")
    assert Config.from_env().mashups_enabled is False
    monkeypatch.setenv("MASHUPS_ENABLED", "0")
    assert Config.from_env().mashups_enabled is False
```

- [ ] **Step 2: Run; confirm FAIL** (`AttributeError: ... 'mashups_enabled'`).
Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_config.py -v`

- [ ] **Step 3: Edit `backend/radioai/config.py`**
Add field at end of the `Config` dataclass (after `topics`):
```python
    mashups_enabled: bool = True
```
In `from_env`'s `return cls(...)`, add after `topics=...,`:
```python
            mashups_enabled=os.environ.get("MASHUPS_ENABLED", "true").lower()
            not in ("false", "0", "no", ""),
```

- [ ] **Step 4: Run tests; confirm PASS.** Full suite `...python.exe -m pytest -q` → 99 passed.

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/config.py backend/tests/test_config.py
git commit -m "feat: add mashups_enabled config flag"
```
Verify `git diff --ignore-all-space --stat` clean.

---

## Task 2: mashup_gate

**Files:** Create `backend/radioai/mashup.py`, `backend/tests/test_mashup.py`

- [ ] **Step 1: Write `backend/tests/test_mashup.py`**
```python
from dataclasses import dataclass
from radioai.mashup import mashup_gate


@dataclass
class _TA:
    bpm: float
    key_camelot: str


def test_gate_passes_same_key_close_bpm():
    assert mashup_gate(_TA(120, "8A"), _TA(123, "8A")) is True     # 2.5% bpm, same key


def test_gate_passes_relative_key():
    assert mashup_gate(_TA(120, "8A"), _TA(120, "8B")) is True     # relative


def test_gate_fails_far_bpm():
    assert mashup_gate(_TA(120, "8A"), _TA(140, "8A")) is False    # >6%


def test_gate_fails_clashing_key():
    assert mashup_gate(_TA(120, "8A"), _TA(121, "11A")) is False


def test_gate_fails_adjacent_key():
    assert mashup_gate(_TA(120, "8A"), _TA(121, "9A")) is False    # adjacent != same/relative


def test_gate_guards_zero_bpm():
    assert mashup_gate(_TA(0, "8A"), _TA(120, "8A")) is False
```

- [ ] **Step 2: Run; confirm FAIL** (`ModuleNotFoundError: radioai.mashup`).

- [ ] **Step 3: Write `backend/radioai/mashup.py`** (gate part)
```python
import numpy as np
from radioai.keys import camelot_relation
from radioai.mixrenderer import SR, time_stretch_to_bpm, start_on_beat

_MAX_BPM_RATIO = 1.06


def mashup_gate(prev, nxt) -> bool:
    """True only when a clean acapella-over-next mashup is feasible:
    same/relative key (no pitch shift) AND BPM within ~6% (gentle stretch)."""
    if prev.bpm <= 0 or nxt.bpm <= 0:
        return False
    if camelot_relation(prev.key_camelot, nxt.key_camelot) not in ("same", "relative"):
        return False
    ratio = max(prev.bpm, nxt.bpm) / min(prev.bpm, nxt.bpm)
    return ratio <= _MAX_BPM_RATIO
```

- [ ] **Step 4: Run tests; confirm PASS (6).** Full suite → 105 passed.

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/mashup.py backend/tests/test_mashup.py
git commit -m "feat: add mashup_gate (strict key+tempo compatibility)"
```
Verify `git diff --ignore-all-space --stat` clean.

---

## Task 3: build_mashup

**Files:** Modify `backend/radioai/mashup.py`, `backend/tests/test_mashup.py`

- [ ] **Step 1: Append failing tests to `backend/tests/test_mashup.py`**
```python
import numpy as np
from radioai.mixrenderer import SR


def test_build_mashup_overlay_then_full():
    from radioai.mashup import build_mashup
    bpm = 120
    voc = np.full(SR * 20, 0.3, dtype=np.float32)     # outgoing acapella
    inst = np.full(SR * 30, 0.2, dtype=np.float32)    # incoming instrumental bed
    full = np.full(SR * 30, 0.7, dtype=np.float32)    # incoming full track
    beats = [0.0, 0.5, 1.0]                           # first beat at 0 -> no trim
    out = build_mashup(voc, inst, full, bpm, bpm, beats, bars=8)
    window_n = int(8 * 4 * 60 / bpm * SR)             # 16s
    # during the window: bed(0.2) + acapella(0.3) = 0.5
    assert abs(float(np.mean(out[: window_n])) - 0.5) < 0.05
    # after the window: full incoming track (0.7)
    assert abs(float(np.mean(out[window_n + SR: window_n + 2 * SR])) - 0.7) < 0.05
    # total length ~ the full incoming track
    assert abs(len(out) - SR * 30) < SR * 0.2
    assert float(np.max(np.abs(out))) <= 1.0001


def test_build_mashup_empty_raises():
    from radioai.mashup import build_mashup
    import pytest
    with pytest.raises(ValueError):
        build_mashup(np.zeros(0, dtype=np.float32), np.ones(SR, dtype=np.float32),
                     np.ones(SR, dtype=np.float32), 120, 120, [0.0])
```

- [ ] **Step 2: Run; confirm FAIL** (`ImportError: cannot import name 'build_mashup'`).

- [ ] **Step 3: Append to `backend/radioai/mashup.py`**
```python
def build_mashup(prev_vocals, nxt_instrumental, nxt_full, prev_bpm, nxt_bpm,
                 nxt_beats, bars: int = 8, sr: int = SR):
    """Acapella-over-next: outgoing vocal (tempo-matched) over the incoming
    instrumental for `bars` bars, then the full incoming track continues."""
    if len(prev_vocals) == 0 or len(nxt_instrumental) == 0 or len(nxt_full) == 0:
        raise ValueError("empty stem input")
    if nxt_bpm <= 0:
        raise ValueError("invalid incoming bpm")

    window_n = int(bars * 4 * 60.0 / nxt_bpm * sr)  # 4 beats/bar

    acap = prev_vocals[-window_n:] if len(prev_vocals) >= window_n else prev_vocals
    acap = time_stretch_to_bpm(acap, prev_bpm, nxt_bpm)

    bed = start_on_beat(nxt_instrumental, nxt_beats)[:window_n]
    full = start_on_beat(nxt_full, nxt_beats)

    n = min(len(bed), len(acap))
    if n == 0:
        raise ValueError("mashup window empty")

    seg1 = np.clip(bed[:n] + acap[:n], -1.0, 1.0).astype(np.float32)
    seg2 = full[n:]
    return np.concatenate([seg1, seg2]).astype(np.float32)
```

- [ ] **Step 4: Run tests; confirm PASS (2 new).** Full suite → 107 passed.

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/mashup.py backend/tests/test_mashup.py
git commit -m "feat: add build_mashup (acapella over incoming instrumental)"
```
Verify `git diff --ignore-all-space --stat` clean.

---

## Task 4: StemSeparator (Demucs, cached)

**Files:** Create `backend/radioai/stems.py`, `backend/tests/test_stems.py`

- [ ] **Step 1: Write `backend/tests/test_stems.py`**
```python
import os
import numpy as np
import soundfile as sf
from radioai.stems import stem_cache_paths, StemSeparator


def test_stem_cache_paths_deterministic_and_distinct(tmp_path):
    v1, i1 = stem_cache_paths("a.mp3", str(tmp_path))
    v2, i2 = stem_cache_paths("a.mp3", str(tmp_path))
    v3, i3 = stem_cache_paths("b.mp3", str(tmp_path))
    assert (v1, i1) == (v2, i2)   # deterministic
    assert v1 != i1               # vocals != instrumental
    assert v1 != v3               # per-input


def test_separate_writes_cache_and_reuses(tmp_path):
    calls = {"n": 0}

    def fake_separate(path):
        calls["n"] += 1
        src = tmp_path / "src"
        src.mkdir(exist_ok=True)
        v = str(src / "vocals.wav")
        i = str(src / "no_vocals.wav")
        sf.write(v, np.zeros(1000, dtype="float32"), 44100)
        sf.write(i, np.zeros(1000, dtype="float32"), 44100)
        return v, i

    sep = StemSeparator(str(tmp_path), separate_fn=fake_separate)
    voc, inst = sep.separate(str(tmp_path / "song.mp3"))
    assert os.path.exists(voc) and os.path.exists(inst)
    assert calls["n"] == 1
    # second call: cache hit, fake not invoked again
    voc2, inst2 = sep.separate(str(tmp_path / "song.mp3"))
    assert (voc2, inst2) == (voc, inst)
    assert calls["n"] == 1
```

- [ ] **Step 2: Run; confirm FAIL** (`ModuleNotFoundError: radioai.stems`).

- [ ] **Step 3: Write `backend/radioai/stems.py`**
```python
import os
import glob
import shutil
import hashlib
import tempfile
import subprocess
import sys


def stem_cache_paths(path: str, cache_dir: str):
    """Deterministic cache file paths for a song's (vocals, instrumental)."""
    h = hashlib.sha1(os.path.abspath(path).encode("utf-8")).hexdigest()[:16]
    d = os.path.join(cache_dir, "stems")
    return (os.path.join(d, f"{h}_vocals.wav"),
            os.path.join(d, f"{h}_instrumental.wav"))


class StemSeparator:
    """Separates a song into (vocals, instrumental), cached to disk. The actual
    separation is injectable via `separate_fn(path) -> (vocals_path, instrumental_path)`;
    the default shells out to Demucs (--two-stems=vocals)."""

    def __init__(self, cache_dir: str, separate_fn=None):
        self.cache_dir = cache_dir
        self._separate = separate_fn or self._demucs_separate

    def separate(self, path: str):
        voc, inst = stem_cache_paths(path, self.cache_dir)
        if os.path.exists(voc) and os.path.exists(inst):
            return voc, inst
        os.makedirs(os.path.dirname(voc), exist_ok=True)
        src_voc, src_inst = self._separate(path)
        shutil.copyfile(src_voc, voc)
        shutil.copyfile(src_inst, inst)
        return voc, inst

    def _demucs_separate(self, path: str):
        out_dir = tempfile.mkdtemp(prefix="demucs_")
        subprocess.run(
            [sys.executable, "-m", "demucs", "--two-stems=vocals",
             "-o", out_dir, path],
            check=True, capture_output=True,
        )
        vocals = glob.glob(os.path.join(out_dir, "**", "vocals.wav"), recursive=True)
        no_vocals = glob.glob(os.path.join(out_dir, "**", "no_vocals.wav"), recursive=True)
        if not vocals or not no_vocals:
            raise RuntimeError(f"Demucs produced no stems for {path}")
        return vocals[0], no_vocals[0]
```

- [ ] **Step 4: Run tests; confirm PASS (2).** Full suite → 109 passed.

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/stems.py backend/tests/test_stems.py
git commit -m "feat: add cached StemSeparator (Demucs two-stems, injectable)"
```
Verify `git diff --ignore-all-space --stat` clean.

---

## Task 5: Render integration + install Demucs + golden-ear

**Files:** Modify `backend/radioai/render_show.py`. (Integration; pure pieces already tested. Controller runs the golden-ear.)

- [ ] **Step 1: Edit `backend/radioai/render_show.py`** (READ it first)

(a) Add imports with the other `from radioai...` imports:
```python
from radioai.stems import StemSeparator
from radioai.mashup import mashup_gate, build_mashup
```

(b) The fetch/analyze loop currently builds `tracks` as `(song, analyze(path), mx.load_mono(path))`. Change it to ALSO carry the file path. Find:
```python
    for song in setlist:
        try:
            path = fetcher.fetch(song)
            tracks.append((song, analyze(path), mx.load_mono(path)))
        except Exception as e:
            print(f"  [skip] {song.title} — {song.artist}: {e}")
```
Replace the append line so each tuple is `(song, analysis, audio, path)`:
```python
    for song in setlist:
        try:
            path = fetcher.fetch(song)
            tracks.append((song, analyze(path), mx.load_mono(path), path))
        except Exception as e:
            print(f"  [skip] {song.title} — {song.artist}: {e}")
```

(c) The loop unpacks `prev_song, prev_an, _ = tracks[i - 1]` and `song, an, audio = tracks[i]`. Update BOTH unpackings to include the path:
```python
        prev_song, prev_an, _, prev_path = tracks[i - 1]
        song, an, audio, song_path = tracks[i]
```

(d) Create a stem separator once, right after `voice = VoiceRenderer(...)` is built:
```python
    separator = StemSeparator(cache_dir=cfg.cache_dir)
```

(e) Replace the entire `elif t.type == "beatmatch":` branch with a mashup-or-beatmatch version:
```python
        elif t.type == "beatmatch":
            did_mashup = False
            if cfg.mashups_enabled and mashup_gate(prev_an, an):
                try:
                    print(f"    [mashup] {prev_song.title} x {song.title} (separating stems...)")
                    pv, _pi = separator.separate(prev_path)     # outgoing vocals
                    _nv, ni = separator.separate(song_path)     # incoming instrumental
                    seg = build_mashup(mx.load_mono(pv), mx.load_mono(ni), audio,
                                       prev_an.bpm, an.bpm, an.beat_times)
                    timeline = mx.equal_power_crossfade(timeline, seg, overlap_s=2.0)
                    dj_lines.append(f"[mashup: {prev_song.title} x {song.title}]")
                    did_mashup = True
                except Exception as e:
                    print(f"    [mashup-fallback] {e}")
            if not did_mashup:
                on_beat = mx.start_on_beat(audio, an.beat_times)
                stretched = mx.time_stretch_to_bpm(on_beat, an.bpm, prev_an.bpm)
                overlap = mx.snap_overlap_to_beats(t.duration_s, prev_an.bpm)
                timeline = mx.bass_swap_crossfade(timeline, stretched, overlap_s=overlap)
```
Leave the talkover/crossfade/cut branches unchanged. (`dj_lines` already exists.)

- [ ] **Step 2: Sanity import + full suite**
Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -c "import radioai.render_show"` → no error.
Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest -q` → 109 passed (no regressions).

- [ ] **Step 3: Commit**
```bash
git add backend/radioai/render_show.py
git commit -m "feat: render-time mashup on gated beatmatch pairs (fallback to M5)"
```
Verify `git diff --ignore-all-space --stat` clean.

- [ ] **Step 4: Install Demucs** (heavy — PyTorch). Run from `backend/`:
```
C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pip install demucs
```
Then verify it imports / CLI exists:
```
C:\dev\RadioAI\backend\.venv\Scripts\python.exe -c "import demucs; print('demucs ok')"
```
If install fails (e.g. torch wheel issues), report the exact error as DONE_WITH_CONCERNS — the code still falls back to beatmatch when separation fails, so the app keeps working. Do NOT block.

- [ ] **Step 5: STOP — do not run the full render.** The controller will run a golden-ear with a hand-picked compatible pair.

## Controller golden-ear (run by controller after Task 5)
Pick two compatible songs (same/relative Camelot key + BPM within 6%) so the gate fires, set them as a temporary pinned setlist (via `DEMO_SETLIST` / `MASHUPS_ENABLED=true`), render, and listen for: outgoing vocal riding the incoming bed for ~8 bars, then the full incoming track. Confirm stems get cached (2nd run fast).

---

## Self-Review

**Spec coverage:**
- Demucs separation, cached, injectable → Task 4 ✓
- Strict mashup gate → Task 2 ✓
- Acapella-over-next builder → Task 3 ✓
- MASHUPS_ENABLED flag → Task 1 ✓
- Render integration + fallback to M5 beatmatch → Task 5 ✓ (mashup decided at render-time on a gated beatmatch pair — cleaner than a new MixPlanner type; documented deviation)
- Backend-only / heavy dep isolated → Tasks 4-5 ✓; golden-ear → controller step ✓

**Placeholder scan:** No TBD/TODO; every code step has full code; run steps have commands + expected counts. The Demucs install is explicitly best-effort with a defined fallback. ✓

**Type consistency:** `mashup_gate(prev, nxt)->bool` (T2) used in render (T5). `build_mashup(prev_vocals, nxt_instrumental, nxt_full, prev_bpm, nxt_bpm, nxt_beats, bars, sr)` (T3) called identically in render (T5) with `mx.load_mono(pv)`, `mx.load_mono(ni)`, `audio`, `prev_an.bpm`, `an.bpm`, `an.beat_times`. `StemSeparator(cache_dir, separate_fn).separate(path)->(voc,inst)` (T4) used in render (T5). `stem_cache_paths(path, cache_dir)` consistent. `cfg.mashups_enabled` (T1) read in render (T5). `tracks` tuples extended to 4 elements with both unpackings updated (T5). Reuses `mx.time_stretch_to_bpm`, `mx.start_on_beat`, `mx.equal_power_crossfade`, `mx.bass_swap_crossfade`, `mx.snap_overlap_to_beats`, `mx.load_mono`, `mx.SR`, `camelot_relation` — all existing. ✓
