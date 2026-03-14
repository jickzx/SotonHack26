"""
SOUNDSCAPE — Music to Minecraft Seed Generator
================================================
Takes a Spotify track URL, analyses its audio features,
and generates a deterministic Minecraft world seed + biome profile.

Setup:
  pip install -r requirements.txt
  Set your Spotify credentials in .env or directly below.
  Run: python main.py
  Open: http://localhost:5000
"""

import hashlib
import os
import re
from flask import Flask, render_template, request, jsonify
import spotipy
from spotipy.oauth2 import SpotifyClientCredentials
from dotenv import load_dotenv

load_dotenv()

app = Flask(__name__)

# --- Spotify credentials ---
# Create a free app at https://developer.spotify.com/dashboard
SPOTIFY_CLIENT_ID     = os.getenv("SPOTIFY_CLIENT_ID", "YOUR_CLIENT_ID_HERE")
SPOTIFY_CLIENT_SECRET = os.getenv("SPOTIFY_CLIENT_SECRET", "YOUR_CLIENT_SECRET_HERE")

sp = spotipy.Spotify(auth_manager=SpotifyClientCredentials(
    client_id=SPOTIFY_CLIENT_ID,
    client_secret=SPOTIFY_CLIENT_SECRET
))

SPOTIFY_AUDIO_FEATURES_NOTE = (
    "Spotify blocked Audio Features for this app, so Soundscape used a "
    "metadata-based fallback. The seed is still deterministic, but the stats "
    "are estimated from track metadata instead of Spotify's audio analysis."
)


def extract_track_id(url: str) -> str:
    """Pull the track ID from a Spotify URL or URI."""
    # Handle: https://open.spotify.com/track/XXXXX or spotify:track:XXXXX
    match = re.search(r'track[/:]([A-Za-z0-9]+)', url)
    if not match:
        raise ValueError("Could not extract track ID from URL.")
    return match.group(1)


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    """Clamp a numeric value into a target range."""
    return max(low, min(high, value))


def stable_fraction(*parts: object) -> float:
    """Return a repeatable 0-1 float derived from the supplied parts."""
    payload = "||".join("" if part is None else str(part) for part in parts)
    digest = hashlib.sha256(payload.encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") / float((1 << 64) - 1)


def stable_track_hash(track_id: str) -> int:
    """Produce a repeatable short integer from the track ID."""
    digest = hashlib.sha256(track_id.encode("utf-8")).hexdigest()
    return int(digest[:12], 16) % 1_000_000


def parse_release_year(track: dict) -> int:
    """Extract a best-effort release year from a track payload."""
    release_date = ((track.get("album") or {}).get("release_date") or "").strip()
    if len(release_date) >= 4 and release_date[:4].isdigit():
        return int(release_date[:4])
    return 2000


def metadata_to_features(track: dict, track_id: str) -> dict:
    """
    Build a deterministic fallback feature set from track metadata.

    Spotify no longer grants Audio Features to many newer/development apps, so
    we synthesise a consistent fingerprint from the metadata we can still read.
    """
    album = track.get("album") or {}
    artists = track.get("artists") or []
    primary_artist = artists[0] if artists else {}

    duration_ms = int(track.get("duration_ms") or 180_000)
    album_total_tracks = max(int(album.get("total_tracks") or 1), 1)
    track_number = max(int(track.get("track_number") or 1), 1)
    disc_number = max(int(track.get("disc_number") or 1), 1)
    explicit = 1.0 if track.get("explicit") else 0.0
    popularity = track.get("popularity")
    popularity_norm = clamp(((50 if popularity is None else popularity) / 100))
    duration_norm = clamp((duration_ms - 90_000) / 330_000)
    track_slot_norm = clamp((track_number - 1) / max(album_total_tracks - 1, 1))
    year_norm = clamp((parse_release_year(track) - 1960) / 80)

    signature = (
        track_id,
        track.get("name", ""),
        primary_artist.get("id") or primary_artist.get("name", ""),
        album.get("name", ""),
        album.get("release_date", ""),
        duration_ms,
        track_number,
        disc_number,
        explicit,
    )

    energy = clamp(
        0.45 * stable_fraction("energy", *signature) +
        0.25 * popularity_norm +
        0.15 * track_slot_norm +
        0.15 * (0.90 if explicit else 0.35)
    )
    valence = clamp(
        0.45 * stable_fraction("valence", *signature) +
        0.30 * year_norm +
        0.25 * (0.15 if explicit else 0.85)
    )
    tempo = round(
        80 + 95 * (
            0.55 * duration_norm +
            0.45 * stable_fraction("tempo", *signature)
        ),
        2,
    )
    key = min(11, int(stable_fraction("key", *signature) * 12))
    mode = 1 if (
        0.55 * stable_fraction("mode", *signature) +
        0.25 * year_norm +
        0.20 * (0.0 if explicit else 1.0)
    ) >= 0.5 else 0
    acousticness = clamp(
        0.50 * stable_fraction("acousticness", *signature) +
        0.30 * (1 - popularity_norm) +
        0.20 * (1 - energy)
    )
    danceability = clamp(
        0.45 * stable_fraction("danceability", *signature) +
        0.35 * track_slot_norm +
        0.20 * popularity_norm
    )
    loudness = round(
        -32 + 28 * energy + 6 * (stable_fraction("loudness", *signature) - 0.5),
        2,
    )

    return {
        "energy": energy,
        "valence": valence,
        "tempo": tempo,
        "key": key,
        "mode": mode,
        "acousticness": acousticness,
        "danceability": danceability,
        "loudness": loudness,
    }


def should_fallback_from_exception(exc: Exception) -> bool:
    """Detect Spotify's blocked Audio Features response."""
    return (
        getattr(exc, "http_status", None) == 403 and
        "audio-features" in str(exc)
    )


def fetch_track_features(track_id: str, track: dict) -> tuple[dict, str | None]:
    """Get real audio features when available, otherwise use metadata fallback."""
    try:
        features_list = sp.audio_features([track_id]) or []
        features = features_list[0] if features_list else None
        if features:
            return features, None
        return metadata_to_features(track, track_id), (
            "Spotify did not return audio features for this track, so "
            "Soundscape used a metadata-based fallback."
        )
    except Exception as exc:
        if should_fallback_from_exception(exc):
            return metadata_to_features(track, track_id), SPOTIFY_AUDIO_FEATURES_NOTE
        raise


def features_to_seed(features: dict, track_id: str) -> int:
    """
    Convert Spotify audio features into a deterministic Minecraft seed.

    Minecraft seeds are 64-bit signed integers (-2^63 to 2^63-1).
    We spread the features across different magnitude "slots" so each
    one independently contributes to the final number.
    """
    energy       = features.get("energy",       0.5)   # 0.0 – 1.0
    valence      = features.get("valence",       0.5)   # 0.0 – 1.0  (positivity)
    tempo        = features.get("tempo",         120)   # BPM
    key          = features.get("key",           0)     # 0–11 (C=0, C#=1 … B=11)
    mode         = features.get("mode",          1)     # 1=major, 0=minor
    acousticness = features.get("acousticness",  0.5)   # 0.0 – 1.0
    danceability = features.get("danceability",  0.5)   # 0.0 – 1.0
    loudness     = features.get("loudness",     -10)    # dB, typically -60 to 0

    # Normalise loudness to 0–1
    loudness_norm = max(0.0, min(1.0, (loudness + 60) / 60))

    seed = int(
        energy       * 1_000_000_000_000 +
        valence      * 100_000_000_000  +
        (tempo / 250)* 10_000_000_000   +
        key          * 1_000_000_000    +
        mode         * 100_000_000      +
        acousticness * 10_000_000       +
        danceability * 1_000_000        +
        loudness_norm* 100_000
    )

    # Mix in a hash of the track ID for uniqueness between similar-sounding songs
    track_hash = stable_track_hash(track_id)
    seed = (seed + track_hash) % (2**63)

    return seed


def build_world_profile(features: dict) -> dict:
    """
    Describe the kind of Minecraft world this song will generate,
    based on its audio fingerprint.
    """
    energy       = features.get("energy",       0.5)
    valence      = features.get("valence",       0.5)
    tempo        = features.get("tempo",         120)
    mode         = features.get("mode",          1)
    acousticness = features.get("acousticness",  0.5)
    danceability = features.get("danceability",  0.5)
    key          = features.get("key",           0)

    # --- Terrain descriptor ---
    if energy > 0.75:
        terrain = "Extreme — jagged mountains, deep ravines, volatile terrain"
    elif energy > 0.45:
        terrain = "Varied — rolling hills, mixed elevations, caves common"
    else:
        terrain = "Gentle — flat plains, shallow valleys, calm landscape"

    # --- Dominant biome ---
    if mode == 0 and energy > 0.6:
        dominant_biome = "Nether / Deep Dark — dark, hostile, dangerous"
        mod_genre = "metal"
    elif valence > 0.7 and acousticness > 0.4:
        dominant_biome = "Cherry Grove / Pale Garden — soft, dreamlike, lush"
        mod_genre = "indipop" if valence > 0.8 else "country"
    elif acousticness > 0.6:
        dominant_biome = "Old Growth Forest / Swamp — dense, organic, ancient"
        mod_genre = "ambient"
    elif danceability > 0.7:
        dominant_biome = "Badlands / Savanna — open, bright, energetic"
        mod_genre = "electronic"
    elif key in [0, 5, 7]:  # C, F, G — bright keys
        dominant_biome = "Meadow / Flower Forest — cheerful, open"
        mod_genre = "jazz"
    else:
        dominant_biome = "Mixed Overworld — balanced, classic Minecraft feel"
        mod_genre = "default"

    # --- Atmosphere ---
    if valence < 0.3:
        atmosphere = "Melancholic and brooding — expect overcast skies and sparse life"
    elif valence < 0.6:
        atmosphere = "Neutral and grounded — neither too harsh nor too inviting"
    else:
        atmosphere = "Warm and alive — abundant nature, bright colours, welcoming"

    # --- Hostility level ---
    hostility_score = (energy * 0.5) + ((1 - valence) * 0.3) + ((1 - acousticness) * 0.2)
    if hostility_score > 0.65:
        hostility = "High — prepare for frequent mob encounters"
    elif hostility_score > 0.35:
        hostility = "Moderate — balanced survival experience"
    else:
        hostility = "Low — peaceful exploration encouraged"

    return {
        "terrain":        terrain,
        "dominant_biome": dominant_biome,
        "atmosphere":     atmosphere,
        "hostility":      hostility,
        "mod_genre":      mod_genre,
        "stats": {
            "energy":       round(energy * 100),
            "positivity":   round(valence * 100),
            "tempo":        round(tempo),
            "acousticness": round(acousticness * 100),
            "danceability": round(danceability * 100),
        }
    }


@app.route("/")
def index():
    return render_template("index.html")


@app.route("/generate", methods=["POST"])
def generate():
    data = request.get_json(silent=True) or {}
    url  = data.get("url", "").strip()

    if not url:
        return jsonify({"error": "Please provide a Spotify track URL."}), 400

    if (
        not SPOTIFY_CLIENT_ID or
        not SPOTIFY_CLIENT_SECRET or
        SPOTIFY_CLIENT_ID == "YOUR_CLIENT_ID_HERE" or
        SPOTIFY_CLIENT_SECRET == "YOUR_CLIENT_SECRET_HERE"
    ):
        return jsonify({
            "error": "Set SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET in seed-generator/.env first."
        }), 500

    try:
        track_id = extract_track_id(url)

        # Fetch track metadata first, then attempt audio features.
        track    = sp.track(track_id)
        features, analysis_note = fetch_track_features(track_id, track)

        seed    = features_to_seed(features, track_id)
        profile = build_world_profile(features)

        return jsonify({
            "track_name":  track["name"],
            "artist":      track["artists"][0]["name"],
            "album_art":   track["album"]["images"][0]["url"] if track["album"]["images"] else None,
            "seed":        str(seed),
            "profile":     profile,
            "analysis_note": analysis_note,
            "analysis_source": "metadata-fallback" if analysis_note else "audio-features",
        })

    except ValueError as e:
        return jsonify({"error": str(e)}), 400
    except Exception as e:
        return jsonify({"error": f"Spotify API error: {str(e)}"}), 500


if __name__ == "__main__":
    app.run(debug=True, host="127.0.0.1", port=5000)
