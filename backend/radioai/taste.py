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


def _profile_to_dict(p: TasteProfile) -> dict:
    return {
        "top_tracks": [{"title": t.title, "artist": t.artist,
                        "duration_s": t.duration_s} for t in p.top_tracks],
        "top_artists": p.top_artists,
    }


def _profile_from_dict(d: dict) -> TasteProfile:
    return TasteProfile(
        top_tracks=[TasteTrack(**t) for t in d.get("top_tracks", [])],
        top_artists=d.get("top_artists", []),
    )


class TasteService:
    """Reads Spotify taste (top tracks/artists), cached to disk. Inject a
    `client` (spotipy.Spotify-like) for tests; otherwise one is built lazily
    via OAuth from config."""

    SCOPE = "user-top-read"

    def __init__(self, config, client=None):
        self.config = config
        self._client = client
        self.cache_path = os.path.join(config.cache_dir, "taste.json")

    def _spotify(self):
        if self._client is None:
            from spotipy import Spotify
            from spotipy.oauth2 import SpotifyOAuth
            auth = SpotifyOAuth(
                client_id=self.config.spotify_client_id,
                client_secret=self.config.spotify_client_secret,
                redirect_uri=self.config.spotify_redirect_uri,
                scope=self.SCOPE,
                cache_path=os.path.join(self.config.cache_dir, ".spotify-token"),
                open_browser=True,
            )
            self._client = Spotify(auth_manager=auth)
        return self._client

    def get_profile(self, use_cache: bool = True) -> TasteProfile:
        if use_cache and os.path.exists(self.cache_path):
            with open(self.cache_path, "r", encoding="utf-8") as f:
                return _profile_from_dict(json.load(f))
        sp = self._spotify()
        tracks = sp.current_user_top_tracks(limit=20, time_range="medium_term")
        artists = sp.current_user_top_artists(limit=10, time_range="medium_term")
        profile = parse_profile(tracks, artists)
        os.makedirs(self.config.cache_dir, exist_ok=True)
        with open(self.cache_path, "w", encoding="utf-8") as f:
            json.dump(_profile_to_dict(profile), f, ensure_ascii=False, indent=2)
        return profile
