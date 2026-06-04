import os
import math
import hashlib
from typing import Optional
import yt_dlp
from radioai.models import Song

_GOOD_KEYWORDS = ("official audio", "official video", "official", "audio",
                  "הרשמי", "הקליפ הרשמי")
# Covers / alternate cuts / foreign-language re-recordings we want to avoid.
_BAD_KEYWORDS = ("live", "remix", "reaction", "cover", "karaoke", "sped up",
                 "slowed", "nightcore", "8d", "instrumental", "acoustic",
                 "lyrics video", "version", "english", "spanish", "portuguese",
                 "french", "francais", "traducao", "mashup")


def _has_hebrew(text: str) -> bool:
    return any("א" <= ch <= "ת" or "֐" <= ch <= "׿" for ch in text)


def _candidate_score(song: Song, cand: dict) -> float:
    raw_title = cand.get("title", "")
    title = raw_title.lower()
    score = 0.0
    if song.duration_s and cand.get("duration"):
        diff = abs(cand["duration"] - song.duration_s)
        score += max(0.0, 30.0 - diff)  # closer duration = higher
    if any(kw in title for kw in _GOOD_KEYWORDS):   # count once, no stacking
        score += 5.0
    for kw in _BAD_KEYWORDS:
        if kw in title:
            score -= 25.0
    # Hebrew-titled uploads are the canonical Israeli originals for our station.
    if _has_hebrew(raw_title):
        score += 10.0
    # Popularity: the original release is almost always the most-viewed.
    views = cand.get("view_count") or 0
    if views > 0:
        score += min(20.0, math.log10(views + 1) * 2.0)
    return score


def pick_best_candidate(song: Song, candidates: list[dict]) -> Optional[dict]:
    if not candidates:
        return None
    return max(candidates, key=lambda c: _candidate_score(song, c))


class AudioFetcher:
    def __init__(self, cache_dir: str):
        self.cache_dir = cache_dir
        os.makedirs(cache_dir, exist_ok=True)

    def _cache_path(self, song: Song) -> str:
        key = f"{song.artist}-{song.title}".lower()
        h = hashlib.sha1(key.encode("utf-8")).hexdigest()[:16]
        return os.path.join(self.cache_dir, f"{h}.mp3")

    def _search(self, query: str, limit: int = 8) -> list[dict]:
        opts = {"quiet": True, "skip_download": True, "extract_flat": True}
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(f"ytsearch{limit}:{query}", download=False)
        return info.get("entries", []) if info else []

    def fetch(self, song: Song) -> str:
        """Return a local mp3 path for the song, downloading if not cached."""
        out_path = self._cache_path(song)
        if os.path.exists(out_path):
            return out_path

        q = song.query or f"{song.artist} {song.title}"
        if "youtube.com" in q or "youtu.be" in q:
            url = q  # pinned exact video
        else:
            candidates = self._search(q)
            best = pick_best_candidate(song, candidates)
            if best is None:
                raise RuntimeError(f"No candidate found for {song.artist} - {song.title}")
            url = (best["id"] if "http" in str(best.get("id", ""))
                   else f"https://www.youtube.com/watch?v={best['id']}")

        opts = {
            "quiet": True,
            "format": "bestaudio/best",
            "outtmpl": out_path.replace(".mp3", ".%(ext)s"),
            "postprocessors": [
                {"key": "FFmpegExtractAudio", "preferredcodec": "mp3",
                 "preferredquality": "192"},
            ],
        }
        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.download([url])
        return out_path
