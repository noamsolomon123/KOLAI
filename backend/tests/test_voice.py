import os
import numpy as np
import soundfile as sf
from radioai.voice import VoiceRenderer


class _FakeSynth:
    """Returns 1 second of silence as wav bytes for any text."""
    def synth(self, text: str) -> bytes:
        import io
        buf = io.BytesIO()
        sf.write(buf, np.zeros(22050, dtype=np.float32), 22050, format="WAV")
        return buf.getvalue()


def test_render_creates_djslot(tmp_path):
    r = VoiceRenderer(synth=_FakeSynth(), out_dir=str(tmp_path))
    slot = r.render("שלום עולם")
    assert os.path.exists(slot.audio_path)
    assert slot.text == "שלום עולם"
    assert abs(slot.duration_s - 1.0) < 0.1
