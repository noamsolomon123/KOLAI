# KOLAI Generation & Study Plan (2026-06-13)

Goal: generate a large corpus of what the engine *actually produces* — song picks,
DJ scripts, and audio features — then study it to find bugs and make the
selection / rhythm algorithm better.

## What we generate

A **faithful JVM harness** runs the REAL engine code (no mocks): `TastePoolPlanner`
+ `RollingPlanner` (history, artist-fatigue, epsilon tail floor) + `DeezerDiscovery`
+ `DjBrain` (all beats: song / weather / news / handover / good_thing / trivia /
banter / cue / recap), driven against the bundled `taste.json`. It simulates a long
continuous broadcast and logs a structured JSONL corpus.

- **Scale:** ~250 songs (a few hours of simulated radio), swept across all 5 moods
  and several times-of-day / calendar states (incl. one somber-day pass and a
  Friday-recap pass).
- **Per song entry:** title, artist, `tasteRank`, discovery flag, mood, language,
  beat that surrounded it, and the **DJ script** text (the "voice over" — what the
  host said), with speaker tags for two-host bits.
- **Audio features:** BPM / key (Camelot) / energy per song — from Deezer's track
  API where available, backfilled by downloading a subset (yt-dlp) and analyzing
  (librosa on PC, mirroring the on-device Essentia fields).
- **Audio sample:** ~15 fully-rendered blocks spanning the 5 moods, so we can
  *hear* the new per-mood voices + the vocal-onset fix (doubles as on-device QA).

## What we study

### A. Song selection
1. **Repetition** — any song/artist recurring inside the no-repeat window; recurrence gaps.
2. **Artist clustering** — gap distribution between same-artist plays (is artist-fatigue working?).
3. **Taste coverage** — histogram of which taste ranks get aired; does the epsilon floor surface the tail? % of the pool ever played in 250.
4. **Discovery** — actual discovery rate vs the 25% target; are discoveries real/findable; Deezer seed-rotation (no repeat mining).
5. **Language alternation** — does Hebrew/intl actually alternate or clump?
6. **Mood curation** — when a mood is set, do picks measurably shift toward its vibe (cross-checked against Deezer genre/BPM)?

### B. DJ text (the "voice overs")
0. **OUTPUT DIVERSITY (priority — user-reported "close results")** — measure how
   similar the DJ scripts are to each other: pairwise n-gram/Jaccard overlap, opener
   distribution, vocabulary spread, and how much the anti-repetition ring + flavor
   nudges actually reduce sameness. ROOT-CAUSE HYPOTHESIS: the prompt skeleton is
   identical every call (same composed fragments + a tiny 5-item nudge) and the LLM
   temperature may be low/default. **Queued #1 improvement (build right after the
   baseline corpus generates, NOT during — would corrupt the baseline):** rotate
   many distinct prompt ANGLES/framings per call (song-vibe / memory / question to
   the listener / contrast with previous song / time-of-day / confession / fact),
   raise the DJ-text temperature (verify GeminiTextClient even sets one), expand the
   angle pool — then regenerate a sample and measure before/after diversity.
1. **Repetition** — n-gram overlap of openers/phrases across scripts despite the anti-repetition ring.
2. **One-listener doctrine** — scan for banned plural address (מאזינים / אתם / כולם / חברים): a concrete bug hunt.
3. **Over-talk** — word-count distribution vs the sparse-talk law; any walls of text.
4. **Hallucination markers** — flag suspicious specifics (years, numbers, factual claims) in trivia / good_thing; verify naming.
5. **Naming accuracy** — does the script name the CORRECT upcoming song/artist (the "wrong song under label" class)?
6. **Beat mix** — how often each beat fires; are latches producing a sane balance or is something over/under-firing?
7. **Mood/calendar/somber correctness** — zero wordplay/banter on somber days; djLine present when a mood is set.

### C. BPM / rhythm / transitions (the algorithm-improvement core)
1. **|ΔBPM| between consecutive songs** — jarring jumps (e.g. 75→140)? distribution.
2. **Key compatibility** — Camelot adjacency of consecutive songs; fraction harmonically compatible vs clashing.
3. **Energy arc** — is there any structure, or a random walk? Does energy match the mood (party high, late_night low)?
4. **Immediate-vocals frequency** — how often songs start singing at t≈0 (quantifies how common the talk-over-the-singer bug was).
5. **Duration sanity** — anything >12min / compilations slipping through.

### D. Cross-cutting bug hunt
Exceptions during generation, FetchExceptions (unfindable songs), title-coverage
rejects (hallucinated/unfindable picks), Deezer failures, empty results, latch
misbehavior.

## Output
1. A findings report (`docs/studies/2026-06-13-generation-study-findings.md`).
2. A **prioritized list of concrete algorithm improvements** (e.g. BPM-aware
   ordering within a block, energy-arc planning, epsilon tuning, repetition fixes,
   somber-gate tightening) — which become the next build wave.

## Sequencing
Runs **after** the in-flight integration wave finishes and the new build is
installed (the harness uses `:station`/`:acquire` classes the integration agent is
currently editing — building it now would collide). Order:
integration wave → build → install on device → build harness → generate corpus →
analyze → findings + improvement proposals.
