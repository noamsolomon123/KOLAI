# Radio AI — M5: Deep Transitions — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make beatmatched transitions pro-grade — incoming song enters on its downbeat, blend snapped to whole beats, tempo-matched (rubberband), with an EQ bass-swap so two basslines never clash, and blend depth driven by Camelot harmony.

**Architecture:** Pure helpers added to `keys.py` (Camelot relation) and `mixrenderer.py` (start_on_beat, snap_overlap_to_beats, band_split, bass_swap_crossfade), tested deterministically on synthetic signals; `mixplanner` picks blend length by harmony; `render_show`'s beatmatch branch wires them together. All backend DSP — never runs on the phone.

**Tech Stack:** Python 3.11+, numpy, `scipy.signal` (ships with librosa), ffmpeg, optional `rubberband-cli`. Existing modules reused.

---

## Environment notes (every task)
- Work from `C:\dev\RadioAI\backend`. Use venv python for ALL commands: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe`
- Run tests with that interpreter (e.g. `...\.venv\Scripts\python.exe -m pytest tests/<file> -v`).
- Prefer the PowerShell tool. Hebrew (if any) is UTF-8; if Write/Edit errors on it, use `[System.IO.File]::WriteAllText`.
- Repo has `core.autocrlf=true` and a prior crash dropped commits. After each commit, VERIFY with `git diff --ignore-all-space --stat` that nothing real is uncommitted; re-add/commit if so. If files OTHER than the ones you touched show as changed, do NOT touch them — report it.
- End every commit message with a blank line then: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- Current full-suite baseline: 87 passed.

## File Structure
```
backend/radioai/
  keys.py          # MODIFY: add camelot_relation
  mixplanner.py    # MODIFY: beatmatch duration by Camelot relation
  mixrenderer.py   # MODIFY: add start_on_beat, snap_overlap_to_beats, band_split, bass_swap_crossfade
  render_show.py   # MODIFY: beatmatch branch uses the new helpers
backend/tests/
  test_keys.py        # MODIFY
  test_mixplanner.py  # MODIFY
  test_mixrenderer.py # MODIFY
```

---

## Task 1: Camelot relation

**Files:** Modify `backend/radioai/keys.py`, `backend/tests/test_keys.py`

- [ ] **Step 1: Append failing tests to `backend/tests/test_keys.py`**
```python
from radioai.keys import camelot_relation


def test_camelot_relation_same():
    assert camelot_relation("8A", "8A") == "same"


def test_camelot_relation_relative():
    assert camelot_relation("8A", "8B") == "relative"


def test_camelot_relation_adjacent_and_wrap():
    assert camelot_relation("8A", "9A") == "adjacent"
    assert camelot_relation("8A", "7A") == "adjacent"
    assert camelot_relation("12A", "1A") == "adjacent"


def test_camelot_relation_clash():
    assert camelot_relation("8A", "11A") == "clash"
    assert camelot_relation("8A", "3B") == "clash"
```

- [ ] **Step 2: Run; confirm FAIL** (`ImportError: cannot import name 'camelot_relation'`).
Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_keys.py -v`

- [ ] **Step 3: Append to `backend/radioai/keys.py`** (reuses the existing `_parse` helper):
```python
def camelot_relation(a: str, b: str) -> str:
    """Classify the harmonic relationship between two Camelot codes."""
    na, la = _parse(a)
    nb, lb = _parse(b)
    if a == b:
        return "same"
    if na == nb and la != lb:
        return "relative"
    if la == lb:
        diff = abs(na - nb)
        if diff == 1 or diff == 11:
            return "adjacent"
    return "clash"
```

- [ ] **Step 4: Run tests; confirm PASS.** Full suite `...python.exe -m pytest -q` → 91 passed.

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/keys.py backend/tests/test_keys.py
git commit -m "feat: add camelot_relation (same/relative/adjacent/clash)"
```
Verify `git diff --ignore-all-space --stat` clean.

---

## Task 2: MixPlanner blend depth by harmony

**Files:** Modify `backend/radioai/mixplanner.py`, `backend/tests/test_mixplanner.py`

- [ ] **Step 1: Append failing test to `backend/tests/test_mixplanner.py`** (reuses the file's existing `_track` helper):
```python
def test_beatmatch_duration_deeper_for_compatible_keys():
    a = _track(120, "8A", 0.6)
    same = choose_transition(a, _track(122, "8A", 0.6), has_dj=False)
    adjacent = choose_transition(a, _track(122, "9A", 0.6), has_dj=False)
    assert same.type == "beatmatch"
    assert adjacent.type == "beatmatch"
    assert same.duration_s > adjacent.duration_s   # same key blends longer
```

- [ ] **Step 2: Run; confirm FAIL** (same.duration_s == adjacent.duration_s == 8.0 currently).
Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest tests/test_mixplanner.py -v`

- [ ] **Step 3: Edit `backend/radioai/mixplanner.py`**
Add an import at the top (with the existing imports):
```python
from radioai.keys import camelot_relation
```
In `choose_transition`, replace the beatmatch return branch. The current code is:
```python
    if score >= _BEATMATCH_MIN:
        return Transition(type="beatmatch", duration_s=_BLEND_SECONDS)
```
Replace it with:
```python
    if score >= _BEATMATCH_MIN:
        relation = camelot_relation(prev.key_camelot, nxt.key_camelot)
        dur = 12.0 if relation in ("same", "relative") else _BLEND_SECONDS
        return Transition(type="beatmatch", duration_s=dur)
```
(`_BLEND_SECONDS` is the existing 8.0 constant; leave it and the crossfade/cut branches unchanged.)

- [ ] **Step 4: Run tests; confirm PASS** (existing mixplanner tests still pass — they assert `type`, which is unchanged). Full suite → 92 passed.

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/mixplanner.py backend/tests/test_mixplanner.py
git commit -m "feat: deeper beatmatch blend for harmonically compatible keys"
```
Verify `git diff --ignore-all-space --stat` clean.

---

## Task 3: start_on_beat + snap_overlap_to_beats

**Files:** Modify `backend/radioai/mixrenderer.py`, `backend/tests/test_mixrenderer.py`

- [ ] **Step 1: Append failing tests to `backend/tests/test_mixrenderer.py`** (file already imports `numpy as np` and `SR`):
```python
def test_start_on_beat_trims_to_first_beat():
    from radioai.mixrenderer import start_on_beat
    sr = SR
    audio = np.ones(sr * 4, dtype=np.float32)
    out = start_on_beat(audio, [0.5, 1.0, 1.5], sr=sr)
    assert abs(len(out) - sr * 3.5) <= 2   # trimmed first 0.5s


def test_start_on_beat_no_beats_passthrough():
    from radioai.mixrenderer import start_on_beat
    audio = np.ones(100, dtype=np.float32)
    assert len(start_on_beat(audio, [], sr=SR)) == 100


def test_start_on_beat_ignores_late_first_beat():
    from radioai.mixrenderer import start_on_beat
    audio = np.ones(SR * 2, dtype=np.float32)
    # first beat at 9s (beyond max_skip) -> do not trim
    assert len(start_on_beat(audio, [9.0], sr=SR)) == SR * 2


def test_snap_overlap_to_beats():
    from radioai.mixrenderer import snap_overlap_to_beats
    assert abs(snap_overlap_to_beats(4.8, 120) - 5.0) < 1e-6   # 0.5s/beat -> 10 beats
    assert abs(snap_overlap_to_beats(0.1, 120) - 0.5) < 1e-6   # min 1 beat
```

- [ ] **Step 2: Run; confirm FAIL** (`ImportError: cannot import name 'start_on_beat'`).

- [ ] **Step 3: Append to `backend/radioai/mixrenderer.py`**
```python
def start_on_beat(audio: np.ndarray, beat_times, sr: int = SR,
                  max_skip_s: float = 4.0) -> np.ndarray:
    """Trim leading audio so the track starts on its first detected beat.
    No beats, or a first beat beyond max_skip_s -> returned unchanged."""
    if not beat_times:
        return audio
    first = beat_times[0]
    if first <= 0 or first > max_skip_s:
        return audio
    start = int(first * sr)
    return audio[start:] if start < len(audio) else audio


def snap_overlap_to_beats(overlap_s: float, bpm: float) -> float:
    """Round an overlap length to a whole number of beats (min 1) at `bpm`."""
    if bpm <= 0:
        return overlap_s
    beat = 60.0 / bpm
    n = max(1, round(overlap_s / beat))
    return n * beat
```

- [ ] **Step 4: Run tests; confirm PASS (4).** Full suite → 96 passed.

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/mixrenderer.py backend/tests/test_mixrenderer.py
git commit -m "feat: add start_on_beat and snap_overlap_to_beats"
```
Verify `git diff --ignore-all-space --stat` clean.

---

## Task 4: EQ band split + bass_swap_crossfade

**Files:** Modify `backend/radioai/mixrenderer.py`, `backend/tests/test_mixrenderer.py`

- [ ] **Step 1: Append failing tests to `backend/tests/test_mixrenderer.py`**
```python
def _sine(freq, seconds, sr):
    t = np.linspace(0, seconds, int(seconds * sr), endpoint=False, dtype=np.float32)
    return (0.5 * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def test_band_split_separates_low_and_high():
    from radioai.mixrenderer import band_split
    low_tone = _sine(60, 1.0, SR)     # below 200 Hz crossover
    lo, hi = band_split(low_tone, crossover_hz=200)
    assert np.mean(lo ** 2) > np.mean(hi ** 2) * 5   # energy mostly in low band

    high_tone = _sine(4000, 1.0, SR)
    lo2, hi2 = band_split(high_tone, crossover_hz=200)
    assert np.mean(hi2 ** 2) > np.mean(lo2 ** 2) * 5


def test_bass_swap_crossfade_length_and_swap():
    from radioai.mixrenderer import bass_swap_crossfade
    a = _sine(80, 4.0, SR)                       # outgoing: a bassline
    b = np.zeros(SR * 4, dtype=np.float32)       # incoming: silent
    out = bass_swap_crossfade(a, b, overlap_s=2.0)
    assert abs(len(out) - SR * 6) <= 4           # 4 + 4 - 2 overlap
    assert np.max(np.abs(out)) <= 1.0001         # no clipping
    # within the overlap, outgoing bass is cut in the second half
    n = SR * 2
    head = len(out) - n - (SR * 4 - n)           # = len(a)-n
    ov = out[len(a) - n: len(a)]
    first_half = np.mean(ov[: n // 2] ** 2)
    second_half = np.mean(ov[n // 2:] ** 2)
    assert second_half < first_half * 0.5        # bass swapped away to (silent) b
```

- [ ] **Step 2: Run; confirm FAIL** (`ImportError: cannot import name 'band_split'`).

- [ ] **Step 3: Append to `backend/radioai/mixrenderer.py`**
Add this import near the top of the file (with the existing imports):
```python
from scipy.signal import butter, filtfilt
```
Then append:
```python
def band_split(audio: np.ndarray, crossover_hz: float = 200.0,
               sr: int = SR):
    """Split audio into (low, high) bands at crossover_hz (zero-phase Butterworth)."""
    wc = crossover_hz / (0.5 * sr)
    bl, al = butter(4, wc, btype="low")
    bh, ah = butter(4, wc, btype="high")
    low = filtfilt(bl, al, audio).astype(np.float32)
    high = filtfilt(bh, ah, audio).astype(np.float32)
    return low, high


def bass_swap_crossfade(a: np.ndarray, b: np.ndarray, overlap_s: float,
                        crossover_hz: float = 200.0) -> np.ndarray:
    """Equal-power crossfade where the bass is swapped at the overlap midpoint,
    so only one bassline plays at a time (no muddy double-bass)."""
    n = int(overlap_s * SR)
    n = min(n, len(a), len(b))
    if n <= 0:
        return np.concatenate([a, b])

    a_low, a_high = band_split(a[-n:], crossover_hz)
    b_low, b_high = band_split(b[:n], crossover_hz)

    t = np.linspace(0, 1, n, dtype=np.float32)
    fade_out = np.cos(t * np.pi / 2)
    fade_in = np.cos((1 - t) * np.pi / 2)
    high_mix = a_high * fade_out + b_high * fade_in

    half = n // 2
    low_mix = np.where(np.arange(n) < half, a_low, b_low).astype(np.float32)
    # short equal-power ramp around the swap point to avoid a click
    r = min(int(0.05 * SR), half, n - half)
    if r > 0:
        seg = np.linspace(0, 1, 2 * r, dtype=np.float32)
        fo2 = np.cos(seg * np.pi / 2)
        fi2 = np.cos((1 - seg) * np.pi / 2)
        s0 = half - r
        low_mix[s0:s0 + 2 * r] = a_low[s0:s0 + 2 * r] * fo2 + b_low[s0:s0 + 2 * r] * fi2

    mixed = high_mix + low_mix
    out = np.concatenate([a[:-n], mixed, b[n:]])
    return np.clip(out, -1.0, 1.0)
```

- [ ] **Step 4: Run tests; confirm PASS (2).** Full suite → 98 passed.

- [ ] **Step 5: Commit**
```bash
git add backend/radioai/mixrenderer.py backend/tests/test_mixrenderer.py
git commit -m "feat: add EQ band split and bass_swap_crossfade"
```
Verify `git diff --ignore-all-space --stat` clean.

---

## Task 5: Wire into render + rubberband + golden-ear

**Files:** Modify `backend/radioai/render_show.py`

This is integration (the helpers are already unit-tested). I will run the golden-ear render myself after the commit.

- [ ] **Step 1: Edit the beatmatch branch in `backend/radioai/render_show.py`**
Find the current beatmatch branch in `main()`:
```python
        elif t.type == "beatmatch":
            stretched = mx.time_stretch_to_bpm(audio, an.bpm, prev_an.bpm)
            timeline = mx.equal_power_crossfade(timeline, stretched, overlap_s=t.duration_s)
```
Replace it with:
```python
        elif t.type == "beatmatch":
            on_beat = mx.start_on_beat(audio, an.beat_times)   # enter on downbeat
            stretched = mx.time_stretch_to_bpm(on_beat, an.bpm, prev_an.bpm)
            overlap = mx.snap_overlap_to_beats(t.duration_s, prev_an.bpm)
            timeline = mx.bass_swap_crossfade(timeline, stretched, overlap_s=overlap)
```
Leave the talkover/crossfade/cut branches unchanged.

- [ ] **Step 2: Sanity import + full suite**
Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -c "import radioai.render_show"` → no error.
Run: `C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m pytest -q` → 98 passed (no regressions).

- [ ] **Step 3: Commit**
```bash
git add backend/radioai/render_show.py
git commit -m "feat: beatmatch uses on-beat entry, beat-snapped overlap, bass-swap"
```
Verify `git diff --ignore-all-space --stat` clean.

- [ ] **Step 4: Best-effort install rubberband-cli** (improves tempo-stretch; librosa fallback already works)
Try, in order, and stop at the first that makes `rubberband` available on PATH:
1. `choco install rubberband -y` (if `choco` exists)
2. `winget install --id=Breakfastquay.Rubberband -e` (id may not exist; ignore failure)
3. Manual: download the official Windows executable zip from https://breakfastquay.com/rubberband/ , extract `rubberband.exe` to `C:\dev\RadioAI\tools\rubberband\`, and add that dir to the user PATH (`[Environment]::SetEnvironmentVariable("Path", $env:Path + ";C:\dev\RadioAI\tools\rubberband", "User")`).
Verify with `rubberband --version` (or `C:\dev\RadioAI\tools\rubberband\rubberband.exe --version`).
If NONE succeed, that is acceptable — report it; `time_stretch_to_bpm` keeps using the librosa fallback. Do NOT block on this.

- [ ] **Step 5: STOP — do not run the full render.** Report status; the controller will run the golden-ear render (cached Spotify taste → no browser).

## Controller golden-ear (run after Task 5, by the controller — not in the plan tasks)
```
Remove-Item C:\dev\RadioAI\backend\cache\show.mp3 -Force -ErrorAction SilentlyContinue
Remove-Item C:\dev\RadioAI\backend\cache\voice\*.wav -Force -ErrorAction SilentlyContinue
C:\dev\RadioAI\backend\.venv\Scripts\python.exe -m radioai.render_show
```
Verify `ffprobe` duration; listen for on-beat, bass-clean beatmatched transitions; note whether rubberband is active.

---

## Self-Review

**Spec coverage:**
- Camelot relation → Task 1 ✓
- Camelot-driven blend depth → Task 2 ✓
- start_on_beat + snap_overlap_to_beats (beat-aware blend) → Task 3 ✓
- band_split + bass_swap_crossfade (EQ bass-swap) → Task 4 ✓
- render integration (on-beat entry, snapped overlap, bass-swap) → Task 5 Steps 1-3 ✓
- rubberband install (best-effort) → Task 5 Step 4 ✓
- Testing deterministic on synthetic signals → Tasks 1-4 ✓; golden-ear → controller step ✓
- Backend-only / no heavy deps (scipy via librosa) ✓

**Placeholder scan:** No TBD/TODO; every code step has complete code; run steps have commands + expected counts. The rubberband install lists concrete commands (best-effort by design, with explicit acceptable-failure). ✓

**Type consistency:** `camelot_relation(a,b)->str` (T1) used in mixplanner (T2). `start_on_beat(audio, beat_times, sr, max_skip_s)`, `snap_overlap_to_beats(overlap_s, bpm)`, `band_split(audio, crossover_hz, sr)->(low,high)`, `bass_swap_crossfade(a, b, overlap_s, crossover_hz)` (T3/T4) all called identically in render (T5). `prev.key_camelot`/`nxt.key_camelot`, `an.beat_times`, `an.bpm` match the existing `TrackAnalysis` fields. `_BLEND_SECONDS`/`_BEATMATCH_MIN` are existing mixplanner constants. `mx.time_stretch_to_bpm`, `mx.equal_power_crossfade`, `mx.SR` unchanged. ✓
