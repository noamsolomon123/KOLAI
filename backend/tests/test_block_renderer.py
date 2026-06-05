"""Tests for radioai.block_renderer.BlockRenderer - natural radio cadence.

All deps are faked: no network (FakeFetcher), no real audio (a flat tone +
tiny real wavs written to tmp for the DJ voice), no LLM (FakeBrain). This lets
us assert the CADENCE decisions deterministically.
"""
import os
from types import SimpleNamespace

import numpy as np
import soundfile as sf
import pytest

from radioai.models import Song
from radioai.mixrenderer import SR
from radioai.block_renderer import BlockRenderer, BlockResult, beat_for_break, BEATS


# --------------------------------------------------------------------- fakes
class FakeFetcher:
    def __init__(self):
        self.fetched = []

    def fetch(self, song):
        self.fetched.append(song)
        return f"/x/{song.title}.wav"


def fake_load(path):
    # 2s tone per song so crossfades have room to work
    return np.full(int(2.0 * SR), 0.1, dtype=np.float32)


def fake_analyze(path):
    return SimpleNamespace(bpm=120.0, key_camelot="8A",
                           beat_times=[0.0, 0.5, 1.0, 1.5])


class FakeBrain:
    """Records every call. write_break returns None when allow_skip=True
    (simulating "nothing cool to say"), and a naming line when allow_skip=False
    (forced/opening). write_banter returns a 2-turn dialogue."""

    def __init__(self, *, break_skip_when_allowed=True, banter_turns=None):
        self.break_calls = []
        self.banter_calls = []
        self.break_skip_when_allowed = break_skip_when_allowed
        self._banter_turns = banter_turns or [("A", "a"), ("B", "b")]

    def write_break(self, prev, nxt, beat, ctx, seconds, topic=None,
                    allow_skip=False):
        self.break_calls.append({
            "prev": prev, "nxt": nxt, "beat": beat, "seconds": seconds,
            "topic": topic, "allow_skip": allow_skip})
        if allow_skip and self.break_skip_when_allowed:
            return None
        return f"DJ LINE naming {nxt.title}"

    def write_banter(self, ctx, seconds=12.0, topic=None):
        self.banter_calls.append({"seconds": seconds, "topic": topic})
        return list(self._banter_turns)


class FakeVoice:
    """Writes a tiny real 1s wav for every render so mixrenderer.duck /
    load_mono operate on real audio. Records calls."""

    def __init__(self, tmpdir, secs=1.0):
        self.tmpdir = str(tmpdir)
        self.secs = secs
        self.render_calls = []
        self.banter_calls = []
        os.makedirs(self.tmpdir, exist_ok=True)
        self._n = 0

    def _write_wav(self, tag):
        self._n += 1
        p = os.path.join(self.tmpdir, f"{tag}_{self._n}.wav")
        # non-silent so trim_silence keeps it
        sf.write(p, np.full(int(self.secs * SR), 0.2, dtype=np.float32), SR)
        return p

    def render(self, text):
        p = self._write_wav("dj")
        self.render_calls.append(text)
        return SimpleNamespace(text=text, audio_path=p, duration_s=self.secs)

    def render_banter(self, turns, voice_a=None, voice_b=None):
        p = self._write_wav("banter")
        self.banter_calls.append({"turns": list(turns),
                                  "voice_a": voice_a, "voice_b": voice_b})
        rep = " / ".join(f"{s}: {t}" for s, t in turns)
        return SimpleNamespace(text=rep, audio_path=p, duration_s=self.secs)


class SeqRandom:
    """Deterministic random() that yields a fixed sequence, then repeats the
    last value. Lets tests dictate every coin-flip."""

    def __init__(self, values):
        self._values = list(values)
        self._i = 0

    def random(self):
        if self._i < len(self._values):
            v = self._values[self._i]
            self._i += 1
            return v
        return self._values[-1] if self._values else 0.0


def _ctx():
    return SimpleNamespace(time_str="12:00", part_of_day="צהריים",
                           weather="22 מעלות", general_headline="כותרת",
                           topic_headlines={})


def _songs(n, prefix="S"):
    return [Song(title=f"{prefix}{i}", artist="ARTIST") for i in range(n)]


def _make(tmp_path, voice, brain, **kw):
    kw.setdefault("write", False)
    return BlockRenderer(
        FakeFetcher(), brain, voice, _ctx(),
        blocks_dir=str(tmp_path / "blocks"),
        voice_a="Algieba", voice_b="Puck",
        analyze_fn=fake_analyze, load_fn=fake_load, **kw)


# ----------------------------------------------------------------- helpers
def _talk_at_boundary(meta, song_titles):
    """Map each talk entry to the boundary (incoming song index) it precedes,
    by comparing talk.start_s against segment boundaries. Returns a set of
    boundary indices that have talk (the opening over song 0 counts as 0)."""
    # simpler: count talk entries; the renderer guarantees ordering, so we use
    # explicit assertions in the tests instead.
    return [t for t in meta["talk"]]


# ----------------------------------------------------------------- tests
def test_returns_blockresult_with_path_and_segments(tmp_path):
    voice = FakeVoice(tmp_path / "voice")
    brain = FakeBrain()
    br = _make(tmp_path, voice, brain, write=True)
    songs = _songs(3)
    res = br.render(songs, index=0)

    assert isinstance(res, BlockResult)
    assert os.path.exists(res.path)
    assert res.meta["index"] == 0
    segs = res.meta["segments"]
    assert len(segs) == 3
    # ordered & non-overlapping
    for a, b in zip(segs, segs[1:]):
        assert a["start_s"] <= a["end_s"]
        assert a["end_s"] <= b["start_s"] + 1e-6
        assert a["start_s"] <= b["start_s"]
    assert segs[-1]["end_s"] == res.meta["duration_s"]


def test_block0_opens_with_cold_intro(tmp_path):
    voice = FakeVoice(tmp_path / "voice")
    brain = FakeBrain()
    br = _make(tmp_path, voice, brain)
    res = br.render(_songs(3), index=0)  # no prev_track
    # an opening DJ line was rendered over song 0
    assert len(voice.render_calls) >= 1
    assert any(t["start_s"] < 1.0 for t in res.meta["talk"])


def test_forced_talk_after_quiet_run(tmp_path):
    """max_silence=3, brain always skips when allowed -> after 3 quiet
    boundaries a FORCED talk must happen (write_break with allow_skip=False)."""
    voice = FakeVoice(tmp_path / "voice")
    brain = FakeBrain(break_skip_when_allowed=True)
    # never want banter, never pass the talk coin-flip -> stay quiet until forced
    rng = SeqRandom([0.99])
    br = _make(tmp_path, voice, brain, max_silence=3, talk_chance=0.0,
               banter_chance=0.0, rng=rng)
    # one block 0 (opening sets since_talk=0), then enough boundaries to force.
    # 5 songs => boundaries 1,2,3,4 ; with max_silence=3 boundary 3 is forced.
    res = br.render(_songs(5), index=0)
    # break_calls[0] is the block opening; a forced boundary break is any later
    # call with allow_skip=False (we never skip a forced line).
    forced = [c for c in brain.break_calls[1:] if c["allow_skip"] is False]
    assert forced, "expected a forced (allow_skip=False) break after quiet run"
    # and a corresponding talk entry beyond the opening
    assert len(res.meta["talk"]) >= 2


def test_no_back_to_back_talk(tmp_path):
    """Two talk events never land on consecutive boundaries. Force the coin so
    every boundary WOULD talk; the >=2-since-talk guard must still space them."""
    voice = FakeVoice(tmp_path / "voice")
    brain = FakeBrain(break_skip_when_allowed=False)  # always has something
    rng = SeqRandom([0.0])  # always pass talk coin; banter coin too -> but...
    # banter_chance=0 so the 0.0 only triggers talk; want_banter stays False.
    br = _make(tmp_path, voice, brain, max_silence=100, talk_chance=1.0,
               banter_chance=0.0, rng=rng)
    res = br.render(_songs(6), index=0)

    # Determine which boundary each non-opening talk entry sits at by matching
    # start_s to the segment that contains it.
    segs = res.meta["segments"]
    boundaries_with_talk = set()
    for t in res.meta["talk"]:
        # opening sits at ~0.5 (over song 0) -> boundary 0
        if t["start_s"] < segs[1]["start_s"] if len(segs) > 1 else False:
            boundaries_with_talk.add(0)
            continue
        # find boundary index = first segment whose start_s <= talk start
        idx = 0
        for j, s in enumerate(segs):
            if s["start_s"] <= t["start_s"] + 1e-6:
                idx = j
        boundaries_with_talk.add(idx)

    ordered = sorted(boundaries_with_talk)
    for a, b in zip(ordered, ordered[1:]):
        assert b - a >= 2, f"talk on consecutive boundaries {a},{b}: {ordered}"


def test_skip_respected_means_no_talk(tmp_path):
    """When NOT forced and the brain returns None (skip), that boundary has no
    talk - just a musical transition."""
    voice = FakeVoice(tmp_path / "voice")
    brain = FakeBrain(break_skip_when_allowed=True)
    rng = SeqRandom([0.0])  # always eligible by coin, but brain skips
    br = _make(tmp_path, voice, brain, max_silence=100, talk_chance=1.0,
               banter_chance=0.0, rng=rng)
    res = br.render(_songs(4), index=0)  # block 0: opening only
    # only the opening talk entry should exist; every boundary skipped.
    assert len(res.meta["talk"]) == 1
    # brain was asked at boundaries with allow_skip=True and returned None
    skipped = [c for c in brain.break_calls if c["allow_skip"] is True]
    assert skipped, "expected skip-eligible break attempts"


def test_banter_path(tmp_path):
    """Force banter: eligible + banter_chance=1.0 -> write_banter and
    render_banter are called and a banter talk entry appears."""
    voice = FakeVoice(tmp_path / "voice")
    brain = FakeBrain(break_skip_when_allowed=False)
    # first coin -> talk-eligible (0.0 < talk_chance); second coin -> banter
    rng = SeqRandom([0.0])
    br = _make(tmp_path, voice, brain, max_silence=100, talk_chance=1.0,
               banter_chance=1.0, rng=rng)
    res = br.render(_songs(4), index=0)

    assert brain.banter_calls, "write_banter should be called"
    assert voice.banter_calls, "render_banter should be called"
    assert voice.banter_calls[0]["voice_a"] == "Algieba"
    assert voice.banter_calls[0]["voice_b"] == "Puck"
    assert any(t["beat"] == "banter" for t in res.meta["talk"])


def test_continuity_opening_names_both_and_last_track(tmp_path):
    """With prev_track, an opening line is produced and write_break is called
    with prev=prev_track[0] and nxt=tracks[0][0] (naming both). last_track is
    tracks[-1]."""
    voice = FakeVoice(tmp_path / "voice")
    brain = FakeBrain()
    br = _make(tmp_path, voice, brain)
    prev_song = Song(title="PREVIOUS", artist="OLDARTIST")
    prev_track = (prev_song, fake_analyze("/x/p.wav"),
                  fake_load("/x/p.wav"), "/x/p.wav")
    songs = _songs(3, prefix="N")
    res = br.render(songs, index=4, prev_track=prev_track)

    opening = brain.break_calls[0]
    assert opening["prev"] is prev_song
    assert opening["nxt"].title == "N0"
    assert opening["allow_skip"] is False
    # opening talk entry exists
    assert any("naming N0" in t["text"] for t in res.meta["talk"])
    # last_track is the last loaded track tuple, song first
    assert res.last_track[0].title == "N2"


def test_cadence_state_persists_across_blocks(tmp_path):
    """ONE BlockRenderer across blocks: songs_since_talk carries over so the
    opening of a fresh block (with prev_track) resets it, and block boundaries
    continue the rotation. Smoke-check two sequential renders work and thread
    last_track."""
    voice = FakeVoice(tmp_path / "voice")
    brain = FakeBrain(break_skip_when_allowed=False)
    rng = SeqRandom([0.9])  # mostly quiet
    br = _make(tmp_path, voice, brain, max_silence=100, talk_chance=0.0,
               banter_chance=0.0, rng=rng)
    r0 = br.render(_songs(3, "A"), index=0)
    r1 = br.render(_songs(3, "B"), index=1, prev_track=r0.last_track)
    # block 1 opening should name block 0's last song as prev
    open1 = brain.break_calls[len([c for c in brain.break_calls]) - 1]
    # find the opening of block 1: the call whose prev is r0.last_track[0]
    assert any(c["prev"] is r0.last_track[0] for c in brain.break_calls)
    assert r1.meta["index"] == 1


def test_beat_rotation_helper():
    assert BEATS[0] == "song"
    seq = [beat_for_break(k) for k in range(len(BEATS) + 1)]
    assert seq[:len(BEATS)] == BEATS
    assert seq[len(BEATS)] == BEATS[0]


def test_raises_when_no_playable_tracks(tmp_path):
    class DeadFetcher:
        def fetch(self, song):
            raise RuntimeError("download failed")

    voice = FakeVoice(tmp_path / "voice")
    brain = FakeBrain()
    br = BlockRenderer(DeadFetcher(), brain, voice, _ctx(),
                       blocks_dir=str(tmp_path / "blocks"),
                       analyze_fn=fake_analyze, load_fn=fake_load, write=False)
    with pytest.raises(ValueError):
        br.render(_songs(3), index=0)