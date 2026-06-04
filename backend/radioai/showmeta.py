import json
from datetime import datetime, timezone


def build_segments(items, total_s):
    """Sort song markers by start_s and fill end_s (next start, last=total)."""
    ordered = sorted(items, key=lambda x: x["start_s"])
    out = []
    for idx, it in enumerate(ordered):
        end = ordered[idx + 1]["start_s"] if idx + 1 < len(ordered) else total_s
        out.append({**it, "end_s": round(float(end), 2)})
    return out


def write_show_json(path, *, station, dj, duration_s, segments, talk,
                    generated_at=None):
    meta = {
        "station": station,
        "dj": dj,
        "duration_s": round(float(duration_s), 2),
        "generated_at": generated_at or datetime.now(timezone.utc).isoformat(),
        "segments": segments,
        "talk": talk,
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    return meta