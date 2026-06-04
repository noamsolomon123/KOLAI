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

# Pro radio-host delivery direction handed to the TTS for every DJ line.
RADIO_STYLE = (
    "Read the following like a charismatic, warm, professional Israeli FM radio "
    "host. Energetic but smooth and confident, natural broadcast pacing, a real "
    "radio personality - not a robot. Speak only the Hebrew:"
)

# Hardcoded setlist for M1 (replaced by Spotify+LLM in M2).
SETLIST = [
    Song(title="Tudo Bom", artist="Static & Ben El Tavori"),
    Song(title="Hofim", artist="Idan Raichel"),
    Song(title="Malkat Hayofi", artist="Eden Ben Zaken"),
]

_SEGUE_S = 4.0  # crossfade out of a DJ talkover into the next song


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

    print("Fetching + analyzing...")
    tracks = []
    for song in SETLIST:
        path = fetcher.fetch(song)
        tracks.append((song, analyze(path), mx.load_mono(path)))

    timeline = tracks[0][2]
    for i in range(1, len(tracks)):
        prev_song, prev_an, _ = tracks[i - 1]
        song, an, audio = tracks[i]
        has_dj = (i == 1)  # DJ intro before the 2nd song only (M1 keeps it simple)
        t = choose_transition(prev_an, an, has_dj=has_dj)
        print(f"  {prev_song.title} -> {song.title}: {t.type}")
        if t.type == "talkover":
            script = brain.write_intro(prev=prev_song, nxt=song, seconds=t.duration_s)
            print(f"    DJ: {script}")
            slot = voice.render(script)
            dj_audio = mx.load_mono(slot.audio_path)
            # Talk over the current song's outro, then segue (crossfade) into the
            # next song instead of a hard cut.
            duck_start = max(0.0, len(timeline) / mx.SR - slot.duration_s - 1.0)
            timeline = mx.duck(timeline, dj_audio, start_s=duck_start)
            timeline = mx.equal_power_crossfade(timeline, audio, overlap_s=_SEGUE_S)
        elif t.type == "beatmatch":
            stretched = mx.time_stretch_to_bpm(audio, an.bpm, prev_an.bpm)
            timeline = mx.equal_power_crossfade(timeline, stretched, overlap_s=t.duration_s)
        elif t.type == "crossfade":
            timeline = mx.equal_power_crossfade(timeline, audio, overlap_s=t.duration_s)
        else:  # cut
            timeline = np.concatenate([timeline, audio])

    out = os.path.join(cfg.cache_dir, "show.mp3")
    mx.write_mp3(out, timeline)
    print(f"Done -> {out}")


if __name__ == "__main__":
    main()
