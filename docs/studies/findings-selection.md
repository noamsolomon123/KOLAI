# KOLAI Song-Selection Findings (corpus.jsonl, 250 airings)

Method: every corpus row carries one real songTitle/songArtist and is treated as one airing (250 airings, seq 1..250). Song identity = normalized title+artist. Non-song beats each still front a real upcoming song, so all 250 rows count as airings.

## 1. Repetition

- Distinct songs aired: **74** across 250 airings -> avg **3.4 airings per song**.
- Distinct artists aired: **69**.
- Total song repeats (airings beyond first): **176**.
- Song-repeat gap distribution (between consecutive airings of SAME song): min=2 med=20.0 mean=27.8 max=151 (n=176)
- **No-repeat window = 50 songs. Repeats landing INSIDE that window (gap < 50): 140 of 176 repeat-intervals.**

Most-aired songs (count, min-gap, positions):
  - x18  mingap=3  positions=[11, 28, 33, 41, 57, 67, 85, 88, 122, 161, 165, 172, 202, 208, 217, 224, 240, 247]
  - x17  mingap=2  positions=[17, 35, 44, 46, 63, 69, 75, 84, 132, 155, 179, 198, 212, 226, 228, 243, 246]
  - x12  mingap=6  positions=[6, 29, 43, 49, 97, 110, 116, 128, 138, 187, 239, 248]
  - x12  mingap=6  positions=[25, 68, 86, 129, 151, 157, 178, 188, 197, 213, 234, 242]
  - x11  mingap=2  positions=[19, 42, 94, 109, 152, 156, 171, 173, 181, 232, 250]
  - x10  mingap=3  positions=[2, 26, 60, 103, 115, 130, 133, 143, 195, 241]
  - x10  mingap=7  positions=[9, 61, 104, 117, 169, 185, 192, 210, 220, 235]
  - x10  mingap=5  positions=[12, 31, 45, 59, 64, 76, 127, 149, 175, 219]
  - x10  mingap=3  positions=[14, 47, 50, 81, 98, 146, 163, 189, 205, 249]
  - x8  mingap=13  positions=[5, 52, 80, 113, 160, 203, 223, 236]
  - x8  mingap=2  positions=[10, 30, 32, 36, 82, 135, 186, 237]
  - x8  mingap=2  positions=[21, 54, 72, 87, 137, 139, 191, 222]
  - x8  mingap=12  positions=[23, 65, 77, 90, 141, 170, 183, 200]
  - x8  mingap=2  positions=[24, 40, 92, 96, 142, 144, 184, 221]

Most-aired artist counts (top): x23, x18, x17, x14, x12, x11, x10, x10, x10, x10, x8, x8

**Starvation math:** taste pool = 20 tracks, no-repeat window = 50. A window needs 50 distinct titles but the curated pool offers only 20. From taste alone a window can be filled at most 20/50 = 40% without a repeat; the other 30 slots must be repeats or discovery. The window is structurally unsatisfiable from the pool.

## 2. Taste coverage

- Taste (non-discovery) airings: **190** of 250 (76%).
- Distinct tasteRanks that ever aired: **20 of 20** -> **100% of the pool aired.**
- Pool ranks that NEVER aired: none

Histogram of airings per tasteRank (0 = head/top, 19 = tail):
  rank  0: 12 ############
  rank  1: 18 ##################
  rank  2: 17 #################
  rank  3: 10 ##########
  rank  4: 10 ##########
  rank  5:  8 ########
  rank  6: 10 ##########
  rank  7: 12 ############
  rank  8:  6 ######
  rank  9:  8 ########
  rank 10: 11 ###########
  rank 11:  8 ########
  rank 12:  7 #######
  rank 13:  7 #######
  rank 14:  6 ######
  rank 15: 10 ##########
  rank 16:  8 ########
  rank 17:  7 #######
  rank 18:  7 #######
  rank 19:  8 ########

- Head (ranks 0-9) airings: **111**  |  Tail (ranks 10-19) airings: **79**  -> head/tail ratio 1.41.

## 3. Discovery

- Discovery airings: **60** of 250 = **24%** (plan target 25%).
- Unique discovery songs: **54**; unique discovery artists: **52**.
- Discovery repeats (same discovery song aired >1x): **6**.
- (Full discovery song list written to _discovery_songs.txt for manual sensibility review.)
- Discovery artists aired more than once: 6 (max repeats=3).

## 4. Artist clustering (artist-fatigue)

- Same-artist gap distribution: min=2 med=18 mean=25.0 max=151 (n=181)
- Same-artist airings spaced <=3 apart (clumping): **13** intervals.
Most-repeated artists (count, min-gap):
  - x23  mingap=2
  - x18  mingap=3
  - x17  mingap=2
  - x14  mingap=5
  - x12  mingap=6
  - x11  mingap=2
  - x10  mingap=3
  - x10  mingap=7
  - x10  mingap=5
  - x10  mingap=3

## 5. Language alternation (he / intl)

- he=100, intl=150 (40% / 60%).
- Number of runs: 139; run-length distribution: min=1 med=1 mean=1.8 max=12 (n=139)
- Adjacent-different (alternation) rate: **55%** of consecutive pairs.
- Run-length histogram:
    len 1: 89 runs
    len 2: 23 runs
    len 3: 14 runs
    len 4: 6 runs
    len 5: 2 runs
    len 6: 2 runs
    len 7: 1 runs
    len 8: 1 runs
    len 12: 1 runs

## 6. Mood differentiation

Per-mood unique songs / artists / discovery share:
  - mix        : 27 unique songs, 25 unique artists, discovery 7/50 (14%)
  - party      : 34 unique songs, 32 unique artists, discovery 14/50 (28%)
  - late_night : 33 unique songs, 30 unique artists, discovery 13/50 (26%)
  - focus      : 31 unique songs, 28 unique artists, discovery 12/50 (24%)
  - morning    : 32 unique songs, 30 unique artists, discovery 14/50 (28%)

Pairwise song-set Jaccard overlap between moods (1.0 = identical music):
  - mix vs party: songs J=0.49, artists J=0.46
  - mix vs late_night: songs J=0.54, artists J=0.53
  - mix vs focus: songs J=0.57, artists J=0.56
  - mix vs morning: songs J=0.44, artists J=0.45
  - party vs late_night: songs J=0.49, artists J=0.48
  - party vs focus: songs J=0.44, artists J=0.43
  - party vs morning: songs J=0.38, artists J=0.35
  - late_night vs focus: songs J=0.45, artists J=0.45
  - late_night vs morning: songs J=0.38, artists J=0.36
  - focus vs morning: songs J=0.37, artists J=0.38

- Mean pairwise song-set Jaccard: **0.45**; mean artist-set Jaccard: **0.44**.
- Songs aired in ALL 5 moods: **17**.

Deezer genre spread per mood (top genres):
  - mix        : track:47
  - party      : track:48
  - late_night : track:49
  - focus      : track:48
  - morning    : track:48

NOTE: Deezer audio metadata is unusable in this corpus. `deezerGenre` is only the literal
token `track` (an API-shape artifact, not a real genre) and `deezerBpm == 0` for ALL 250 rows.
So mood differentiation here can only be judged by the song/artist SETS, not by genre/BPM. The
audio-feature backfill (Deezer track API / librosa) described in the plan did not populate.

---

## Verdict

The selection engine is **mechanically healthy** (epsilon floor, language alternation, artist
spacing, discovery rate all behave as designed) but the station is **starved by a 20-track taste
pool**. The single dominant problem is pool size, and it cascades into repetition and weak mood
differentiation.

Scorecard:

| Dimension | Result | Verdict |
|---|---|---|
| Repetition | 74 distinct songs / 250 airings; **140 of 176** repeat-intervals fall inside the 50-song no-repeat window; tightest gap = **2** | FAIL — window is structurally unsatisfiable |
| Taste coverage | **100%** of pool aired (20/20); head/tail ratio **1.41** | PASS — epsilon floor surfaces the whole tail |
| Discovery | **24%** (target 25%); 54 unique songs, 6 repeats; relatives are sensible | PASS |
| Artist clustering | median same-artist gap **18**; only **13** intervals <=3 apart; min gap **2** | MOSTLY PASS — a few adjacents leak through |
| Language alternation | **55%** adjacent-different; 89/139 runs are length 1; one run of **12** | MOSTLY PASS — one long clump |
| Mood differentiation | mean song-set Jaccard **0.45**; **17** songs air in ALL 5 moods | WEAK — moods share ~half their music |

### 1. Repetition — the core failure
With only 74 distinct titles over 250 airings (avg 3.4 plays each) and the 8 most-aired taste
tracks playing 10-18 times each at min-gaps of 2-3, the 50-song no-repeat window is violated 140
times. This is not a bug in the repetition logic — it is arithmetic: a 50-slot window needs 50
distinct titles, but taste offers 20 and discovery (24%) adds only ~12 more per window, so ~18
slots per window must repeat. **The window cannot be honored until the pool grows.**

### 2. Taste coverage — working well
Every one of the 20 ranks aired, and the tail (ranks 10-19 = 79 airings) is nearly as active as
the head (ranks 0-9 = 111, ratio 1.41). The epsilon tail-floor is doing its job: it is NOT
head-heavy. If anything, with a tiny pool the floor over-surfaces the tail (a 20-track pool has no
real "tail" worth de-emphasizing).

### 3. Discovery — working well
24% vs the 25% target is essentially on-spec. 54 unique discovery songs / 52 unique artists with
only 6 repeats means seed rotation is healthy. Spot-check of the discovery list shows sensible
relatives: EDM (Galantis, AFROJACK, Cash Cash, 3LAU, Otto Knows) for the Avicii/Zedd/Calvin-Harris
taste; pop-punk/emo (Taking Back Sunday, As It Is, Neck Deep, Yellowcard, Daughtry) for All Time
Low/Nickelback; mainstream Israeli pop (Eyal Golan, Itay Levy, Eden Ben Zaken, Keren Peles) for the
Hebrew taste. Discovery is the station's only real source of variety right now.

### 4. Artist clustering — mostly fine, small leak
Median same-artist gap is 18 with only 13 intervals spaced <=3 apart, so fatigue spacing largely
works. But min gap = 2 means same-artist songs occasionally land nearly back-to-back. The top
artist (x23) is almost certainly יובי, who appears 4 times in the 20-track pool (3 songs +
top-artist) — concentrated-artist pools defeat fatigue spacing.

### 5. Language alternation — mostly fine, one clump
55% adjacent-different and 89/139 runs of length 1 show real alternation, but there is one run of
12 same-language songs and a 40/60 he/intl skew. The intl skew is driven by discovery leaning
international (most discovery rows are intl).

### 6. Mood differentiation — the weak point
Mean pairwise song-set Jaccard is 0.45 and **17 songs air in all 5 moods** — i.e. roughly half of
any mood's music is the same shared taste core regardless of vibe. party-vs-morning (0.38) and
focus-vs-morning (0.37) differ most; mix-vs-focus (0.57) least. The differentiation that DOES exist
comes almost entirely from each mood's discovery picks (party/morning 28% discovery vs mix 14%),
not from the taste core. Because BPM/genre metadata is missing, we cannot confirm the discovered
songs actually match each mood's energy — that check is blocked until audio features populate.

---

## Concrete recommendations (prioritized)

1. **Grow the taste pool to >= 60-80 tracks (highest impact).** Pull more than the top 20 from
   Spotify (top 50-100 tracks, or top-tracks-per-artist for the top_artists list). This is the
   single fix that unblocks the 50-song no-repeat window and stops the 10-18x replays. Until the
   pool >= the window size, repetition is mathematically forced.

2. **Lean discovery harder per mood, and genre-filter it.** With a small pool, discovery is the
   only lever that moves mood differentiation. Raise discovery to ~35-40% specifically for
   high-variety moods (party/morning), and constrain Deezer discovery by mood-appropriate
   genre/BPM bands (party=high-energy/dance, late_night=low-BPM/chill, focus=instrumental/mellow).
   This would push the cross-mood Jaccard well below 0.45.

3. **Cap any single artist's share of the pool.** יובי occupies ~4 of 20 pool slots, forcing the
   x23 artist count and the min-gap-2 adjacents. Limit to <=2 tracks per artist in the active pool
   so artist-fatigue spacing has room to work.

4. **If the pool must stay small, shrink the no-repeat window to ~pool_size*0.6** (e.g. 12-15) so
   the constraint is satisfiable and "violation" counts become meaningful. A 50-window over a
   20-pool is a constraint the engine can never honor; right now it is silently overridden 140x.

5. **Epsilon tail-floor: leave it (or slightly lower it).** Coverage is already 100% and the tail
   is well-surfaced (head/tail 1.41). With a larger pool, lower epsilon a bit so the genuine
   head gets modestly more weight; do NOT raise it.

6. **Tighten language alternation against long runs.** Add a soft cap (e.g. no more than 3-4
   same-language songs in a row) to break up the length-12 clump; rebalance discovery so it does
   not skew intl (currently driving the 40/60 split).

7. **Fix the audio-feature pipeline before re-studying mood/BPM.** `deezerBpm==0` and
   `deezerGenre=='track'` for all 250 rows mean the Deezer track-API / librosa backfill never
   populated. Sections C (BPM/rhythm) and the genre-cross-check for mood are un-analyzable until
   this is fixed — repair it before the next corpus generation.
