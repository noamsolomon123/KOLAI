# KOLAI Generation Study -- BPM / Rhythm, v3 corpus

Date: 2026-06-13
Corpus: docs/studies/corpus-v3.jsonl (250 broadcast song rows, 75 unique songs, 50 songs/mood across the 5 moods).
Baseline for comparison: docs/studies/findings-bpm.md (original code, 250-row corpus.jsonl).
Enrichment cache: docs/studies/audio-features-v3.json (reuses docs/studies/audio-features.json for overlapping titles).
Tooling: reuse of base cache (27 songs) + Deezer /search -> /track/{id} for bpm/gain/duration on the 48 new titles; librosa beat_track / RMS-energy / chroma-Camelot ground-truth on a 15-song subset (3 per mood) via Deezer 30s previews.

## TL;DR -- enhancement D (tempo-aware ordering + tempo-distinct moods) is STILL OPEN

The v3 batch changed DJ-text generation (prompt variety, temperature, dangling-name guard, somber suppression, plural/hallucination prompts, JSON-beat retry). It did NOT touch the song selection/ordering path. Confirmed by code inspection (see section 6): TastePoolPlanner has zero BPM/tempo/ordering logic, and the only tempo awareness anywhere is the audio-render crossfade beat-snap in BlockRenderer (which never reorders songs). The rhythm metrics confirm this:

- Consecutive jarring-transition rate (|dBPM| > 25): **57%** (baseline 48%) -- no improvement; if anything marginally worse due to a different, more discovery-heavy v3 song mix.
- Moods are still **tempo-indistinct**: per-mood mean BPM spans only 120-133 (a ~13 BPM band) and every mood covers the same ~70-185 BPM range; within-mood std (~28-33) dwarfs the between-mood spread.

## 0. BPM coverage achieved (v3)

| Source | Unique songs with BPM |
|---|---|
| Deezer /track (bpm > 0) | 22 / 75 (29%) |
| librosa beat_track (base-cache 13 + new 15-song preview subset) | 28 / 75 (37%) |
| Combined (any BPM) | 50 / 75 (67%) |
| Songs found on Deezer at all | 74 / 75 |

Notes:
- The v3 corpus shipped with `deezerBpm` = null on every row and `deezerBpmKnown: 0, deezerBpmCoverage: 0.0` in corpus-v3-run-summary.json. The CorpusGenerator enriches from Deezer **/search**, which carries no BPM -- so generation captured zero BPM. This study re-enriches via the **/track/{id}** detail endpoint (the endpoint that does carry bpm), matching the baseline methodology. Deezer /track still returns bpm=0 (unknown) for the majority (~63% of v3 hits), so Deezer alone remains an unreliable BPM source for ~2/3 of this catalog.
- 167 of 249 consecutive song pairs have BPM on both sides -- a large, representative sample.
- librosa beat_track on 30s Deezer previews is the ground-truth estimator. Octave (half/double-time) errors are possible on a few tracks but do not change conclusions, which concern gaps of 30-100 BPM, not a few percent. Where both exist, librosa is preferred over Deezer.

## 1. Consecutive |dBPM| -- the jarring-transition rate (THE headline)

Songs are still ordered straight from the taste pool with ZERO tempo awareness. Result on the v3 broadcast order (167 pairs with BPM on both sides):

| |dBPM| bucket | pairs | share | baseline share |
|---|---|---|---|---|
| 0-8 (smooth, beatmatched feel) | 22 | 13% | 21% |
| 9-16 (ok) | 24 | 14% | 12% |
| 17-25 (noticeable) | 25 | 15% | 19% |
| >25 (JARRING) | 96 | **57%** | 48% |

- mean |dBPM| = **36.1** (baseline 31.8), median 35.7 (baseline 22.9), max 103.9 (baseline 103.9).
- Robust to BPM source: jarring is 57% on combined BPM, 58% on Deezer-only (26 pairs), 62% on librosa-only (56 pairs). The result is not an artifact of which estimator is used.
- The slight rise vs baseline (48% -> 57%) is corpus/sample noise -- v3 picks a different, more discovery-heavy set (24% discovery rate) of songs -- NOT an algorithm change. The ordering algorithm is byte-for-byte the same.
- Worst jumps (seq: outBPM -> inBPM = dBPM): 78: 185->81 (-104); 224: 83->185 (+101); 155: 86->180 (+94); 77: 92->185 (+92); 27: 172->81 (-91). The catalog repeatedly slams a ~185 BPM track against an ~81-92 BPM track with no transition planning.

## 2. Per-mood tempo & energy -- are moods audibly different in RHYTHM?

No. Moods remain tempo- and energy-indistinct.

| mood | n(BPM) | mean BPM | median BPM | BPM std | BPM range | mean RMS energy (subset) |
|---|---|---|---|---|---|---|
| morning | 43 | 119.8 | 117.5 | 30.3 | 70-185 | 0.2347 (n=25) |
| mix | 38 | 132.7 | 123.0 | 33.4 | 81-185 | 0.2574 (n=21) |
| party | 40 | 132.9 | 123.4 | 30.9 | 81-185 | 0.2792 (n=23) |
| focus | 41 | 129.3 | 122.0 | 29.9 | 81-185 | 0.2506 (n=21) |
| late_night | 43 | 123.8 | 117.5 | 28.1 | 81-185 | 0.2482 (n=28) |

- Between-mood mean BPM spread is only 120-133 (a ~13 BPM band, baseline was a ~10 BPM band) -- dwarfed by within-mood std of ~28-33. Statistically meaningless separation at this n.
- Every mood spans essentially the identical ~70-185 BPM range. party (132.9) is not meaningfully faster than late_night (123.8) or focus (129.3); morning is nominally slowest but its range still reaches 185.
- The Moods.kt curation hints DO say the right things (party: keep the energy high and tempo up; late_night: avoid loud high-tempo bangers) but they are LLM curation prose with no numeric tempo gate, so they do not translate into measurable tempo separation. The mood label is still cosmetic on the rhythm axis.
- Energy: party 0.279 (highest) vs morning 0.235 (lowest) is a ~19% gap -- a slightly clearer ordering than baseline (party > mix > focus > late_night > morning), but still < 1 std and on a 15-song-derived subset, so weak. Energy across the broadcast is near-random (lag-1 autocorr on consecutive energy-known pairs = +0.14): no sustained arc, build, or wind-down.

## 3. What ordering would buy us -- simulation (unchanged, still the fix)

Re-ordering the SAME v3 songs (same mood grouping, just reordered by BPM):

| Strategy | mean |dBPM| | median | jarring (>25) |
|---|---|---|---|
| Current (i.i.d., as broadcast) | 36.1 | 35.7 | **57%** |
| BPM-sort within each mood-run (unknowns parked at run boundary) | 2.7 | 0.0 | **1%** |
| Full-broadcast BPM sort (theoretical floor) | 0.6 | 0.0 | 0% |

Sorting each mood-run by BPM still essentially eliminates jarring transitions (57% -> 1%) using only data we already have. This remains the highest-leverage rhythm fix, no new audio analysis at order time beyond one BPM number per candidate. Parking unknown-BPM songs at run boundaries prevents a missing value from fabricating a false jump.

## 4. Key / Camelot harmonic adjacency (secondary)

- 56 consecutive pairs have a librosa-estimated Camelot key on both sides (more than baseline 46, thanks to the librosa subset).
- Harmonically compatible (same key / +-1 on the wheel / relative major-minor): 10 / 56 = 18% -- at chance (~17% random baseline). No harmonic mixing, as expected since nothing orders by key.

## 5. Duration sanity

Unchanged axis; Deezer durations for the v3 hits look healthy (typical 2.5-4 min radio singles, no compilation/album-side leaks). Not the bottleneck.

## 6. Confirmation: the transition / ordering algorithm was NOT changed in v3

- `TastePoolPlanner.kt` (the v3 song-selection path): grep for bpm/tempo/sortedBy/orderBy/shuffle -> **no matches**. It picks a set; the broadcast plays them in pick order.
- The only tempo references in main code:
  - `BlockRenderer.snappedMusicOverlap()` -- beat-snaps the crossfade overlap length of the outgoing song to its own BPM (audio-render seam). Does not reorder anything.
  - `Moods.kt` -- prose curation hints (keep tempo up; avoid high-tempo bangers), no numeric gate.
  - `SetlistPlanner.kt` -- the prompt ASKS the LLM for similar BPM/tempo so they beat-mix cleanly and avoid back-to-back tempo jumps, but (a) this is the legacy Gemini-setlist path, superseded by TastePoolPlanner per the setlist-planner-redesign, and (b) even there it is an unenforced prompt wish, and the data shows it does not hold.
- corpus-v3-run-summary.json: `deezerBpmKnown: 0` -- v3 never even captured BPM, so there was no data with which to order even if ordering existed.

Conclusion: no tempo-aware ordering pass exists; enhancement D is untouched and OPEN.

## 7. Recommendations (carried forward, unchanged in priority)

- **P0 -- Tempo-aware ORDERING pass.** Add a final pass that BPM-sorts (snake/monotonic) the songs queued for each block/mood-run. Measured benefit on this corpus: mean |dBPM| 36 -> ~2.7, jarring 57% -> ~1%. Pure ordering, no extra LLM calls, O(n log n).
- **P0.5 -- Fix BPM capture at pick time.** Enrichment must call Deezer **/track/{id}** (has bpm), not /search (no bpm). Today the generator reads bpm off the search response and gets 0% coverage. Pair with on-device Essentia BPM (the player already extracts it) as the primary source, Deezer /track as secondary, and unknown-handling that parks unknown-BPM songs at run boundaries.
- **P1 -- BPM as a soft scorer term** so the POOL is tempo-coherent before ordering, anchored to a per-block target tempo.
- **P2 -- Make moods numerically tempo-distinct.** Give each mood a target BPM window + energy band (party ~118-135 high-energy; morning ~100-120 rising; focus ~90-115 steady-low; late_night ~70-100 low-energy; mix unconstrained). Today the labels are cosmetic on rhythm.
- **P3 -- Energy-arc planning** per mood (autocorr +0.14 ~= random walk today).
- **P4 -- Camelot adjacency as a tie-breaker** (18%, at chance today).

## Files produced by this study (read-only otherwise)

- docs/studies/audio-features-v3.json -- BPM/gain/duration + librosa ground-truth cache for the v3 catalog.
- docs/studies/findings-bpm-v3.md -- this file.
