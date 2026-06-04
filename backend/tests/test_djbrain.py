from radioai.models import Song
from radioai.djbrain import DJBrain, words_for_seconds


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
