import pytest
from radioai.taste import TasteProfile, TasteTrack
from radioai.models import Song
from radioai.setlist import parse_setlist

_TASTE = TasteProfile(
    top_tracks=[TasteTrack(title="Million Dollar", artist="Noa Kirel", duration_s=180.5)],
    top_artists=["Noa Kirel", "Omer Adam"],
)


def test_parse_plain_json():
    text = '[{"title": "Tel Aviv", "artist": "Omer Adam"}, {"title": "Million Dollar", "artist": "Noa Kirel"}]'
    songs = parse_setlist(text, _TASTE, n=6)
    assert [s.title for s in songs] == ["Tel Aviv", "Million Dollar"]
    assert all(isinstance(s, Song) for s in songs)


def test_parse_tolerates_code_fence_and_prose():
    text = 'Sure! Here you go:\n```json\n[{"title": "Tel Aviv", "artist": "Omer Adam"}]\n```\nEnjoy.'
    songs = parse_setlist(text, _TASTE, n=6)
    assert songs[0].title == "Tel Aviv"


def test_duration_attached_for_known_track():
    text = '[{"title": "million dollar", "artist": "noa kirel"}, {"title": "Tel Aviv", "artist": "Omer Adam"}]'
    songs = parse_setlist(text, _TASTE, n=6)
    assert abs(songs[0].duration_s - 180.5) < 0.001  # case-insensitive match
    assert songs[1].duration_s is None               # unknown -> no duration


def test_dedupe_and_cap():
    text = ('[{"title": "A", "artist": "B"}, {"title": "A", "artist": "B"}, '
            '{"title": "C", "artist": "D"}, {"title": "E", "artist": "F"}]')
    songs = parse_setlist(text, _TASTE, n=2)
    assert [(s.title, s.artist) for s in songs] == [("A", "B"), ("C", "D")]


def test_malformed_raises():
    with pytest.raises(ValueError):
        parse_setlist("no json here at all", _TASTE, n=6)


from radioai.setlist import SetlistPlanner


class _FakeLLM:
    def __init__(self, text):
        self._text = text
        self.last_prompt = None

    def complete(self, prompt: str) -> str:
        self.last_prompt = prompt
        return self._text


def test_planner_builds_songs_and_prompt_mentions_taste():
    llm = _FakeLLM('[{"title": "Tel Aviv", "artist": "Omer Adam"}]')
    planner = SetlistPlanner(client=llm)
    songs = planner.plan(_TASTE, n=6)
    assert songs[0].title == "Tel Aviv"
    assert "Noa Kirel" in llm.last_prompt        # taste grounding
    assert "Million Dollar" in llm.last_prompt
    assert "6" in llm.last_prompt                 # requested count


def test_plan_includes_exclude_and_seed_in_prompt():
    from radioai.setlist import SetlistPlanner
    from radioai.taste import TasteProfile, TasteTrack
    from radioai.models import Song
    captured = {}
    class FakeLLM:
        def complete(self, prompt):
            captured["prompt"] = prompt
            return '[{"title": "Fresh Song", "artist": "Someone"}]'
    taste = TasteProfile(
        top_tracks=[TasteTrack("My Track", "My Artist", 200.0)],
        top_artists=["My Artist"],
    )
    planner = SetlistPlanner(client=FakeLLM())
    songs = planner.plan(taste, n=1,
                         exclude=["Old Hit — Past Artist"],
                         seed=Song(title="Seed Song", artist="Seed Artist"))
    assert songs and songs[0].title == "Fresh Song"
    p = captured["prompt"]
    assert "Old Hit — Past Artist" in p
    assert "Seed Song" in p and "Seed Artist" in p
    assert "Do NOT include" in p


def test_plan_without_new_args_still_works():
    from radioai.setlist import SetlistPlanner
    from radioai.taste import TasteProfile, TasteTrack
    class FakeLLM:
        def complete(self, prompt):
            return '[{"title": "A", "artist": "B"}, {"title": "C", "artist": "D"}]'
    taste = TasteProfile(top_tracks=[TasteTrack("T", "AR", 1.0)], top_artists=["AR"])
    songs = SetlistPlanner(client=FakeLLM()).plan(taste, n=2)
    assert len(songs) == 2

