import numpy as np
from radioai.mixrenderer import (
    equal_power_crossfade, duck, time_stretch_to_bpm, SR,
)


def test_crossfade_output_length():
    sr = SR
    a = np.ones(sr * 3, dtype=np.float32)  # 3s
    b = np.ones(sr * 3, dtype=np.float32)  # 3s
    out = equal_power_crossfade(a, b, overlap_s=1.0)
    # total = 3 + 3 - 1 overlap = 5s
    assert abs(len(out) - sr * 5) <= 2


def test_crossfade_is_continuous_no_clipping():
    sr = SR
    a = np.ones(sr * 2, dtype=np.float32) * 0.8
    b = np.ones(sr * 2, dtype=np.float32) * 0.8
    out = equal_power_crossfade(a, b, overlap_s=1.0)
    assert np.max(np.abs(out)) <= 1.0001


def test_duck_attenuates_music_under_voice():
    sr = SR
    music = np.ones(sr * 4, dtype=np.float32) * 0.8
    voice = np.ones(sr * 2, dtype=np.float32) * 0.1
    out = duck(music, voice, start_s=1.0, attenuation_db=-7.0)
    # During the voiced region the music is ducked, so combined level < original.
    before = np.max(np.abs(out[: int(0.5 * sr)]))
    during = np.max(np.abs(out[int(1.5 * sr) : int(2.5 * sr)]))
    assert during < before


def test_time_stretch_changes_length_toward_target():
    sr = SR
    audio = np.random.uniform(-0.3, 0.3, sr * 4).astype(np.float32)
    stretched = time_stretch_to_bpm(audio, src_bpm=120, dst_bpm=140)
    # faster target -> shorter audio
    assert len(stretched) < len(audio)


def test_trim_silence_removes_leading_and_trailing():
    from radioai.mixrenderer import trim_silence
    sr = SR
    sig = np.concatenate([
        np.zeros(sr, dtype=np.float32),            # 1s leading silence
        (np.ones(sr, dtype=np.float32) * 0.5),     # 1s tone
        np.zeros(sr, dtype=np.float32),            # 1s trailing silence
    ])
    out = trim_silence(sig)
    assert len(out) < len(sig)
    assert abs(out[0]) > 0.01
    assert abs(out[-1]) > 0.01
    # roughly the 1s tone remains (allow a little slack)
    assert abs(len(out) - sr) < sr * 0.1


def test_trim_silence_all_silence_returns_input():
    from radioai.mixrenderer import trim_silence
    sig = np.zeros(SR, dtype=np.float32)
    assert len(trim_silence(sig)) == len(sig)

def test_start_on_beat_trims_to_first_beat():
    from radioai.mixrenderer import start_on_beat
    sr = SR
    audio = np.ones(sr * 4, dtype=np.float32)
    out = start_on_beat(audio, [0.5, 1.0, 1.5], sr=sr)
    assert abs(len(out) - sr * 3.5) <= 2


def test_start_on_beat_no_beats_passthrough():
    from radioai.mixrenderer import start_on_beat
    audio = np.ones(100, dtype=np.float32)
    assert len(start_on_beat(audio, [], sr=SR)) == 100


def test_start_on_beat_ignores_late_first_beat():
    from radioai.mixrenderer import start_on_beat
    audio = np.ones(SR * 2, dtype=np.float32)
    assert len(start_on_beat(audio, [9.0], sr=SR)) == SR * 2


def test_snap_overlap_to_beats():
    from radioai.mixrenderer import snap_overlap_to_beats
    assert abs(snap_overlap_to_beats(4.8, 120) - 5.0) < 1e-6
    assert abs(snap_overlap_to_beats(0.1, 120) - 0.5) < 1e-6

def _sine(freq, seconds, sr):
    t = np.linspace(0, seconds, int(seconds * sr), endpoint=False, dtype=np.float32)
    return (0.5 * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def test_band_split_separates_low_and_high():
    from radioai.mixrenderer import band_split
    low_tone = _sine(60, 1.0, SR)
    lo, hi = band_split(low_tone, crossover_hz=200)
    assert np.mean(lo ** 2) > np.mean(hi ** 2) * 5

    high_tone = _sine(4000, 1.0, SR)
    lo2, hi2 = band_split(high_tone, crossover_hz=200)
    assert np.mean(hi2 ** 2) > np.mean(lo2 ** 2) * 5


def test_bass_swap_crossfade_length_and_swap():
    from radioai.mixrenderer import bass_swap_crossfade
    a = _sine(80, 4.0, SR)
    b = np.zeros(SR * 4, dtype=np.float32)
    out = bass_swap_crossfade(a, b, overlap_s=2.0)
    assert abs(len(out) - SR * 6) <= 4
    assert np.max(np.abs(out)) <= 1.0001
    n = SR * 2
    ov = out[len(a) - n: len(a)]
    first_half = np.mean(ov[: n // 2] ** 2)
    second_half = np.mean(ov[n // 2:] ** 2)
    assert second_half < first_half * 0.5
