import io
import os
import hashlib
import wave
from typing import Protocol
import numpy as np
import soundfile as sf
from radioai.models import DJSlot


class Synth(Protocol):
    def synth(self, text: str, voice: str | None = None) -> bytes: ...


def pcm_to_wav(pcm_bytes: bytes, sample_rate: int = 24000) -> bytes:
    """Wrap raw 16-bit mono PCM (Gemini TTS output) into WAV container bytes."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)  # 16-bit
        wf.setframerate(sample_rate)
        wf.writeframes(pcm_bytes)
    return buf.getvalue()


class GeminiTTSSynth:
    """Synth over the Gemini TTS API. Returns WAV bytes (wraps Gemini's raw
    24 kHz PCM). Rotates across multiple API keys on failure (rate-limit).
    A per-call `voice` overrides the default voice (used for two-host banter)."""

    def __init__(self, api_keys: list[str], model: str, voice: str,
                 style: str = "", sample_rate: int = 24000, clients=None):
        self._model = model
        self._voice = voice
        self._style = style
        self._sample_rate = sample_rate
        self._idx = 0
        if clients is not None:
            self._clients = list(clients)
        else:
            from google import genai
            self._clients = [genai.Client(api_key=k) for k in api_keys]
        if not self._clients:
            raise ValueError("GeminiTTSSynth needs at least one API key/client")

    def synth(self, text: str, voice: str | None = None) -> bytes:
        from google.genai import types
        cfg = types.GenerateContentConfig(
            response_modalities=["AUDIO"],
            speech_config=types.SpeechConfig(
                voice_config=types.VoiceConfig(
                    prebuilt_voice_config=types.PrebuiltVoiceConfig(
                        voice_name=voice or self._voice))))
        errors = []
        for _ in range(len(self._clients)):
            client = self._clients[self._idx]
            try:
                contents = f"{self._style}\n\n{text}" if self._style else text
                resp = client.models.generate_content(
                    model=self._model, contents=contents, config=cfg)
                pcm = resp.candidates[0].content.parts[0].inline_data.data
                return pcm_to_wav(pcm, self._sample_rate)
            except Exception as e:  # rate-limit/transient -> rotate to next key
                errors.append(repr(e))
                self._idx = (self._idx + 1) % len(self._clients)
        raise RuntimeError(f"All Gemini TTS keys failed: {errors}")


class VoiceRenderer:
    def __init__(self, synth: Synth, out_dir: str):
        self.synth = synth
        self.out_dir = out_dir
        os.makedirs(out_dir, exist_ok=True)

    def render(self, text: str) -> DJSlot:
        data = self.synth.synth(text)
        h = hashlib.sha1(text.encode("utf-8")).hexdigest()[:16]
        path = os.path.join(self.out_dir, f"dj_{h}.wav")
        with open(path, "wb") as f:
            f.write(data)
        info = sf.info(path)
        return DJSlot(text=text, audio_path=path, duration_s=float(info.duration))

    def render_banter(self, turns, voice_a: str | None = None,
                      voice_b: str | None = None, gap_s: float = 0.25) -> DJSlot:
        """Render a two-host dialogue: speaker "A" -> voice_a, "B" -> voice_b.
        Synthesizes each turn with its mapped voice and stitches them with a
        short gap into one WAV. `turns` is a list of (speaker, text)."""
        if not turns:
            raise ValueError("render_banter needs at least one turn")
        segs = []
        sr = self._synth_sr()
        for speaker, text in turns:
            voice = voice_b if str(speaker).upper() == "B" else voice_a
            data = self.synth.synth(text, voice=voice)
            arr, file_sr = sf.read(io.BytesIO(data), dtype="float32")
            if getattr(arr, "ndim", 1) > 1:
                arr = arr.mean(axis=1)
            sr = file_sr
            segs.append(arr)
            segs.append(np.zeros(int(gap_s * file_sr), dtype=np.float32))
        combined = np.concatenate(segs) if segs else np.zeros(0, dtype=np.float32)
        key = "|".join(f"{s}:{t}" for s, t in turns)
        h = hashlib.sha1(key.encode("utf-8")).hexdigest()[:16]
        path = os.path.join(self.out_dir, f"banter_{h}.wav")
        sf.write(path, combined, sr)
        info = sf.info(path)
        text_repr = " / ".join(f"{s}: {t}" for s, t in turns)
        return DJSlot(text=text_repr, audio_path=path, duration_s=float(info.duration))

    def _synth_sr(self) -> int:
        return int(getattr(self.synth, "_sample_rate", 24000))
