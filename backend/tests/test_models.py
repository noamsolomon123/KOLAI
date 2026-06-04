from radioai.models import Song, TrackAnalysis, DJSlot, Transition, PlanItem


def test_song_minimal():
    s = Song(title="Tudo Bom", artist="Static & Ben El")
    assert s.title == "Tudo Bom"
    assert s.artist == "Static & Ben El"
    assert s.duration_s is None


def test_track_analysis_fields():
    a = TrackAnalysis(
        path="x.wav", duration_s=180.0, bpm=120.0, beat_times=[0.5, 1.0],
        key_camelot="8A", energy=0.7, intro_end_s=8.0, outro_start_s=170.0,
        vocal_onset_s=12.0,
    )
    assert a.bpm == 120.0
    assert a.beat_times == [0.5, 1.0]
    assert a.key_camelot == "8A"


def test_transition_defaults_no_dj():
    t = Transition(type="beatmatch", duration_s=8.0)
    assert t.dj_slot is None


def test_plan_item_links_song_and_transition():
    s = Song(title="A", artist="B")
    a = TrackAnalysis(path="a.wav", duration_s=10, bpm=120, beat_times=[],
                      key_camelot="8A", energy=0.5, intro_end_s=1, outro_start_s=9,
                      vocal_onset_s=2)
    t = Transition(type="cut", duration_s=0.0)
    item = PlanItem(song=s, analysis=a, transition_in=t)
    assert item.song.title == "A"
    assert item.transition_in.type == "cut"
