import numpy as np
import librosa
from radioai.models import TrackAnalysis
from radioai.keys import camelot_from_key

_PITCHES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

# Krumhansl-Schmuckler key profiles.
_MAJOR_PROFILE = np.array(
    [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
_MINOR_PROFILE = np.array(
    [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])


def _normalized_energy(samples: np.ndarray) -> float:
    rms = float(np.sqrt(np.mean(np.square(samples)))) if samples.size else 0.0
    # Map RMS (0..1) onto 0..1 with a gentle curve; clamp.
    return max(0.0, min(1.0, rms))


def _estimate_key(y: np.ndarray, sr: int) -> str:
    chroma = librosa.feature.chroma_cqt(y=y, sr=sr)
    profile = chroma.mean(axis=1)
    best_score = -np.inf
    best = ("C", "major")
    for i in range(12):
        rotated = np.roll(profile, -i)
        maj = float(np.corrcoef(rotated, _MAJOR_PROFILE)[0, 1])
        minr = float(np.corrcoef(rotated, _MINOR_PROFILE)[0, 1])
        if maj > best_score:
            best_score, best = maj, (_PITCHES[i], "major")
        if minr > best_score:
            best_score, best = minr, (_PITCHES[i], "minor")
    return camelot_from_key(best[0], best[1])


def _vocal_onset(y: np.ndarray, sr: int) -> float:
    # Heuristic: first strong onset after the very start ~ vocal/lead entry.
    onsets = librosa.onset.onset_detect(y=y, sr=sr, units="time", backtrack=True)
    for t in onsets:
        if t > 1.0:
            return float(t)
    return 0.0


def analyze(path: str) -> TrackAnalysis:
    y, sr = librosa.load(path, mono=True)
    duration = float(librosa.get_duration(y=y, sr=sr))

    # Pre-compute onset envelope to avoid n_fft/frame-size issues on short signals.
    onset_env = librosa.onset.onset_strength(y=y, sr=sr)
    tempo, beats = librosa.beat.beat_track(onset_envelope=onset_env, sr=sr)
    bpm = float(np.atleast_1d(tempo)[0])
    beat_times = [float(t) for t in librosa.frames_to_time(beats, sr=sr)]

    key = _estimate_key(y, sr)
    energy = _normalized_energy(y)

    # Intro/outro regions: first/last ~8s (refined in later milestones).
    intro_end = min(8.0, duration * 0.1)
    outro_start = max(duration - 8.0, duration * 0.9)
    vocal_onset = _vocal_onset(y, sr)

    return TrackAnalysis(
        path=path, duration_s=duration, bpm=bpm, beat_times=beat_times,
        key_camelot=key, energy=energy, intro_end_s=intro_end,
        outro_start_s=outro_start, vocal_onset_s=vocal_onset,
    )
