from radioai.keys import camelot_from_key, are_keys_compatible


def test_camelot_major_mapping():
    assert camelot_from_key("C", "major") == "8B"
    assert camelot_from_key("A", "minor") == "8A"
    assert camelot_from_key("G", "major") == "9B"


def test_same_key_is_compatible():
    assert are_keys_compatible("8A", "8A") is True


def test_relative_major_minor_compatible():
    # same number, different letter
    assert are_keys_compatible("8A", "8B") is True


def test_adjacent_on_wheel_compatible():
    assert are_keys_compatible("8A", "9A") is True
    assert are_keys_compatible("8A", "7A") is True


def test_wheel_wraps_around():
    assert are_keys_compatible("12A", "1A") is True
    assert are_keys_compatible("1A", "12A") is True


def test_distant_keys_incompatible():
    assert are_keys_compatible("8A", "11A") is False

from radioai.keys import camelot_relation


def test_camelot_relation_same():
    assert camelot_relation("8A", "8A") == "same"


def test_camelot_relation_relative():
    assert camelot_relation("8A", "8B") == "relative"


def test_camelot_relation_adjacent_and_wrap():
    assert camelot_relation("8A", "9A") == "adjacent"
    assert camelot_relation("8A", "7A") == "adjacent"
    assert camelot_relation("12A", "1A") == "adjacent"


def test_camelot_relation_clash():
    assert camelot_relation("8A", "11A") == "clash"
    assert camelot_relation("8A", "3B") == "clash"
