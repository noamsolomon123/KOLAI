import os
import json
from dataclasses import dataclass


@dataclass
class TasteTrack:
    title: str
    artist: str
    duration_s: float


@dataclass
class TasteProfile:
    top_tracks: list[TasteTrack]
    top_artists: list[str]


def parse_profile(top_tracks_json: dict, top_artists_json: dict) -> TasteProfile:
    tracks = []
    for it in top_tracks_json.get("items", []):
        artists = it.get("artists", [])
        artist = artists[0].get("name", "") if artists else ""
        tracks.append(TasteTrack(
            title=it.get("name", ""),
            artist=artist,
            duration_s=(it.get("duration_ms", 0) or 0) / 1000.0,
        ))
    artist_names = [a.get("name", "") for a in top_artists_json.get("items", [])]
    return TasteProfile(top_tracks=tracks, top_artists=artist_names)
