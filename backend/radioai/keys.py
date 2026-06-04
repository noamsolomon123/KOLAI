# Camelot wheel: maps a musical key to a code like "8A" (minor) / "8B" (major).
# Harmonic mixing: keys are compatible if equal, relative (same number, A<->B),
# or adjacent on the wheel (number +/- 1 mod 12, same letter).

_MAJOR_TO_CAMELOT = {
    "C": "8B", "G": "9B", "D": "10B", "A": "11B", "E": "12B", "B": "1B",
    "F#": "2B", "Gb": "2B", "Db": "3B", "C#": "3B", "Ab": "4B", "G#": "4B",
    "Eb": "5B", "D#": "5B", "Bb": "6B", "A#": "6B", "F": "7B",
}
_MINOR_TO_CAMELOT = {
    "A": "8A", "E": "9A", "B": "10A", "F#": "11A", "Gb": "11A",
    "C#": "12A", "Db": "12A", "G#": "1A", "Ab": "1A", "D#": "2A", "Eb": "2A",
    "A#": "3A", "Bb": "3A", "F": "4A", "C": "5A", "G": "6A", "D": "7A",
}


def camelot_from_key(tonic: str, mode: str) -> str:
    """tonic like 'C', 'F#'; mode 'major' or 'minor'. Returns e.g. '8B'."""
    table = _MAJOR_TO_CAMELOT if mode == "major" else _MINOR_TO_CAMELOT
    if tonic not in table:
        raise ValueError(f"Unknown tonic {tonic!r} for mode {mode!r}")
    return table[tonic]


def _parse(code: str) -> tuple[int, str]:
    letter = code[-1]
    number = int(code[:-1])
    return number, letter


def are_keys_compatible(a: str, b: str) -> bool:
    na, la = _parse(a)
    nb, lb = _parse(b)
    if a == b:
        return True
    if na == nb and la != lb:  # relative major/minor
        return True
    if la == lb:               # adjacent on the wheel (1..12 wraps)
        diff = abs(na - nb)
        return diff == 1 or diff == 11
    return False


def camelot_relation(a: str, b: str) -> str:
    """Classify the harmonic relationship between two Camelot codes."""
    na, la = _parse(a)
    nb, lb = _parse(b)
    if a == b:
        return "same"
    if na == nb and la != lb:
        return "relative"
    if la == lb:
        diff = abs(na - nb)
        if diff == 1 or diff == 11:
            return "adjacent"
    return "clash"
