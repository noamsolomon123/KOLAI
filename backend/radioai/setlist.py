import re
import json
from typing import Protocol
from radioai.models import Song
from radioai.taste import TasteProfile

_EM = "—"  # em dash used in "Title - Artist" lines


def _extract_json_array(text: str):
    """Pull the first JSON array out of an LLM reply, tolerating code fences,
    prose, and trailing commentary. Tries the greedy match first, then the
    first balanced array if the greedy parse fails."""
    if text is None:
        raise ValueError("No JSON array found in LLM output")
    match = re.search(r"\[.*\]", text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(0))
        except (ValueError, json.JSONDecodeError):
            pass
    depth = 0
    start = -1
    for i, ch in enumerate(text):
        if ch == "[":
            if depth == 0:
                start = i
            depth += 1
        elif ch == "]" and depth > 0:
            depth -= 1
            if depth == 0 and start >= 0:
                chunk = text[start:i + 1]
                try:
                    return json.loads(chunk)
                except (ValueError, json.JSONDecodeError):
                    start = -1
                    continue
    raise ValueError("No JSON array found in LLM output")


# Hints that a "song" is actually an alternate version we never want to queue.
_ALT_VERSION = re.compile(
    r"\b(remix|live|sped[\s-]?up|slowed|acoustic|instrumental|karaoke|"
    r"cover|edit|reprise|demo|remaster(?:ed)?|re-?recorded|"
    r"taylor'?s version|extended|radio edit|mix)\b",
    re.IGNORECASE,
)


def _base_title(title: str) -> str:
    """Normalize a title for duplicate detection: drop bracketed/dashed
    qualifiers like '(Live)', '- Remix', 'feat. X' so two versions of the same
    underlying song collapse to one key."""
    t = title.lower().strip()
    t = re.sub(r"[\(\[\{].*?[\)\]\}]", " ", t)
    t = re.split(r"\s[-–—]\s", t)[0]
    t = re.split(r"\b(?:feat|ft|featuring|with)\b\.?", t)[0]
    t = re.sub(r"[^\w\s]", " ", t)
    t = re.sub(r"\s+", " ", t).strip()
    return t


def parse_setlist(text: str, taste: TasteProfile, n: int = 6) -> list[Song]:
    data = _extract_json_array(text)
    durations = {(t.title.lower(), t.artist.lower()): t.duration_s
                 for t in taste.top_tracks}
    songs: list[Song] = []
    seen_exact = set()
    seen_base = set()
    for item in data:
        if not isinstance(item, dict):
            continue
        title = (item.get("title") or "").strip()
        artist = (item.get("artist") or "").strip()
        if not title or not artist:
            continue
        key = (title.lower(), artist.lower())
        if key in seen_exact:
            continue
        base_key = (_base_title(title), artist.lower())
        if base_key in seen_base:
            continue
        seen_exact.add(key)
        seen_base.add(base_key)
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
    LLM client (the project's GeminiClient). Uses a two-pass
    generate -> self-critique -> refine loop for higher-quality, repeat-free
    setlists, with a robust single fallback to the first draft."""

    def __init__(self, client: "_LLM"):
        self.client = client

    def _taste_block(self, taste: TasteProfile) -> str:
        tracks = "\n".join(
            f'- "{t.title}" {_EM} {t.artist}' for t in taste.top_tracks
        )
        artists = ", ".join(taste.top_artists)
        return f"Top tracks:\n{tracks}\n\nTop artists: {artists}\n"

    def _constraints_block(self, exclude=None, seed=None) -> str:
        seed_line = ""
        if seed is not None:
            seed_line = (
                f'\nCONTINUITY: the station just played "{seed.title}" {_EM} '
                f"{seed.artist}. The FIRST pick must flow naturally out of it - "
                f"compatible key/BPM, sensible energy step, no jarring jump.\n"
            )
        exclude_line = ""
        if exclude:
            joined = "; ".join(exclude)
            exclude_line = (
                "\nHARD EXCLUDE - Do NOT include any of these recently played "
                f"songs, in any version: {joined}.\n"
            )
        return seed_line + exclude_line

    def _craft_rules(self, n: int) -> str:
        return (
            f"Curate a flowing {n}-song set like a world-class radio music "
            "director. Apply ALL of these:\n"
            "1. ENERGY ARC: shape a deliberate energy arc across the set - an "
            "inviting open, a build, a peak, then a graceful comedown - rather "
            "than a flat or random sequence.\n"
            "2. HARMONIC + TEMPO FLOW: order songs so transitions are smooth - "
            "adjacent tracks should sit in compatible musical keys "
            "(Camelot-wheel neighbours: same number, +/-1, or relative "
            "major/minor) and similar BPM/tempo so they beat-mix cleanly. "
            "Avoid back-to-back tracks that clash harmonically or jump tempo "
            "violently.\n"
            "3. HEBREW <-> INTERNATIONAL BALANCE: deliberately balance Hebrew/"
            "Israeli songs with international/English ones, reflecting the "
            "listener's taste - alternate languages tastefully instead of "
            "clumping them.\n"
            "4. FAMILIAR + DISCOVERY: mix beloved familiar favorites the "
            "listener clearly loves WITH tasteful discovery - related artists, "
            "collaborators, same-scene or same-era deep cuts they would "
            "probably love but might not know.\n"
            "5. MOOD + ERA VARIETY: vary mood and era across the set so it "
            "feels curated, not monotonous.\n"
            "6. NO DUPLICATES / REAL SONGS ONLY: never repeat a song or artist "
            "back-to-back unnecessarily; NEVER include two versions of the "
            "same song (no live, remix, sped-up, slowed, acoustic, cover or "
            "alternate edits as separate picks); include ONLY real, "
            "verifiably-existing released songs - do NOT invent songs or pair "
            "a real title with the wrong artist.\n"
        )

    _SHAPE = (
        "Return ONLY a JSON array, no prose, no code fence, in exactly this "
        'shape:\n[{"title": "...", "artist": "..."}, ...]'
    )

    def _prompt(self, taste: TasteProfile, n: int, exclude=None, seed=None) -> str:
        return (
            "You are a radio music director building a personal station for one "
            "listener. Here is their recent taste.\n\n"
            f"{self._taste_block(taste)}"
            f"{self._constraints_block(exclude=exclude, seed=seed)}\n"
            f"{self._craft_rules(n)}"
            f"{self._SHAPE}"
        )

    def _refine_prompt(self, taste: TasteProfile, n: int, draft_json: str,
                       exclude=None, seed=None) -> str:
        return (
            "You are a meticulous radio music director doing QUALITY CONTROL on "
            "a draft setlist before it goes on air. CRITIQUE then REFINE it.\n\n"
            f"{self._taste_block(taste)}"
            f"{self._constraints_block(exclude=exclude, seed=seed)}\n"
            "Here is the DRAFT setlist to review:\n"
            f"{draft_json}\n\n"
            "Silently critique the draft for: repeated songs or artists; two "
            "versions of the same song (live/remix/sped-up/acoustic/cover/"
            "edit); any fake, mislabelled or non-existent songs; weak energy "
            "arc; harmonic or tempo clashes between neighbours; poor "
            "Hebrew/international balance; and weak taste-fit. Then RETURN A "
            "REPAIRED setlist that fixes every problem - swap out bad picks for "
            "better real songs, reorder for a smoother energy + harmonic/tempo "
            f"flow, and keep exactly {n} songs.\n"
            f"{self._craft_rules(n)}"
            f"{self._SHAPE}"
        )

    def plan(self, taste: TasteProfile, n: int = 6, exclude=None, seed=None,
             refine: bool = True) -> list[Song]:
        """Generate a setlist. By default runs a second self-critique/refine
        LLM pass; set refine=False to skip it. Always falls back to the first
        draft if the refine pass fails or yields nothing usable."""
        draft_text = self.client.complete(
            self._prompt(taste, n, exclude=exclude, seed=seed))
        draft = parse_setlist(draft_text, taste, n)
        if not refine:
            return draft

        draft_json = json.dumps(
            [{"title": s.title, "artist": s.artist} for s in draft],
            ensure_ascii=False)
        try:
            refined_text = self.client.complete(
                self._refine_prompt(taste, n, draft_json,
                                    exclude=exclude, seed=seed))
            refined = parse_setlist(refined_text, taste, n)
            if refined:
                return refined
        except Exception:
            pass
        return draft