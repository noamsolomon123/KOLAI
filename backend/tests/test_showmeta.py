import json
from radioai.showmeta import build_segments, write_show_json


def test_build_segments_fills_end_and_sorts():
    items = [{"type": "song", "title": "B", "artist": "y", "start_s": 100.0},
             {"type": "song", "title": "A", "artist": "x", "start_s": 0.0}]
    segs = build_segments(items, 250.0)
    assert [s["title"] for s in segs] == ["A", "B"]
    assert segs[0]["end_s"] == 100.0
    assert segs[1]["end_s"] == 250.0


def test_write_show_json_roundtrip(tmp_path):
    p = str(tmp_path / "show.json")
    write_show_json(p, station="רדיו AI", dj="רדיו AI", duration_s=123.4,
                    segments=[{"type": "song", "title": "A", "artist": "x",
                               "start_s": 0.0, "end_s": 123.4}],
                    talk=[{"beat": "weather", "text": "שלום", "start_s": 1.0, "end_s": 3.0}],
                    generated_at="2026-06-05T00:00:00Z")
    with open(p, encoding="utf-8") as f:
        d = json.load(f)
    assert d["station"] == "רדיו AI"
    assert d["segments"][0]["title"] == "A"
    assert d["talk"][0]["beat"] == "weather"
    assert d["generated_at"] == "2026-06-05T00:00:00Z"
    assert d["duration_s"] == 123.4