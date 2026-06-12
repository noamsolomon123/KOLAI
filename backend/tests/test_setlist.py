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

# === SMART PLANNER TESTS (appended) ===
from radioai.setlist import SetlistPlanner as _SP


class _RecordingLLM:
    """Records prompts; returns canned outputs in order (last one repeats)."""

    def __init__(self, outputs):
        if isinstance(outputs, str):
            outputs = [outputs]
        self._outputs = list(outputs)
        self.prompts = []

    def complete(self, prompt: str) -> str:
        self.prompts.append(prompt)
        idx = min(len(self.prompts) - 1, len(self._outputs) - 1)
        return self._outputs[idx]


_DRAFT = '[{"title": "Tel Aviv", "artist": "Omer Adam"}, {"title": "Million Dollar", "artist": "Noa Kirel"}]'
_REFINED = '[{"title": "Yalla", "artist": "Static & Ben El"}, {"title": "Tudo Bom", "artist": "Static & Ben El"}]'


def test_prompt_mentions_energy_arc_and_flow_craft():
    llm = _RecordingLLM([_DRAFT, _REFINED])
    songs = _SP(client=llm).plan(_TASTE, n=2)
    assert songs
    p = llm.prompts[0].lower()
    assert "energy" in p and "arc" in p
    assert "camelot" in p or "harmonic" in p
    assert "bpm" in p or "tempo" in p
    assert "key" in p
    assert "hebrew" in p
    assert "international" in p or "english" in p
    assert "discover" in p or "deep cut" in p or "related" in p
    assert "favorite" in p or "familiar" in p
    assert "remix" in p or "live" in p or "sped" in p or "version" in p
    assert "real" in p
    assert "era" in p or "mood" in p


def test_plan_runs_generate_then_critique_refine_two_calls():
    llm = _RecordingLLM([_DRAFT, _REFINED])
    songs = _SP(client=llm).plan(_TASTE, n=2)
    assert len(llm.prompts) == 2
    crit = llm.prompts[1].lower()
    assert "critique" in crit or "refine" in crit or "review" in crit
    assert "Tel Aviv" in llm.prompts[1]
    assert [(s.title, s.artist) for s in songs] == [
        ("Yalla", "Static & Ben El"), ("Tudo Bom", "Static & Ben El")]


def test_plan_falls_back_to_draft_when_refine_is_garbage():
    llm = _RecordingLLM([_DRAFT, "totally not json, the model rambled"])
    songs = _SP(client=llm).plan(_TASTE, n=2)
    assert len(llm.prompts) == 2
    assert [(s.title, s.artist) for s in songs] == [
        ("Tel Aviv", "Omer Adam"), ("Million Dollar", "Noa Kirel")]


def test_plan_falls_back_to_draft_when_refine_is_empty_array():
    llm = _RecordingLLM([_DRAFT, "[]"])
    songs = _SP(client=llm).plan(_TASTE, n=2)
    assert [(s.title, s.artist) for s in songs] == [
        ("Tel Aviv", "Omer Adam"), ("Million Dollar", "Noa Kirel")]


def test_plan_can_disable_refine_pass():
    llm = _RecordingLLM([_DRAFT, _REFINED])
    songs = _SP(client=llm).plan(_TASTE, n=2, refine=False)
    assert len(llm.prompts) == 1
    assert [(s.title, s.artist) for s in songs] == [
        ("Tel Aviv", "Omer Adam"), ("Million Dollar", "Noa Kirel")]


def test_refine_prompt_carries_exclude_and_seed_constraints():
    from radioai.models import Song as _Song
    excl = "Old Hit — Past Artist"
    llm = _RecordingLLM([_DRAFT, _REFINED])
    _SP(client=llm).plan(
        _TASTE, n=2,
        exclude=[excl],
        seed=_Song(title="Seed Song", artist="Seed Artist"))
    for p in llm.prompts:
        assert excl in p
        assert "Seed Song" in p


# === MOOD TESTS (appended) ===
def test_plan_threads_party_mood_vibe_into_prompt():
    llm = _FakeLLM('[{"title": "Tel Aviv", "artist": "Omer Adam"}]')
    planner = SetlistPlanner(client=llm)
    songs = planner.plan(_TASTE, n=1, mood="party")
    assert songs and songs[0].title == "Tel Aviv"
    p = llm.last_prompt.lower()
    assert "party" in p or "high-energy" in p
    # still grounded in taste
    assert "Noa Kirel" in llm.last_prompt


def test_plan_mood_carries_into_refine_prompt():
    llm = _RecordingLLM([_DRAFT, _REFINED])
    _SP(client=llm).plan(_TASTE, n=2, mood="late_night")
    for prompt in llm.prompts:
        low = prompt.lower()
        assert "late-night" in low or "low-energy" in low


def test_plan_without_mood_omits_vibe_line():
    llm = _FakeLLM('[{"title": "Tel Aviv", "artist": "Omer Adam"}]')
    SetlistPlanner(client=llm).plan(_TASTE, n=1)
    assert "VIBE for this set" not in llm.last_prompt
