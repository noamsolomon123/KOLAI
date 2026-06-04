import os
import json
import sys
import subprocess
from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import StreamingResponse, FileResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from radioai.config import Config


def create_app(cache_dir: str | None = None) -> FastAPI:
    if cache_dir is None:
        cache_dir = Config.from_env().cache_dir
    app = FastAPI(title="Radio AI")
    app.add_middleware(
        CORSMiddleware, allow_origins=["*"], allow_methods=["*"],
        allow_headers=["*"],
    )

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

    @app.post("/api/generate")
    def generate():
        subprocess.Popen([sys.executable, "-m", "radioai.render_show"],
                         cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        return {"status": "started"}

    _backend = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    _root = os.path.dirname(_backend)
    for _candidate in (os.path.join(_root, "frontend", "dist"),
                       os.path.join(_backend, "frontend", "dist")):
        if os.path.isdir(_candidate):
            app.mount("/", StaticFiles(directory=_candidate, html=True), name="frontend")
            break

    return app


app = create_app()