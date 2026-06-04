import numpy as np
import soundfile as sf
from radioai.analyzer import analyze, _normalized_energy


def _make_click_track(path, bpm=120, seconds=8, sr=22050):
    n = int(seconds * sr)
    audio = np.zeros(n, dtype=np.float32)
    interval = int(sr * 60.0 / bpm)
    click_len = 200
    for start in range(0, n - click_len, interval):
        audio[start:start + click_len] += np.hanning(click_len).astype(np.float32)
    sf.write(path, audio, sr)
    return path


def test_analyze_detects_bpm(tmp_path):
    p = _make_click_track(str(tmp_path / "click.wav"), bpm=120)
    result = analyze(p)
    # librosa may report a metrical multiple; accept 120 or 60.
    assert abs(result.bpm - 120) < 6 or abs(result.bpm - 60) < 6


def test_analyze_populates_fields(tmp_path):
    p = _make_click_track(str(tmp_path / "click.wav"), bpm=100, seconds=6)
    result = analyze(p)
    assert result.duration_s > 5.0
    assert len(result.beat_times) > 0
    assert 0.0 <= result.energy <= 1.0
    assert result.key_camelot[-1] in ("A", "B")


def test_normalized_energy_bounds():
    loud = np.ones(1000, dtype=np.float32)
    quiet = np.zeros(1000, dtype=np.float32)
    assert _normalized_energy(loud) > _normalized_energy(quiet)
    assert 0.0 <= _normalized_energy(loud) <= 1.0
