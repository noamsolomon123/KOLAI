from radioai.models import Song
from radioai.planner_rolling import RollingPlanner


class FakeTaste:
    def __init__(self):
        self.calls = []          # records use_cache values
        self.profile = "PROFILE"
    def get_profile(self, use_cache=True):
        self.calls.append(use_cache)
        return self.profile


class FakePlanner:
    def __init__(self):
        self.calls = []          # records {n, exclude, seed}
        self._i = 0
    def plan(self, profile, n=6, exclude=None, seed=None):
        self.calls.append({"n": n, "exclude": list(exclude or []), "seed": seed})
        out = []
        for _ in range(n):
            self._i += 1
            out.append(Song(title=f"S{self._i}", artist="A"))
        return out


class Clock:
    def __init__(self): self.t = 1000.0
    def __call__(self): return self.t


def test_first_call_loads_profile_and_returns_n():
    taste, planner = FakeTaste(), FakePlanner()
    rp = RollingPlanner(taste, planner, refresh_every=5, clock=Clock())
    songs = rp.next_songs(3)
    assert len(songs) == 3
    assert taste.calls == [True]                 # first load may use cache


def test_no_refresh_before_threshold():
    taste, planner = FakeTaste(), FakePlanner()
    rp = RollingPlanner(taste, planner, refresh_every=5, clock=Clock())
    rp.next_songs(3)                             # counter 0->3
    rp.next_songs(1)                             # counter 3->4 (<5)
    assert taste.calls == [True]                 # no forced refresh yet


def test_force_refresh_at_song_threshold():
    taste, planner = FakeTaste(), FakePlanner()
    rp = RollingPlanner(taste, planner, refresh_every=5, clock=Clock())
    rp.next_songs(3)                             # counter ->3   calls [True]
    rp.next_songs(3)                             # counter ->6   still [True] (3<5 at check time)
    rp.next_songs(1)                             # check 6>=5 -> FORCE refresh
    assert taste.calls == [True, False]


def test_ttl_expiry_forces_refresh():
    taste, planner = FakeTaste(), FakePlanner()
    clk = Clock()
    rp = RollingPlanner(taste, planner, refresh_every=999, refresh_ttl_s=600, clock=clk)
    rp.next_songs(1)                             # [True]
    clk.t += 601                                 # past TTL
    rp.next_songs(1)                             # TTL exceeded -> force refresh
    assert taste.calls == [True, False]


def test_excludes_recent_history():
    taste, planner = FakeTaste(), FakePlanner()
    rp = RollingPlanner(taste, planner, no_repeat_window=50, clock=Clock())
    rp.next_songs(2)                             # adds "S1 — A","S2 — A"
    rp.next_songs(2)                             # adds S3,S4
    second = planner.calls[1]
    assert "S1 — A" in second["exclude"] and "S2 — A" in second["exclude"]


def test_seed_passed_to_planner():
    taste, planner = FakeTaste(), FakePlanner()
    rp = RollingPlanner(taste, planner, clock=Clock())
    seed = Song(title="Seed", artist="X")
    rp.next_songs(1, seed=seed)
    assert planner.calls[0]["seed"] is seed


def test_relaxes_when_starved():
    taste = FakeTaste()
    class RepeatPlanner:
        def plan(self, profile, n=6, exclude=None, seed=None):
            return [Song(title="Same", artist="A") for _ in range(n)]
    rp = RollingPlanner(taste, RepeatPlanner(), clock=Clock())
    rp.next_songs(1)                             # history ["Same — A"]
    out = rp.next_songs(1)                       # planner repeats -> relax & accept, don't stall
    assert len(out) == 1
