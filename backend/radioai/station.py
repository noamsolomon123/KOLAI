import os
import threading


class StationEngine:
    """Endless rolling block queue. Renders blocks strictly in order (continuity
    via a single rolling prev-track), keeps blocks [current .. current+buffer_ahead]
    ready, and PRUNES old block files + registry entries so it runs forever without
    filling disk or RAM."""

    def __init__(self, planner, block_renderer, *, songs_per_block=3,
                 buffer_ahead=2, keep_behind=2, blocks_dir="cache/blocks"):
        self._planner = planner
        self._renderer = block_renderer
        self._songs_per_block = songs_per_block
        self._buffer_ahead = buffer_ahead
        self._keep_behind = keep_behind
        self._blocks_dir = blocks_dir
        os.makedirs(blocks_dir, exist_ok=True)
        self._blocks = {}            # index -> {"meta": dict, "path": str}  (prunable)
        self._frontier = 0           # next index to render (monotonic; survives prune)
        self._prev_last_track = None # continuity: last_track of frontier-1
        self._prev_last_song = None  # continuity: last song of frontier-1 (planner seed)
        self._current = 0
        self._lock = threading.Lock()
        self._render_lock = threading.Lock()
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._thread = None

    def _ensure_through(self, target):
        with self._render_lock:
            while True:
                with self._lock:
                    i = self._frontier
                if i > target:
                    break
                seed = self._prev_last_song
                songs = self._planner.next_songs(self._songs_per_block, seed=seed)
                res = self._renderer.render(songs, i, prev_track=self._prev_last_track)
                with self._lock:
                    self._blocks[i] = {"meta": res.meta, "path": res.path}
                    self._prev_last_track = res.last_track
                    self._prev_last_song = songs[-1] if songs else None
                    self._frontier = i + 1

    def get_block_meta(self, n):
        with self._lock:
            b = self._blocks.get(n)
        return b["meta"] if b else None

    def get_block_path(self, n):
        self._ensure_through(n)
        with self._lock:
            return self._blocks[n]["path"]

    def advance(self, n):
        with self._lock:
            if n > self._current:
                self._current = n
        self._wake.set()
        self._prune()

    def _prune(self):
        """Delete block files + registry entries older than current-keep_behind."""
        with self._lock:
            low = self._current - self._keep_behind
            stale = [i for i in self._blocks if i < low]
        for i in stale:
            path = os.path.join(self._blocks_dir, f"block_{i}.mp3")
            try:
                if os.path.exists(path):
                    os.remove(path)
            except OSError:
                pass
            with self._lock:
                self._blocks.pop(i, None)

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
            self._prune()
            self._wake.wait(timeout=2.0)
            self._wake.clear()
