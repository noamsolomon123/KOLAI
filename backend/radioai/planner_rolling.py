import time as _time
from radioai.models import Song


class RollingPlanner:
    """Endless, taste-refreshing, non-repeating song selection.

    Wraps a TasteService (Spotify) and a SetlistPlanner (LLM). Re-pulls the
    Spotify taste every `refresh_every` songs or after `refresh_ttl_s` seconds
    so the station keeps learning. Excludes the last `no_repeat_window` titles;
    relaxes (accepts) rather than stalling when the planner is starved.
    """

    def __init__(self, taste_service, setlist_planner, *,
                 refresh_every: int = 5, refresh_ttl_s: float = 600.0,
                 no_repeat_window: int = 50, clock=None):
        self._taste = taste_service
        self._planner = setlist_planner
        self._refresh_every = refresh_every
        self._refresh_ttl_s = refresh_ttl_s
        self._no_repeat_window = no_repeat_window
        self._clock = clock or _time.monotonic
        self._profile = None
        self._songs_since_refresh = 0
        self._last_refresh = 0.0
        self.history: list[str] = []

    def _key(self, song) -> str:
        return f"{song.title} — {song.artist}"

    def _ensure_profile(self):
        now = self._clock()
        if self._profile is None:
            self._profile = self._taste.get_profile(use_cache=True)   # first load: cache ok
            self._last_refresh = now
            self._songs_since_refresh = 0
        elif (self._songs_since_refresh >= self._refresh_every
              or (now - self._last_refresh) > self._refresh_ttl_s):
            self._profile = self._taste.get_profile(use_cache=False)  # FORCE re-learn from Spotify
            self._last_refresh = now
            self._songs_since_refresh = 0
        return self._profile

    def next_songs(self, n: int, seed=None) -> list[Song]:
        profile = self._ensure_profile()
        recent = self.history[-self._no_repeat_window:]
        picks = self._planner.plan(profile, n=n, exclude=recent, seed=seed)
        recent_set = set(recent)
        fresh = [s for s in picks if self._key(s) not in recent_set]
        chosen = (fresh if len(fresh) >= n else picks)[:n]   # relax: accept if starved
        for s in chosen:
            self.history.append(self._key(s))
        self._songs_since_refresh += len(chosen)
        return chosen
