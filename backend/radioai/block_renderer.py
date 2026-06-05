"""BlockRenderer - assemble one endless-engine "block" of songs + DJ talk with
natural, human radio pacing.

This is the core of the realer-radio goal: name songs on air (back-announce the
song that just played + intro the one coming up), occasionally a short two-host
banter, but don't over-talk. The cadence is deliberately NOT a metronome:

  * Block 0 opens with a short cold intro; later blocks open with a back-announce
    that names the previous block's last song AND this block's first song.
  * Between songs we usually just segue musically. We only talk when it feels
    natural - never two boundaries in a row, a coin-flip otherwise, and a forced
    (deeper) line when we've been quiet too long.
  * Cadence state (songs-since-talk, talk-count) persists ACROSS blocks because
    StationEngine reuses ONE BlockRenderer instance for the whole station.

The renderer is fully dependency-injected so it can be exercised with fakes (no
network / no real audio / no LLM) in tests.
"""
import os
import random

import numpy as np

from radioai import mixrenderer as mx
from radioai.analyzer import analyze as _default_analyze
from radioai.showmeta import build_segments

# DJ "beat" rotation - mirrors render_show.beat_for_break so the whole codebase
# rotates talk topics the same way (song handoff / weather / topic / news).
BEATS = ["song", "weather", "topic", "news"]


def beat_for_break(k: int) -> str:
    return BEATS[k % len(BEATS)]


class BlockResult:
    """Result of rendering one block: the raw audio, the meta dict the
    StationEngine serves, the written mp3 path, and the opaque last_track tuple
    that bridges into the next block."""

    def __init__(self, audio, meta, path, last_track):
        self.audio = audio
        self.meta = meta
        self.path = path
        self.last_track = last_track


class BlockRenderer:
    def __init__(self, fetcher, brain, voice, ctx, *, blocks_dir="cache/blocks",
                 voice_a=None, voice_b=None, duck_db=-15.0, segue_s=1.5,
                 max_silence=4, banter_every=3, talk_chance=0.5,
                 banter_chance=0.2, rng=None, analyze_fn=None, load_fn=None,
                 write=True):
        self.fetcher = fetcher
        self.brain = brain
        self.voice = voice
        self.ctx = ctx
        self.blocks_dir = blocks_dir
        self.voice_a = voice_a
        self.voice_b = voice_b
        self.duck_db = duck_db
        self.segue_s = segue_s
        self.max_silence = max_silence
        self.banter_every = banter_every
        self.talk_chance = talk_chance
        self.banter_chance = banter_chance
        self.rng = rng or random.Random(7)
        self.analyze_fn = analyze_fn or _default_analyze
        self.load_fn = load_fn or mx.load_mono
        self.write = write

        os.makedirs(blocks_dir, exist_ok=True)

        # CROSS-BLOCK cadence state. Start "since talk" high so block 0 opens
        # with a DJ intro and the first real boundary is eligible to talk.
        self._songs_since_talk = 10 ** 9
        self._talk_count = 0
        # rotate beats / topics across the whole station, not just one block
        self._beat_k = 0
        self._topic_k = 0

    # ------------------------------------------------------------------ topics
    def _next_topic(self):
        """Round-robin a topic key from ctx.topic_headlines (or None)."""
        try:
            keys = list(self.ctx.topic_headlines.keys())
        except Exception:
            keys = []
        if not keys:
            return None
        topic = keys[self._topic_k % len(keys)]
        self._topic_k += 1
        return topic

    def _next_beat(self):
        beat = beat_for_break(self._beat_k)
        self._beat_k += 1
        return beat

    # -------------------------------------------------------------------- plan
    def _plan(self, tracks, prev_track):
        """Decide the timeline of events for this block. Returns a list of event
        dicts. Event kinds:
          {"kind": "song", "i": idx}                      -> play tracks[idx]
          {"kind": "open", "text": str, "i": 0}           -> opening talkover
          {"kind": "break", "text": str, "beat": str, "i": idx}
          {"kind": "banter", "turns": list, "beat": str, "i": idx}

        The opening event (if any) precedes the song-0 event; every other talk
        event precedes the song event for its boundary. This method only DECIDES
        - it calls brain.write_break / brain.write_banter (cheap LLM) but does
        NOT touch audio.
        """
        events = []

        # ---- block opening ------------------------------------------------
        first_song = tracks[0][0]
        if prev_track is not None:
            prev_song = prev_track[0]
            text = self.brain.write_break(
                prev=prev_song, nxt=first_song, beat="song", ctx=self.ctx,
                seconds=8, topic=None, allow_skip=False)
            if text:
                events.append({"kind": "open", "text": text, "i": 0})
                self._songs_since_talk = 0
        else:
            # block 0: brief cold intro (name the very first song). Keep short.
            text = self.brain.write_break(
                prev=first_song, nxt=first_song, beat="song", ctx=self.ctx,
                seconds=6, topic=None, allow_skip=False)
            if text:
                events.append({"kind": "open", "text": text, "i": 0})
                self._songs_since_talk = 0

        events.append({"kind": "song", "i": 0})

        # ---- per-boundary cadence ----------------------------------------
        for i in range(1, len(tracks)):
            self._songs_since_talk += 1
            forced = self._songs_since_talk >= self.max_silence
            # never talk two boundaries in a row (>= 2 since last talk) unless
            # forced; otherwise it's a coin-flip.
            eligible = forced or (
                self._songs_since_talk >= 2
                and self.rng.random() < self.talk_chance)

            did_talk = False
            if eligible:
                want_banter = (
                    (forced and
                     self._talk_count % self.banter_every == self.banter_every - 1)
                    or (not forced and self.rng.random() < self.banter_chance))

                topic = self._next_topic()

                if want_banter:
                    turns = self.brain.write_banter(
                        self.ctx, seconds=(16 if forced else 12), topic=topic)
                    if turns:
                        events.append({"kind": "banter", "turns": turns,
                                       "beat": "banter", "i": i})
                        did_talk = True
                else:
                    beat = self._next_beat()
                    seconds = 18 if forced else 9
                    text = self.brain.write_break(
                        prev=tracks[i - 1][0], nxt=tracks[i][0], beat=beat,
                        ctx=self.ctx, seconds=seconds, topic=topic,
                        allow_skip=(not forced))
                    if text:
                        events.append({"kind": "break", "text": text,
                                       "beat": beat, "i": i})
                        did_talk = True

            if did_talk:
                self._songs_since_talk = 0
                self._talk_count += 1

            events.append({"kind": "song", "i": i})

        return events

    # ----------------------------------------------------------------- render
    def render(self, songs, index, prev_track=None):
        # 1. load + analyze each song, skipping any that fail
        tracks = []
        for song in songs:
            try:
                path = self.fetcher.fetch(song)
                tracks.append((song, self.analyze_fn(path), self.load_fn(path),
                               path))
            except Exception as e:
                print(f"  [skip] {getattr(song, 'title', song)}: {e}")
        if not tracks:
            raise ValueError("No playable tracks in block")

        # 2. plan the cadence
        events = self._plan(tracks, prev_track)

        # 3. assemble audio + collect timings
        timeline = tracks[0][2]
        song_events = []
        talk = []

        # opening talkover (if any) - DJ over the intro of song 0
        opening = next((e for e in events if e["kind"] == "open"), None)
        if opening is not None:
            slot = self.voice.render(opening["text"])
            dj_audio = mx.trim_silence(self.load_fn(slot.audio_path))
            dj_dur = len(dj_audio) / mx.SR
            start_s = 0.5
            timeline = mx.duck(timeline, dj_audio, start_s=start_s,
                               attenuation_db=self.duck_db)
            talk.append({"beat": "song", "text": slot.text,
                         "start_s": round(start_s, 2),
                         "end_s": round(start_s + dj_dur, 2)})

        song_events.append({"type": "song", "title": tracks[0][0].title,
                            "artist": tracks[0][0].artist, "start_s": 0.0})

        # walk boundary events for songs 1..n-1
        for i in range(1, len(tracks)):
            song, an, audio, _ = tracks[i]
            boundary = round(len(timeline) / mx.SR, 2)

            talk_event = next(
                (e for e in events
                 if e.get("i") == i and e["kind"] in ("break", "banter")), None)

            if talk_event is not None:
                if talk_event["kind"] == "banter":
                    slot = self.voice.render_banter(
                        talk_event["turns"], self.voice_a, self.voice_b)
                else:
                    slot = self.voice.render(talk_event["text"])
                dj_audio = mx.trim_silence(self.load_fn(slot.audio_path))
                dj_dur = len(dj_audio) / mx.SR
                # duck the DJ over the TAIL of the current timeline (talkover),
                # then crossfade into the next song.
                duck_start = max(
                    0.0, len(timeline) / mx.SR - dj_dur - self.segue_s - 0.3)
                timeline = mx.duck(timeline, dj_audio, start_s=duck_start,
                                   attenuation_db=self.duck_db)
                talk.append({"beat": talk_event["beat"], "text": slot.text,
                             "start_s": round(duck_start, 2),
                             "end_s": round(duck_start + dj_dur, 2)})

            # musical (or post-talkover) segue into the next song
            timeline = mx.equal_power_crossfade(timeline, audio,
                                                overlap_s=self.segue_s)
            song_events.append({"type": "song", "title": song.title,
                                "artist": song.artist, "start_s": boundary})

        # 4. write mp3 + build meta
        total_s = round(len(timeline) / mx.SR, 2)
        path = os.path.join(self.blocks_dir, f"block_{index}.mp3")
        if self.write:
            mx.write_mp3(path, timeline)

        segments = build_segments(song_events, total_s)
        meta = {"index": index, "duration_s": total_s,
                "segments": segments, "talk": talk}

        return BlockResult(audio=timeline, meta=meta, path=path,
                           last_track=tracks[-1])