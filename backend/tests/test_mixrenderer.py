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
