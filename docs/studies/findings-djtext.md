# KOLAI DJ Text - Bug & Quality Findings

Corpus: docs/studies/corpus.jsonl - 250 rows, 249 non-null scripts (1 null script, 1 error row).

## Executive summary

| Check | Result |
|---|---|
| One-listener violations | **9** (3.6% of scripts) |
| Naming beats | 50/174 full (28.7%), 2 partial, 122 unnamed |
| Possible mislabels | 5 |
| Over-talk outliers | 0 |
| Somber rows flagged | 0/10 |
| Trivia/good_thing w/ claims | 9 |

## 1. One-listener doctrine violations (BUG)

Rule: DJ addresses ONE listener (2nd-person singular). Banned tokens: `מאזינים`, `המאזינים`, `אתם`, `כולם`, `לכולם`, `חברים`, `שלכם`, `אתכם`, `אנשים`, `מישהו מכם`.

**9 violations** = 3.6% of 249 scripts.

Token frequency: `כולם`x7, `אנשים`x2, `לכולם`x1

All offending rows:

- seq 12 / `handover` / [כולם, לכולם]: "השעה רבע לאחת עשרה, הגיע הזמן להוריד הילוך ופשוט לנשום. בוא תתמסר לכולם גנבים של אושר כהן."
- seq 37 / `trivia` / [אנשים]: "A: תגיד, מייקל ג'קסון באמת היה צריך חמישה אנשים רק כדי לעזור לו לנעול את הנעליים המיוחדות שלו? B: מה פתאום! נראה לי שזו הנפצה מטורפת, בטח הוא פשוט עשה מונבאק ונ..."
- seq 45 / `trivia` / [כולם]: "A: תגיד, אושר כהן נראה לך אחד שיוצא לקניות עם רשימה מסודרת או שזורם? B: נראה לי איש של אימפולסיביות מוחלטת, בטח קונה חצי סופר בלי לשים לב! A: בול, נגלה אם הוא ג..."
- seq 48 / `trivia` / [אנשים]: "A: תגיד, יובי נראה לך טיפוס שמסדר את הגרביים לפי צבעים או שזורק הכל לערימה? B: וואו, נראה לי הוא מהאנשים שחיים בבלגן מתוזמן, מהמר על הערימה! A: יש מצב. נגלה את ..."
- seq 127 / `song` / [כולם]: "אחרי הרהורי העולם הבא של קפליו, ננסה להבין את המציאות המורכבת שלנו עם אושר כהן בכולם גנבים, בשקט של הלילה הזה."
- seq 149 / `song` / [כולם]: "אחרי הילד המוזר הזה, תזהר מהכיסא שלך, כי אושר כהן בא להגיד לך שכולם גנבים, וזה קורה ממש עכשיו."
- seq 150 / `song` / [כולם]: "אושר כהן אומר שכולם גנבים, אז תנעל את הדלת, כי מייקל ג'קסון בא להזכיר לך שהם בטח לא שמים עלינו פס."
- seq 219 / `song` / [כולם]: "אחרי ששלומי שבת הזכיר לנו שזה יחד על הבמה, נראה לי שאתה מוכן לאמת הכואבת של אושר כהן. כולם גנבים, שבת שלום."
- seq 220 / `song` / [כולם]: "אחרי שכולם גנבו לך את האנרגיה, בוא נחלומי חלום קטן עם ה- & לכבוד ערב שבת רגוע במיוחד."

## 2. Naming accuracy

Naming beats present: **174 rows**.

- Full name (title or artist): **50 (28.7%)**
- Partial: 2
- Names NEITHER: **122 (70.1%)**

Examples naming neither (up to 15):

- seq 1 `open` - song="Cute Without The 'E' (Cut From The Team)" / artist="Taking Back Sunday": "בוקר טוב! שבע ארבעים, הקפה נגמר והפקק תקוע. בוא נתניע את היום עם קצת אנרגיה, הנה בשיר שירים אותך ישר לשיא."
- seq 7 `handover` - song="How You Remind Me" / artist="Nickelback": "שבע וארבעים, הלחץ של הבוקר מתחיל להתפוגג. תירגע רגע עם של ניקלבק."
- seq 9 `handover` - song="Dream A Little Dream Of Me" / artist="The Mamas & The Papas": "השעה כבר עשר ועשר, והשמש מתחילה לרדת. בוא תוריד רגל מהגז עם."
- seq 11 `handover` - song="The Middle" / artist="Zedd": "שש וחצי, האור בחוץ מתרכך וזה בדיוק הזמן לנקות את הראש. קח אוויר עם של."
- seq 17 `handover` - song="Waiting For Love" / artist="Avicii": "שש וחצי, האור בחוץ מתרכך בדיוק כמו שצריך. תעלה קצת את הווליום עם של."
- seq 19 `handover` - song="What Is Jazz?" / artist="Jdr": "אחת ורבע, הלילה נכנס עמוק לתוך הוורידים ורק אתה ואני נשארנו ערים. תתמסר ל- של."
- seq 21 `handover` - song="Monsters (feat. blackbear)" / artist="All Time Low": "כבר שבע ארבעים והקפה מתחיל להזכיר לך שהעולם דורש שתתעורר. בוא נצלול עם של."
- seq 27 `handover` - song="How You Remind Me" / artist="Nickelback": "שבע ארבעים, הקפה כבר התקרר אבל האנרגיה רק מתחילה לבעור. קח את של ניקלבאק ישר לווריד."
- seq 50 `cue` - song="אישהשלי" / artist="Tzukush": "תקשיב רגע לטירוף שעולה בדקה הראשונה, הבאס שם נכנס בצורה כזאת שפשוט אי אפשר להישאר אדיש. תגביר רגע את הווליום ותראה על מה אני מדבר."
- seq 51 `cue` - song="SHE'S NOT THERE" / artist="The Zombies": "תקשיב רגע לאורגן שנכנס אחרי הבית הראשון, הוא פשוט מפרק את השיר הזה לגורמים ונותן לו את כל הוייב המטורף שלו."
- seq 52 `cue` - song="Good News" / artist="Shaboozey": "תקשיב רגע לאיך שהביט מתנקה בדיוק לפני הפזמון השני, זה הרגע שהופך את כל השיר הזה למכונת אנרגיה טהורה. תגביר, אתה הולך לעוף על זה."
- seq 53 `cue` - song="מערב ראשון" / artist="Itay Levy": "תקשיב רגע לאיך שהקצב נחתך בשנייה שהפזמון נכנס, זה הרגע שהכל מתחבר לך בבטן."
- seq 54 `cue` - song="Monsters (feat. blackbear)" / artist="All Time Low": "תעקוב אחרי הדיסטורשן בגיטרה לפני הפזמון האחרון, זה הרגע שהשיר הזה באמת מתפוצץ לך בתוך האוזן."
- seq 55 `cue` - song="The Stigma (Boys Don’t Cry)" / artist="As It Is": "שים לב לאיך שהתופים נעלמים פתאום באמצע הבית השני, זה הרגע שהאנרגיה בשיא שלה והשיר פשוט עף באוויר."
- seq 56 `cue` - song="עולם הבא" / artist="קפליו": "תחזיק חזק, כי בדיוק אחרי הדקה הראשונה הביט מתפרק לאיזה סאונד אלקטרוני שעושה חשק לרקוד בטירוף, אל תפספס את זה."

### Possible MISLABELS (different known artist named)

- seq 106 `song` - row artist="Ida Corr" but mentions "ליעם חכמון": "היית ילד מוזר עם ליעם חכמון, ועכשיו מבקשת שתחשוב על זה רגע, אז תן לביט הזה להרגיע אותך בתוך הלילה."
- seq 113 `song` - row artist="Shaboozey" but mentions "בן צור": "בן צור צדק, לפעמים העיכוב משתלם. הנה החדשות הטובות שלך להלילה עם בשיר שפשוט עושה חשק להירגע ולשקוע במושב."
- seq 125 `song` - row artist="Pe'er Tasi" but mentions "ליעם חכמון": "ליעם חכמון השאיר אותנו במחשבות עמוקות, ועכשיו נתעטף בשקט עם מלודי של פאר טסי. יהי זכרם ברוך."
- seq 137 `song` - row artist="All Time Low" but mentions "תמר יהלומי": "תמר יהלומי בהחלט הביאה לנו מזל, אבל עכשיו הגיע הזמן להתמודד עם ה- של שבאים להעיר אותך מהחלומות הכי הזויים."
- seq 200 `song` - row artist="Paperboy Fabe" but mentions "ליעם חכמון": "הילד המוזר של ליעם חכמון לגמרי הוציא אותי מאיזון, אז בוא נלמד שפה חדשה עם של, אולי זה יעזור לנו לתקשר."

## 3. Over-talk (word count)

Overall mean wordcount 22.6 (sigma=7.7).

Per-beat:

| beat | n | mean | median | p90 | max |
|---|---|---|---|---|---|
| recap | 2 | 47.0 | 47 | 47 | 47 |
| trivia | 16 | 40.5 | 40 | 43 | 47 |
| banter | 16 | 36.9 | 37 | 41 | 43 |
| good_thing | 16 | 27.8 | 28 | 30 | 30 |
| open | 1 | 21.0 | 21 | 21 | 21 |
| cue | 16 | 20.9 | 21 | 25 | 25 |
| song | 141 | 19.9 | 21 | 22 | 22 |
| news | 12 | 18.3 | 21 | 22 | 22 |
| weather | 13 | 17.5 | 21 | 22 | 22 |
| handover | 16 | 15.8 | 16 | 18 | 20 |

Worst offenders (>mean+2sigma in beat):


Top 10 longest overall:

- seq 37 `trivia` wc=47: "A: תגיד, מייקל ג'קסון באמת היה צריך חמישה אנשים רק כדי לעזור לו לנעול את הנעליים המיוחדות שלו? B: מה פתאום! נראה לי שזו הנפצה מטורפת, בטח הוא פשוט עשה מונבאק ונעל לבד. A: נשאיר אותך במתח עד שנחזור מהב..."
- seq 38 `trivia` wc=47: "A: תגיד, דון דיאבלו נראה לך כמו בחור שיודע להכין קפה או רק שותה רד בול? B: עם האנרגיות שלו? אין סיכוי שהוא נוגע בקפאין, הוא בטח אוכל חשמל לארוחת בוקר. A: צודק, הוא טוען שמוזיקה זה הדלק שלו. נגלה את הס..."
- seq 151 `recap` wc=47: "ערב טוב, איזה כיף שבאת הביתה. יום שישי הגיע ואתה צריך לנוח. השבוע חרשת על האוזניות, שמעת אחת-עשרה שעות רצופות, אבל הקטע הכי הזוי? אביצ'י ניגן אצלך תשע פעמים, נראה לי שאתה קצת מאוהב בו. בוא נצלול לעומק..."
- seq 152 `recap` wc=47: "לילה טוב, יפה שלי. כבר אחת עשרה ורבע, זמן מעולה להוריד הילוך. השבוע חרשת על האוזניות, אה? 11 שעות של מוזיקה, שזה הישג מרשים, אבל הנתון הכי מטורף? 9 פעמים אבצ'י? אתה חייב לגוון את הפלייליסט, אחי. בוא נ..."
- seq 29 `banter` wc=43: "A: איזה קצב עשה לי, אבל עכשיו זמן להוריד הילוך וקצת להשתולל עם מוטי טקא. B: ידעת שמוטי התחיל בכלל בלהקת מחול? הבן אדם יודע להזיז את הגוף, תתכונן. A: מלך! אז תגביר את הווליום, תן למוטי לקחת אותך למסע, ..."
- seq 46 `trivia` wc=43: "A: תגיד, אביצ'י נראה לך טיפוס שהיה מעדיף לגלוש על גלים או לרחף עם מצנח רחיפה? B: וואו, קשה... אני מהמר על גלישה, הוא נראה לי בחור של וייב רגוע על המים. A: בול מה שחשבתי! נגלה אם צדקת בהמשך. הנה הלהיט ..."
- seq 39 `trivia` wc=42: "A: תגיד, אלה לנגלי נראית לך כמו אחת שבוחרת טקסס בגלל הבוקרים או בגלל הסטייקים? B: בואנה, שאלה קשה. אני מהמר שהיא באה נטו בשביל הברביקיו והבשר העסיסי! A: חשיבה של רעבים! נגלה את האמת אחרי הלהיט הבא שלה..."
- seq 44 `trivia` wc=42: "A: תגיד, אביצ'י היה עושה לך קעקוע של תרנגול על הכתף או שהוא יותר טיפוס של חתולים? B: וואלה, נראה לי הוא בקטע של כלבים משוגעים. בטח היה מאמץ גולדן רטריבר לאולפן! A: חחח נגלה אחרי שנשמע את הלהיט הזה, קב..."
- seq 13 `banter` wc=41: "A: אושרי כהן קצת עצבני היום, הא? בוא נרגיע את הרוחות עם משהו קליל יותר. B: וואו, לגמרי! אני כבר מרגיש את האווירה משתנה פה, זה הולך להיות אש! A: עזוב אותך מדרמות, תגביר ווליום, ג'ונאס בלו נוחת אצלך ברמ..."
- seq 23 `banter` wc=41: "A: איזה וייב מרגש של איזי, עכשיו בא לי לחזור לילדות, מה אתה אומר? B: יודע שהשיר הבא, ' ', מזכיר ששפה זה כלי העבודה הכי חזק שיש לנו? A: חכם כרגיל. בוא נראה איך פייפרבוי פייב מדבר אלייך, תגביר רגע!"

## 4. Somber correctness

10 somber rows. Flagged: **0**.

Somber rows/beats: seq121(song), seq122(song), seq123(song), seq124(song), seq125(song), seq126(song), seq127(song), seq128(song), seq129(song), seq130(song)

No somber row showed light beats, fun tokens, or multi-exclamation energy.

## 5. Hallucination risk (trivia / good_thing)

9 fact-beat scripts contain years/numbers/superlatives - surface for verification:

- seq 36 `trivia` artist="יובי" [superlatives=הכי]: "A: יובי הזה מקפיץ. תגיד, לדעתך השם שלו הוא קיצור של יוגב או משהו מתוחכם? B: אני מהמר על יוגב, נשמע לי הכי ישראלי שיש. צדקתי? A: נשאיר אותך במתח. קבל את 'לטס גו' ותגלה לבד, יאללה שגר!"
- seq 38 `trivia` artist="Don Diablo" [superlatives=הכי]: "A: תגיד, דון דיאבלו נראה לך כמו בחור שיודע להכין קפה או רק שותה רד בול? B: עם האנרגיות שלו? אין סיכוי שהוא נוגע בקפאין, הוא בטח אוכל חשמל לארוחת בוקר. A: צודק, הוא טוען שמוזיקה זה הדלק שלו. נגלה את הסוד בסיבוב הבא, תהנה ..."
- seq 47 `trivia` artist="Tzukush" [superlatives=הכי]: "A: תגיד, צוקוש נראה לך טיפוס שיודע להכין חביתה מושלמת או ששורף את המטבח? B: נראה לי הוא עושה חביתה עם קסם, בטח עם תבלין סודי של ראפרים. A: האמת? הוא מלך הסטייקים, אבל נגלה אחרי שנשמע את 'אישהשלי'. יאללה, תהנה!"
- seq 68 `good_thing` artist="Calvin Harris" [superlatives=הכי]: "ומשהו טוב לדרך - קלווין האריס התחיל את הקריירה שלו בכלל כשהעלה דמואים למייספייס מחדר השינה שלו בסקוטלנד. איזה כיף שהכישרון הזה הגיע ישר לרמקולים שלך. תגביר את הווליום."
- seq 71 `good_thing` artist="ליעם חכמון" [superlatives=הכי]: "ומשהו טוב לדרך - ליעם חכמון לגמרי הוכיח שגם אם אתה מרגיש קצת אחרת, בסוף הסטייל האישי שלך הוא הדבר הכי חזק שיש. תעלה את הווליום, זה ילד מוזר."
- seq 72 `good_thing` artist="All Time Low" [superlatives=הראשונה]: "ומשהו טוב לדרך - אלכס גאסקרט, הסולן של אול טיים לאו, בכלל התחיל את הדרך שלו כשהוא מנגן על תופים בלהקה הראשונה שלו. הנה הוא עם מונסטרס, תגביר את הווליום!"
- seq 77 `good_thing` artist="Paperboy Fabe" [superlatives=הגדול]: "ומשהו טוב לדרך - לא רק שר, הוא גם מפיק ווקאלי מהליגה של הגדולים, שעיצב את הסאונד המדויק שלו לגמרי לבד. תגביר ווליום, זה הולך לתפוס אותך חזק."
- seq 78 `good_thing` artist="The Cowsills" [superlatives=הכי]: "ומשהו טוב לדרך - המשפחה הכי מוזיקלית בסיקסטיז, הקאוסילס, קיבלו השראה ללהיט הזה מפרח שראו בטיול בפארק. תגביר את הווליום ותן לווייב המטורף הזה להרים אותך. הנה הם באים."
- seq 81 `good_thing` artist="Tzukush" [superlatives=הכי]: "ומשהו טוב לדרך - צוקוש לא רק מפיק תותח, הוא ידוע בסטודיו שלו כמי שמעדיף לעבוד רק על הציוד האנלוגי הכי נוסטלגי שיש."

## 6. Beat mix + mood

Beat counts: song=142, handover=16, banter=16, trivia=16, cue=16, good_thing=16, weather=13, news=12, recap=2, open=1

Mood metrics (calmness proxy = lower mean wc / fewer exclamations):

| mood | n | mean wc | excl/script |
|---|---|---|---|
| morning | 50 | 19.6 | 0.04 |
| late_night | 49 | 19.8 | 0.06 |
| focus | 50 | 20.4 | 0.00 |
| party | 50 | 22.4 | 0.08 |
| mix | 50 | 30.7 | 0.78 |

Beat x mood matrix (non-song):

| mood | open | handover | banter | trivia | cue | good_thing | news | weather | recap |
|---|---|---|---|---|---|---|---|---|---|
| focus | 0 | 0 | 0 | 0 | 0 | 0 | 5 | 5 | 2 |
| late_night | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 3 | 0 |
| mix | 1 | 16 | 16 | 16 | 1 | 0 | 0 | 0 | 0 |
| morning | 0 | 0 | 0 | 0 | 0 | 0 | 3 | 3 | 0 |
| party | 0 | 0 | 0 | 0 | 15 | 16 | 2 | 2 | 0 |

## 2b. Naming - corrected per-beat view

The 70%% 'unnamed' figure above is mostly a MEASUREMENT artifact: international artists are written in the script transliterated to Hebrew (e.g. Nickelback -> `ניקלבק`, Avicii -> `אביצ'י`), so a latin-substring match misses them. Per-beat (latin substring match):

| beat | n | named | none | note |
|---|---|---|---|---|
| song | 141 | 29% | 98 | many are pure segue lines; transliteration lowers measured rate |
| handover | 16 | 56% | 7 | 7 of the 'none' are the dangling-name bug below |
| cue | 16 | 0% | 16 | BY DESIGN - cue describes a musical moment, never names the track |
| open | 1 | 0% | 1 | single sample |

Takeaway: naming is NOT broadly broken; the cue beat intentionally never names, and intl transliteration defeats automated matching. The real defect is the dangling-name bug:

## 2c. DANGLING-NAME / BROKEN-TEMPLATE BUG (HIGH)

**26 of 174 naming scripts (14.9%)** end with or contain a dangling connector where the song/artist name is missing - the line reads like a template that failed to substitute the name.

Flag counts: im-shel=14, ends-shel=8, ends-im=7, bare-amp=1

- `im-shel` = the phrase "עם של" ("with [the song] of") with no name after it.
- `ends-shel`/`ends-im`/`ends-he-dash` = script ENDS on a bare connector ("של", "עם", "ה-").
- `bare-amp` = a lone `&` left where a title should be.

All offending rows:

- seq 7 `handover` [im-shel] song="How You Remind Me" artist="Nickelback": "שבע וארבעים, הלחץ של הבוקר מתחיל להתפוגג. תירגע רגע עם של ניקלבק."
- seq 9 `handover` [ends-im] song="Dream A Little Dream Of Me" artist="The Mamas & The Papas": "השעה כבר עשר ועשר, והשמש מתחילה לרדת. בוא תוריד רגל מהגז עם."
- seq 11 `handover` [im-shel,ends-shel] song="The Middle" artist="Zedd": "שש וחצי, האור בחוץ מתרכך וזה בדיוק הזמן לנקות את הראש. קח אוויר עם של."
- seq 14 `handover` [ends-shel] song="אישהשלי" artist="Tzukush": "07:40, הדרכים כבר גועשות ואתה בטח צריך משהו שירגיע לך את הבוקר. בוא תקשיב ל-אישהשלי של."
- seq 17 `handover` [im-shel,ends-shel] song="Waiting For Love" artist="Avicii": "שש וחצי, האור בחוץ מתרכך בדיוק כמו שצריך. תעלה קצת את הווליום עם של."
- seq 19 `handover` [ends-shel] song="What Is Jazz?" artist="Jdr": "אחת ורבע, הלילה נכנס עמוק לתוך הוורידים ורק אתה ואני נשארנו ערים. תתמסר ל- של."
- seq 21 `handover` [im-shel,ends-shel] song="Monsters (feat. blackbear)" artist="All Time Low": "כבר שבע ארבעים והקפה מתחיל להזכיר לך שהעולם דורש שתתעורר. בוא נצלול עם של."
- seq 88 `song` [ends-im] song="The Middle" artist="Zedd": "עזוב את המפלצות האלה, בוא נחזור למסלול. אם אתה כבר תקוע בחיים, לפחות תעשה את זה בקצב של זד עם."
- seq 104 `song` [im-shel,ends-shel] song="Dream A Little Dream Of Me" artist="The Mamas & The Papas": "אחרי האש של אמדורסקי, הגיע הזמן שתנמיך הילוך ותחלום קצת, כי המאמאז אנד דה פאפאס לוקחים אותך עכשיו לחלום מתוק בטעם של"
- seq 109 `song` [im-shel] song="What Is Jazz?" artist="Jdr": "קפליו שלח אותנו לעולם הבא, אבל בוא נחזור לקרקע עם של, אולי זה סוף סוף יסביר לי מה הלוז כאן."
- seq 122 `song` [im-shel] song="The Middle" artist="Zedd": "איזי השאיר אותנו במחשבות על מה שהיה, ועכשיו ננסה למצוא קצת שקט בתוך הלילה הזה עם של זד."
- seq 139 `song` [ends-shel] song="Monsters (feat. blackbear)" artist="All Time Low": "מוטי טקה השאיר אותנו בלילה, אבל עכשיו הגיע הזמן להתמודד עם המפלצות שלך בגרסה הכי טובה של."
- seq 156 `song` [ends-im] song="What Is Jazz?" artist="Jdr": "אביצ'י ידע לחכות לאהבה, אבל אתה בטח יודע שג'אז הוא התשובה, אז בוא נבדוק מה זה ג'אז עם."
- seq 164 `song` [ends-im] song="Edge of Desire" artist="Jonas Blue": "צוקוש סיכם את המצב עם אישהשלי, עכשיו ג׳ונאס בלו לוקח אותך בדיוק לקצה של הרצון שלך עם."
- seq 173 `song` [ends-im] song="What Is Jazz?" artist="Jdr": "זד נתן בראש באמצע, אבל עכשיו נשאלת השאלה הגורלית - מה זה ג'אז בכלל? בוא נגלה את זה יחד עם."
- seq 181 `song` [im-shel] song="What Is Jazz?" artist="Jdr": "אחרי הטרור של יובי הגיע הזמן לעשות קצת סדר בראש עם של, אולי סוף סוף נבין מה זה לעזאזל."
- seq 183 `song` [im-shel] song="Language" artist="Paperboy Fabe": "ניקלבק הזכירו לך נשכחות, אבל עכשיו הגיע הזמן לעבור לשפה אחרת לגמרי עם של פייפרבוי פייב, בוא תגביר, אתה הרי חולה על"
- seq 185 `song` [im-shel] song="Dream A Little Dream Of Me" artist="The Mamas & The Papas": "יובי השאיר אותנו עם טעם של סליים, אבל הגיע הזמן לחלום קצת עם דרים א ליטל דרים אוף מי של המאמאס אנד"
- seq 191 `song` [ends-shel] song="Monsters (feat. blackbear)" artist="All Time Low": "גידי גוב הזכיר לנו שזה עניין של זמן, אבל אם אתה מרגיש מוקף במפלצות, תגביר רגע את של."
- seq 200 `song` [im-shel] song="Language" artist="Paperboy Fabe": "הילד המוזר של ליעם חכמון לגמרי הוציא אותי מאיזון, אז בוא נלמד שפה חדשה עם של, אולי זה יעזור לנו לתקשר."
- seq 211 `song` [im-shel] song="TABOO" artist="Isaiah Falls": "איזה חלום מתוק עברנו עכשיו, אבל הגיע הזמן לשבור קצת שגרה עם של. שבת שלום, תגביר את הווליום ותהנה מהטירוף הזה."
- seq 215 `song` [ends-im] song="How You Remind Me" artist="Nickelback": "יונתן כחול עשה לנו כאן אווירה של מסיבה, אבל עכשיו הגיע הזמן להיזכר איך נראית נוסטלגיה ברוק, הנה ניקלבק עם."
- seq 218 `song` [im-shel] song="לכל אחד יש - יחד על הבמה" artist="Shlomi Shabat": "אחרי זד והאמצע שלו, בוא נחזור למרכז הישראלי עם שלומי שבת וקלאסיקה שתעשה לך שבת שלום בלב."
- seq 220 `song` [bare-amp] song="Dream A Little Dream Of Me" artist="The Mamas & The Papas": "אחרי שכולם גנבו לך את האנרגיה, בוא נחלומי חלום קטן עם ה- & לכבוד ערב שבת רגוע במיוחד."
- seq 236 `song` [im-shel] song="Good News" artist="Shaboozey": "אחרי החלומות המתוקים של המאמאז, הגיע הזמן להתעורר למציאות עם של שאבוזי, כי בוקר כזה דורש חדשות טובות באוזניים."
- seq 250 `song` [ends-im] song="What Is Jazz?" artist="Jdr": "צוקוש היה מרגש, אבל עכשיו הגיע הזמן לשאלה שאין לה תשובה – נראה אם יצליח לענות לך עם?"

## 2d. Note on 'mislabels' (section 2 above)

The 5 flagged mislabels are NOT wrong-song errors - they are legitimate SEGUE references: the script names the PREVIOUS song's artist (outro) while introducing the current one. Example seq 125 names `ליעם חכמון` (previous) AND `פאר טסי` = Pe'er Tasi (the correct current artist, just transliterated). No true mislabels detected.

## 7. Prioritized bug list & fix recommendations

1. **[HIGH] Dangling-name / broken template** (sec 2c, 26 rows / 14.9%%). Lines ship with `עם של` or trailing `של`/`עם`/`ה-` and NO song name, plus one bare `&`. Fix: (a) add a post-generation validator that rejects/regenerates any naming-beat script ending in a connector or containing `עם של` / empty quotes / bare `&`; (b) ensure the title-substitution step actually injects the (possibly transliterated) name before TTS.
2. **[MEDIUM] One-listener doctrine** (sec 1, 9 rows / 3.6%%). Plural address leaks via `כולם` (x7), `אנשים` (x2), `לכולם` (x1). NOTE: most `כולם` hits are inside the SONG TITLE "כולם גנבים" (Osher Cohen - 'Kulam Ganavim') and are false positives; genuine violations are the trivia rows seq37/45/48 (`אנשים`/`כולם`) and seq12 handover (`לכולם`). Fix: add a plural-address post-filter that is TITLE-AWARE (strip the known songTitle before scanning) to avoid false positives, and strengthen the prompt ban for trivia/banter.
3. **[LOW] Over-talk** (sec 3): no per-beat 2-sigma outliers. trivia/banter/recap run long (40-47 words) BY FORMAT (two-host A/B dialogue). Acceptable, but if the user wants tighter, cap trivia/banter at ~35 words.
4. **[OK] Somber** (sec 4): all 10 somber rows are `song` beats; zero light beats fired, zero fun tokens, zero multi-exclamation. Working as intended.
5. **[VERIFY] Hallucination risk** (sec 5, 9 rows): superlative/'first'/'biggest' claims in good_thing/trivia need human fact-check - e.g. seq68 Calvin Harris MySpace-from-bedroom origin, seq72 All Time Low drummer-first-band, seq78 The Cowsills 'most musical family of the sixties', seq77 Paperboy Fabe 'top-league vocal producer'. These are plausible but unverified; gate good_thing facts behind a citation/whitelist or soften to opinion.
6. **[INFO] Beat/mood mix** (sec 6): `song`=142 dominates (expected). `mix` mood carries ALL banter/trivia/handover (and the only exclamations, 0.78/script) while focus/late_night/morning/party never fire banter or trivia - beat selection is hard-tied to the `mix` mood. mix mean wc 30.7 vs ~20 elsewhere. If banter/trivia should appear in other moods, the beat-picker is mis-gated; if intentional, fine. late_night/morning are appropriately calmer (low exclamation, ~19.6 wc).

