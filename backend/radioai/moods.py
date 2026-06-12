"""Mood presets for the station. Each mood reshapes song selection (LLM vibe
guidance), the DJ voice delivery (TTS style prefix), and the talk cadence."""

MOODS = {
    "mix": {
        "label": "מיקס", "emoji": "🎚️",
        "song": ("a flowing mix across energies with a natural arc - the "
                 "listener's favorites and closely related songs"),
        "voice_style": ("Read this like a charismatic, warm, professional Israeli "
                        "FM radio host. Energetic but smooth, natural broadcast "
                        "pacing - a real radio personality. Speak only the Hebrew:"),
        "talk_chance": 0.5, "banter_chance": 0.2, "max_silence": 4,
    },
    "late_night": {
        "label": "לילה", "emoji": "🌙",
        "song": ("low-energy, slow, smooth, intimate late-night songs - mellow "
                 "R&B, downtempo, soft ballads, chill electronic, dreamy vibes; "
                 "avoid loud high-tempo bangers"),
        "voice_style": ("Read this like a soft, warm, intimate late-night radio "
                        "host. Slow, smooth, relaxed, calming, low and gentle - "
                        "unhurried. Speak only the Hebrew:"),
        "talk_chance": 0.3, "banter_chance": 0.1, "max_silence": 5,
    },
    "party": {
        "label": "מסיבה", "emoji": "🎉",
        "song": ("high-energy, upbeat, danceable party bangers - energetic pop, "
                 "dance, EDM, hip-hop bangers (think 'The Middle' energy); keep "
                 "the energy high and the tempo up"),
        "voice_style": ("Read this like a high-energy, hyped, exciting party radio "
                        "host. Fast, punchy, fun, full of energy. Speak only the "
                        "Hebrew:"),
        "talk_chance": 0.7, "banter_chance": 0.4, "max_silence": 3,
    },
    "focus": {
        "label": "ריכוז", "emoji": "🎯",
        "song": ("steady, mellow, non-distracting songs for focus - chill, "
                 "instrumental-leaning, lo-fi, smooth grooves, minimal vocals; "
                 "consistent calm energy, nothing jarring"),
        "voice_style": ("Read this calmly, briefly and low-key, unobtrusive and "
                        "even. Speak only the Hebrew:"),
        "talk_chance": 0.2, "banter_chance": 0.05, "max_silence": 6,
    },
    "morning": {
        "label": "בוקר", "emoji": "☀️",
        "song": ("bright, warm, uplifting mid-energy morning songs - feel-good "
                 "pop, sunny vibes, easy upbeat tracks; a positive start to the "
                 "day"),
        "voice_style": ("Read this like a warm, friendly, bright morning radio "
                        "host. Cheerful and welcoming, medium pace. Speak only "
                        "the Hebrew:"),
        "talk_chance": 0.55, "banter_chance": 0.25, "max_silence": 4,
    },
}

DEFAULT_MOOD = "mix"
MOOD_ORDER = ["mix", "party", "late_night", "focus", "morning"]


def mood_list():
    """Ordered [{key,label,emoji}] for the UI."""
    return [{"key": k, "label": MOODS[k]["label"], "emoji": MOODS[k]["emoji"]}
            for k in MOOD_ORDER if k in MOODS]
