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


def test_pcm_to_wav_duration(tmp_path):
    from radioai.voice import pcm_to_wav
    sr = 24000
    pcm = b"\x00\x00" * sr  # 1.0s of 16-bit mono silence
    wav = pcm_to_wav(pcm, sample_rate=sr)
    p = tmp_path / "x.wav"
    p.write_bytes(wav)
    info = sf.info(str(p))
    assert abs(info.duration - 1.0) < 0.01


# --- Fakes mimicking the Gemini TTS response shape ---
class _FakeInline:
    def __init__(self, data):
        self.data = data


class _FakePart:
    def __init__(self, data):
        self.inline_data = _FakeInline(data)


class _FakeContent:
    def __init__(self, data):
        self.parts = [_FakePart(data)]


class _FakeCandidate:
    def __init__(self, data):
        self.content = _FakeContent(data)


class _FakeTTSResp:
    def __init__(self, data):
        self.candidates = [_FakeCandidate(data)]


class _FakeTTSModels:
    def __init__(self, behavior):
        self._behavior = behavior

    def generate_content(self, model, contents, config):
        r = self._behavior()
        if isinstance(r, Exception):
            raise r
        return _FakeTTSResp(r)


class _FakeTTSClient:
    def __init__(self, behavior):
        self.models = _FakeTTSModels(behavior)


def test_gemini_tts_synth_returns_wav(tmp_path):
    from radioai.voice import GeminiTTSSynth
    sr = 24000
    pcm = b"\x00\x00" * (sr // 2)  # 0.5s
    synth = GeminiTTSSynth(api_keys=[], model="m", voice="Puck",
                           sample_rate=sr, clients=[_FakeTTSClient(lambda: pcm)])
    wav = synth.synth("שלום")
    p = tmp_path / "y.wav"
    p.write_bytes(wav)
    info = sf.info(str(p))
    assert abs(info.duration - 0.5) < 0.02


def test_gemini_tts_rotates_on_failure(tmp_path):
    from radioai.voice import GeminiTTSSynth
    sr = 24000
    pcm = b"\x00\x00" * sr

    def fail():
        raise RuntimeError("429 rate limit")

    def ok():
        return pcm

    synth = GeminiTTSSynth(api_keys=[], model="m", voice="Puck", sample_rate=sr,
                           clients=[_FakeTTSClient(fail), _FakeTTSClient(ok)])
    wav = synth.synth("שלום")
    p = tmp_path / "z.wav"
    p.write_bytes(wav)
    assert abs(sf.info(str(p)).duration - 1.0) < 0.01


def test_gemini_tts_requires_a_client():
    from radioai.voice import GeminiTTSSynth
    import pytest
    with pytest.raises(ValueError):
        GeminiTTSSynth(api_keys=[], model="m", voice="Puck", clients=[])


def test_voice_renderer_with_gemini_synth(tmp_path):
    """VoiceRenderer should turn GeminiTTSSynth WAV output into a DJSlot."""
    from radioai.voice import GeminiTTSSynth, VoiceRenderer
    sr = 24000
    pcm = b"\x00\x00" * sr
    synth = GeminiTTSSynth(api_keys=[], model="m", voice="Puck", sample_rate=sr,
                           clients=[_FakeTTSClient(lambda: pcm)])
    r = VoiceRenderer(synth=synth, out_dir=str(tmp_path))
    slot = r.render("שלום עולם")
    assert slot.text == "שלום עולם"
    assert abs(slot.duration_s - 1.0) < 0.05

def test_gemini_tts_prepends_style(tmp_path):
    from radioai.voice import GeminiTTSSynth
    captured = {}

    class _CapModels:
        def generate_content(self, model, contents, config):
            captured["contents"] = contents
            return _FakeTTSResp(b"\x00\x00" * 24000)

    class _CapClient:
        def __init__(self):
            self.models = _CapModels()

    synth = GeminiTTSSynth(api_keys=[], model="m", voice="Algieba",
                           style="Read like a pro radio host", clients=[_CapClient()])
    synth.synth("hello world")
    assert "Read like a pro radio host" in captured["contents"]
    assert "hello world" in captured["contents"]


def test_gemini_tts_no_style_sends_plain_text(tmp_path):
    from radioai.voice import GeminiTTSSynth
    captured = {}

    class _CapModels:
        def generate_content(self, model, contents, config):
            captured["contents"] = contents
            return _FakeTTSResp(b"\x00\x00" * 24000)

    class _CapClient:
        def __init__(self):
            self.models = _CapModels()

    synth = GeminiTTSSynth(api_keys=[], model="m", voice="Algieba",
                           clients=[_CapClient()])
    synth.synth("plain text")
    assert captured["contents"] == "plain text"


class _RecordingSynth:
    """Records (text, voice) per call; returns `secs` of silence wav."""
    def __init__(self, secs=0.5, sr=24000):
        self.calls = []
        self._secs = secs
        self._sample_rate = sr
    def synth(self, text, voice=None):
        import io
        self.calls.append((text, voice))
        buf = io.BytesIO()
        sf.write(buf, np.zeros(int(self._secs * self._sample_rate), dtype=np.float32),
                 self._sample_rate, format="WAV")
        return buf.getvalue()


def test_render_banter_two_voices(tmp_path):
    from radioai.voice import VoiceRenderer
    synth = _RecordingSynth(secs=0.5)
    r = VoiceRenderer(synth=synth, out_dir=str(tmp_path))
    turns = [("A", "shalom shalom"), ("B", "ma nishma"), ("A", "hakol tov")]
    slot = r.render_banter(turns, voice_a="Algieba", voice_b="Puck")
    assert os.path.exists(slot.audio_path)
    assert synth.calls[0] == ("shalom shalom", "Algieba")
    assert synth.calls[1] == ("ma nishma", "Puck")
    assert synth.calls[2] == ("hakol tov", "Algieba")
    assert slot.duration_s > 1.4   # 3 x 0.5s + gaps


def test_render_banter_empty_raises(tmp_path):
    from radioai.voice import VoiceRenderer
    import pytest
    r = VoiceRenderer(synth=_RecordingSynth(), out_dir=str(tmp_path))
    with pytest.raises(ValueError):
        r.render_banter([])


def test_gemini_synth_voice_override(tmp_path):
    from radioai.voice import GeminiTTSSynth
    captured = {}

    class _CapModels:
        def generate_content(self, model, contents, config):
            vc = config.speech_config.voice_config.prebuilt_voice_config.voice_name
            captured["voice"] = vc
            return _FakeTTSResp(b"\x00\x00" * 24000)

    class _CapClient:
        def __init__(self):
            self.models = _CapModels()

    synth = GeminiTTSSynth(api_keys=[], model="m", voice="Algieba", clients=[_CapClient()])
    synth.synth("hi", voice="Puck")
    assert captured["voice"] == "Puck"
    synth.synth("hi")
    assert captured["voice"] == "Algieba"
