# KOLAI DJ Output Diversity v2 - Prompt-Variety + Temperature Fix

Compares the NEW corpus (`corpus-v2.jsonl`, 118 non-null DJ scripts, generated WITH the fix:
temperature 1.15 + 12 rotating angles + bigger anti-repetition ring + opener set + anti-cliche +
per-mood register) against the BASELINE (`corpus.jsonl`, 249 non-null scripts, OLD code, no
temperature). All metrics use the EXACT definitions from `findings-diversity.md` /
`android/artifacts/div_analysis.py` (char-3gram cosine, 3-word-shingle openers, etc.).

Because the corpora differ in size (249 vs 118), every COUNT-based metric is reported BOTH raw
and as a rate, and the baseline is ALSO subsampled to N=118 averaged over 30 random trials
(seed=42) for an apples-to-apples comparison.

## TL;DR VERDICT: the fix WORKED directionally. Every metric improved; 1 of 4 hard targets cleared.

All four metrics moved the right way and by a large margin. Only one (the `higia hazman` tic)
strictly clears its absolute bar; the mood-delta target is a near-miss (it doubled but lands just
under +0.05). The other two -- top-pair cosine and share-first-word -- improved substantially
(both fell ~0.09 vs the size-matched baseline) but did NOT reach the aggressive < 0.45 bars. The
user-visible 'samey' complaint is materially reduced; further tuning (a touch more temperature, a
wider opener pool, de-templating good_thing) would close the remaining gap.

| # | Metric | Target (after) | Baseline (full N=249) | Baseline sub (N=118, 30 trials) | v2 (N=118) | Verdict |
|---|---|---|---|---|---|---|
| 1 | Top-pair char-3gram cosine | < 0.45 | 0.677 | 0.613 | **0.521** | MISSED |
| 2 | Share-first-word fraction | < 0.45 | 0.747 | 0.667 | **0.576** | MISSED |
| 3 | Top connective bigram `higia hazman` rate | < 15 / 100 | 38 (15.3/100) | 18.2 raw avg | **6 (5.1/100)** | MET |
| 4 | Within - across mood cosine delta | > 0.05 | +0.020 | +0.019 | **+0.040** | NEAR-MISS |

Magnitude of improvement (normalized, vs baseline subsampled to the same N=118):
- Top-pair cosine: 0.613 -> 0.521 (drop of 0.091). Misses the 0.45 bar but the worst-case duplicate is gone.
- Share-first-word: 0.667 -> 0.576 (drop of 0.091). Misses 0.45 but well below the 0.75 baseline.
- `higia hazman`: 18.2 -> 6 raw (about 3x fewer). CLEARS target.
- Mood delta: +0.019 -> +0.040 (doubled; across-mood distance fell, so moods sound more distinct). NEAR-MISS on >0.05.

## 1. Top-pair char-3gram cosine similarity  (target < 0.45)

- Baseline full: **0.677** (the Avicii->Zedd near-duplicate, pair #1 in the original study).
- Baseline subsampled to N=118: **0.613** avg over 30 trials (still far above target -- size is NOT the cause).
- v2: **0.521**  ->  MISSED. Below baseline by a wide margin, but above the 0.45 bar.

The catastrophic verbatim collapse is gone: the worst v2 pair is 0.521, vs the baseline's 0.677.
Higher temperature stopped identical prompts (recurring song pairs) from collapsing onto one
phrasing. The residual ~0.52 pairs are same-artist intros that still share connective tissue.

## 2. Share-first-word  (target < 0.45)

- Baseline full: **0.747** (75% of scripts shared a first word).
- Baseline subsampled (N=118): **0.667**.
- v2: **0.576**  ->  MISSED (down from 0.75, but above the 0.45 bar).

The rotating opener set is clearly working: scripts now begin with vibe lines, questions, scenes,
and facts instead of the same `[artist] hish'ir otanu` lead-in. The residual sharing is dominated
by the 16 hard-coded `u-mashehu tov la-derech` good_thing openers -- excluding those, the song-beat
openers are highly varied (see section 7). Distinct first words: baseline 95/249, v2 70/118.

## 3. Most-frequent connective bigram  (target equiv < 15 / 100, i.e. < ~6 raw on 118)

The baseline's signature tic `higia hazman` fired **38 times (15.3 / 100)**.
In v2 it fired **6 times (5.1 / 100)**  ->  MET (below 15/100 and at the ~6 raw bar).

Subsampled baseline `higia hazman` averaged **18.2 raw** over 30 N=118 trials -- so even after
normalizing for size, v2 cut this connective tic by roughly 3x.

IMPORTANT nuance: the *overall* most-frequent bigram in v2 is no longer `higia hazman`. It is the
`good_thing` brand `u-mashehu tov` / `tov la-derech` at 16 each -- but that is the intentional,
hard-coded brand printed on all 16 good_thing beats, NOT a temperature-driven structural tic. The
connective tic that the target tracks (`higia hazman`) dropped to 6.

## 4. Within-mood vs across-mood cosine delta  (target > 0.05)

- Baseline full: within 0.140 / across 0.119 / delta **+0.020**.
- Baseline subsampled: within 0.138 / across 0.119 / delta **+0.019**.
- v2: within 0.141 / across 0.102 / delta **+0.040**  ->  NEAR-MISS on the >0.05 bar, but DOUBLED vs baseline.

The per-mood register pools are starting to bite: across-mood distance dropped from 0.119 to 0.102
(different moods now share less wording), so the delta widened from +0.020 to +0.040. It falls just
short of the +0.05 absolute target, but the direction and 2x magnitude confirm the per-mood
register change is real and measurable (in the baseline it was statistical noise).

## 5. TTR + cliche-tic per-100 rates

- Type-token ratio: baseline **0.305** (1693 unique / 5556 tokens)  ->  v2 **0.418** (1115 / 2667), a large
  jump in lexical variety (+37% relative). TTR does rise as N shrinks, but the gain dwarfs any
  size drift, and every cliche tic below is rate-normalized so it is size-independent.

| cliche tic | baseline raw / per100 | baseline sub (N=118 avg raw) | v2 raw / per100 | direction |
|---|---|---|---|---|
| higia hazman (`הגיע הזמן`) | 38 / 15.3 | 18.2 | 7 / 5.9 | down |
| tagbir et havolume (`תגביר את הווליום`) | 10 / 4.0 | 4.5 | 0 / 0.0 | down |
| tarim et havolume (`תרים את הווליום`) | 4 / 1.6 | 2.3 | 0 / 0.0 | down |
| az bo (`אז בוא`) | 27 / 10.8 | 13.1 | 8 / 6.8 | down |
| aval achshav (`אבל עכשיו`) | 26 / 10.4 | 12.5 | 0 / 0.0 | down |

Every tic is down or eliminated. `tagbir/tarim et havolume` (the forced volume-bump close) and
`aval achshav` fell to ZERO occurrences in v2.

## 6. REGRESSION SCAN (did the higher temperature break anything?)

### 6a. Empty / garbage JSON beats (banter / trivia / twoTruths)
- v2: **2 empty** out of 32 such beats (6.2%). Both empties are `banter` (seq 8, seq 23),
  returned with NO error field -- i.e. the model produced un-parseable / empty JSON for the
  two-host dialogue, not a network failure.
- baseline: **0 empty** out of 32 (0.0%). (The baseline's single global null was a song beat
  that timed out at the network layer, not a JSON-parse failure.)
- VERDICT: a small NEW regression -- 2 banter beats came back empty under the higher temperature,
  whereas the baseline had 0 empty JSON beats. Low volume (1.7% of v2 rows) but worth a guard:
  banter / two-host JSON should use a LOWER temperature (the original R1 recommendation) or retry
  on empty parse.

### 6b. Dangling song-name lines (`am shel`, bare trailing `shel` / `am` / `&`)
Strict trailing-token detector: v2 has **1** flagged line (seq 41, a trivia beat ending in a bare `shel!`).
Broadening to the empty-title-SLOT pattern (`shel` / `am` immediately followed by comma / period /
end-of-line -- the song-title placeholder dropped), across the whole corpus:

- baseline: **40 / 249 scripts (16.1%)** had an empty title slot (26 on song/cue beats).
- v2: **8 / 118 scripts (6.8%)** (5 on song/cue).

VERDICT: the dangling fix IMPROVED this markedly (16.1% -> 6.8%) but did NOT reach ZERO. A handful
of song intros still trail off with a bare `shel` where the (English) title should slot in -- e.g.
seq 70 (`...la-shir ha-ba, shel Zedd.`), seq 89 (`...nechapes oto yachad be-toch shel,`), seq 97
(`...ba-bitsua shel,`). These cluster where the title is English and the Hebrew line leaves the slot
empty instead of transliterating. NOT a temperature regression (baseline was worse) -- residual from
the same root cause the fix only partly closed.

### 6c. Naming accuracy on song / cue beats
Strict check (does the upcoming title OR artist appear, by Hebrew token match?):
- baseline: 45 / 157 = 28.7% on song+cue (cue beats name 0 / 16 by design -- they point at a moment).
- v2: 18 / 53 = 34.0% -- slightly BETTER than baseline, NOT a regression.

The low absolute number is largely a MEASUREMENT artifact: titles are English (`The Middle`,
`Waiting For Love`) while scripts are Hebrew, so a Hebrew-token match cannot credit them, and `cue`
beats intentionally do not name the song. Where v2 does name, it names the ARTIST correctly (seq 70
Zedd, seq 72 Asaf Amdursky, seq 73 Moti Taka). No naming regression from the higher temperature.

### 6d. Hebrew fluency / word-budget overruns
- v2 word counts: min 2, median 21, max **42**, mean 23.4. Baseline max was 47, mean 22.6.
- Scripts over 45 words: **0** (none). The higher temperature did NOT cause runaway length.
- Spot-reading the v2 scripts shows fluent, idiomatic Hebrew with varied register; no obvious
  garbage, no broken encoding, no English leakage beyond the (expected) English song titles.

## 7. BEFORE vs AFTER opener examples

BEFORE (baseline) -- the cloned `[artist] hish'ir otanu/otcha ...` lead-in that the original study
flagged as the dominant samey opener (these are the near-duplicate pairs from that study):

- seq 85 (song/party): אביצ'י השאיר אותנו לחכות לאהבה, אז בוא נמצא את האמצע עם זד והלהיט שפשוט יעיף אותך עכשיו לאוויר.
- seq 247 (song/morning): אביצ'י השאיר אותך בציפייה לאהבה, אז בוא נמצא את האמצע של הבוקר הזה עם זד והלהיט שיעיף אותך קדימה.
- seq 139 (song/late_night): מוטי טקה השאיר אותנו בלילה, אבל עכשיו הגיע הזמן להתמודד עם המפלצות שלך בגרסה הכי טובה של.
- seq 240 (song/morning): מוטי טקה השאיר אותנו בלילה, אבל הגיע הזמן לעבור לאמצע עם זד ו-, כי אנחנו יודעים שזה הקטע הכי אהוב עליך בבוקר.
- seq 179 (song/focus): קלווין האריס שאל כמה עמוקה האהבה שלך, אז בוא נראה אם היא מספיקה בזמן שאתה מחכה לאביצ'י ב-.
- seq 243 (song/morning): קלווין האריס שאל כמה עמוקה האהבה שלך, אז בוא נבדוק את זה עם אביצ'י שמחכה לה, הנה.

AFTER (v2) -- varied angles (vibe / scene / question / fact / memory); each opens differently:

- seq 69 (song/late_night): הקול של ליעם חכמון דועך, ועכשיו נלווה אותך בשעה הזו
- seq 71 (song/late_night): זד דועך בשקט, ואנחנו עוצרים לרגע כדי להקשיב למילים של שתשרף האהבה בביצוע אסף אמדורסקי, שמבקשות מאיתנו להתייחד עם הכאב של היום
- seq 73 (song/late_night): הלילה של מוטי טקה התפוגג, ובתוך השקט של היום הזה נקשיב לשתשרף האהבה של אסף אמדורסקי, שיר שמבקש מאיתנו רגע של התכנסות
- seq 88 (song/focus): יצאת מאיזה ילד מוזר ועכשיו מגיע הזמן להתניע בחושך עם בלילה של מוטי טקה, תן לזה לעטוף אותך בדרך הביתה.
- seq 89 (song/focus): מרגיש שחסר לך קצת איזון אחרי מוטי טקה? בוא נחפש אותו יחד בתוך של, אולי נצליח למצוא את האמצע בדרך.
- seq 90 (song/focus): בוא נצלול ישר לתוך המועדף עליך.

## 8. SCORECARD

| Target | Before | After | Hit? |
|---|---|---|---|
| 1. Top-pair cosine < 0.45 | 0.677 | 0.521 | NO (improved, 0.677->0.521) |
| 2. Share-first-word < 0.45 | 0.75 | 0.576 | NO (improved, 0.75->0.576) |
| 3. `higia hazman` < 15/100 | 15.3/100 (38 raw) | 5.1/100 (6 raw) | YES |
| 4. Within-vs-across delta > 0.05 | +0.020 | +0.040 | NEAR (2x better, just under bar) |

Regressions found: (a) 2 empty `banter` JSON beats under high temperature (baseline had 0) --
recommend lower temp / retry for two-host JSON; (b) residual empty-title-slot dangling at 6.8%
(down from 16.1%, not eliminated). No length, fluency, or naming regressions.
