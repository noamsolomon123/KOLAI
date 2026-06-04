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