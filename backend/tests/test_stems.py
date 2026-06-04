import os
import numpy as np
import soundfile as sf
from radioai.stems import stem_cache_paths, StemSeparator


def test_stem_cache_paths_deterministic_and_distinct(tmp_path):
    v1, i1 = stem_cache_paths("a.mp3", str(tmp_path))
    v2, i2 = stem_cache_paths("a.mp3", str(tmp_path))
    v3, i3 = stem_cache_paths("b.mp3", str(tmp_path))
    assert (v1, i1) == (v2, i2)
    assert v1 != i1
    assert v1 != v3


def test_separate_writes_cache_and_reuses(tmp_path):
    calls = {"n": 0}

    def fake_separate(path):
        calls["n"] += 1
        src = tmp_path / "src"
        src.mkdir(exist_ok=True)
        v = str(src / "vocals.wav")
        i = str(src / "no_vocals.wav")
        sf.write(v, np.zeros(1000, dtype="float32"), 44100)
        sf.write(i, np.zeros(1000, dtype="float32"), 44100)
        return v, i

    sep = StemSeparator(str(tmp_path), separate_fn=fake_separate)
    voc, inst = sep.separate(str(tmp_path / "song.mp3"))
    assert os.path.exists(voc) and os.path.exists(inst)
    assert calls["n"] == 1
    voc2, inst2 = sep.separate(str(tmp_path / "song.mp3"))
    assert (voc2, inst2) == (voc, inst)
    assert calls["n"] == 1   # cache hit, fake not called again
