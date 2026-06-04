import numpy as np
import soundfile as sf
import librosa
import pyrubberband as pyrb

SR = 44100  # working sample rate


def load_mono(path: str) -> np.ndarray:
    y, _ = librosa.load(path, sr=SR, mono=True)
    return y.astype(np.float32)


def trim_silence(audio: np.ndarray, threshold: float = 0.01) -> np.ndarray:
    """Strip leading/trailing near-silence so speech starts immediately."""
    nz = np.where(np.abs(audio) > threshold)[0]
    if len(nz) == 0:
        return audio
    return audio[nz[0]: nz[-1] + 1]


def equal_power_crossfade(a: np.ndarray, b: np.ndarray,
                          overlap_s: float) -> np.ndarray:
    n = int(overlap_s * SR)
    n = min(n, len(a), len(b))
    if n <= 0:
        return np.concatenate([a, b])
    t = np.linspace(0, 1, n, dtype=np.float32)
    fade_out = np.cos(t * np.pi / 2)   # equal-power
    fade_in = np.cos((1 - t) * np.pi / 2)
    head = a[:-n]
    mixed = a[-n:] * fade_out + b[:n] * fade_in
    tail = b[n:]
    out = np.concatenate([head, mixed, tail])
    return np.clip(out, -1.0, 1.0)


def duck(music: np.ndarray, voice: np.ndarray, start_s: float,
         attenuation_db: float = -7.0, ramp_s: float = 0.3) -> np.ndarray:
    out = music.copy()
    start = int(start_s * SR)
    end = min(len(out), start + len(voice))
    gain = 10 ** (attenuation_db / 20.0)
    ramp = int(ramp_s * SR)
    # ramp down
    for i in range(start, min(start + ramp, end)):
        f = (i - start) / max(1, ramp)
        out[i] *= (1.0 - f) + f * gain
    # steady duck
    out[min(start + ramp, end):end] *= gain
    # overlay voice
    vlen = end - start
    out[start:end] += voice[:vlen]
    return np.clip(out, -1.0, 1.0)


def time_stretch_to_bpm(audio: np.ndarray, src_bpm: float,
                        dst_bpm: float) -> np.ndarray:
    if src_bpm <= 0 or dst_bpm <= 0:
        return audio
    rate = dst_bpm / src_bpm   # >1 = faster = shorter
    try:
        return pyrb.time_stretch(audio, SR, rate).astype(np.float32)
    except Exception:
        # rubberband CLI binary may not be installed; fall back to librosa's
        # pure-Python phase vocoder (lower quality, no external dependency).
        return librosa.effects.time_stretch(audio, rate=rate).astype(np.float32)


def write_mp3(path: str, audio: np.ndarray) -> None:
    wav_path = path.replace(".mp3", ".wav")
    sf.write(wav_path, np.clip(audio, -1.0, 1.0), SR)
    import subprocess
    subprocess.run(
        ["ffmpeg", "-y", "-i", wav_path, "-b:a", "192k", path],
        check=True, capture_output=True,
    )

def start_on_beat(audio: np.ndarray, beat_times, sr: int = SR,
                  max_skip_s: float = 4.0) -> np.ndarray:
    """Trim leading audio so the track starts on its first detected beat.
    No beats, or a first beat beyond max_skip_s -> returned unchanged."""
    if not beat_times:
        return audio
    first = beat_times[0]
    if first <= 0 or first > max_skip_s:
        return audio
    start = int(first * sr)
    return audio[start:] if start < len(audio) else audio


def snap_overlap_to_beats(overlap_s: float, bpm: float) -> float:
    """Round an overlap length to a whole number of beats (min 1) at `bpm`."""
    if bpm <= 0:
        return overlap_s
    beat = 60.0 / bpm
    n = max(1, round(overlap_s / beat))
    return n * beat

from scipy.signal import butter, filtfilt


def band_split(audio: np.ndarray, crossover_hz: float = 200.0,
               sr: int = SR):
    """Split audio into (low, high) bands at crossover_hz (zero-phase Butterworth)."""
    wc = crossover_hz / (0.5 * sr)
    bl, al = butter(4, wc, btype="low")
    bh, ah = butter(4, wc, btype="high")
    low = filtfilt(bl, al, audio).astype(np.float32)
    high = filtfilt(bh, ah, audio).astype(np.float32)
    return low, high


def bass_swap_crossfade(a: np.ndarray, b: np.ndarray, overlap_s: float,
                        crossover_hz: float = 200.0) -> np.ndarray:
    """Equal-power crossfade where the bass is swapped at the overlap midpoint,
    so only one bassline plays at a time (no muddy double-bass)."""
    n = int(overlap_s * SR)
    n = min(n, len(a), len(b))
    if n <= 0:
        return np.concatenate([a, b])

    a_low, a_high = band_split(a[-n:], crossover_hz)
    b_low, b_high = band_split(b[:n], crossover_hz)

    t = np.linspace(0, 1, n, dtype=np.float32)
    fade_out = np.cos(t * np.pi / 2)
    fade_in = np.cos((1 - t) * np.pi / 2)
    high_mix = a_high * fade_out + b_high * fade_in

    half = n // 2
    low_mix = np.where(np.arange(n) < half, a_low, b_low).astype(np.float32)
    r = min(int(0.05 * SR), half, n - half)
    if r > 0:
        seg = np.linspace(0, 1, 2 * r, dtype=np.float32)
        fo2 = np.cos(seg * np.pi / 2)
        fi2 = np.cos((1 - seg) * np.pi / 2)
        s0 = half - r
        low_mix[s0:s0 + 2 * r] = a_low[s0:s0 + 2 * r] * fo2 + b_low[s0:s0 + 2 * r] * fi2

    mixed = high_mix + low_mix
    out = np.concatenate([a[:-n], mixed, b[n:]])
    return np.clip(out, -1.0, 1.0)
