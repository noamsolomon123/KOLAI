import numpy as np
from radioai.keys import camelot_relation
from radioai.mixrenderer import SR, time_stretch_to_bpm, start_on_beat

_MAX_BPM_RATIO = 1.06


def mashup_gate(prev, nxt) -> bool:
    """True only when a clean acapella-over-next mashup is feasible:
    same/relative key (no pitch shift) AND BPM within ~6% (gentle stretch)."""
    if prev.bpm <= 0 or nxt.bpm <= 0:
        return False
    if camelot_relation(prev.key_camelot, nxt.key_camelot) not in ("same", "relative"):
        return False
    ratio = max(prev.bpm, nxt.bpm) / min(prev.bpm, nxt.bpm)
    return ratio <= _MAX_BPM_RATIO


def _peak_vocal_start(vocals, window_n, sr: int = SR) -> int:
    """Index of the highest-energy window_n slice (the most vocal-rich section)."""
    if len(vocals) <= window_n:
        return 0
    step = max(1, sr // 2)  # 0.5s hops
    best_i, best_e = 0, -1.0
    for i in range(0, len(vocals) - window_n + 1, step):
        e = float(np.mean(vocals[i:i + window_n] ** 2))
        if e > best_e:
            best_e, best_i = e, i
    return best_i


_BED_DUCK = 0.7          # incoming instrumental sits slightly back...
_VOCAL_TARGET = 1.4      # ...so the outgoing vocal is ~1.4x the bed
_MAX_VOCAL_BOOST = 4.0


def _vocal_forward_gains(bed, acap):
    """Gains so the acapella sits clearly on top of the (ducked) bed."""
    def _rms(x):
        return float(np.sqrt(np.mean(x ** 2))) if len(x) else 0.0
    br, ar = _rms(bed), _rms(acap)
    if ar <= 1e-6:
        return _BED_DUCK, 1.0
    acap_gain = min(_MAX_VOCAL_BOOST,
                    max(1.0, (_VOCAL_TARGET * br * _BED_DUCK) / ar))
    return _BED_DUCK, acap_gain


def same_recording(a, b, sr: int = SR, dur_s: float = 20.0,
                   threshold: float = 0.95) -> bool:
    """True when a and b are the same underlying recording."""
    n = int(dur_s * sr)

    def _mid(x):
        if len(x) <= n:
            return x
        s = (len(x) - n) // 2
        return x[s:s + n]

    a2, b2 = _mid(a), _mid(b)
    m = min(len(a2), len(b2))
    if m < sr:
        return False
    a2, b2 = a2[:m], b2[:m]
    if float(np.std(a2)) < 1e-6 or float(np.std(b2)) < 1e-6:
        return False
    return float(np.corrcoef(a2, b2)[0, 1]) >= threshold


def build_mashup(prev_vocals, nxt_instrumental, nxt_full, prev_bpm, nxt_bpm,
                 nxt_beats, bars: int = 8, sr: int = SR):
    """Acapella-over-next: outgoing vocal (tempo-matched) over the incoming
    instrumental for `bars` bars, then the full incoming track continues."""
    if len(prev_vocals) == 0 or len(nxt_instrumental) == 0 or len(nxt_full) == 0:
        raise ValueError("empty stem input")
    if nxt_bpm <= 0:
        raise ValueError("invalid incoming bpm")

    window_n = int(bars * 4 * 60.0 / nxt_bpm * sr)  # 4 beats/bar

    if len(prev_vocals) >= window_n:
        _s = _peak_vocal_start(prev_vocals, window_n)
        acap = prev_vocals[_s:_s + window_n]
    else:
        acap = prev_vocals
    acap = time_stretch_to_bpm(acap, prev_bpm, nxt_bpm)

    bed = start_on_beat(nxt_instrumental, nxt_beats)[:window_n]
    full = start_on_beat(nxt_full, nxt_beats)

    n = min(len(bed), len(acap))
    if n == 0:
        raise ValueError("mashup window empty")

    bg, ag = _vocal_forward_gains(bed[:n], acap[:n])
    mixed = bed[:n] * bg + acap[:n] * ag
    peak = float(np.max(np.abs(mixed))) if n else 0.0
    if peak > 0.98:
        mixed = mixed * (0.98 / peak)
    seg1 = mixed.astype(np.float32)
    seg2 = full[n:]
    return np.concatenate([seg1, seg2]).astype(np.float32)