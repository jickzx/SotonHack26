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

import os
import re
import math
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


def extract_track_id(url: str) -> str:
    """Pull the track ID from a Spotify URL or URI."""
    # Handle: https://open.spotify.com/track/XXXXX or spotify:track:XXXXX
    match = re.search(r'track[/:]([A-Za-z0-9]+)', url)
    if not match:
        raise ValueError("Could not extract track ID from URL.")
    return match.group(1)


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
    track_hash = abs(hash(track_id)) % 1_000_000
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
    data = request.get_json()
    url  = data.get("url", "").strip()

    if not url:
        return jsonify({"error": "Please provide a Spotify track URL."}), 400

    try:
        track_id = extract_track_id(url)

        # Fetch track metadata + audio features
        track    = sp.track(track_id)
        features = sp.audio_features(track_id)[0]

        if not features:
            return jsonify({"error": "Could not retrieve audio features for this track."}), 400

        seed    = features_to_seed(features, track_id)
        profile = build_world_profile(features)

        return jsonify({
            "track_name":  track["name"],
            "artist":      track["artists"][0]["name"],
            "album_art":   track["album"]["images"][0]["url"] if track["album"]["images"] else None,
            "seed":        str(seed),
            "profile":     profile
        })

    except ValueError as e:
        return jsonify({"error": str(e)}), 400
    except Exception as e:
        return jsonify({"error": f"Spotify API error: {str(e)}"}), 500


if __name__ == "__main__":
    app.run(debug=True, host="127.0.0.1", port=5000)
