# DJ-TEXT BUGS - v3 (all fixes) vs baseline

Corpora: `corpus-v3.jsonl` (ALL fixes: prompt variety+temperature, dangling-name
guard incl. two-host JSON beats, somber suppression, plural/hallucination prompts,
JSON-beat retry) vs `corpus.jsonl` (original code). 250 rows each, aligned by seq
/beat/mood. Detectors are 1:1 ports of the v3 Kotlin guards
(`DjBrain.danglingName`, `oneListenerLine` bans, `calendarLine` somber discipline,
`noInventedFactsLine` targets, `namingLine`). Two-host scripts have their `A:`/`B:`
speaker tags stripped before the connector/dangling scan.

## Scorecard (before -> after)

| Metric | Baseline | v3 | Verdict |
|---|---|---|---|
| Dropped/dangling song name - STRICT (ends on bare connector / `עם של` / empty slot) | 43/250 = 17.2% | 3/250 = 1.2% | IMPROVED |
| Dropped/dangling - BROAD (strict + `עם של` substring + trailing-of/with) | 46/250 = 18.4% | 3/250 = 1.2% | IMPROVED |
| Dropped name - GENUINE (incl. mid-line `של של`/`ל- של`/connector+comma) | 58/250 = 23.2% | 22/250 = 8.8% | IMPROVED (not eliminated) |
| One-listener PLURAL-address - raw term hits (titles stripped) | 1 | 4 | mixed counts, see note |
| One-listener PLURAL-address - GENUINE crowd address | 0 | 0 | SAME (clean) |
| `מאזינים` / `אתם` as direct address anywhere | 0 | 0 | SAME (clean) |
| Somber discipline - leaks (humor/banter/`!`) on the 10 somber rows | 0/10 | 0/10 | SAME (clean) |
| Hallucination markers in trivia/good_thing (years/numbers/superlatives) | 8/32 = 25% | 1/32 = 3.1% | IMPROVED |
| Hallucination - GENUINE invented FACT (excl. playful opinion) | ~5/32 | 0/32 | IMPROVED |
| Naming accuracy - exact title mention on HEBREW-title naming beats | 43/74 = 58.1% | 56/76 = 73.7% | IMPROVED |
| Naming - INTL (Latin) titles | always Latin-stripped by clean() (verbatim impossible, by design) | same | SAME |

## 1. Dropped / dangling song name

The end-dangling guard (deDangleNaming -> repairDangling -> finalDeDangle, plus
the two-host deDangleTurns) is highly effective: STRICT end-dangling dropped from
17.2% to 1.2%. The broad rate matches strict in v3 (3 rows).

RESIDUAL CLASS (NEW finding): a MID-LINE dropped-name class survives in v3 -
22/250 (8.8%) vs 58/250 (23.2%) baseline. Root cause: clean() unconditionally
strips ALL Latin ([A-Za-z]+), so an intl (Latin) title vanishes, leaving the
Hebrew connector skeleton with no name; and danglingName only catches END
position + the `עם של` adjacency, so these mid-line shapes slip through:
  - `של של` adjacency (seq 137: `השקט בחדר הזה כבד, אז אולי המפלצות של של יגרשו את הצללים שקפליו השאיר כאן עכשיו.`)
  - `של עם` adjacency (seq 163: `יובי עשה פה בלאגן, אז כדי לסדר את המצב הנה של עם וייב אופטימי ומעיף שקשה להתעלם ממנו.`)
  - `ל- של` after a stripped Latin title (seq 90: `השאירו אותך שבור, אבל הגיע הזמן לדבר באמת: תגביר ל- של, השיר שחיכית לשמוע כל היום.`)
  - connector + comma mid-sentence (seq 89: `יובי צרח עליך לזוז, אבל נראה שמישהו כאן קצת נשבר בדרך. קבל את של, אולי זה יאחה לך את הלב.`)
  - `של -,` maqaf with stripped name (seq 116: `עזבנו את השפה של, ועכשיו נצלול למחשבות של פעם עם לפני שחלמתי על ילד של -, מין רגע נוסטלגי כזה לפני השינה.`)

UNGUARDED BEATS (NEW finding): weather/news beats are NOT routed through
deDangleNaming (writeBreak sets isNaming=true only on the song fallback), so the
3 STRICT v3 dangling rows are exactly there:
  - seq 87 (weather): `שבע ארבעים בחוץ, שמונה עשרה מעלות של שמש משוגעת. עזוב את המפלצות של בלאקבר, בוא נדבר בשפה של. תהנה מהקצב.`
  - seq 226 (news): `הממשלה סוף סוף הזרימה כסף לתחבורה, אז אולי עכשיו תפסיק לחכות לאוטובוס ותעבור לחכות לאביצ'י עם. איזה וייב של בוקר.`
  - seq 234 (news): `שאבוזי נתן פה אחלה וויב, אבל עם תקציב התחבורה החדש אולי סוף סוף נגיע מהר לעומר אדם, כי זו תהיה מהפכה של`
seq 226 also exists dangling in baseline; seq 87 and 234 are NEW dangling rows in v3.

## 2. One-listener plural-address

GENUINE crowd-address violations: 0 in BOTH corpora. Neither corpus contains
`מאזינים` or `אתם` as address anywhere - the oneListenerLine ban holds.
After stripping song TITLES, v3 shows 4 RAW term hits, all INCIDENTAL (not crowd
address), and all FALSE POSITIVES for the doctrine:
  - seq 35 (trivia): `...שגונב לכולם את הפיצוחים` - idiom (steals everyone's nuts), about the artist as a cartoon
  - seq 41 (trivia): `להקה של חברים בפיג'מה` - `friends` describes the band, not the listener
  - seq 79 (good_thing): `...נפוץ אצל חברים שלו` - the ARTIST's friends
  - seq 105 (song): `...להזכיר לכולם ש-` - the song's message (and a dropped title slot), not a crowd hail
Baseline had 1 raw hit (seq 37: `חמישה אנשים` = five people helping MJ - incidental).
Net: plural-address discipline is clean in v3; no genuine regression.

## 3. Somber discipline

10 somber rows in each corpus (late_night, songIntoMood 20..28). ZERO leaks in
both: no `!`, no two-host A:/B: banter, no levity tokens on any somber row. The
somber override in calendarLine/moodLine/flavorLine and the somber short-circuit
in writeBanter/writeTrivia/writeListeningCue hold perfectly. No regression.

## 4. Hallucination markers (trivia / good_thing)

Flagged scripts dropped from 8/32 (25%) to 1/32 (3.1%). The baseline flags include
genuine invented biography/superlatives:
  - seq 68: `קלווין האריס ... מייספייס ... בסקוטלנד` (invented origin story)
  - seq 72: `... מנגן על תופים בלהקה הראשונה שלו` (invented `first band`)
  - seq 36: `השם שלו הוא קיצור של יוגב` (invented name etymology)
v3's single flag is seq 38 (`הכי רומנטיקן שיש`) - a playful OPINION superlative, which
noInventedFactsLine explicitly permits (opinion/taste questions are allowed). So
GENUINE invented-fact assertions in v3 = 0. Strong improvement.

## 5. Naming accuracy

Measured on HEBREW-title naming beats (song/handover/open/recap/weather/news),
where exact mention is achievable because clean() never strips Hebrew:
  - Baseline: 43/74 = 58.1% mention the exact title
  - v3:       56/76 = 73.7% mention the exact title  (+15.6pp)
INTL (Latin) titles are stripped by clean() for Hebrew TTS in BOTH corpora
(100% of intl naming scripts carry no Latin char), so verbatim title mention is
impossible by design there - not a regression. The stronger namingLine + the
de-dangle guards keep the full Hebrew name in more often -> the naming lift.

## NEW issues introduced in v3

1. seq 87 (weather, intl `Paperboy Fabe - Language`): ends a sentence on a bare
   `של` (`...בוא נדבר בשפה של. תהנה מהקצב.`). NEW dangling - weather beat is
   unguarded (deDangleNaming not applied to weather/news).
2. seq 234 (news, he `Omer Adam - מהפכה של שמחה`): ends on bare `של`
   (`...כי זו תהיה מהפכה של`). NEW end-dangling on an unguarded news beat.
3. Mid-line dropped-name class (15 v3 song beats incl. 90/137/163/191/222): the
   guarded song beats still emit `ל- של` / `של של` / `של -,` because clean()
   strips the Latin title and danglingName does not detect these non-end shapes.
   Lower in absolute terms than baseline, but a persistent NEW-visible defect.

No NEW somber, plural-address, or hallucination regressions: those three are clean
or strictly better in v3.
