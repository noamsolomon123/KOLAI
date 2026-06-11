# Israeli Radio Hosting Culture — a verified playbook for KOLAI's DJ

*Deep-research run, 2026-06-11. 5 search angles (Hebrew-first), 20 sources fetched,
73 claims extracted, 25 adversarially verified (3-vote): 23 confirmed, 2 refuted.
This document keeps only what survived verification, with confidence + sources.*

## Why this exists

KOLAI's DJ should feel like a real Israeli radio host taking the listener on a
journey — welcomed at the top, carried through the hour, sent off at the end.
This is the craft research behind that, written as actionable rules for
`DjBrain` / `DjText` prompts and the station engine.

---

## The playbook (verified findings → design rules)

### 1. Talk to ONE listener, in second person — never to a crowd  *(HIGH confidence)*
Professional radio training (Ed Shane via peer-reviewed research, Valerie
Geller's canon, BBC/Bauer coaching) is unanimous: visualize one specific
listener and talk to that person — "you" (את/אתה), never "המאזינים",
"כל מי שמאזין", "אתם שם בבית". Radio is heard alone (car, kitchen,
headphones); each listener experiences the show as an individual.
**KOLAI rule:** every DJ line addresses a single "you"; inclusive "אנחנו"
is for shared moments ("איזה יום היה לנו"), plural address is banned.
[SAGE / New Media & Society], [Mixcloud], [Radio.co]

### 2. The host is a friend, but a brief one  *(MEDIUM)*
The host-listener bond is parasocial friendship: small personal touches and a
persistent persona drive tune-in loyalty — gated by hard anti-waffle doctrine:
"if you have nothing to say then just play the music."
**KOLAI rule:** the DJ has one continuous personality with occasional tiny
personal asides — and the existing quality-gate (skip the talk if there's
nothing cool to say) stays. [pauldenton.co.uk + parasocial literature]

### 3. Anchor the open in the listener's shared moment  *(MEDIUM)*
The morning-opening archetype: greet, name the weekday/day-part, meet the
listener inside their situation (commute, coffee, end of a workday).
**KOLAI rule:** the first DJ break of a session = a real OPENING: shalom,
the day/part-of-day, one warm line about the shared moment, then into the
music. This is exactly the user's "journey begins with a welcome."
[Radio.co], [cloudrad.io]

### 4. The broadcast clock is real — fixed anchors, kept consistent  *(HIGH)*
The format clock (broadcast clock/clockwheel) lays the hour out with fixed
segments at fixed minute positions, repeated hour after hour; changing it
daily confuses listeners. NOTE: the Hebrew term "שעון רדיו" was NOT verified
as industry jargon (in everyday Hebrew it's an alarm clock; the term of art
may be שעון שידור — unverified).
**KOLAI rule:** give the station a recognizable hourly rhythm (e.g., a
"מה השעה" style beat at predictable points) while keeping individual links
unpredictable (see finding 10). [Wikipedia Broadcast clock], [Denton]

### 5. Israeli clock anchors, concretely documented  *(MEDIUM)*
Galgalatz: news bulletin every round hour ("מה שקורה עכשיו", with opening/
closing jingles). Galei Tzahal's בוקר טוב ישראל: ~07:30 news update that
includes traffic; the show's open is fused with the 06:00 מבזק. Top-of-hour
news + mid-hour traffic are the fixed skeleton of Israeli radio.
**KOLAI rule:** when news/weather beats land, attach them to round-hour /
half-hour moments so they feel like real radio, not random insertions.
[he.wikipedia: גלגלצ, בוקר טוב ישראל]

### 6. Galgalatz doctrine: music first, sparse speech  *(MEDIUM)*
Station manager Eldad Koblenz's documented philosophy: listeners come "בגלל
המוזיקה... ומוזיקה בלבד" — hosts speak minimally. Matching craft rule of
thumb: ~one short link every two songs, ~30 seconds, short regular links
over long blocks; silence beats waffle.
**KOLAI rule:** the engine's existing no-fixed-cadence + don't-over-talk
behavior is the correct Israeli flagship sound. Default ≈ 1 link per 2 songs,
~20-30s spoken. [he.wikipedia: גלגלצ], [Denton]

### 7. Transitions (קרוסים) are a craft of their own  *(MEDIUM)*
Israeli teaching (BPM College, working broadcaster Nitzan Pinko): "קרוסים היא
אמנות בפני עצמה" — avoid jarring genre jumps; buffer big shifts with a jingle
or break. Talked intros: time the talk to end exactly as the vocal begins
("talking up to the vocal"); crashing into lyrics is the violation. Talking
over the instrumental intro is GOOD craft (the refuted claim below matters).
**KOLAI rule:** the renderer already ducks the DJ over intros — keep it, and
prefer ending DJ speech at/just before the first vocal onset (we have
vocal-onset detection from Essentia analysis). [bpm-music.com], [Mixcloud]

### 8. Tie music + talk to the public agenda  *(MEDIUM)*
Israeli music editors deliberately match songs to holidays (freedom songs on
Passover), weather (winter songs in storms), and current events; Galgalatz's
day-parting (Kabbalat Shabbat hour, quiet Hebrew at night, somber Yom
HaZikaron) corroborates.
**KOLAI rule:** feed date/holiday/weather into BOTH the setlist planner and
the DJ context — the mood system should breathe with the calendar.
[bpm-music.com]

### 9. The open is sonic before it is verbal — פתיחים  *(MEDIUM)*
Classic Israeli shows were identified by signature instrumental opening
themes listeners knew by heart (catalog of 42; Galei Tzahal released an
official compilation of 22). Example: Alex Ansky's "707" (started 7:07 after
the news, ran ~30 years) opened with Pierre Bachelet's "O' et la rencontre".
**KOLAI rule:** give KOLAI a short signature audio ident (a פתיח) that plays
at session start before the DJ's first words — instant "radio" feel and
brand. [orendj blog], [YouTube primary], [קול הזמן compilation]

### 10. Personality beats format — the Dori Ben-Zev model  *(MEDIUM)*
Haaretz on the beloved Kan 88 host: a typical moment is "קודם כל מצחיק מאוד,
ובו בזמן מבלבל, בלתי צפוי, מעורר מחשבה" — humor + deliberate unpredictability,
sustained ~50 years; he "invented himself as a unique genre."
**KOLAI rule:** validates the existing DJ-naturalness principle: no fixed
formula per link; let the DJ surprise (within the sparse-talk budget).
[Haaretz Friday cover, 2021]

---

## Refuted — do NOT apply
- ✗ "Opening hook must be <12s / opening <30s / first 15 seconds decide" (0-3).
- ✗ "Never talk over song starts/ends; always let proper endings finish" (0-3)
  — only crashing the VOCAL is the violation; talking over instrumental
  intros/outros is standard craft.

## Caveats
- The **verbatim Hebrew catchphrase appendix is still missing**: the Wikiquote
  category page (27 Israeli broadcaster pages) was confirmed to be just an
  index; individual host pages + archival audio were not fetched. Needs a
  dedicated follow-up pass before writing "in the style of" prompt examples.
- Much craft material is UK/US training literature — culturally plausible,
  not Israel-specific evidence (Israeli sources cover stations, clocks,
  transitions, agenda-tying, פתיחים, Ben-Zev).
- Institutional flux: Galei Tzahal's closure was ordered for March 2026 and
  frozen by the Supreme Court (Feb 2026); verify station references before
  shipping copy that names them.
- Wikipedia/blog-tier sources dominate; votes were unanimous but confidence
  is mostly MEDIUM.

## Open questions (next research pass)
1. Verbatim opening lines + catchphrases of specific hosts (Wikiquote pages,
   archival גלגלצ/כאן 88/רשת ג audio, host interviews).
2. The real published hourly clock of גלגלצ/כאן 88 (exact minute positions),
   and the correct Hebrew term (שעון שידור?).
3. The מבזק handoff ritual verbatim ("השעה שמונה, חדשות..." and the re-entry
   to music).
4. Post-ruling fate of גלי צה"ל/גלגלצ.

## Implementation backlog distilled for KOLAI
1. **DjText prompts:** one-listener second-person address; ban plural; opening
   break = day/part-of-day welcome; closing-style wind-down line when a
   session ends. (findings 1, 3)
2. **Cadence defaults:** ≈1 link / 2 songs, 20-30s, quality-gate stays.
   (finding 6)
3. **Vocal-aware ducking:** end DJ speech at first vocal onset. (finding 7)
4. **Calendar/weather context** into planner + DJ. (findings 5, 8)
5. **Signature פתיח** ident at session start. (finding 9)
6. **Hourly-feeling anchors** for news/weather beats. (findings 4, 5)
7. **Follow-up research pass** for the Hebrew phrase appendix. (caveat 1)

## Sources (fetched + verified against)
SAGE New Media & Society 10.1177/14614448241287913 (primary) · Mixcloud
presenter guide · Radio.co 7-tips · pauldenton.co.uk Presentertips ·
cloudrad.io opening-lines · Wikipedia Broadcast clock · radioiloveit.com ·
he.wikipedia: גלגלצ, בוקר טוב ישראל, מבזק חדשות · bpm-music.com (10 טיפים
לעריכה מוזיקלית ברדיו) · Haaretz Friday cover 2021 (דורי בן-זאב) · orendj
blog (42 פתיחים) · YouTube: נעימת פתיח 707 (primary) · Globes · yosmusic
(קול הזמן).