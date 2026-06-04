import os
import hashlib
from typing import Protocol
import soundfile as sf
from radioai.models import DJSlot


class Synth(Protocol):
    def synth(self, text: str) -> bytes: ...


class ElevenLabsSynth:
    """Thin wrapper around the ElevenLabs SDK; returns wav/mp3 bytes."""

    def __init__(self, api_key: str, voice_id: str,
                 model: str = "eleven_multilingual_v2"):
        from elevenlabs.client import ElevenLabs
        self._client = ElevenLabs(api_key=api_key)
        self._voice_id = voice_id
        self._model = model

    def synth(self, text: str) -> bytes:
        audio = self._client.text_to_speech.convert(
            voice_id=self._voice_id,
            model_id=self._model,
            text=text,
            output_format="mp3_44100_128",
        )
        return b"".join(audio)


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
