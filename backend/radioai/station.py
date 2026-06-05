import os
import threading


class StationEngine:
    """Endless rolling block queue. Keeps blocks [current .. current+buffer_ahead]
    rendered ahead of playback. Blocks render strictly in order so each block's
    last track can seed + bridge the next (continuity)."""

    def __init__(self, planner, block_renderer, *, songs_per_block=3,
                 buffer_ahead=1, blocks_dir="cache/blocks"):
        self._planner = planner
        self._renderer = block_renderer
        self._songs_per_block = songs_per_block
        self._buffer_ahead = buffer_ahead
        self._blocks_dir = blocks_dir
        os.makedirs(blocks_dir, exist_ok=True)
        self._blocks = {}     # index -> render result (.meta, .path, .last_track)
        self._songs = {}      # index -> list[Song]
        self._current = 0
        self._lock = threading.Lock()
        self._render_lock = threading.Lock()
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._thread = None

    def _ensure_through(self, target):
        with self._render_lock:
            i = 0
            while i in self._blocks:
                i += 1
            while i <= target:
                seed = None
                if i > 0 and self._songs.get(i - 1):
                    seed = self._songs[i - 1][-1]
                songs = self._planner.next_songs(self._songs_per_block, seed=seed)
                prev_track = self._blocks[i - 1].last_track if i > 0 else None
                res = self._renderer.render(songs, i, prev_track=prev_track)
                with self._lock:
                    self._songs[i] = songs
                    self._blocks[i] = res
                i += 1

    def get_block_meta(self, n):
        with self._lock:
            res = self._blocks.get(n)
        return res.meta if res is not None else None

    def get_block_path(self, n):
        self._ensure_through(n)
        with self._lock:
            return self._blocks[n].path

    def advance(self, n):
        with self._lock:
            if n > self._current:
                self._current = n
        self._wake.set()

    def start(self):
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        self._wake.set()

    def _run(self):
        while not self._stop.is_set():
            with self._lock:
                target = self._current + self._buffer_ahead
            try:
                self._ensure_through(target)
            except Exception as e:
                print(f"[station] render error: {e}")
            self._wake.wait(timeout=2.0)
            self._wake.clear()