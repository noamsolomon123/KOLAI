from radioai.moods import MOODS, DEFAULT_MOOD, MOOD_ORDER, mood_list

_KEYS = {"mix", "late_night", "party", "focus", "morning"}
_FIELDS = ("label", "emoji", "song", "voice_style",
           "talk_chance", "banter_chance", "max_silence")


def test_moods_has_the_five_keys():
    assert set(MOODS.keys()) == _KEYS


def test_each_mood_has_all_fields():
    for key, preset in MOODS.items():
        for field in _FIELDS:
            assert field in preset, f"{key} missing {field}"
        assert isinstance(preset["label"], str) and preset["label"]
        assert isinstance(preset["emoji"], str) and preset["emoji"]
        assert isinstance(preset["song"], str) and preset["song"]
        assert isinstance(preset["voice_style"], str) and preset["voice_style"]
        assert isinstance(preset["talk_chance"], (int, float))
        assert isinstance(preset["banter_chance"], (int, float))
        assert isinstance(preset["max_silence"], int)


def test_default_mood_is_valid():
    assert DEFAULT_MOOD in MOODS


def test_mood_list_returns_ordered_dicts():
    out = mood_list()
    assert [m["key"] for m in out] == [k for k in MOOD_ORDER if k in MOODS]
    for m in out:
        assert set(m.keys()) == {"key", "label", "emoji"}
        assert m["label"] == MOODS[m["key"]]["label"]
        assert m["emoji"] == MOODS[m["key"]]["emoji"]


def test_party_mood_voice_is_hyped_and_song_is_high_energy():
    party = MOODS["party"]
    assert "high-energy" in party["song"]
    assert "energy" in party["voice_style"].lower()
    assert party["talk_chance"] == 0.7
