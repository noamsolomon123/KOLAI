"""Render a 2-3 song show with one Hebrew DJ intro into a single MP3 (M1).

Usage:  python -m radioai.render_show
"""
import os
import numpy as np
from radioai.config import Config
from radioai.models import Song
from radioai.fetcher import AudioFetcher
from radioai.analyzer import analyze
from radioai.mixplanner import choose_transition
from radioai.djbrain import DJBrain, GeminiClient
from radioai.voice import VoiceRenderer, GeminiTTSSynth
from radioai import mixrenderer as mx
from radioai.taste import TasteService
from radioai.setlist import SetlistPlanner
from radioai.djcontext import DJContext
from radioai.stems import StemSeparator
from radioai.mashup import mashup_gate, build_mashup
from radioai.showmeta import build_segments, write_show_json

# Pro radio-host delivery direction handed to the TTS for every DJ line.
RADIO_STYLE = (
    "Read the following like a charismatic, warm, professional Israeli FM radio "
    "host. Energetic but smooth and confident, natural broadcast pacing, a real "
    "radio personality - not a robot. Speak only the Hebrew:"
)

# Hardcoded setlist for M1 (replaced by Spotify+LLM in M2). Verified Israeli
# hits, pinned to their official videos so versions are guaranteed correct.
DEMO_SETLIST = [
    Song(title="טודו בום", artist="סטטיק ובן אל",
         query="https://www.youtube.com/watch?v=Y_OLslE3bX8"),
    Song(title="מיליון דולר", artist="נועה קירל",
         query="https://www.youtube.com/watch?v=oQbh5Kvet04"),
    Song(title="תל אביב", artist="עומר אדם",
         query="https://www.youtube.com/watch?v=nMQw29nfzpg"),
]

_SEGUE_S = 1.5  # crossfade out of a DJ talkover into the next song
DUCK_DB = -15.0

BEATS = ["song", "weather", "topic", "news"]


def beat_for_break(k: int) -> str:
    return BEATS[k % len(BEATS)]


def build_setlist(cfg):
    """Build the setlist from Spotify taste; fall back to the demo set offline."""
    if cfg.spotify_client_id and cfg.spotify_client_secret:
        try:
            profile = TasteService(cfg).get_profile()
            planner = SetlistPlanner(
                client=GeminiClient(api_keys=cfg.gemini_api_keys,
                                    model=cfg.llm_model))
            songs = planner.plan(profile, n=6)
            if songs:
                return songs
        except Exception as e:
            print(f"[warn] Spotify setlist failed ({e}); using demo setlist")
    return DEMO_SETLIST


def main() -> None:
    cfg = Config.from_env()
    fetcher = AudioFetcher(cache_dir=cfg.cache_dir)
    brain = DJBrain(
        client=GeminiClient(api_keys=cfg.gemini_api_keys, model=cfg.llm_model),
        persona="רדיו AI",
    )
    voice = VoiceRenderer(
        synth=GeminiTTSSynth(api_keys=cfg.gemini_api_keys, model=cfg.tts_model,
                             voice=cfg.tts_voice, style=RADIO_STYLE),
        out_dir=os.path.join(cfg.cache_dir, "voice"),
    )

    separator = StemSeparator(cache_dir=cfg.cache_dir)
    setlist = build_setlist(cfg)
    print("Setlist:")
    for s in setlist:
        print(f"  - {s.title} — {s.artist}")

    print("Fetching + analyzing...")
    tracks = []
    for song in setlist:
        try:
            path = fetcher.fetch(song)
            tracks.append((song, analyze(path), mx.load_mono(path), path))
        except Exception as e:
            print(f"  [skip] {song.title} — {song.artist}: {e}")
    if len(tracks) < 2:
        raise RuntimeError("Not enough playable songs to build a show")

    ctx = DJContext.build(cfg)
    print(f"Context: {ctx.time_str} {ctx.part_of_day} | weather={ctx.weather} | "
          f"topics={list(ctx.topic_headlines)}")
    topic_list = list(ctx.topic_headlines.keys())
    break_k = 0
    topic_k = 0

    timeline = tracks[0][2]
    dj_lines = []
    song_events = [{"type": "song", "title": tracks[0][0].title,
                    "artist": tracks[0][0].artist, "start_s": 0.0}]
    talk = []
    for i in range(1, len(tracks)):
        boundary = round(len(timeline) / mx.SR, 2)
        prev_song, prev_an, _, prev_path = tracks[i - 1]
        song, an, audio, song_path = tracks[i]
        has_dj = (i % 2 == 1)  # witty talkover every ~2 songs
        t = choose_transition(prev_an, an, has_dj=has_dj)
        print(f"  {prev_song.title} -> {song.title}: {t.type}")
        if t.type == "talkover":
            beat = beat_for_break(break_k)
            break_k += 1
            topic = None
            if beat == "topic" and topic_list:
                topic = topic_list[topic_k % len(topic_list)]
                topic_k += 1
            script = brain.write_break(prev=prev_song, nxt=song, beat=beat,
                                       ctx=ctx, seconds=t.duration_s, topic=topic)
            print(f"    DJ [{beat}]: {script}")
            dj_lines.append(f"[{beat}: {prev_song.title} -> {song.title}]\n{script}")
            slot = voice.render(script)
            dj_audio = mx.trim_silence(mx.load_mono(slot.audio_path))
            dj_dur = len(dj_audio) / mx.SR
            duck_start = max(0.0, len(timeline) / mx.SR - dj_dur - _SEGUE_S - 0.3)
            talk.append({"beat": beat, "text": script,
                         "start_s": round(duck_start, 2),
                         "end_s": round(duck_start + dj_dur, 2)})
            timeline = mx.duck(timeline, dj_audio, start_s=duck_start,
                               attenuation_db=DUCK_DB)
            timeline = mx.equal_power_crossfade(timeline, audio, overlap_s=_SEGUE_S)
        elif t.type == "beatmatch":
            did_mashup = False
            if cfg.mashups_enabled and mashup_gate(prev_an, an):
                try:
                    print(f"    [mashup] {prev_song.title} x {song.title} (separating stems...)")
                    pv, _pi = separator.separate(prev_path)     # outgoing vocals
                    _nv, ni = separator.separate(song_path)     # incoming instrumental
                    seg = build_mashup(mx.load_mono(pv), mx.load_mono(ni), audio,
                                       prev_an.bpm, an.bpm, an.beat_times)
                    timeline = mx.equal_power_crossfade(timeline, seg, overlap_s=2.0)
                    dj_lines.append(f"[mashup: {prev_song.title} x {song.title}]")
                    did_mashup = True
                except Exception as e:
                    print(f"    [mashup-fallback] {e}")
            if not did_mashup:
                on_beat = mx.start_on_beat(audio, an.beat_times)
                stretched = mx.time_stretch_to_bpm(on_beat, an.bpm, prev_an.bpm)
                overlap = mx.snap_overlap_to_beats(t.duration_s, prev_an.bpm)
                timeline = mx.bass_swap_crossfade(timeline, stretched, overlap_s=overlap)
        elif t.type == "crossfade":
            timeline = mx.equal_power_crossfade(timeline, audio, overlap_s=t.duration_s)
        else:  # cut
            timeline = np.concatenate([timeline, audio])
        song_events.append({"type": "song", "title": song.title,
                            "artist": song.artist, "start_s": boundary})

    script_path = os.path.join(cfg.cache_dir, "show_script.txt")
    with open(script_path, "w", encoding="utf-8") as f:
        f.write("\n\n".join(dj_lines) if dj_lines else "(no DJ lines this render)")
    print(f"DJ script -> {script_path}")

    out = os.path.join(cfg.cache_dir, "show.mp3")
    mx.write_mp3(out, timeline)
    print(f"Done -> {out}")
    total_s = round(len(timeline) / mx.SR, 2)
    segments = build_segments(song_events, total_s)
    write_show_json(os.path.join(cfg.cache_dir, "show.json"),
                    station="רדיו AI", dj="רדיו AI", duration_s=total_s,
                    segments=segments, talk=talk)
    print(f"Show meta -> {os.path.join(cfg.cache_dir, 'show.json')}")


if __name__ == "__main__":
    main()