# KOLAI Song-Selection Findings — v3 vs baseline (250 airings each)

**Question:** Did the v3 changes (prompt variety + temperature, dangling-name guard incl. two-host beats,
somber suppression, plural/hallucination prompts, JSON-beat retry) affect SONG SELECTION?

**Expectation:** the planner (TastePoolPlanner + Deezer discovery + epsilon tail-floor + artist/language
spacing) was **NOT changed** by the v3 batch — every v3 fix targets DJ-text generation, not picking. So
selection should be at **parity**, and any deltas should be run-to-run sampling noise, not signal. This
study confirms exactly that.

- Corpora: `corpus.jsonl` (baseline, original code) and `corpus-v3.jsonl` (all fixes). Both 250 rows.
- Method: identical to the original selection study. Every row fronts one real songTitle/songArtist and
  counts as one airing (250 airings/corpus). Song identity = NFKC-normalized title+artist; artist identity
  = normalized artist. No-repeat window = 50 songs. tasteRank None ⟺ discovery row (61 discovery in v3,
  60 in baseline).
- Script: `docs/studies/_sel_compare.py`; raw dump: `docs/studies/_sel_compare.json`.
- **Taste pool: `android/app/src/main/assets/taste.json` is still 20 tracks** (top_tracks) + 10 top_artists.
  Unchanged. This remains the binding constraint on everything below.

---

## Side-by-side scorecard (BEFORE = baseline → AFTER = v3)

| Metric | baseline | v3 | Δ | tag |
|---|---|---|---|---|
| Distinct songs / 250 airings | 74 | 75 | +1 | same |
| Avg airings per song | 3.38 | 3.33 | −0.05 | same |
| Distinct artists | 69 | 71 | +2 | same |
| Total repeats (airings beyond first) | 176 | 175 | −1 | same |
| Repeat-gap min / median / mean / max | 2 / 20 / 27.8 / 151 | 2 / 24 / 28.6 / 157 | median +4 | same (noise) |
| **Repeats inside 50-song window** | **140 / 176** | **135 / 175** | −5 | same (still ~77%) |
| Most-aired single song (count) | 18 | 15 | −3 | same (noise) |
| Top-10 most-aired song counts | 18,17,12,12,11,10,10,10,10,8 | 15,14,14,12,11,10,10,9,9,9 | flatter top | same (noise) |
| Taste airings (non-discovery) | 190 | 189 | −1 | same |
| Distinct tasteRanks aired (of 20) | 20 (100%) | 20 (100%) | 0 | same |
| Ranks never aired | none | none | 0 | same |
| Min / max airings per rank (epsilon floor) | 6 / 18 | 5 / 15 | — | same |
| Head (rank 0–9) / Tail (10–19) airings | 111 / 79 | 107 / 82 | — | same |
| Head/tail ratio | 1.41 | 1.30 | −0.11 | same (noise) |
| Discovery airings | 60 | 61 | +1 | same |
| Discovery rate | 24.0% | 24.4% | +0.4pt | same |
| Unique discovery songs | 54 | 55 | +1 | same |
| Unique discovery artists | 52 | 54 | +2 | same |
| Discovery song repeats | 5 | 6 | +1 | same |
| Discovery artist multi (>1 airing) / max repeat | 6 / 3 | 6 / 3 | 0 | same |
| Same-artist gap min / median / mean / max | 2 / 18 / 25.0 / 151 | 2 / 21 / 26.0 / 157 | median +3 | same (noise) |
| Same-artist clumps (gap ≤ 3) | 13 | 17 | +4 | same (noise) |
| Top artist airing count | 23 | 20 | −3 | same (noise) |
| Hebrew / international airings | 100 / 150 (40/60) | 104 / 146 (42/58) | — | same |
| Language runs / adjacent-different rate | 139 / 55.4% | 141 / 56.2% | — | same |
| **Longest same-language run** | **12** | **6** | **−6** | **improved (noise-driven)** |
| Mean cross-mood song-set Jaccard | 0.455 | 0.462 | +0.007 | same |
| Mean cross-mood artist-set Jaccard | 0.444 | 0.447 | +0.003 | same |
| **Songs aired in ALL 5 moods** | **17** | **19** | **+2** | same / slightly worse |

**Verdict: PARITY.** Every metric moves within run-to-run sampling noise. There is no systematic shift —
nothing in the v3 batch touched the picker, and the numbers prove it. The two largest-looking deltas both
have non-structural explanations: the longest language run dropping 12→6 is a lucky reshuffle of discovery
(intl-leaning) picks, not a new soft cap (no language-run cap was added); and songs-in-all-moods rising
17→19 is the same shared-taste-core phenomenon, marginally worse, again noise from a 20-track pool that
every mood must draw from.

---

## Detail by section

### 1. Repetition — identical failure mode
- v3: 75 distinct songs over 250 airings → 3.33 plays each (baseline 74 / 3.38). The most-aired song
  dropped 18→15 and the head of the distribution is a touch flatter, but this is reshuffling, not relief.
- **135 of 175 repeat-intervals (77%) still land inside the 50-song no-repeat window** (baseline 140/176,
  also 80%). Tightest gap = 2 in BOTH corpora.
- Arithmetic unchanged: a 50-slot window needs 50 distinct titles; taste offers 20 and ~24% discovery adds
  only ~12 more per window, so ~18 slots/window are forced repeats. **The window is structurally
  unsatisfiable from a 20-track pool in both runs.**

### 2. Taste coverage — epsilon floor still healthy in both
- 100% pool coverage in both (all 20 ranks aired, none starved). v3 min airings/rank = 5, max = 15
  (baseline 6 / 18) — slightly tighter spread, i.e. the tail floor is, if anything, marginally more even.
- Head/tail ratio 1.30 (v3) vs 1.41 (baseline): both ≈ flat, both confirm the epsilon tail-floor is NOT
  head-heavy. With a 20-track pool there is no real "tail" to de-emphasize.

### 3. Discovery — on-spec in both
- v3 24.4% vs baseline 24.0% (target 25%). 55 unique discovery songs / 54 unique artists (baseline 54/52),
  only 6 repeats, max artist repeat 3. Seed rotation healthy and unchanged.
- Discovery relatives remain sensible (EDM cluster for Avicii/Zedd/Calvin Harris; pop-punk/emo for All Time
  Low/Nickelback; mainstream Israeli pop for the Hebrew taste). v3 artist sample is in `_sel_compare.json`.

### 4. Artist clustering — same, small leak in both
- Same-artist median gap 21 (v3) vs 18 (baseline); min gap 2 in both. v3 has 17 clumps ≤3 apart vs 13 in
  baseline — slightly more, still small, within noise. Top artist 20 airings (v3) vs 23 (baseline), almost
  certainly יובי, who occupies ~4 of 20 pool slots (3 songs + a top_artist entry). Concentrated pool still
  defeats fatigue spacing in both.

### 5. Language alternation — same alternation, no new cap
- v3 56.2% adjacent-different vs baseline 55.4%; he/intl 42/58 vs 40/60. Run counts ~equal (141 vs 139).
- v3 run-length histogram `{1:77, 2:39, 3:13, 4:5, 5:6, 6:1}` vs baseline `{1:89,…,8:1,12:1}`. The baseline's
  single length-12 clump did not recur (max run 6 in v3). Because **no language-run soft cap was added in
  v3**, this is a fortunate reshuffle of (intl-leaning) discovery, not a fix — do not credit the engine.

### 6. Mood differentiation — still the weak point, unchanged
- Mean cross-mood song-set Jaccard 0.462 (v3) vs 0.455 (baseline); artist Jaccard 0.447 vs 0.444. Per-pair
  v3 range 0.43–0.50 (baseline 0.37–0.57) — flatter but same ~0.45 center.
- **19 songs air in all 5 moods (v3) vs 17 (baseline)** — i.e. ~half of any mood's music is the shared
  taste core regardless of vibe, marginally more shared in v3. The only differentiation lever (per-mood
  discovery share) is unchanged. Deezer BPM/genre still unpopulated (`deezerBpm==0`, `deezerGenre=='track'`)
  in both corpora, so energy-match per mood remains unverifiable here.

---

## Verdict

Song selection is at **parity between baseline and v3** — exactly as expected, because the v3 batch only
touched DJ-text generation (prompt variety/temperature, dangling-name guard, somber suppression,
plural/hallucination prompts, JSON-beat retry) and left the planner untouched. All metric deltas are
run-to-run sampling noise; none is a systematic improvement or regression.

**The 20-track taste pool (`android/app/src/main/assets/taste.json`, still 20 tracks) remains the single
open bottleneck.** It forces:
- 77% of repeats inside the 50-song no-repeat window (structurally unsatisfiable: 50-slot window, 20-track
  pool, ~12 discovery/window ⇒ ~18 forced repeats),
- the x15–x20 most-played song/artist counts and min-gap-2 adjacents (one artist owns ~4 pool slots),
- and weak mood differentiation (≈0.46 cross-mood Jaccard, 19 songs in all 5 moods) because every mood
  draws from the same tiny core.

The selection engine's mechanics (epsilon tail-floor → 100% coverage; ~24% discovery on-spec; artist and
language spacing) are healthy in BOTH runs. The fix is not in the picking code — it is **grow the pool to
≥ 60–80 tracks** (top 50–100 Spotify tracks or top-tracks-per-artist), cap any single artist at ≤2 pool
slots, and genre/BPM-filter discovery per mood. Until the pool ≥ the no-repeat window, repetition and weak
mood separation are mathematically forced, regardless of any text-generation work.
