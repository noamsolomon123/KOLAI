# Radio AI — M5: Deep Transitions (beat-aware blends + EQ bass-swap) — Design

**Date:** 2026-06-05
**Status:** Approved design (pre-implementation)
**Builds on:** M1–M3 (complete). Mashups via stem separation are deferred to **M6**.

## 1. Goal

Make song-to-song transitions sound like a real DJ set: incoming track enters on
its own downbeat, blend length snapped to whole beats, tempo-matched with
higher-quality stretching (rubberband), and an **EQ bass-swap** so two basslines
never clash. Blend depth is driven by **Camelot** harmonic relationship.

## 2. Scope

- **In:** beat-aware blend (incoming-on-downbeat + whole-beat overlap),
  `bass_swap_crossfade` (scipy filters), Camelot-driven blend depth, install
  `rubberband-cli` for better tempo-stretch.
- **Out:** mashups / stem separation (**M6**); generate-ahead engine + web/APK (**M4**).
- All work is **backend-side** (numpy/scipy/ffmpeg) — never runs on the phone; the
  future APK only streams the finished audio (heavy DSP stays on the server/PC).
- Honest non-goal: full global beat-grid lock across the accumulated timeline
  (hard once the running mix has been spliced repeatedly) — a future refinement.

## 3. Key decisions

| Decision | Choice |
|----------|--------|
| Beat alignment | Incoming track trimmed to start on its **first detected beat**; overlap length **snapped to a whole number of beats** at the outgoing tempo |
| Bass clash | **EQ bass-swap**: ~200 Hz crossover; incoming plays highs-only first, basses swap at the overlap midpoint (scipy Butterworth) |
| Blend depth | Camelot relation: same/relative → long blend; adjacent → medium; clash → short crossfade or cut |
| Tempo-stretch quality | Install **rubberband-cli**; `time_stretch_to_bpm` already prefers `pyrubberband` and falls back to librosa, so it auto-upgrades once the binary is present |
| Dependencies | `scipy` (already via librosa) + `rubberband-cli` binary (best-effort install) |

## 4. Architecture (changes only)

| File | Change |
|------|--------|
| `radioai/keys.py` | ADD `camelot_relation(a, b) -> "same"\|"relative"\|"adjacent"\|"clash"` (pure) |
| `radioai/mixrenderer.py` | ADD `start_on_beat`, `snap_overlap_to_beats`, `band_split`, `bass_swap_crossfade` |
| `radioai/mixplanner.py` | beatmatch `duration_s` chosen by Camelot relation (longer when harmonically compatible) |
| `radioai/render_show.py` | beatmatch branch: start incoming on its downbeat, snap overlap to beats, use `bass_swap_crossfade` |
| `radioai/analyzer.py` | unchanged (already provides `beat_times`, `key_camelot`) |

## 5. Beat-aware blend

- `start_on_beat(audio, beat_times, sr=SR)`: trim everything before `beat_times[0]`
  so the track enters on its first strong beat (downbeat). If no beats, return as-is.
- `snap_overlap_to_beats(overlap_s, bpm)`: round the requested overlap to the
  nearest whole number of beats (`beat = 60/bpm`), min 1 beat — so the blend spans
  whole beats and feels musical.
- Render flow for a beatmatch: stretch incoming to the outgoing BPM
  (`time_stretch_to_bpm`, now rubberband-backed), `start_on_beat` it, compute
  `overlap = snap_overlap_to_beats(t.duration_s, prev_bpm)`, then
  `bass_swap_crossfade(timeline, incoming, overlap)`.

## 6. EQ bass-swap

- `band_split(audio, crossover_hz=200, sr=SR) -> (low, high)` using `scipy.signal`
  Butterworth low-pass and high-pass (order 4), zero-phase (`filtfilt`).
- `bass_swap_crossfade(a, b, overlap_s, crossover_hz=200)`:
  - Equal-power crossfade of the **high** bands across the whole overlap.
  - **Low** bands: outgoing `a` keeps its bass for the **first half** of the overlap,
    then swaps to incoming `b`'s bass for the **second half** (short ramp at the swap).
  - Sum lows + highs; clip to [-1, 1]. Net effect: only ONE bassline at any moment.
- Falls back to a plain equal-power crossfade if `scipy` is unavailable (it isn't —
  ships with librosa) or overlap ≤ 0.

## 7. Camelot-driven blend depth

- `camelot_relation(a, b)`: `same` (equal), `relative` (same number, A↔B),
  `adjacent` (±1 mod 12, same letter), else `clash`.
- `MixPlanner.choose_transition`: when score selects a beatmatch, set
  `duration_s` = 12s for `same`/`relative`, 8s for `adjacent`, and fall to a
  shorter `crossfade` for `clash`. (Existing tests only assert `type`; the added
  duration logic keeps them green.)

## 8. Testing & success

- **TDD (deterministic DSP / pure):**
  - `camelot_relation` — same/relative/adjacent (incl. 12↔1 wrap)/clash.
  - `start_on_beat` — trims leading audio before the first beat; no-beats passthrough.
  - `snap_overlap_to_beats` — rounds to whole beats at a given BPM; min 1 beat.
  - `band_split` — low band has most energy for a low-freq sine; high band for a high-freq sine.
  - `bass_swap_crossfade` — output length correct; with `a`=low-freq tone and `b`=silence,
    low-frequency energy in the **second half** of the overlap is much lower than the **first half**
    (outgoing bass was cut); no clipping.
  - `mixplanner` — beatmatch `duration_s` longer for same/relative than adjacent.
- **Integration (golden-ear):** re-render the cached setlist; beatmatched transitions
  should sound on-beat and bass-clean. Confirm whether `rubberband` is active.
- **Success:** transitions are noticeably more seamless — on-beat entries, no muddy
  double-bass, harmonically deeper blends on compatible keys.

## 9. Risks

- **rubberband-cli on Windows:** no guaranteed winget package; install is best-effort
  (winget/choco/manual). If it fails, the librosa fallback still works — document and move on.
- **Beat detection noise:** `start_on_beat` depends on `beat_times[0]`; if the first
  detected beat is late, the trim could be large — guard by ignoring beats beyond a few seconds.
- **filtfilt edge effects** on very short overlaps — keep overlaps ≥ ~1s (snap min 1 beat).
- **Timeline grid drift:** full global beat-lock is out of scope (documented in §2).
