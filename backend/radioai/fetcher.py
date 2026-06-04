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
                 "french", "francais", "tradução", "traducao", "mashup")


def _candidate_score(song: Song, cand: dict) -> float:
    title = cand.get("title", "").lower()
    score = 0.0
    if song.duration_s and cand.get("duration"):
        diff = abs(cand["duration"] - song.duration_s)
        score += max(0.0, 30.0 - diff)  # closer duration = higher
    for kw in _GOOD_KEYWORDS:
        if kw in title:
            score += 5.0
    for kw in _BAD_KEYWORDS:
        if kw in title:
            score -= 25.0
    # Popularity: originals are usually the most-viewed. Log-scaled tiebreaker.
    views = cand.get("view_count") or 0
    if views > 0:
        score += min(12.0, math.log10(views + 1) * 1.5)
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

        query = song.query or f"{song.artist} {song.title}"
        candidates = self._search(query)
        best = pick_best_candidate(song, candidates)
        if best is None:
            raise RuntimeError(f"No candidate found for {song.artist} - {song.title}")

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
            ydl.download([best["id"] if "http" in str(best.get("id", ""))
                          else f"https://www.youtube.com/watch?v={best['id']}"])
        return out_path
