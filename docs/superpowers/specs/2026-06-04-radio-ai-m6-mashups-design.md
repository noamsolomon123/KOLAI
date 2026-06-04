# Radio AI — M6: Mashups via Stem Separation — Design

**Date:** 2026-06-05
**Status:** Approved design (pre-implementation)
**Builds on:** M1–M5 (complete).

## 1. Goal

When two adjacent songs genuinely fit, perform a DJ **acapella-over-next** mashup:
the outgoing song's **vocal** (isolated via Demucs) rides the incoming song's
**instrumental** bed for ~8 bars, then resolves into the full incoming track.
Opportunistic and strictly gated — quality over quantity.

## 2. Scope

- **In:** Demucs stem separation (cached), a strict mashup gate, a `MashupRenderer`
  that builds the acapella-over-next segment, a new `mashup` transition type, render
  integration with an on/off flag, graceful fallback to M5 beatmatch.
- **Out:** full-section/produced mashups; pitch-shifting for key (gate avoids it);
  the web app/APK (**M4**).
- **All backend-side.** Heavy DSP/ML never runs on the phone (thin streaming client).

## 3. Key decisions

| Decision | Choice |
|----------|--------|
| Mashup style | **Acapella-over-next**: outgoing vocal over incoming instrumental for ~8 bars, then full incoming |
| Separation | **Demucs** `--two-stems=vocals` → `(vocals, no_vocals)`; cached to `cache/stems/` |
| Gate (strict) | Attempt only if `camelot_relation(prev, nxt) ∈ {same, relative}` **AND** BPM ratio ≤ 1.06 |
| Tempo/key | Tempo-match the acapella to the incoming BPM (rubberband); **no pitch-shift** (gate keeps keys compatible) |
| Window | 8 bars at the incoming track's tempo (`32 * 60/bpm` seconds, 4/4) |
| Control | `MASHUPS_ENABLED` env flag (default `true`) to skip the slow stem step for fast iteration |
| Resilience | Separation failure / empty stems / gate fail → fall back to M5 beatmatch (never crash) |
| Dependencies | `demucs` (pulls PyTorch) — the one heavy add; `rubberband` already installed |

Honest expectations: Demucs is a large install and **slow on CPU** (minutes/song,
cached after first run). With the strict gate, **mashups are rare** — many setlists
have no qualifying adjacent pair, so some renders produce **zero** mashups and just
use M5 transitions. That is by design.

## 4. Architecture

| File | Responsibility |
|------|----------------|
| `radioai/stems.py` | `StemSeparator` — `separate(path) -> (vocals_path, instrumental_path)`, cached; Demucs call isolated in one mockable method. Pure helper `stem_cache_paths(path, cache_dir)`. |
| `radioai/mashup.py` | `mashup_gate(prev, nxt) -> bool` (pure); `build_mashup(prev_vocals, nxt_instrumental, nxt_full, prev_bpm, nxt_bpm, nxt_beats, bars=8) -> np.ndarray` (pure audio assembly). |
| `radioai/mixplanner.py` | ADD `mashup` transition type, chosen (above beatmatch) when `mashup_gate` passes. |
| `radioai/render_show.py` | Handle the `mashup` transition: separate stems (cached), call `build_mashup`, splice in; flag + fallback. |
| `radioai/config.py` | ADD `mashups_enabled: bool` (`MASHUPS_ENABLED`, default true). |
| `radioai/analyzer.py` | unchanged (provides bpm, key_camelot, beat_times, outro_start_s). |

## 5. Stem separation (`stems.py`)

- `StemSeparator(cache_dir, separate_fn=None)`: `separate_fn` injectable for tests;
  default runs Demucs via subprocess (`python -m demucs --two-stems=vocals -o <tmp> <path>`)
  and locates the produced `vocals.wav` / `no_vocals.wav`.
- `separate(path)`:
  - cached: if `stem_cache_paths(path, cache_dir)` both exist, return them.
  - else run `separate_fn(path)`, copy/convert outputs to the cache paths
    (`cache/stems/<sha1(path)>_vocals.wav`, `_instrumental.wav`), return them.
- `stem_cache_paths(path, cache_dir)` is a pure function (tested): deterministic
  hashed filenames.

## 6. Mashup gate + builder (`mashup.py`)

- `mashup_gate(prev: TrackAnalysis, nxt: TrackAnalysis) -> bool`:
  `camelot_relation(prev.key_camelot, nxt.key_camelot) in {"same","relative"}`
  AND `max(prev.bpm,nxt.bpm)/min(prev.bpm,nxt.bpm) <= 1.06` (both bpm > 0).
- `build_mashup(prev_vocals, nxt_instrumental, nxt_full, prev_bpm, nxt_bpm, nxt_beats, bars=8, sr=SR)`:
  - `window = bars * 4 * 60/nxt_bpm` seconds (4 beats/bar).
  - Take the **tail** of `prev_vocals` (~window長, from its end) = the outgoing acapella;
    tempo-match it to `nxt_bpm` (reuse `mixrenderer.time_stretch_to_bpm`), then
    `start_on_beat` so it enters cleanly.
  - `bed = start_on_beat(nxt_instrumental, nxt_beats)[:window]` (incoming instrumental).
  - `seg1 = bed + acapella` (sum, length = window, clipped) — one vocal over one bed.
  - `seg2 = nxt_full[window:]` (the incoming song continues, full).
  - return `concatenate([seg1, seg2])`.
  - If any input is empty/too short, raise `ValueError` (caller falls back).

## 7. Render integration

- `MixPlanner.choose_transition`: if `mashup_gate(prev, nxt)` and not has_dj →
  `Transition(type="mashup", duration_s=window_seconds)`. (Gate already implies high
  tempo+key compatibility, so it sits above beatmatch.)
- `render_show` `mashup` branch (only if `cfg.mashups_enabled`):
  - `pv = StemSeparator(...).separate(prev_path)`; `ni = ...separate(song_path)`.
  - load vocals(prev), instrumental(nxt), full(nxt); `seg = build_mashup(...)`.
  - splice: short crossfade from current timeline into `seg` (reuse `bass_swap_crossfade`/`equal_power_crossfade`).
  - on ANY exception or if `mashups_enabled` is false → behave as a `beatmatch`
    (existing M5 path). Log which path was taken; record the mashup in `show_script.txt`.
  - Note: render must keep each track's downloaded file path (extend the `tracks`
    list to carry `path`) so stems can be separated.

## 8. Testing & success

- **TDD (pure):**
  - `mashup_gate` — same/relative + close BPM → True; clash key or far BPM → False; bpm≤0 guard.
  - `stem_cache_paths` — deterministic, distinct per input.
  - `StemSeparator.separate` — with an injected fake `separate_fn` + tmp cache: returns cached paths, and a second call does **not** re-invoke `separate_fn` (cache hit).
  - `build_mashup` — on synthetic arrays: output length = window + remainder of `nxt_full`; within the window both the acapella tone and the bed tone are present (sum); raises on empty input.
- **Integration (golden-ear):** real Demucs on a hand-picked **compatible pair** (same/relative key, ~equal BPM) to actually hear a mashup; confirm caching makes the 2nd run fast.
- **Success:** when a compatible pair occurs, the outgoing vocal rides the incoming
  bed for ~8 bars cleanly (one vocal, no double-bass), then resolves to full track;
  incompatible pairs silently use M5 transitions; nothing crashes.

## 9. Risks

- **Demucs install/size/speed:** large (PyTorch); slow on CPU. Mitigated by caching +
  the `MASHUPS_ENABLED` flag. First render of a new setlist with a mashup is slow.
- **Separation quality:** acapellas have bleed/artifacts → mashup may sound imperfect
  even when gated; acceptable for a POC, and rare by design.
- **Beat/section alignment:** `start_on_beat` + tempo-match is approximate; the acapella
  may not line up perfectly phrase-wise. Bounded to 8 bars to limit drift.
- **Memory/time on CI-less dev box:** fine on the 16 GB PC; never runs on the phone.
- **The lost-commit gotcha** persists — verify `git diff --ignore-all-space` after commits.
