import re
import json
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
