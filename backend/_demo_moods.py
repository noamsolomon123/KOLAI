"""Throwaway: render a short (~2-song) KOLAI demo show for each mood.

Modeled on _demo30.py: patches render_show's setlist builder + TTS style per
mood, runs the stock main(), then copies cache/show.mp3 to
demo/kolai-demo-<mood>.mp3.

Usage:  python _demo_moods.py [mood]      (no arg = all moods, in order)
"""
import os
import shutil
import sys
import traceback

import radioai.render_show as rs
from radioai.config import Config
from radioai.taste import TasteService
from radioai.setlist import SetlistPlanner
from radioai.djbrain import GeminiClient
from radioai.fetcher import AudioFetcher
from radioai.moods import MOODS, MOOD_ORDER

_HERE = os.path.dirname(os.path.abspath(__file__))
DEMO_DIR = os.path.normpath(os.path.join(_HERE, "..", "demo"))
N_SONGS = 2        # keep each demo short (~5-8 min) to bound repo size
N_CANDIDATES = 5   # ask the planner for spares in case some don't fetch


def _first_playable(cfg, songs, n):
    """Pre-fetch candidates (cached, so main()'s re-fetch is instant) and keep
    the first n that actually download."""
    fetcher = AudioFetcher(cache_dir=cfg.cache_dir)
    out = []
    for s in songs:
        try:
            fetcher.fetch(s)
            out.append(s)
        except Exception as e:
            print(f"  [prefetch-skip] {s.title} - {s.artist}: {e}")
        if len(out) >= n:
            break
    return out


def _used_path(cfg):
    return os.path.join(cfg.cache_dir, "_demo_moods_used.txt")


def _load_used(cfg):
    try:
        with open(_used_path(cfg), encoding="utf-8") as f:
            return [ln.strip() for ln in f if ln.strip()]
    except OSError:
        return []


def _save_used(cfg, used):
    with open(_used_path(cfg), "w", encoding="utf-8") as f:
        f.write("\n".join(used))


def _make_build_setlist(mood):
    def build(cfg):
        profile = TasteService(cfg).get_profile()
        planner = SetlistPlanner(
            client=GeminiClient(api_keys=cfg.gemini_api_keys,
                                model=cfg.llm_model))
        # exclude songs already used by the other mood demos so each mood
        # demo showcases a different slice of the taste pool
        used = _load_used(cfg)
        songs = planner.plan(profile, n=N_CANDIDATES, mood=mood,
                             exclude=used or None)
        playable = _first_playable(cfg, songs, N_SONGS)
        if len(playable) < 2:
            raise RuntimeError(
                f"only {len(playable)} playable songs for mood '{mood}'")
        _save_used(cfg, used + [f"{s.title} - {s.artist}" for s in playable])
        return playable
    return build


def render_mood(mood):
    cfg = Config.from_env()
    rs.build_setlist = _make_build_setlist(mood)
    rs.RADIO_STYLE = MOODS[mood]["voice_style"]  # main() reads it at call time
    rs.main()
    src = os.path.join(cfg.cache_dir, "show.mp3")
    os.makedirs(DEMO_DIR, exist_ok=True)
    dst = os.path.join(DEMO_DIR, f"kolai-demo-{mood}.mp3")
    shutil.copyfile(src, dst)
    print(f"[mood:{mood}] -> {dst} ({os.path.getsize(dst) / 1e6:.1f} MB)")


def main():
    moods = sys.argv[1:] or MOOD_ORDER
    failed = []
    for mood in moods:
        if mood not in MOODS:
            raise SystemExit(f"unknown mood: {mood}")
        ok = False
        for attempt in (1, 2):
            print(f"=== rendering mood '{mood}' (attempt {attempt}) ===")
            try:
                render_mood(mood)
                ok = True
                break
            except Exception:
                traceback.print_exc()
        if not ok:
            failed.append(mood)
    if failed:
        print(f"FAILED moods: {failed}")
        raise SystemExit(1)
    print("All requested mood demos rendered.")


if __name__ == "__main__":
    main()