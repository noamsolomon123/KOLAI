import json
from fastapi.testclient import TestClient
from radioai.server import create_app


def test_health():
    c = TestClient(create_app("."))
    assert c.get("/api/health").json()["status"] == "ok"


def test_show_404_then_200(tmp_path):
    c = TestClient(create_app(str(tmp_path)))
    assert c.get("/api/show").status_code == 404
    (tmp_path / "show.json").write_text(
        json.dumps({"station": "radio AI", "segments": [], "talk": []}),
        encoding="utf-8")
    r = c.get("/api/show")
    assert r.status_code == 200
    assert r.json()["station"] == "radio AI"


def test_audio_range(tmp_path):
    (tmp_path / "show.mp3").write_bytes(b"0123456789" * 100)  # 1000 bytes
    c = TestClient(create_app(str(tmp_path)))
    r = c.get("/api/audio", headers={"Range": "bytes=0-9"})
    assert r.status_code == 206
    assert r.headers["content-range"].startswith("bytes 0-9/1000")
    assert len(r.content) == 10


def test_audio_404_when_missing(tmp_path):
    c = TestClient(create_app(str(tmp_path)))
    assert c.get("/api/audio").status_code == 404


def test_station_endpoints(tmp_path):
    from radioai.server import create_app
    from fastapi.testclient import TestClient
    import os

    class FakeEngine:
        def __init__(self):
            self.meta = None
            self.advanced = []
            self.started = False
        def start(self): self.started = True
        def advance(self, n): self.advanced.append(n)
        def get_block_meta(self, n): return self.meta

    fake = FakeEngine()
    app = create_app(cache_dir=str(tmp_path), engine=fake)
    c = TestClient(app)

    r = c.post("/api/station/start")
    assert r.status_code == 200 and r.json()["block"] == 0
    assert fake.started is True

    r = c.get("/api/station/block/0/meta")          # not ready -> 202
    assert r.status_code == 202
    assert 0 in fake.advanced

    fake.meta = {"index": 0, "duration_s": 1.0, "segments": [], "talk": []}
    r = c.get("/api/station/block/0/meta")          # ready -> 200
    assert r.status_code == 200 and r.json()["index"] == 0

    r = c.get("/api/station/block/0")               # file missing -> 202
    assert r.status_code == 202

    os.makedirs(os.path.join(tmp_path, "blocks"), exist_ok=True)
    with open(os.path.join(tmp_path, "blocks", "block_0.mp3"), "wb") as f:
        f.write(bytes([0]) * 5000)
    r = c.get("/api/station/block/0", headers={"Range": "bytes=0-99"})   # -> 206
    assert r.status_code == 206
    assert r.headers["Content-Range"].startswith("bytes 0-99/")


def test_station_settings(tmp_path):
    """POST /api/station/settings updates _renderer attributes and returns the preset."""

    class FakeRenderer:
        talk_chance = 0.5
        banter_chance = 0.2
        max_silence = 4

    class FakeEngine:
        _renderer = FakeRenderer()
        def start(self): pass
        def advance(self, n): pass
        def get_block_meta(self, n): return None

    fake = FakeEngine()
    app = create_app(cache_dir=str(tmp_path), engine=fake)
    c = TestClient(app)

    r = c.post("/api/station/settings", json={"level": "more"})
    assert r.status_code == 200
    data = r.json()
    assert data["ok"] is True
    assert data["level"] == "more"
    assert data["talk_chance"] == 0.85
    assert data["max_silence"] == 2
    # Confirm the live renderer was mutated
    assert fake._renderer.talk_chance == 0.85
    assert fake._renderer.banter_chance == 0.40
    assert fake._renderer.max_silence == 2