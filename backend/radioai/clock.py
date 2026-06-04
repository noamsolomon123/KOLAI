from datetime import datetime


def now_parts(dt: datetime) -> tuple[str, str]:
    """Return (HH:MM, Hebrew part-of-day) for the given datetime."""
    time_str = dt.strftime("%H:%M")
    h = dt.hour
    if 5 <= h <= 11:
        part = "בוקר"
    elif 12 <= h <= 16:
        part = "צהריים"
    elif 17 <= h <= 21:
        part = "ערב"
    else:
        part = "לילה"
    return time_str, part