import os
import time
from types import SimpleNamespace
from radioai.models import Song
from radioai.station import StationEngine


class FakePlanner:
    def __init__(self):
        self.n = 0
        self.calls = []
    def next_songs(self, n, seed=None):
        self.calls.append({"n": n, "seed": seed})
        out = []
        for _ in range(n):
            self.n += 1
            out.append(Song(title=f"T{self.n}", artist="A"))
        return out


class FakeRenderer:
    def __init__(self, blocks_dir):
        self.blocks_dir = blocks_dir
        self.calls = []
    def render(self, songs, index, prev_track=None):
        self.calls.append({"index": index, "songs": list(songs), "prev_track": prev_track})
        path = os.path.join(self.blocks_dir, f"block_{index}.mp3")
        # Write a tiny real file so prune tests can verify deletion
        open(path, "wb").write(b"ID3")
        return SimpleNamespace(
            meta={"index": index, "duration_s": 1.0, "segments": [], "talk": []},
            path=path,
            last_track=f"track-{index}",
        )


def _engine(tmp_path, **kw):
    renderer = FakeRenderer(blocks_dir=str(tmp_path))
    return StationEngine(FakePlanner(), renderer, blocks_dir=str(tmp_path), **kw)


def test_get_block_path_renders_block0(tmp_path):
    eng = _engine(tmp_path)
    p = eng.get_block_path(0)
    assert os.path.basename(p) == "block_0.mp3"
    assert eng._renderer.calls[0]["index"] == 0
    assert eng._renderer.calls[0]["prev_track"] is None


def test_meta_none_until_rendered(tmp_path):
    eng = _engine(tmp_path)
    assert eng.get_block_meta(5) is None
    eng.get_block_path(0)
    assert eng.get_block_meta(0)["index"] == 0


def test_continuity_threads_prev_track_and_seed(tmp_path):
    eng = _engine(tmp_path)
    eng.get_block_path(1)                       # renders 0 then 1, in order
    calls = eng._renderer.calls
    assert calls[0]["index"] == 0 and calls[1]["index"] == 1
    assert calls[1]["prev_track"] == "track-0"  # block 1 bridges from block 0's last track
    last0 = calls[0]["songs"][-1]
    assert eng._planner.calls[1]["seed"] is last0   # block 1 seeded by block 0's last song


def test_no_double_render(tmp_path):
    eng = _engine(tmp_path)
    eng.get_block_path(0)
    eng.get_block_path(0)
    assert len([c for c in eng._renderer.calls if c["index"] == 0]) == 1


def test_worker_buffers_ahead(tmp_path):
    eng = _engine(tmp_path, buffer_ahead=1)
    eng.start()
    eng.advance(0)
    waited = 0
    while eng.get_block_meta(1) is None and waited < 60:
        time.sleep(0.05); waited += 1
    eng.stop()
    assert eng.get_block_meta(0) is not None
    assert eng.get_block_meta(1) is not None


def test_prunes_old_blocks(tmp_path):
    eng = _engine(tmp_path, buffer_ahead=1, keep_behind=1)
    for i in range(4):
        eng.get_block_path(i)
        eng.advance(i)
    # current=3, keep_behind=1 -> blocks with index < 2 are pruned (files + registry)
    assert not os.path.exists(os.path.join(str(tmp_path), "block_0.mp3"))
    assert not os.path.exists(os.path.join(str(tmp_path), "block_1.mp3"))
    assert eng.get_block_meta(0) is None      # registry pruned
    assert eng.get_block_meta(3) is not None  # recent block kept


def test_frontier_advances_past_pruned(tmp_path):
    eng = _engine(tmp_path, buffer_ahead=1, keep_behind=1)
    for i in range(4):
        eng.get_block_path(i)
        eng.advance(i)
    # frontier is now 4; rendering block 4 must not re-render any pruned index
    eng.get_block_path(4)
    indices = [c["index"] for c in eng._renderer.calls]
    # no duplicates
    assert len(indices) == len(set(indices)), f"duplicate renders: {indices}"
    # block 4 was rendered
    assert 4 in indices


def test_reset_starts_fresh_keeps_history(tmp_path):
    eng = _engine(tmp_path)
    eng.get_block_path(0)
    eng.get_block_path(1)
    first_songs = [c["songs"] for c in eng._renderer.calls]
    eng.advance(1)
    eng.reset()
    assert eng.get_block_meta(0) is None      # registry cleared
    assert eng.get_block_meta(1) is None
    eng.get_block_path(0)                       # re-renders a fresh block 0
    assert eng.get_block_meta(0) is not None
    # fresh queue uses NEW songs (planner history preserved -> different picks)
    new_block0 = eng._renderer.calls[-1]["songs"]
    assert new_block0 != first_songs[0]
