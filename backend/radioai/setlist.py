import re
import json
from typing import Protocol
from radioai.models import Song
from radioai.taste import TasteProfile


def _extract_json_array(text: str):
    match = re.search(r"\[.*\]", text, re.DOTALL)
    if not match:
        raise ValueError("No JSON array found in LLM output")
    return json.loads(match.group(0))


def parse_setlist(text: str, taste: TasteProfile, n: int = 6) -> list[Song]:
    data = _extract_json_array(text)
    durations = {(t.title.lower(), t.artist.lower()): t.duration_s
                 for t in taste.top_tracks}
    songs: list[Song] = []
    seen = set()
    for item in data:
        if not isinstance(item, dict):
            continue
        title = (item.get("title") or "").strip()
        artist = (item.get("artist") or "").strip()
        if not title or not artist:
            continue
        key = (title.lower(), artist.lower())
        if key in seen:
            continue
        seen.add(key)
        songs.append(Song(title=title, artist=artist,
                          duration_s=durations.get(key)))
        if len(songs) >= n:
            break
    if not songs:
        raise ValueError("SetlistPlanner produced no usable songs")
    return songs


class _LLM(Protocol):
    def complete(self, prompt: str) -> str: ...


class SetlistPlanner:
    """Curates a flowing radio setlist grounded in the listener's taste, via an
    LLM client (the project's GeminiClient)."""

    def __init__(self, client: "_LLM"):
        self.client = client

    def _prompt(self, taste: TasteProfile, n: int) -> str:
        tracks = "\n".join(
            f'- "{t.title}" — {t.artist}' for t in taste.top_tracks
        )
        artists = ", ".join(taste.top_artists)
        return (
            "You are a radio music director building a personal station for one "
            "listener. Here is their recent taste.\n\n"
            f"Top tracks:\n{tracks}\n\n"
            f"Top artists: {artists}\n\n"
            f"Curate a flowing {n}-song setlist with a natural energy arc. Prefer "
            "the listener's own tracks and closely related real songs by the same "
            "or adjacent artists. Only include real, well-known songs.\n"
            "Return ONLY a JSON array, no prose, in exactly this shape:\n"
            '[{"title": "...", "artist": "..."}, ...]'
        )

    def plan(self, taste: TasteProfile, n: int = 6) -> list[Song]:
        text = self.client.complete(self._prompt(taste, n))
        return parse_setlist(text, taste, n)