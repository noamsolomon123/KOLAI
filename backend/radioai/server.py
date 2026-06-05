import os
import json
import sys
import subprocess
from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import StreamingResponse, FileResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from radioai.config import Config


_STATION_STYLE = (
    "Read the following like a charismatic, warm, professional Israeli FM radio "
    "host. Energetic but smooth and confident, natural broadcast pacing, a real "
    "radio personality - not a robot. Speak only the Hebrew:")


def _serve_range(path, request):
    size = os.path.getsize(path)
    range_header = request.headers.get("range")
    if range_header and range_header.startswith("bytes="):
        rng = range_header.split("=", 1)[1]
        start_s, _, end_s = rng.partition("-")
        start = int(start_s) if start_s else 0
        end = int(end_s) if end_s else size - 1
        end = min(end, size - 1)
        start = max(0, min(start, end))
        length = end - start + 1

        def iterfile():
            with open(path, "rb") as f:
                f.seek(start)
                remaining = length
                while remaining > 0:
                    chunk = f.read(min(65536, remaining))
                    if not chunk:
                        break
                    remaining -= len(chunk)
                    yield chunk

        headers = {
            "Content-Range": f"bytes {start}-{end}/{size}",
            "Accept-Ranges": "bytes",
            "Content-Length": str(length),
        }
        return StreamingResponse(iterfile(), status_code=206, headers=headers,
                                 media_type="audio/mpeg")
    return FileResponse(path, media_type="audio/mpeg")


def _build_station(cfg):
    from radioai.fetcher import AudioFetcher
    from radioai.djbrain import DJBrain, GeminiClient
    from radioai.voice import VoiceRenderer, GeminiTTSSynth
    from radioai.taste import TasteService
    from radioai.setlist import SetlistPlanner
    from radioai.planner_rolling import RollingPlanner
    from radioai.djcontext import DJContext
    from radioai.block_renderer import BlockRenderer
    from radioai.station import StationEngine
    blocks_dir = os.path.join(cfg.cache_dir, "blocks")
    fetcher = AudioFetcher(cache_dir=cfg.cache_dir)
    llm = GeminiClient(api_keys=cfg.gemini_api_keys, model=cfg.llm_model)
    brain = DJBrain(client=llm, persona="רדיו AI")
    voice = VoiceRenderer(
        synth=GeminiTTSSynth(api_keys=cfg.gemini_api_keys, model=cfg.tts_model,
                             voice=cfg.tts_voice, style=_STATION_STYLE),
        out_dir=os.path.join(cfg.cache_dir, "voice"))
    taste = TasteService(cfg)
    setlist = SetlistPlanner(client=llm)
    planner = RollingPlanner(taste, setlist)
    ctx = DJContext.build(cfg)
    renderer = BlockRenderer(fetcher, brain, voice, ctx, blocks_dir=blocks_dir,
                             voice_a=cfg.tts_voice, voice_b="Aoede")
    engine = StationEngine(planner, renderer, songs_per_block=3, buffer_ahead=1,
                           blocks_dir=blocks_dir)
    engine.start()
    return engine


def create_app(cache_dir: str | None = None, engine=None) -> FastAPI:
    cfg = Config.from_env()
    if cache_dir is None:
        cache_dir = cfg.cache_dir
    app = FastAPI(title="Radio AI")
    app.add_middleware(
        CORSMiddleware, allow_origins=["*"], allow_methods=["*"],
        allow_headers=["*"],
    )
    state = {"engine": engine}

    def _engine():
        if state["engine"] is None:
            state["engine"] = _build_station(cfg)
        return state["engine"]

    @app.get("/api/health")
    def health():
        return {"status": "ok"}

    @app.get("/api/show")
    def show():
        p = os.path.join(cache_dir, "show.json")
        if not os.path.exists(p):
            raise HTTPException(status_code=404, detail="no show yet")
        with open(p, encoding="utf-8") as f:
            return json.load(f)

    @app.get("/api/audio")
    def audio(request: Request):
        path = os.path.join(cache_dir, "show.mp3")
        if not os.path.exists(path):
            raise HTTPException(status_code=404, detail="no audio yet")
        return _serve_range(path, request)

    @app.post("/api/generate")
    def generate():
        subprocess.Popen([sys.executable, "-m", "radioai.render_show"],
                         cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        return {"status": "started"}

    @app.post("/api/station/start")
    def station_start():
        eng = _engine()
        eng.start()
        return {"ok": True, "block": 0}

    @app.get("/api/station/block/{n}/meta")
    def station_block_meta(n: int):
        eng = _engine()
        eng.advance(n)
        meta = eng.get_block_meta(n)
        if meta is None:
            return JSONResponse({"status": "rendering", "index": n}, status_code=202)
        return meta

    @app.get("/api/station/block/{n}")
    def station_block_audio(n: int, request: Request):
        eng = _engine()
        eng.advance(n)
        meta = eng.get_block_meta(n)
        path = os.path.join(cache_dir, "blocks", f"block_{n}.mp3")
        if meta is None or not os.path.exists(path):
            return JSONResponse({"status": "rendering", "index": n}, status_code=202)
        return _serve_range(path, request)

    @app.post("/api/station/settings")
    def station_settings(payload: dict):
        eng = _engine()
        level = (payload or {}).get("level", "normal")
        presets = {
            "less":   {"talk_chance": 0.25, "banter_chance": 0.10, "max_silence": 6},
            "normal": {"talk_chance": 0.50, "banter_chance": 0.20, "max_silence": 4},
            "more":   {"talk_chance": 0.85, "banter_chance": 0.40, "max_silence": 2},
        }
        cfg_p = presets.get(level, presets["normal"])
        r = getattr(eng, "_renderer", None)
        if r is not None:
            r.talk_chance = cfg_p["talk_chance"]
            r.banter_chance = cfg_p["banter_chance"]
            r.max_silence = cfg_p["max_silence"]
        return {"ok": True, "level": level, **cfg_p}

    _backend = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    _root = os.path.dirname(_backend)
    for _candidate in (os.path.join(_root, "frontend", "dist"),
                       os.path.join(_backend, "frontend", "dist")):
        if os.path.isdir(_candidate):
            app.mount("/", StaticFiles(directory=_candidate, html=True), name="frontend")
            break

    return app


app = create_app()