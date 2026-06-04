import os
import glob
import shutil
import hashlib
import tempfile
import subprocess
import sys


def stem_cache_paths(path: str, cache_dir: str):
    """Deterministic cache file paths for a song's (vocals, instrumental)."""
    h = hashlib.sha1(os.path.abspath(path).encode("utf-8")).hexdigest()[:16]
    d = os.path.join(cache_dir, "stems")
    return (os.path.join(d, f"{h}_vocals.wav"),
            os.path.join(d, f"{h}_instrumental.wav"))


class StemSeparator:
    """Separates a song into (vocals, instrumental), cached to disk. The actual
    separation is injectable via `separate_fn(path) -> (vocals_path,
    instrumental_path)`; the default shells out to Demucs (--two-stems=vocals)."""

    def __init__(self, cache_dir: str, separate_fn=None):
        self.cache_dir = cache_dir
        self._separate = separate_fn or self._demucs_separate

    def separate(self, path: str):
        voc, inst = stem_cache_paths(path, self.cache_dir)
        if os.path.exists(voc) and os.path.exists(inst):
            return voc, inst
        os.makedirs(os.path.dirname(voc), exist_ok=True)
        src_voc, src_inst = self._separate(path)
        shutil.copyfile(src_voc, voc)
        shutil.copyfile(src_inst, inst)
        return voc, inst

    def _demucs_separate(self, path: str):
        import torch
        import soundfile as sf
        from demucs.pretrained import get_model
        from demucs.apply import apply_model
        from demucs.audio import AudioFile

        if getattr(self, "_model", None) is None:
            self._model = get_model("htdemucs")
            self._model.eval()
        model = self._model

        wav = AudioFile(path).read(
            streams=0, samplerate=model.samplerate, channels=model.audio_channels)
        ref = wav.mean(0)
        wav_n = (wav - ref.mean()) / (ref.std() + 1e-8)
        with torch.no_grad():
            sources = apply_model(model, wav_n[None], device="cpu", progress=False)[0]
        sources = sources * ref.std() + ref.mean()

        names = list(model.sources)
        vocals = sources[names.index("vocals")]
        no_vocals = sum(sources[i] for i, n in enumerate(names) if n != "vocals")

        out_dir = tempfile.mkdtemp(prefix="demucs_")
        vpath = os.path.join(out_dir, "vocals.wav")
        ipath = os.path.join(out_dir, "no_vocals.wav")
        sf.write(vpath, vocals.cpu().numpy().T, model.samplerate)
        sf.write(ipath, no_vocals.cpu().numpy().T, model.samplerate)
        return vpath, ipath