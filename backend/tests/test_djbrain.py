from radioai.models import Song
from radioai.djbrain import DJBrain, words_for_seconds
from radioai.djcontext import DJContext


def test_words_for_seconds_budget():
    # ~2.5 Hebrew words/sec
    assert words_for_seconds(6.0) == 15
    assert words_for_seconds(0.0) == 0


class _FakeClient:
    def __init__(self, text):
        self._text = text
        self.last_prompt = None

    def complete(self, prompt: str) -> str:
        self.last_prompt = prompt
        return self._text


def test_intro_includes_song_titles_in_prompt():
    client = _FakeClient("ועכשיו שיר חדש בשבילכם")
    brain = DJBrain(client=client, persona="גלגלצ")
    prev = Song(title="Tudo Bom", artist="Static & Ben El")
    nxt = Song(title="Malkat Hayofi", artist="Eden Ben Zaken")
    script = brain.write_intro(prev=prev, nxt=nxt, seconds=6.0)
    assert "Tudo Bom" in client.last_prompt
    assert "Malkat Hayofi" in client.last_prompt
    assert script.strip() != ""


def test_intro_trims_to_word_budget():
    long_text = " ".join(["מילה"] * 100)
    client = _FakeClient(long_text)
    brain = DJBrain(client=client, persona="גלגלצ")
    script = brain.write_intro(prev=None, nxt=Song("A", "B"), seconds=4.0)
    assert len(script.split()) <= words_for_seconds(4.0)

class _FakeResp:
    def __init__(self, text):
        self.text = text


class _FakeModels:
    def __init__(self, behavior):
        self._behavior = behavior

    def generate_content(self, model, contents):
        r = self._behavior()
        if isinstance(r, Exception):
            raise r
        return _FakeResp(r)


class _FakeGenaiClient:
    def __init__(self, behavior):
        self.models = _FakeModels(behavior)


def test_gemini_client_returns_text():
    from radioai.djbrain import GeminiClient
    c = GeminiClient(api_keys=[], model="m",
                     clients=[_FakeGenaiClient(lambda: "שלום")])
    assert c.complete("hi") == "שלום"


def test_gemini_client_rotates_on_failure():
    from radioai.djbrain import GeminiClient

    def first_fails():
        raise RuntimeError("429 rate limit")

    def second_ok():
        return "הצלחה"

    c = GeminiClient(api_keys=[], model="m",
                     clients=[_FakeGenaiClient(first_fails),
                              _FakeGenaiClient(second_ok)])
    assert c.complete("hi") == "הצלחה"


def test_gemini_client_requires_a_client():
    from radioai.djbrain import GeminiClient
    import pytest
    with pytest.raises(ValueError):
        GeminiClient(api_keys=[], model="m", clients=[])


def test_intro_ends_at_sentence_boundary_when_over_budget():
    # budget = words_for_seconds(4.0) = 10. The first sentence (3 words) is the
    # only complete sentence within the 10-word cap, so we should get exactly it.
    long_text = "ברוכים הבאים לרדיו. " + " ".join(["מילה"] * 100)
    client = _FakeClient(long_text)
    brain = DJBrain(client=client, persona="גלגלצ")
    script = brain.write_intro(prev=None, nxt=Song("A", "B"), seconds=4.0)
    assert script == "ברוכים הבאים לרדיו."
    assert script.endswith(".")


def _ctx(weather=None, general=None, topics=None):
    return DJContext(time_str="19:00", part_of_day="ערב", weather=weather,
                     general_headline=general, topic_headlines=topics or {})


def test_write_break_weather_uses_context():
    client = _FakeClient("ערב טוב, 24 מעלות, שיר בדרך!")
    brain = DJBrain(client=client, persona="רדיו AI")
    ctx = _ctx(weather="24 מעלות, שמיים בהירים")
    out = brain.write_break(prev=None, nxt=Song("A", "B"), beat="weather",
                            ctx=ctx, seconds=4.0)
    assert "24 מעלות" in client.last_prompt
    assert "ערב" in client.last_prompt
    assert out.strip() != ""


def test_write_break_topic_uses_headline():
    client = _FakeClient("חדשות מהעולם הטכנולוגי, ועכשיו שיר!")
    brain = DJBrain(client=client, persona="רדיו AI")
    ctx = _ctx(topics={"AI": "פריצת דרך חדשה ב-AI"})
    out = brain.write_break(prev=None, nxt=Song("A", "B"), beat="topic",
                            ctx=ctx, seconds=4.0, topic="AI")
    assert "פריצת דרך חדשה ב-AI" in client.last_prompt
    assert "AI" in client.last_prompt


def test_write_break_news_uses_general():
    client = _FakeClient("כותרת חמה, ומיד מוזיקה!")
    brain = DJBrain(client=client, persona="רדיו AI")
    ctx = _ctx(general="כותרת חדשותית חשובה")
    out = brain.write_break(prev=None, nxt=Song("A", "B"), beat="news",
                            ctx=ctx, seconds=4.0)
    assert "כותרת חדשותית חשובה" in client.last_prompt


def test_write_break_falls_back_to_song_when_context_missing():
    client = _FakeClient("ברוכים הבאים, שיר ראשון!")
    brain = DJBrain(client=client, persona="רדיו AI")
    ctx = _ctx(weather=None)  # weather beat requested but no weather data
    brain.write_break(prev=None, nxt=Song("A", "B"), beat="weather",
                      ctx=ctx, seconds=4.0)
    assert "זו פתיחת השידור" in client.last_prompt  # fell back to song write_intro

# === WITTY DJBRAIN TESTS (appended) ===
import re as _re
from radioai.djbrain import DJBrain as _DJ


class _SeqClient:
    """Records prompts; returns canned outputs in order (last repeats)."""

    def __init__(self, outputs):
        if isinstance(outputs, str):
            outputs = [outputs]
        self._outputs = list(outputs)
        self.prompts = []

    def complete(self, prompt: str) -> str:
        self.prompts.append(prompt)
        idx = min(len(self.prompts) - 1, len(self._outputs) - 1)
        return self._outputs[idx]


def _ctx2(weather=None, general=None, topics=None):
    return DJContext(time_str="19:00", part_of_day="ערב", weather=weather,
                     general_headline=general, topic_headlines=topics or {})


def test_allow_skip_returns_none_on_skip_token():
    client = _SeqClient("SKIP")
    brain = _DJ(client=client, persona="רדיו AI")
    out = brain.write_break(prev=Song("A", "B"), nxt=Song("C", "D"),
                            beat="song", ctx=_ctx2(), seconds=4.0,
                            allow_skip=True)
    assert out is None


def test_allow_skip_returns_none_when_skip_has_whitespace_or_punct():
    client = _SeqClient("  SKIP.  ")
    brain = _DJ(client=client, persona="רדיו AI")
    out = brain.write_break(prev=None, nxt=Song("C", "D"), beat="song",
                            ctx=_ctx2(), seconds=4.0, allow_skip=True)
    assert out is None


def test_allow_skip_returns_clean_line_when_not_skipping():
    client = _SeqClient("ועכשיו שיר מהמם בשבילכם")
    brain = _DJ(client=client, persona="רדיו AI")
    out = brain.write_break(prev=None, nxt=Song("C", "D"), beat="song",
                            ctx=_ctx2(), seconds=4.0, allow_skip=True)
    assert out is not None
    assert out.strip() != ""


def test_allow_skip_prompt_explains_skip_contract():
    client = _SeqClient("שיר")
    brain = _DJ(client=client, persona="רדיו AI")
    brain.write_break(prev=None, nxt=Song("C", "D"), beat="song",
                      ctx=_ctx2(), seconds=4.0, allow_skip=True)
    assert "SKIP" in client.prompts[-1]


def test_default_never_skips_even_on_skip_text():
    # Without allow_skip the old behavior holds: always returns a string.
    client = _SeqClient("SKIP")
    brain = _DJ(client=client, persona="רדיו AI")
    out = brain.write_break(prev=None, nxt=Song("C", "D"), beat="song",
                            ctx=_ctx2(), seconds=4.0)
    assert isinstance(out, str)
    # and the default prompt must NOT offer the skip escape hatch
    assert "SKIP" not in client.prompts[-1]


def test_longer_seconds_yields_larger_budget_in_prompt():
    short_c = _SeqClient("שיר קצר")
    long_c = _SeqClient("שיר ארוך")
    _DJ(client=short_c, persona="p").write_break(
        prev=None, nxt=Song("C", "D"), beat="song", ctx=_ctx2(), seconds=4.0)
    _DJ(client=long_c, persona="p").write_break(
        prev=None, nxt=Song("C", "D"), beat="song", ctx=_ctx2(), seconds=20.0)

    def nums(s):
        return [int(x) for x in _re.findall(r"\d+", s)]
    assert max(nums(long_c.prompts[-1])) > max(nums(short_c.prompts[-1]))


def test_long_budget_requests_richer_content():
    client = _SeqClient("שיר עם סיפור")
    brain = _DJ(client=client, persona="p")
    brain.write_break(prev=None, nxt=Song("C", "D"), beat="song",
                      ctx=_ctx2(), seconds=24.0)
    p = client.prompts[-1]
    # at a long budget the prompt should ask for a fact / story / angle
    assert ("עובדה" in p) or ("סיפור" in p) or ("זווית" in p)


def test_output_strips_quotes_and_markdown_and_english():
    raw = '**"ועכשיו** (DJ note: be witty) שיר מדהים בשבילכם!"'
    client = _SeqClient(raw)
    brain = _DJ(client=client, persona="p")
    out = brain.write_intro(prev=None, nxt=Song("C", "D"), seconds=8.0)
    assert '"' not in out
    assert "*" not in out
    assert "(" not in out and ")" not in out
    # no leaked latin letters
    assert not _re.search(r"[A-Za-z]", out)


def test_output_sentence_safe_trim_to_budget():
    long_text = "ברוכים הבאים לרדיו. " + " ".join(["מילה"] * 100)
    client = _SeqClient(long_text)
    brain = _DJ(client=client, persona="p")
    out = brain.write_break(prev=None, nxt=Song("A", "B"), beat="song",
                            ctx=_ctx2(), seconds=4.0)
    assert out == "ברוכים הבאים לרדיו."


def test_prompt_requests_wordplay_on_names():
    client = _SeqClient("שיר")
    brain = _DJ(client=client, persona="p")
    brain.write_intro(prev=None, nxt=Song("Million Dollar", "Noa Kirel"),
                      seconds=6.0)
    p = client.prompts[-1]
    assert "משחק מילים" in p or "ווייטור" in p
    assert "Noa Kirel" in p and "Million Dollar" in p


def test_weather_prompt_includes_time_and_weather():
    client = _SeqClient("ערב טוב")
    brain = _DJ(client=client, persona="p")
    brain.write_break(prev=None, nxt=Song("C", "D"), beat="weather",
                      ctx=_ctx2(weather="24 מעלות"), seconds=10.0)
    p = client.prompts[-1]
    assert "24 מעלות" in p
    assert "19:00" in p or "ערב" in p


def test_news_prompt_includes_headline():
    client = _SeqClient("חדשות")
    brain = _DJ(client=client, persona="p")
    brain.write_break(prev=None, nxt=Song("C", "D"), beat="news",
                      ctx=_ctx2(general="כותרת חמה מאוד"), seconds=10.0)
    assert "כותרת חמה מאוד" in client.prompts[-1]


def test_topic_prompt_includes_topic_and_headline():
    client = _SeqClient("נושא")
    brain = _DJ(client=client, persona="p")
    brain.write_break(prev=None, nxt=Song("C", "D"), beat="topic",
                      ctx=_ctx2(topics={"AI": "פריצת דרך ב-AI"}),
                      seconds=10.0, topic="AI")
    p = client.prompts[-1]
    assert "AI" in p and "פריצת דרך ב-AI" in p


# === ROUND 2: NAMING SONGS ON AIR + TWO-HOST BANTER ===
import json as _json


def test_write_break_song_beat_names_both_songs_and_artists():
    # The witty handoff path must put BOTH the outgoing and incoming song
    # titles + artists into the prompt so the DJ can announce/back-announce.
    client = _SeqClient("שיר")
    brain = _DJ(client=client, persona="גלגלצ")
    prev = Song(title="Tudo Bom", artist="Static Ben El")
    nxt = Song(title="Million Dollar", artist="Noa Kirel")
    brain.write_break(prev=prev, nxt=nxt, beat="song", ctx=_ctx2(),
                      seconds=8.0)
    p = client.prompts[-1]
    assert "Tudo Bom" in p and "Static Ben El" in p
    assert "Million Dollar" in p and "Noa Kirel" in p


def test_write_break_song_beat_instructs_to_name_songs_naturally():
    client = _SeqClient("שיר")
    brain = _DJ(client=client, persona="גלגלצ")
    prev = Song(title="A", artist="B")
    nxt = Song(title="C", artist="D")
    brain.write_break(prev=prev, nxt=nxt, beat="song", ctx=_ctx2(),
                      seconds=8.0)
    p = client.prompts[-1]
    assert "שם השיר" in p
    assert ("בטבעיות" in p) or ("טבעי" in p)
    assert ("לא בכל" in p) or ("לא תמיד" in p) or ("מגוון" in p)


def test_write_intro_also_names_songs_naturally():
    client = _SeqClient("שיר")
    brain = _DJ(client=client, persona="גלגלצ")
    brain.write_intro(prev=Song("Prev T", "Prev A"),
                      nxt=Song("Next T", "Next A"), seconds=8.0)
    p = client.prompts[-1]
    assert "Prev T" in p and "Next T" in p
    assert "שם השיר" in p


# ---- write_banter -----------------------------------------------------------

def test_write_banter_parses_json_into_alternating_turns():
    raw = ("כאן בלייני קצת:\n"
           "[{\"speaker\":\"A\",\"text\":\"ערב טוב, איזה מזג אוויר מטורף היום!\"},"
           "{\"speaker\":\"B\",\"text\":\"מטורף? אני כבר נמס פה באולפן.\"},"
           "{\"speaker\":\"A\",\"text\":\"אז בוא נמיס גם את המאזינים עם שיר.\"}]")
    client = _SeqClient(raw)
    brain = _DJ(client=client, persona="גלגלצ")
    turns = brain.write_banter(ctx=_ctx2(weather="32 מעלות"), seconds=12.0)
    assert isinstance(turns, list)
    assert len(turns) >= 2
    speakers = [s for s, _ in turns]
    assert speakers[0] == "A"
    for i in range(1, len(speakers)):
        assert speakers[i] != speakers[i - 1]
        assert speakers[i] in ("A", "B")
    for _, text in turns:
        assert text.strip() != ""
        assert not _re.search(r"[A-Za-z]", text)
        assert '"' not in text and "*" not in text


def test_write_banter_respects_small_word_budget():
    turns_json = _json.dumps(
        [{"speaker": "A", "text": " ".join(["מילה"] * 40)},
         {"speaker": "B", "text": " ".join(["דבר"] * 40)}],
        ensure_ascii=False)
    client = _SeqClient(turns_json)
    brain = _DJ(client=client, persona="גלגלצ")
    turns = brain.write_banter(ctx=_ctx2(), seconds=4.0)
    total = sum(len(t.split()) for _, t in turns)
    assert total <= words_for_seconds(4.0)
    assert len(turns) >= 1


def test_write_banter_prompt_includes_context_and_topic():
    client = _SeqClient("[{\"speaker\":\"A\",\"text\":\"שלום\"},"
                        "{\"speaker\":\"B\",\"text\":\"היי\"}]")
    brain = _DJ(client=client, persona="גלגלצ")
    brain.write_banter(ctx=_ctx2(weather="28 מעלות", general="כותרת חמה"),
                       seconds=12.0, topic="ספורט")
    p = client.prompts[-1]
    assert "28 מעלות" in p
    assert "ספורט" in p
    assert "speaker" in p and "text" in p
    assert "A" in p and "B" in p


def test_write_banter_fallback_returns_single_turn_on_non_json():
    client = _SeqClient("סתם שורה אחת בלי שום ג'ייסון בכלל")
    brain = _DJ(client=client, persona="גלגלצ")
    turns = brain.write_banter(ctx=_ctx2(), seconds=12.0)
    assert len(turns) == 1
    spk, text = turns[0]
    assert spk == "A"
    assert text.strip() != ""
    assert not _re.search(r"[A-Za-z]", text)


def test_write_banter_fallback_when_json_has_no_usable_lines():
    raw = "[{\"speaker\":\"A\",\"text\":\"%%%   ***\"},{\"speaker\":\"B\",\"text\":\"   \"}]"
    client = _SeqClient([raw, "אבל הנה שורה אחת טובה בעברית"])
    brain = _DJ(client=client, persona="גלגלצ")
    turns = brain.write_banter(ctx=_ctx2(), seconds=12.0)
    assert len(turns) == 1
    assert turns[0][0] == "A"
    assert turns[0][1].strip() != ""


def test_write_banter_default_topic_none_is_fine():
    client = _SeqClient("[{\"speaker\":\"A\",\"text\":\"ערב טוב חברים\"},"
                        "{\"speaker\":\"B\",\"text\":\"איזה כיף לחזור לאולפן\"}]")
    brain = _DJ(client=client, persona="גלגלצ")
    turns = brain.write_banter(ctx=_ctx2())
    assert len(turns) >= 2
    assert turns[0][0] == "A" and turns[1][0] == "B"
