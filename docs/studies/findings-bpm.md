# KOLAI Generation Study -- Section C: Rhythm / BPM / Transition Quality

Date: 2026-06-13
Corpus: docs/studies/corpus.jsonl (250 song rows, 74 unique songs, 50 songs/mood across the 5 moods).
Enrichment cache: docs/studies/audio-features.json.
Tooling: Deezer /track/{id} detail endpoint (BPM/gain/duration) + yt-dlp + librosa ground-truth (BPM / RMS-energy / Camelot key) on a 30-song subset.

## 0. BPM coverage achieved

| Source | Unique songs with BPM |
|---|---|
| Deezer /track (bpm > 0) | 32 / 74 (43%) |
| librosa beat_track (downloaded subset) | 30 / 74 (41%) |
| Combined (any BPM) | 62 / 74 (84%) |
| Songs found on Deezer at all | 73 / 74 |

Notes:
- Deezer /search carries no BPM; the /track/{id} detail endpoint does, but ~57% of returned tracks have bpm=0 (unknown). Deezer alone is NOT a reliable BPM source for ~half the catalog.
- The librosa subset deliberately targeted Deezer-missing songs (balanced 6/mood), so there is no Deezer-vs-librosa overlap to cross-validate. librosa beat_track is the standard estimator and is treated as ground truth. Octave (half/double-time) errors are possible on a minority of tracks but do not change conclusions, which are about gaps of 30-100 BPM, not a few percent.
- In the broadcast order, 168 of 249 consecutive song pairs have BPM on both sides -- a large, representative sample.

## 1. Consecutive |dBPM| -- the jarring-transition rate (THE headline)

The planner currently orders songs i.i.d. from the taste pool with ZERO tempo awareness. Result:

| |dBPM| bucket | pairs | share |
|---|---|---|
| 0-8 (smooth, beatmatched feel) | 35 | 21% |
| 9-16 (ok) | 21 | 12% |
| 17-25 (noticeable) | 32 | 19% |
| >25 (JARRING) | 80 | 48% |

- mean |dBPM| = 31.8, median = 22.9, max = 103.9
- Nearly half of all song-to-song transitions are jarring (e.g. seq 6->7 was 103->172 BPM = +68; seq 33->34 was 107->180 = +73).
- Within-block transitions (rare 2-song blocks; corpus is mostly 1 song/block, mean 1.67) are equally bad: 46% jarring. The problem is the global ordering, not just intra-block.

## 2. Per-mood tempo & energy -- are moods audibly different in RHYTHM?

No. Moods are essentially tempo- and energy-indistinct.

| mood | n(BPM) | mean BPM | median BPM | BPM std | BPM range | mean RMS energy (subset) |
|---|---|---|---|---|---|---|
| morning | 44 | 120.6 | 120.1 | 27 | 81-185 | 0.2501 |
| mix | 39 | 127.4 | 120.9 | 31 | 81-185 | 0.2635 |
| party | 43 | 130.9 | 126.0 | 31 | 81-185 | 0.2709 |
| focus | 43 | 124.5 | 122.0 | 27 | 81-185 | 0.2611 |
| late_night | 38 | 125.1 | 117.5 | 27 | 81-185 | 0.2459 |

- Mean BPM spread across all five moods is just 120-131 BPM -- a 10 BPM band, dwarfed by within-mood std ~27-31.
- Every mood spans the identical 81-185 BPM range. party is not meaningfully faster than late_night/focus; the rank order is barely there and statistically meaningless at this n.
- Energy: party 0.271 vs late_night 0.246 (~10% diff, < 1 std). The mood label is NOT translating into an audible rhythmic identity -- consistent with a small taste pool (74 songs) and no tempo/energy curation.
- Energy across the broadcast is a random walk (lag-1 autocorrelation = -0.07): no energy arc, no build, no wind-down.

## 3. Duration sanity

- 240/250 rows have Deezer duration. mean 193s, median 192s, max 372s (6.2 min).
- Zero tracks > 12 min. No compilation/album-side leaks detected. Acquisition title-coverage filtering looks healthy on this axis.

## 4. Key / Camelot harmonic adjacency

- 46 consecutive pairs have a librosa-estimated Camelot key on both sides.
- Harmonically compatible (same key / +-1 on the wheel / relative major-minor): 6 / 46 = 13%.
- Random-shuffle baseline ~= 17%. Consecutive keys are at or below chance -- no harmonic mixing (expected, since nothing orders by key).

## 5. What ordering would buy us -- simulation

Simulated re-ordering of the existing songs (same songs, same mood grouping, just reordered):

| Strategy | mean |dBPM| | jarring (>25) |
|---|---|---|
| Current (i.i.d.) | 31.8 | 48% |
| BPM-sort within each mood-run | 2.6 | 1% |
| Full-broadcast BPM sort (floor) | 0.5 | 0% |

Sorting a block/run's songs by BPM essentially eliminates jarring transitions (48% -> 1%) using data we already have. Highest-leverage rhythm fix, no new audio analysis at order time beyond one BPM number per candidate.

---

## Concrete algorithm recommendations (prioritized)

### P0 -- Add a tempo-aware ORDERING pass (biggest win, lowest cost)
Today TastePoolPlanner picks a set and the broadcast plays them in pick order. Add a final ordering pass that sorts the songs queued for a block/segment by BPM (snake / monotonic within the run) so neighbours are tempo-adjacent.
- Expected benefit (measured on this corpus): mean |dBPM| 31.8 -> ~2.6, jarring transitions 48% -> ~1%.
- Data already exists on-device: the app computes Essentia features locally, and Deezer /track BPM is fetchable at pick time for the ~43% it covers. For the ~57% Deezer gap, use on-device Essentia BPM, or fall back to "unknown -> place at run boundary" so unknown-BPM songs never sit between two known tempos and fabricate a false jump.
- Implementation: pure ordering, no extra LLM calls, O(n log n) per block.

### P1 -- Enrich BPM at PICK time and feed the candidate scorer
- In the acquire/discovery path, after a Deezer search hit, do one extra GET /track/{id} to read bpm/gain. Cache it. (43% hit rate, cheap, one request, proven here.)
- Feed BPM into the existing candidate scorer as a soft term: prefer candidates whose BPM is near a per-block target tempo (see P2). Makes the POOL itself tempo-coherent before ordering, not just the order.
- For the Deezer-unknown half, prefer on-device Essentia BPM the player already extracts; fall back to "no constraint" only when truly unknown.

### P2 -- Make moods actually shape tempo (per-mood target BPM + energy band)
Data proves moods are tempo-indistinct (all 81-185, means within 10 BPM). Give each mood a target BPM window and energy band, used both as a scorer term (P1) and the ordering anchor (P0):
- party ~118-135 BPM / high energy; morning ~100-120 / rising; focus ~90-115 / steady-low; late_night ~70-100 / low energy; mix unconstrained.
- This is what makes moods audibly different -- today the label is cosmetic on the rhythm axis.

### P3 -- Energy-arc planning per mood
Energy is currently a random walk (autocorr -0.07). Plan a per-mood energy contour over each segment (morning ramps up; late_night decays; party plateaus high) and order/select to follow it. Combine with the BPM sort: order each run to follow the arc (ascending for a build, descending for a wind-down) rather than a flat monotonic sort.

### P4 -- (Optional) Camelot/harmonic adjacency as a tie-breaker
Harmonic compatibility is at chance (13% vs 17% baseline). On-device Essentia key + a Camelot table would let the ordering pass break BPM ties toward harmonically adjacent keys ("DJ-grade" mixes). Lower priority than tempo -- listeners feel a 70-BPM jump far more than a key clash -- and key estimation is noisier, so treat as a soft secondary sort only.

### Coverage caveat for the build
Deezer BPM covers only ~43%; do NOT build the ordering pass to depend on Deezer alone. Robust design: on-device Essentia BPM (primary) -> Deezer /track BPM (secondary) -> unknown-handling that parks unknown-BPM songs at run boundaries so a missing value never creates a false jarring jump between two known tempos.
