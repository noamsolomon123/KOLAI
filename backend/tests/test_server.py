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