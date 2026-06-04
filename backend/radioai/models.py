from dataclasses import dataclass
from typing import Literal, Optional

TransitionType = Literal["talkover", "beatmatch", "crossfade", "cut"]


@dataclass
class Song:
    title: str
    artist: str
    duration_s: Optional[float] = None  # expected/reference duration if known
    query: Optional[str] = None         # optional explicit search query override


@dataclass
class TrackAnalysis:
    path: str
    duration_s: float
    bpm: float
    beat_times: list[float]   # beat onset times in seconds
    key_camelot: str          # e.g. "8A"
    energy: float             # 0..1 normalized loudness
    intro_end_s: float        # end of leading instrumental region
    outro_start_s: float      # start of trailing instrumental region
    vocal_onset_s: float      # time the first vocal enters


@dataclass
class DJSlot:
    text: str          # Hebrew script
    audio_path: str    # voiced audio file
    duration_s: float


@dataclass
class Transition:
    type: TransitionType
    duration_s: float
    dj_slot: Optional[DJSlot] = None


@dataclass
class PlanItem:
    song: Song
    analysis: TrackAnalysis
    transition_in: Transition  # how we move INTO this song from the previous one
