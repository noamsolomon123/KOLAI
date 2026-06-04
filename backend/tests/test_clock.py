from datetime import datetime
from radioai.clock import now_parts


def test_time_string():
    ts, _ = now_parts(datetime(2026, 6, 4, 21, 40))
    assert ts == "21:40"


def test_parts_of_day():
    assert now_parts(datetime(2026, 6, 4, 8, 0))[1] == "בוקר"
    assert now_parts(datetime(2026, 6, 4, 14, 0))[1] == "צהריים"
    assert now_parts(datetime(2026, 6, 4, 19, 0))[1] == "ערב"
    assert now_parts(datetime(2026, 6, 4, 2, 0))[1] == "לילה"