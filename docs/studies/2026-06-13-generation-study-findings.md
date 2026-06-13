# KOLAI Generation Study — Consolidated Findings (2026-06-13)

Corpus: 250 simulated airings via the REAL engine (TastePoolPlanner + RollingPlanner
+ DeezerDiscovery + DjBrain/Gemini), swept across 5 moods + dayparts + somber/recap.
Per-dimension detail: findings-diversity.md, findings-djtext.md, findings-selection.md,
findings-bpm.md. This file is the executive summary + prioritized roadmap.

## 1. DJ output diversity (user's #1: "close results")
- ROOT CAUSE: GeminiTextClient.complete() sends NO generationConfig at all — no
  temperature/topP. Identical-ish prompts collapse to one phrasing.
- Prompt skeleton ~95% fixed; only 5 flavor nudges; anti-repetition ring = last 3 of 8
  lines (60-char trunc) — too small to stop reuse over a long broadcast.
- Measured: 75% of scripts share their 1st word, 47% share first two; recurring songs
  regenerate near-identical intros (top pair cosine 0.68); tics: הגיע הזמן 38x, אז בוא
  28x, תגביר את הווליום 10x; mood barely changes wording (delta +0.02 ≈ noise).
- FIX: temperature ~1.15 (0.4 for JSON/refine); rotate 12 distinct prompt ANGLES (not a
  one-line nudge); optional naming/wit to vary STRUCTURE; banned-cliché list; ring
  8->24, avoid 3->8 + long-lived opener set; per-mood register.

## 2. DJ text bugs
- HIGH — DANGLING NAME: 14.9% (26/174) of naming scripts ship with the song name
  MISSING, leaving "עם של" / a bare של/עם/ה- before TTS. The title substitution fails.
  FIX: post-gen validator rejects/regenerates any naming line ending in a connector or
  containing "עם של"/empty-name.
- MEDIUM — beat selection appears HARD-GATED to "mix": banter/trivia/handover NEVER
  fire in party/focus/late_night/morning in the corpus. Needs investigation — likely a
  real gating bug (moods define banterChance but beats only fire in mix).
- MEDIUM — plural-address: a few genuine violations in trivia/handover (אנשים/כולם/
  לכולם); most כולם hits are false positives from the song title "כולם גנבים". FIX:
  title-aware plural filter + strengthen the ban in trivia/banter prompts.
- VERIFY — 9 good_thing/trivia scripts make unverified factual/superlative claims
  (hallucination risk). Consider restricting facts to safe phrasing.
- OK: somber discipline, over-talk (no outliers), most "mislabels"/"unnamed" were
  measurement artifacts (Hebrew transliteration of intl artists; cue beat names nothing
  by design).

## 3. Song selection
- ROOT BOTTLENECK: the bundled taste pool has only 20 tracks. 74 distinct songs / 250
  airings; 140/176 repeat-intervals fall inside the 50-song no-repeat window — the
  window is structurally unsatisfiable with 20 tracks. Hot tracks play 10-18x.
- HEALTHY: epsilon tail-floor surfaces 100% of the pool (not head-heavy); discovery 24%
  with sensible relatives; artist fatigue mostly works (one artist owns ~4 pool slots ->
  x23); language 55% alternating.
- WEAK: mood differentiation — 17 songs air in all 5 moods; only discovery differs.
- FIX: grow taste pool to 60-80 (the unlock); lean discovery 35-40% for party/morning +
  genre-filter it; cap any artist at <=2 pool slots; if pool stays small shrink window
  to ~12-15; 3-4 same-language run cap.

## 4. BPM / rhythm / transitions
- 48% of consecutive transitions are JARRING (|ΔBPM|>25), mean 31.8, max 104 — planner
  orders songs i.i.d. with zero tempo awareness.
- Moods are tempo/energy INDISTINCT: all 5 span 81-185 BPM, means in a 10-BPM band;
  party RMS 0.271 vs late_night 0.246. Mood is cosmetic on the rhythm axis.
- Energy is a random walk (no arc); Camelot adjacency at chance (13% vs 17%); durations
  clean (no >12min leaks).
- PAYOFF: BPM-sort within a block on the SAME songs cuts mean |ΔBPM| 31.8->2.6 and
  jarring 48%->1%.
- FIX: P0 tempo-aware ordering within a block; P1 BPM at pick-time (Essentia on-device
  primary, Deezer /track secondary); P2 per-mood BPM+energy windows (makes moods audibly
  distinct on rhythm); P3 per-mood energy arc; P4 Camelot tie-breaker.

## 5. Already fixed during the study
- DATA RACE (was the intermittent "DJ talks over the singer"): safeIntroByPath was a
  plain HashMap written from 3 parallel load coroutines -> could drop a song's entry ->
  opener wrongly emitted over a singer. Changed to ConcurrentHashMap; 12/12 stable.
  Pending commit.

## Proposed build roadmap (priority order)
- A. PROMPT VARIETY + temperature (fixes #1 "close results"). [diversity]
- B. DANGLING-NAME validator + plural-address title-aware filter. [bug, audible]
- C. Investigate + fix beat-gating-to-mix (banter/trivia in all moods). [bug]
- D. TEMPO-AWARE block ordering (48%->1% jarring) + per-mood BPM/energy windows. [rhythm]
- E. Grow taste pool to 60-80 + per-mood discovery boost + artist-slot cap. [selection]
- F. Commit the ConcurrentHashMap race fix (already done).
