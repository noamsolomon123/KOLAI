# KOLAI DJ Output Diversity - Findings

Corpus: docs/studies/corpus.jsonl - 250 rows, **249 with non-null djScript**.
Analysis script: android/artifacts/div_analysis.py.
Pairs compared: 30,876 (all non-null script pairs).

## TL;DR verdict

- Mean pairwise **char-3gram cosine = 0.123**, median 0.115. 90th pct 0.204, 95th 0.238, 99th 0.322, max 0.677.
- Mean pairwise **3-word-shingle Jaccard = 0.001**, median 0.000, 95th pct 0.000, max 0.286.
- **Within-mood mean cosine 0.140 vs across-mood 0.119** (delta +0.020).
- Type-token ratio 0.305 (1693 unique / 5556 tokens).

## 1. Pairwise similarity distribution

### char-3gram cosine histogram
| range | pairs | pct |
|---|---|---|
| 0.00-0.30 | 30,431 | 98.6% |
| 0.30-0.50 | 429 | 1.4% |
| 0.50-0.60 | 13 | 0.0% |
| 0.60-0.70 | 3 | 0.0% |
| 0.70-0.80 | 0 | 0.0% |
| 0.80-0.90 | 0 | 0.0% |
| 0.90-1.01 | 0 | 0.0% |

### 3-word-shingle Jaccard histogram
| range | pairs | pct |
|---|---|---|
| 0.00-0.05 | 30,685 | 99.4% |
| 0.05-0.10 | 168 | 0.5% |
| 0.10-0.20 | 18 | 0.1% |
| 0.20-0.30 | 5 | 0.0% |
| 0.30-0.50 | 0 | 0.0% |
| 0.50-1.01 | 0 | 0.0% |

### TOP 15 most-similar script pairs (by char-3gram cosine)

**1. cosine=0.677, jaccard(3-shingle)=0.207** - beats: song/song, moods: party/morning
  - seq 85: אביצ'י השאיר אותנו לחכות לאהבה, אז בוא נמצא את האמצע עם זד והלהיט שפשוט יעיף אותך עכשיו לאוויר.
  - seq 247: אביצ'י השאיר אותך בציפייה לאהבה, אז בוא נמצא את האמצע של הבוקר הזה עם זד והלהיט שיעיף אותך קדימה.

**2. cosine=0.657, jaccard(3-shingle)=0.269** - beats: song/song, moods: focus/morning
  - seq 179: קלווין האריס שאל כמה עמוקה האהבה שלך, אז בוא נראה אם היא מספיקה בזמן שאתה מחכה לאביצ'י ב-.
  - seq 243: קלווין האריס שאל כמה עמוקה האהבה שלך, אז בוא נבדוק את זה עם אביצ'י שמחכה לה, הנה.

**3. cosine=0.619, jaccard(3-shingle)=0.273** - beats: handover/handover, moods: mix/mix
  - seq 4: שש וחצי, האור בחוץ מתרכך בדיוק כמו שצריך. תעצום עיניים, תן לקפליו עם עולם הבא לקחת אותך רחוק.
  - seq 17: שש וחצי, האור בחוץ מתרכך בדיוק כמו שצריך. תעלה קצת את הווליום עם של.

**4. cosine=0.596, jaccard(3-shingle)=0.286** - beats: song/song, moods: focus/morning
  - seq 189: קלווין האריס שאל כמה עמוקה האהבה שלך, אז בוא נבדוק מה קורה כשצוקוש מגיע לענות לו עם אישהשלי, השיר שאתה הכי אוהב.
  - seq 243: קלווין האריס שאל כמה עמוקה האהבה שלך, אז בוא נבדוק את זה עם אביצ'י שמחכה לה, הנה.

**5. cosine=0.588, jaccard(3-shingle)=0.179** - beats: news/news, moods: late_night/focus
  - seq 142: היה וייב מטורף, אה? הממשלה אישרה עוד תקציב לאוטובוסים, אז אולי סוף סוף נגיע ליעד בלי להחליק על סליים של יובי.
  - seq 184: וואלה, פייפרבוי פייב היה אש. הממשלה אישרה תקציב תחבורה, אולי סוף סוף נגיע ליעד בלי להחליק.

**6. cosine=0.586, jaccard(3-shingle)=0.027** - beats: song/song, moods: morning/morning
  - seq 229: אביצ׳י אולי חיכה לאהבה, אבל אצלך זה כבר כאן - ששון איפרם שאולוב בא להזכיר לך שתמיד אוהב אותי, תגביר את הווליום
  - seq 230: ששון איפרם שאולוב מזכיר לך שהוא תמיד אוהב אותך, אבל קפליו כבר לוקח אותך לסיבוב בעולם הבא, תגביר רגע את הווליום.

**7. cosine=0.552, jaccard(3-shingle)=0.136** - beats: handover/handover, moods: mix/mix
  - seq 11: שש וחצי, האור בחוץ מתרכך וזה בדיוק הזמן לנקות את הראש. קח אוויר עם של.
  - seq 17: שש וחצי, האור בחוץ מתרכך בדיוק כמו שצריך. תעלה קצת את הווליום עם של.

**8. cosine=0.546, jaccard(3-shingle)=0.121** - beats: song/song, moods: focus/morning
  - seq 193: סגרנו את החלום המתוק של המאמאז, אז בוא ננחת רגע למציאות עם איזי והשיר לפני שחלמתי על ילד, אולי תתעורר שם סוף
  - seq 245: איזה וייב של נוסטלגיה ב-, אבל בוא ננחת לקרקע המציאות עם - והשיר לפני שחלמתי על ילד, אולי תירגע קצת.

**9. cosine=0.533, jaccard(3-shingle)=0.000** - beats: song/song, moods: late_night/late_night
  - seq 122: איזי השאיר אותנו במחשבות על מה שהיה, ועכשיו ננסה למצוא קצת שקט בתוך הלילה הזה עם של זד.
  - seq 123: השיר הזה של זד השאיר אותנו במקום של מחשבה, ועכשיו אלה לאנגלי תלווה אותנו עם הצלילים של ' אל תוך הלילה.

**10. cosine=0.522, jaccard(3-shingle)=0.233** - beats: song/song, moods: focus/focus
  - seq 179: קלווין האריס שאל כמה עמוקה האהבה שלך, אז בוא נראה אם היא מספיקה בזמן שאתה מחכה לאביצ'י ב-.
  - seq 189: קלווין האריס שאל כמה עמוקה האהבה שלך, אז בוא נבדוק מה קורה כשצוקוש מגיע לענות לו עם אישהשלי, השיר שאתה הכי אוהב.

**11. cosine=0.520, jaccard(3-shingle)=0.057** - beats: song/song, moods: focus/morning
  - seq 197: אז הלך הכסף עם גלאנטיס, אבל אל תדאג, קלווין האריס כבר מגיע לבדוק בדיוק כמה עמוקה האהבה שלך.
  - seq 213: היה נחמד לחכות לאהבה עם אביצ'י, אבל עכשיו תגביר קצב ותבדוק בדיוק כמה עמוקה האהבה של קלווין האריס, שבת שלום יא אלוף.

**12. cosine=0.512, jaccard(3-shingle)=0.115** - beats: news/news, moods: late_night/focus
  - seq 134: אסף אמדורסקי השאיר אותנו שרופים לגמרי. הממשלה אישרה תקציב תחבורה, אז אולי סוף סוף תגיע לאנשהו בזמן.
  - seq 184: וואלה, פייפרבוי פייב היה אש. הממשלה אישרה תקציב תחבורה, אולי סוף סוף נגיע ליעד בלי להחליק.

**13. cosine=0.512, jaccard(3-shingle)=0.067** - beats: good_thing/good_thing, moods: party/party
  - seq 69: ומשהו טוב לדרך - אביצ'י האגדי התחיל את הדרך שלו בכלל כשהוא מעלה מרימיקסים לפורומים באינטרנט בחדר השינה שלו. תעלה ווליום, הנה המטורף.
  - seq 72: ומשהו טוב לדרך - אלכס גאסקרט, הסולן של אול טיים לאו, בכלל התחיל את הדרך שלו כשהוא מנגן על תופים בלהקה הראשונה שלו. הנה הוא עם מונסטרס, תגביר את הווליום!

**14. cosine=0.509, jaccard(3-shingle)=0.062** - beats: song/song, moods: party/morning
  - seq 89: זד השאיר אותך בלב העניינים, אבל עכשיו מלך הפופ מגיע כדי להזכיר לך שאולי לאף אחד לא אכפת, אבל כאן בהחלט כן.
  - seq 249: מוטי טקה השאיר אותך בלילה, אבל עכשיו צוקוש מגיע כדי להזכיר לך מי האישהשלי שלך באמת.

**15. cosine=0.508, jaccard(3-shingle)=0.129** - beats: song/song, moods: late_night/morning
  - seq 139: מוטי טקה השאיר אותנו בלילה, אבל עכשיו הגיע הזמן להתמודד עם המפלצות שלך בגרסה הכי טובה של.
  - seq 240: מוטי טקה השאיר אותנו בלילה, אבל הגיע הזמן לעבור לאמצע עם זד ו-, כי אנחנו יודעים שזה הקטע הכי אהוב עליך בבוקר.

## 2. Opener analysis (anti-repetition ring effectiveness)

- Distinct first-word openers: 95 (out of 249 scripts). 186 scripts (75%) share their first word with at least one other.
- Distinct first-2-word openers: 169. 117 scripts (47%) share a 2-word opener.
- Distinct first-3-word openers: 204. 68 scripts (27%) share a 3-word opener.

### Most repeated FIRST WORD
| first word | count |
|---|---|
| אחרי | 30 |
| ומשהו | 16 |
| תגיד | 14 |
| יובי | 13 |
| איזה | 12 |
| מוטי | 8 |
| היה | 8 |
| זד | 7 |
| תקשיב | 7 |
| קלווין | 7 |
| שבע | 6 |
| אביצ | 5 |
| אמדורסקי | 4 |
| ניקלבק | 4 |
| ג | 4 |

### Most repeated 2-word openers
| opener | count |
|---|---|
| ומשהו טוב | 16 |
| קלווין האריס | 7 |
| איזה וייב | 5 |
| אביצ י | 5 |
| יובי השאיר | 5 |
| שבע ארבעים | 4 |
| מוטי טקה | 4 |
| תקשיב רגע | 4 |
| זד השאיר | 4 |
| קפליו שלח | 4 |
| שש וחצי | 3 |
| יובי הזה | 3 |
| תקשיב טוב | 3 |
| מוטי טאקה | 3 |
| אמדורסקי שרף | 3 |

### Most repeated 3-word openers
| opener | count |
|---|---|
| ומשהו טוב לדרך | 16 |
| יובי השאיר אותנו | 4 |
| קלווין האריס שאל | 4 |
| שש וחצי האור | 3 |
| זד השאיר אותך | 3 |
| קפליו שלח אותך | 3 |
| מוטי טקה השאיר | 3 |
| איזה חלום מתוק | 2 |
| תגיד מייקל ג | 2 |
| תגיד אביצ י | 2 |
| תקשיב רגע לאיך | 2 |
| תקשיב טוב לאיך | 2 |
| איזה וייב של | 2 |
| יובי אמר לטס | 2 |
| אביצ י השאיר | 2 |

## 3. Vocabulary

- Total tokens 5556, unique 1693, **TTR 0.305**. (Stopword set: 9 short high-doc-freq tokens, data-derived.)

### Most over-represented content words (excl. stopwords + song/artist names)
| word | count |
|---|---|
| בוא | 66 |
| אותך | 58 |
| הזה | 58 |
| עכשיו | 53 |
| אחרי | 46 |
| הזמן | 43 |
| הגיע | 41 |
| תגביר | 40 |
| נראה | 37 |
| קצת | 29 |
| הוא | 29 |
| שלך | 29 |
| שלו | 29 |
| טוב | 26 |
| הווליום | 24 |
| אתה | 23 |
| לנו | 23 |
| האריס | 23 |
| זד | 23 |
| הנה | 22 |
| בדיוק | 22 |
| השאיר | 22 |
| הלילה | 22 |
| מוטי | 21 |
| קלווין | 21 |
| אם | 21 |
| אני | 20 |
| הכי | 20 |
| כבר | 19 |
| רגע | 19 |

### Most over-represented bigrams
| bigram | count |
|---|---|
| הגיע הזמן | 38 |
| אז בוא | 28 |
| אבל עכשיו | 26 |
| את הווליום | 24 |
| קלווין האריס | 21 |
| אביצ י | 19 |
| ומשהו טוב | 16 |
| טוב לדרך | 16 |
| קבל את | 13 |
| השאיר אותנו | 13 |
| כמה עמוקה | 13 |
| עמוקה האהבה | 13 |
| להזכיר לך | 12 |
| נראה לי | 12 |
| עכשיו הגיע | 12 |
| מוטי טקה | 11 |
| עם של | 11 |
| של יובי | 11 |
| לטס גו | 11 |
| אושר כהן | 11 |
| תגביר את | 10 |
| נראה לך | 10 |
| האהבה שלך | 10 |
| לך את | 9 |
| ליעם חכמון | 9 |

### Most over-represented trigrams
| trigram | count |
|---|---|
| ומשהו טוב לדרך | 16 |
| כמה עמוקה האהבה | 13 |
| אבל עכשיו הגיע | 11 |
| עכשיו הגיע הזמן | 11 |
| תגביר את הווליום | 10 |
| עמוקה האהבה שלך | 10 |
| אבל הגיע הזמן | 8 |
| בוא נראה אם | 7 |
| מייקל ג קסון | 6 |
| זה ג אז | 6 |
| עם אביצ י | 6 |
| נראה לך כמו | 5 |
| את האמצע עם | 5 |
| האמצע עם זד | 5 |
| מה זה ג | 5 |
| אבל אל תדאג | 5 |
| תרים את הווליום | 4 |
| לפני שחלמתי על | 4 |
| שחלמתי על ילד | 4 |
| הזה קבל את | 4 |
| אני מהמר על | 4 |
| נגלה את האמת | 4 |
| נראה לי הוא | 4 |
| נראה לך טיפוס | 4 |
| טוב לדרך ידעת | 4 |

## 4. Breakdown by beat and mood

### By BEAT - count + mean intra-beat cosine (higher = more repetitive)
| beat | scripts | mean intra-beat cosine |
|---|---|---|
| recap | 2 | 0.348 |
| good_thing | 16 | 0.289 |
| trivia | 16 | 0.257 |
| cue | 16 | 0.226 |
| news | 12 | 0.223 |
| banter | 16 | 0.174 |
| weather | 13 | 0.167 |
| handover | 16 | 0.143 |
| song | 141 | 0.141 |
| open | 1 | n/a |

### By MOOD - count + mean intra-mood cosine
| mood | scripts | mean intra-mood cosine |
|---|---|---|
| party | 50 | 0.147 |
| mix | 50 | 0.144 |
| late_night | 49 | 0.142 |
| morning | 50 | 0.135 |
| focus | 50 | 0.131 |

**Within-mood vs across-mood:** within 0.140, across 0.119. A near-zero delta means mood barely changes the wording.

### Word-count distribution
- min 4, median 21, 90th 37, max 47, mean 22.6.

## 5. ROOT CAUSE

### 5a. No sampling temperature is set (GeminiTextClient.kt) - CONFIRMED
`android/voice/src/main/java/ai/kolai/voice/GeminiTextClient.kt` `complete()` builds the
request body as ONLY:

    {"contents":[{"parts":[{"text": prompt}]}]}

There is **no `generationConfig` object at all** - no `temperature`, no `topP`, no `topK`,
no `seed`. The Gemini API therefore applies the model default (for the Gemini 2.x/Flash
family the default temperature is ~1.0, but with NO top-p/top-k floor and a fixed prompt the
practical entropy is low, and on the free tier the model is comparatively conservative). The
upshot, visible in the corpus: when two prompts are near-identical (same beat + same
prev/next songs recurring in the rotation), the outputs collapse onto the same phrasing.
The clearest proof is pair #1 (cosine 0.677) and pairs #2/#10 (Calvin Harris -> Avicii)
where the model reproduces almost word-for-word the SAME opening clause on different days.

### 5b. The prompt skeleton is ~95% FIXED; variety is one 5-item nudge
`android/station/src/main/java/ai/kolai/station/DjBrain.kt` composes every song-intro prompt
from the SAME ordered fragments: `personaLine()` + prevLine + next-song line + `depthLine()`
+ `namingLine()` + `witLine()` + `oneListenerLine()` + (maybeSkip) + `formatLine()`
+ moodLine + calendarLine + tasteWink. Every one of those fragments is a fixed Hebrew
string (or a fixed template with the song name slotted in). The ONLY per-call variation is:

- **`flavorLine()`** - picks 1 of **only 5** `FLAVOR_NUDGES` at random. Five. With ~141 song
  beats that is ~28 reuses of each nudge, and they are gentle ("this time a small personal
  angle", "this time one sentence only") - they nudge LENGTH/tone, not the ANGLE or the
  STRUCTURE of the line.
- **`avoidLine()`** - the anti-repetition ring: lists the last **3** of the last 8 lines
  (each truncated to 60 chars) and says "do not repeat these openers/phrasings". The ring is
  too small and too local: it only fights the *immediately preceding* 3 lines, so the engine
  happily re-uses an opener it last said 10 lines ago - and across a long broadcast the SAME
  song pairs recur (taste pool is finite), regenerating near-duplicate intros that the ring
  never saw together.

The structural sameness is the dominant driver. Every line is "[outgoing song did X], [az/aval]
[connector] [incoming artist] [verb] ... [tagviar et havolume / hagia hazman]". The corpus
proves it: the bigram `הגיע הזמן` fires 38x, `אז בוא` 28x, `אבל עכשיו` 26x, `את הווליום` 24x,
the trigram `עכשיו הגיע הזמן` 11x and `תגביר את הווליום` 10x. These are not song names - they
are the DJ's structural tics, baked in because the prompt asks for the same move every time.

### 5c. Mood barely moves the wording
Within-mood mean cosine (0.140) is essentially equal to across-mood (0.119); the +0.020 delta
is noise. `moodLine()` appends a single Hebrew sentence; it is swamped by the fixed skeleton.
Mood currently changes the MUSIC but not the DJ's language in any measurable way.

### 5d. Where it is worst (by beat)
The branded / templated beats are the most repetitive, exactly as expected:
`good_thing` (0.289) is forced to open with the verbatim brand `ומשהו טוב לדרך - ` (16/16
scripts share that 3-word opener); `trivia` (0.257) and `cue` (0.226) all share the same
quizmaster / "shim lev la-rega" framing. `recap` (0.348) is templated too (only 2 samples).
Plain `song` intros are the LEAST repetitive in aggregate (0.141) only because the song NAMES
differ - but their *connective tissue* is the most cloned (see 5b bigrams).

## 6. HOW SAMEY IS IT - verdict

**Moderately, and very visibly at the seams.** In bulk the corpus is not a disaster: 98.6%
of all pairs are below 0.30 cosine and the median is 0.115 - the song names give surface
variety. BUT the user's complaint ("close results / same prompts") is REAL and the metrics
localize it precisely:

1. **Openers are heavily cloned.** 75% of scripts share their first word; 47% share the first
   two words. The anti-repetition ring is NOT working at scale.
2. **Recurring song pairs produce near-duplicate intros** - cosine 0.5-0.68 (pairs #1-#15),
   several of them effectively the same sentence (#1, #2, #6, #15). When a listener hears the
   rotation loop, THIS is what reads as "samey".
3. **A fixed phrase-kit** ("hagia hazman", "tagviar et havolume", "az bo nir'e", "aval achshav")
   recurs dozens of times - the DJ's verbal fingerprint is too narrow.
4. **Mood and structure never change** - only nouns change.

## 7. RECOMMENDATIONS (drives the #1 build)

### R1. Set a sampling config in GeminiTextClient (highest leverage, ~3 lines)
Add a `generationConfig` to the request body in `complete()`:

    "generationConfig": { "temperature": 1.15, "topP": 0.95, "topK": 64 }

Recommended **temperature 1.1-1.2** for DJ text (creative, short, low-stakes-of-error). Keep a
LOWER value (~0.4) for the `refine()` pass and for banter JSON where structure matters. Easiest
clean design: give `complete()` an optional `temperature` (and topP) parameter so DjBrain can
pass a high value for intros/handovers and a low one for JSON beats. This alone should push the
top-pair cosines down materially because identical prompts will stop collapsing to one phrasing.

### R2. Rotate among N distinct prompt ANGLES per call (not a tone nudge)
Replace the 5 tone-nudges with a rotating pool of **12+ ANGLES** that change WHAT the line is
about and HOW it is built. Pick the angle by `(seq + hash(songId)) % N` (deterministic but
non-repeating) and EXCLUDE the last 3-4 angles used (a proper ring on ANGLES, not on text).
Concrete Hebrew angle prompts to drop in (each replaces the generic "write a link" instruction):

1. זווית "וייב": תאר במשפט אחד את התחושה/הטקסטורה של השיר הבא - לא מה הוא, אלא איך הוא מרגיש.
2. זווית "שאלה למאזין": פתח בשאלה אמיתית וקצרה אל המאזין שנקשרת לשיר, בלי לענות עליה.
3. זווית "זיכרון": קשר את השיר לרגע/מקום/תקופה קטנה (בלי לציין שנה ודאית) - נוסטלגיה זריזה.
4. זווית "ניגוד": חבר בין השיר שהסתיים לשיר הבא דרך ניגוד חד (איטי->מהיר, עצוב->שמח).
5. זווית "עובדה מפתיעה": פתח בעובדה קטנה ואמיתית על האמן הבא, ומשם אל השיר. אסור להמציא.
6. זווית "וידוי": וידוי קטן ואישי של השדרן ("האמת? חיכיתי כל הבוקר לשיר הזה...").
7. זווית "שעה ביום": עגן את הרגע בשעה/אור/מזג האוויר ותן לזה לזרום אל השיר.
8. זווית "הוראת האזנה": כוון את האוזן לרגע ספציפי בשיר - דרופ, מעבר, סולו (כמו ה-cue).
9. זווית "סצנה": צייר תמונה קטנה של איפה המאזין עכשיו (פקק, מטבח, מקלדת) והכנס את השיר לתוכה.
10. זווית "ישר ויבש": בלי קישוט - רק משפט אחד חד שמכריז על השיר. לקוני בכוונה.
11. זווית "אנקדוטה על השם": משחק מילים או סיפור קצר סביב שם השיר/האמן עצמו.
12. זווית "המשכיות": התייחס למה שקרה קודם ברדיו ובנה גשר רגשי קטן אל הבא.

Crucially: each angle should also let the line START differently (some open with a question,
some with a fact, some with a scene) - that directly attacks the 75% shared-opener finding.

### R3. Vary prompt STRUCTURE, not just content
Today every prompt is persona+prev+next+depth+naming+wit+listener+format in the SAME order with
the SAME mandatory moves. Make `namingLine()`/`witLine()` OPTIONAL per call (e.g. drop the
wordplay demand 40% of the time, drop explicit back-announce 50% of the time) so the model is
not always forced into "[song] -> wordplay -> tagviar et havolume". Forcing wordplay + naming
+ volume-bump on every line is exactly what manufactures `תגביר את הווליום` x40.

### R4. Ban the phrase-kit explicitly + enlarge the ring
- Add the over-used phrases to the avoid instruction as a *banned-cliches* list:
  `הגיע הזמן`, `תגביר/תרים את הווליום`, `אז בוא נראה`, `אבל עכשיו`, `השאיר אותנו/אותך`,
  `כמה עמוקה האהבה` (Calvin Harris), `בוא נמצא את האמצע`. Rotate which are banned per call so
  the language has to find new connectors.
- Enlarge the anti-repetition ring: `MEMORY_SIZE 8 -> 24`, `AVOID_COUNT 3 -> 8`, and store a
  normalized OPENER (first 3-4 words, stopwords stripped) in a SEPARATE longer-lived set so
  the engine refuses to reuse an opener for, say, 40 lines - not just 3.

### R5. Make mood actually change the voice
Give each mood a small POOL of register/vocabulary directives (not one line): e.g. party = short,
exclamatory, slangy; late_night = hushed, longer breaths, sensory; focus = calm, minimal, almost
whispered. Rotate within the pool. Target: within-mood cosine should DROP (more internal variety)
while across-mood DISTANCE should RISE (moods sound different). Right now both are ~0.13.

### R6. De-template the branded beats
`good_thing` must open with the brand, but let the SECOND half rotate structure (fact vs
headline vs mini-story) and forbid the verbatim "tagviar et havolume" close. `trivia`/`cue`
should rotate the framing sentence among 3-4 variants instead of one fixed `QUIZMASTER_FRAMING`.

### R7. Verify after the build
Regenerate a fresh sample with R1+R2+R3 applied and re-run `android/artifacts/div_analysis.py`.
Success targets: top-pair cosine **< 0.45** (from 0.677), share-first-word **< 45%** (from 75%),
top connective bigram **< 15** occurrences (from 38), within-vs-across mood delta **> 0.05**.
