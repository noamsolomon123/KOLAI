# KOLAI DJ Output Diversity - v3 vs Baseline Findings

Dimension: output diversity. Comparison of corpus-v3.jsonl (v3 = all fixes applied: prompt
variety + sampling temperature, dangling-name guard incl. two-host beats, somber suppression,
plural/hallucination prompts, JSON-beat retry) vs corpus.jsonl (baseline = original code).
Metrics recomputed with the exact methodology of android/artifacts/div_analysis.py (char-3gram
cosine on tag-stripped tokens) so numbers are directly comparable to findings-diversity.md.
Analysis driver: docs/studies/_div_v3_compare.py.

- Baseline: 250 rows, 249 non-null djScripts (1 null), 32 two-host (tagged) scripts.
- v3: 250 rows, 250 non-null djScripts (0 null), 32 two-host (tagged) scripts.
- Near size-matched (249 vs 250). Pairwise comparisons: baseline 30,876, v3 31,125.

## TL;DR

v3 IMPROVED on every headline diversity metric. Top-pair cosine 0.677 -> 0.556, share-first-word
(tag-stripped) 74.7% -> 64.4%, headline cliche bigram hagia-hazman 38 -> 13, TTR 0.305 -> 0.339,
mean pairwise cosine 0.123 -> 0.109. The structural verbal tics that defined the baseline
(hagia hazman, tagviar et havolume) are largely gone. Two soft regressions to watch: the
within-vs-across mood delta is essentially unchanged (still ~+0.02; mood still barely moves the
voice), and a generic new connective bigram (lecha-et / to-you the) rose from 9 -> 29 scripts.

## Scorecard (BEFORE -> AFTER)

| metric | baseline | v3 | delta | verdict |
|---|---|---|---|---|
| top-pair char-3gram cosine | 0.677 | 0.556 | -0.120 | improved |
| mean pairwise cosine | 0.123 | 0.109 | -0.015 | improved |
| median pairwise cosine | 0.115 | 0.101 | -0.014 | improved |
| share-first-word RAW | 75.9% | 65.6% | -10.3pp | improved |
| share-first-word TAG-STRIPPED | 74.7% | 64.4% | -10.3pp | improved |
| distinct first words (stripped) | 95 | 131 | +36 | improved |
| most-frequent bigram count | הגיע הזמן=38 | לך את=30 | n/a | improved (see note) |
| hagia-hazman bigram count specifically | 38 | 13 | -25 | improved |
| within-mood cosine | 0.140 | 0.123 | -0.017 | improved |
| across-mood cosine | 0.119 | 0.105 | -0.014 | improved |
| within-vs-across mood delta | +0.020 | +0.018 | -0.003 | same (still negligible) |
| TTR | 0.305 | 0.339 | +0.034 | improved |

Note on most-frequent bigram: the COUNT dropped 38 -> 30, but the IDENTITY changed. Baseline top
bigram was the structural slogan הגיע הזמן (the time has come); v3 top bigram is the generic
function-word pair לך את (to-you the), which carries no slogan meaning. The qualitative
improvement is larger than the raw 38->30 implies.

### share-first-word RAW vs TAG-STRIPPED
Both corpora contain exactly 32 two-host (A/B) scripts, so the RAW first word is the literal
speaker tag A in 32 scripts of EACH corpus, inflating RAW share-first identically in both.
TAG-STRIPPED is the fair metric: baseline 74.7% -> v3 64.4% (-10.3pp). Because the tag count
is identical across corpora, RAW and STRIPPED move in lockstep (75.9% -> 65.6% raw),
confirming the two-host beats did not distort the v3-vs-baseline comparison.

## Cliche tic rates (per-script hit-rate % and total occurrences; substring match on raw djScript)

| tic (gloss) | baseline hit% (occ) | v3 hit% (occ) | verdict |
|---|---|---|---|
| הגיע הזמן (the time has come) | 15.3% (38) | 5.2% (13) | improved |
| תגביר את הווליום / תרים את הווליום (turn up the volume) | 5.6% (14) | 0.0% (0) | improved |
| אז בוא נראה (so lets see) | 1.2% (3) | 1.6% (4) | regressed |
| אבל עכשיו (but now) | 10.4% (26) | 0.8% (2) | improved |
| השאיר אותנו / השאיר אותך (left us / left you) | 8.0% (20) | 5.6% (14) | improved |
| כמה עמוקה האהבה (how deep is your love / Calvin Harris) | 5.2% (13) | 1.6% (4) | improved |
| אז בוא (so come) | 10.8% (27) | 9.6% (24) | improved |
| את הווליום (the volume) | 9.6% (24) | 0.0% (0) | improved |

Highlights: turn-up-the-volume and bare the-volume went to ZERO (14->0 and 24->0). but-now
collapsed 26->2. hagia-hazman 38->13. how-deep-is-your-love 13->4. The only tic that did NOT
improve: so-lets-see rose 3->4 (negligible); broader so-come only edged down 27->24 occurrences
and remains the most stubborn structural connector.

## Top similar pair (worst-case cloning)

Baseline #1 (cosine 0.677, seqs [85, 247]), near-verbatim duplicate:
- אביצ'י השאיר אותנו לחכות לאהבה, אז בוא נמצא את האמצע עם זד והלהיט שפשוט יעיף אותך עכשיו לאוויר.
- אביצ'י השאיר אותך בציפייה לאהבה, אז בוא נמצא את האמצע של הבוקר הזה עם זד והלהיט שיעיף אותך קדימה.

v3 #1 (cosine 0.556, seqs [96, 131]), same artist (Amdorski) intro but materially different wording:
- אמדורסקי שרף את הלב, אז בוא נראה מי גנב לך את השקט.
- אמדורסקי שרף כאן את הלב עד היסוד, אז בוא ננקה קצת את האווירה

The worst v3 pair (0.556) is well below the baseline worst (0.677). It does NOT meet the R7
target of <0.45, but the trend is correct and the v3 duplicate is a soft echo, not a near copy.

## Regressions / things to watch

1. Mood still does not move the voice. within-vs-across delta is +0.018 (was +0.020), statistically
   unchanged. R5 (make mood change register/vocabulary) had no measurable effect. Both within
   (0.140->0.123) and across (0.119->0.105) cosine dropped in parallel, so overall text got more
   varied but mood-conditioning did not improve.
2. New generic connective bigram לך את (to-you the) rose 9 -> 29 scripts (now the single most-
   frequent bigram at 30 occurrences). Benign function-word pair, not a slogan, but a new
   concentration worth watching: the model leaned on a different crutch phrase.
3. so-come (אז בוא) barely moved (27->24 scripts; bigram 28->24). That opener structure survived
   the rewrite and is now the most prominent surviving tic.
4. Part of the TTR gain is mechanical. v3 scripts are shorter (median word count 21->19; total
   tokens 5556->4991) and unique tokens essentially flat (1693->1690). TTR rose 0.305->0.339
   partly because the denominator shrank, not only because vocabulary widened. Real diversity
   still improved (top-pair + cliche metrics confirm it), but TTR overstates the gain.

## Verdict

Output diversity IMPROVED. Every primary metric moved the right way: worst-case cloning down
(0.677->0.556), opener repetition down (74.7% -> 64.4% stripped), the defining structural
cliches near-eliminated (volume-bump and but-now to ~zero, hagia-hazman -66%), mean similarity
down and TTR up. The two caveats (mood still not differentiating the voice, and a new generic
crutch bigram) are minor relative to the gains and do not reverse the conclusion.
