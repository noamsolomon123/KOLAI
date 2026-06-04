from radioai.render_show import beat_for_break, BEATS


def test_beats_rotate():
    assert BEATS[0] == "song"
    seq = [beat_for_break(k) for k in range(len(BEATS) + 1)]
    assert seq[:len(BEATS)] == BEATS
    assert seq[len(BEATS)] == BEATS[0]   # wraps around
