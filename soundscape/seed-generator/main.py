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
import tempfile
from flask import Flask, render_template, request, jsonify
import spotipy
from spotipy.oauth2 import SpotifyClientCredentials
from dotenv import load_dotenv
import requests as http_requests
import numpy as np

load_dotenv()

app = Flask(__name__)

# --- Spotify credentials ---
# Create a free app at https://developer.spotify.com/dashboard
SPOTIFY_CLIENT_ID     = os.getenv("SPOTIFY_CLIENT_ID")
SPOTIFY_CLIENT_SECRET = os.getenv("SPOTIFY_CLIENT_SECRET")

if not SPOTIFY_CLIENT_ID or not SPOTIFY_CLIENT_SECRET:
    raise RuntimeError(
        "Set SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET in environment or soundscape/seed-generator/.env"
    )

sp = spotipy.Spotify(auth_manager=SpotifyClientCredentials(
    client_id=SPOTIFY_CLIENT_ID,
    client_secret=SPOTIFY_CLIENT_SECRET
))

SPOTIFY_AUDIO_FEATURES_NOTE = (
    "Spotify blocked Audio Features for this app, so Soundscape analysed "
    "the track's audio preview directly to extract real BPM, key, energy, "
    "and other features from the actual waveform."
)

DEEZER_AUDIO_PREVIEW_NOTE = (
    "Spotify blocked Audio Features and had no preview available, so "
    "Soundscape sourced a 30-second audio preview from Deezer and analysed "
    "the real waveform to extract BPM, key, energy, and other features."
)

SPOTIFY_AUDIO_PREVIEW_FALLBACK_NOTE = (
    "Spotify blocked Audio Features and no audio preview was available, so "
    "Soundscape used a metadata-based fallback.  The seed is deterministic "
    "but energy, valence, and acousticness are derived from popularity and "
    "duration.  Key and mode are fingerprinted from the track ID."
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


# Krumhansl-Kessler key profiles for major and minor keys.
# Used to correlate chroma vectors against known tonal profiles.
_MAJOR_PROFILE = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09,
                           2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
_MINOR_PROFILE = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53,
                           2.54, 4.75, 3.98, 2.69, 3.34, 3.17])


def analyze_audio_preview(preview_url: str) -> dict | None:
    """
    Download a Spotify 30-second preview and extract real audio features
    using librosa.  Returns a dict matching Spotify's audio_features schema
    (energy, valence, tempo, key, mode, acousticness, danceability, loudness)
    or None if analysis fails.
    """
    try:
        import librosa
    except ImportError:
        return None

    tmp_path = None
    try:
        resp = http_requests.get(preview_url, timeout=15)
        if resp.status_code != 200:
            return None

        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as f:
            f.write(resp.content)
            tmp_path = f.name

        y, sr = librosa.load(tmp_path, sr=22050)

        # --- Tempo (BPM) ---
        tempo_result = librosa.beat.beat_track(y=y, sr=sr)
        # librosa >= 0.10 returns (tempo_array, beats); older returns (tempo, beats)
        raw_tempo = tempo_result[0]
        tempo = float(np.atleast_1d(raw_tempo)[0])

        # --- Key and mode via chroma correlation ---
        chroma = librosa.feature.chroma_cqt(y=y, sr=sr)
        chroma_mean = chroma.mean(axis=1)
        best_corr, best_key, best_mode = -2.0, 0, 1
        for shift in range(12):
            rolled = np.roll(chroma_mean, -shift)
            corr_major = float(np.corrcoef(rolled, _MAJOR_PROFILE)[0, 1])
            corr_minor = float(np.corrcoef(rolled, _MINOR_PROFILE)[0, 1])
            if corr_major > best_corr:
                best_corr, best_key, best_mode = corr_major, shift, 1
            if corr_minor > best_corr:
                best_corr, best_key, best_mode = corr_minor, shift, 0
        key = best_key
        mode = best_mode

        # --- Energy (RMS-based, normalized to ~0-1) ---
        rms = float(librosa.feature.rms(y=y)[0].mean())
        energy = min(1.0, rms / 0.08)

        # --- Loudness (dB, matching Spotify's typical range) ---
        loudness = float(librosa.amplitude_to_db(np.array([rms]), ref=1.0)[0])

        # --- Danceability (onset regularity and strength) ---
        onset_env = librosa.onset.onset_strength(y=y, sr=sr)
        onset_mean = float(onset_env.mean())
        # Onset autocorrelation measures rhythmic regularity
        onset_ac = librosa.autocorrelate(onset_env, max_size=sr // 512)
        if len(onset_ac) > 1:
            regularity = float(onset_ac[1] / (onset_ac[0] + 1e-6))
        else:
            regularity = 0.0
        danceability = min(1.0, 0.5 * min(1.0, onset_mean / 8.0) + 0.5 * regularity)

        # --- Acousticness (high spectral centroid = less acoustic) ---
        centroid = float(librosa.feature.spectral_centroid(y=y, sr=sr)[0].mean())
        # Typical centroid range ~500-5000 Hz; lower = more acoustic
        acousticness = max(0.0, min(1.0, 1.0 - (centroid - 500) / 4500))

        # --- Valence (brightness + tempo heuristic) ---
        # Higher spectral contrast in upper bands + faster tempo → happier
        contrast = librosa.feature.spectral_contrast(y=y, sr=sr)
        brightness = float(contrast.mean())
        tempo_factor = min(1.0, max(0.0, (tempo - 60) / 140))
        valence = min(1.0, max(0.0,
            0.4 * min(1.0, max(0.0, (brightness + 25) / 50)) +
            0.3 * tempo_factor +
            0.3 * (1.0 - acousticness)
        ))

        return {
            "energy": round(energy, 4),
            "valence": round(valence, 4),
            "tempo": round(tempo, 2),
            "key": key,
            "mode": mode,
            "acousticness": round(acousticness, 4),
            "danceability": round(danceability, 4),
            "loudness": round(loudness, 2),
        }

    except Exception:
        return None
    finally:
        if tmp_path:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def fetch_deezer_preview(track_name: str, artist_name: str) -> str | None:
    """
    Search Deezer's public API (no auth required) for the track and return
    its 30-second preview URL, or None if not found.
    """
    try:
        params = {"q": f'track:"{track_name}" artist:"{artist_name}"', "limit": 5}
        resp = http_requests.get("https://api.deezer.com/search", params=params, timeout=10)
        if resp.status_code != 200:
            return None
        results = resp.json().get("data") or []
        for result in results:
            preview = result.get("preview")
            if preview:
                return preview
        return None
    except Exception:
        return None


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
    track_number = max(int(track.get("track_number") or 1), 1)
    disc_number = max(int(track.get("disc_number") or 1), 1)
    explicit = 1.0 if track.get("explicit") else 0.0
    popularity = track.get("popularity")
    popularity_norm = clamp(((50 if popularity is None else popularity) / 100))
    duration_norm = clamp((duration_ms - 90_000) / 330_000)

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

    # Energy: popularity is the best available proxy (popular tracks skew energetic).
    # Explicit flag adds a small boost (explicit tracks trend louder/more intense).
    # Duration has a weak negative correlation with energy (long songs tend calmer).
    energy = clamp(
        0.40 * popularity_norm +
        0.30 * (1 - duration_norm) +
        0.20 * stable_fraction("energy", *signature) +
        0.10 * (0.90 if explicit else 0.35)
    )
    # Valence: explicit songs strongly trend negative; year has negligible basis
    # so we drop it. The remainder is a stable fingerprint from the track identity.
    valence = clamp(
        0.55 * stable_fraction("valence", *signature) +
        0.45 * (0.15 if explicit else 0.85)
    )
    # Tempo: longer duration correlates with slower tempo (ballads, ambient).
    # So we invert duration_norm: short songs → faster, long songs → slower.
    tempo = round(
        80 + 95 * (
            0.55 * (1 - duration_norm) +
            0.45 * stable_fraction("tempo", *signature)
        ),
        2,
    )
    # Key: not deducible from metadata — derived entirely from the track's
    # identity hash so it is unique and deterministic per track.
    key = min(11, int(stable_fraction("key", *signature) * 12))
    # Mode: not deducible from metadata — same rationale as key.
    mode = 1 if stable_fraction("mode", *signature) >= 0.5 else 0
    # Acousticness: less popular tracks and shorter tracks lean more acoustic.
    acousticness = clamp(
        0.50 * (1 - popularity_norm) +
        0.30 * duration_norm +
        0.20 * stable_fraction("acousticness", *signature)
    )
    # Danceability: popularity is the strongest available proxy.
    # Explicit flag adds a small positive signal (explicit pop/hip-hop skews danceable).
    danceability = clamp(
        0.55 * popularity_norm +
        0.25 * stable_fraction("danceability", *signature) +
        0.20 * (0.80 if explicit else 0.40)
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
    """
    Get audio features using the best available source:
      1. Spotify Audio Features API (most accurate)
      2. Librosa analysis of the Spotify 30-second preview (real audio analysis)
      3. Librosa analysis of a Deezer 30-second preview (real audio analysis)
      4. Metadata-based fallback (deterministic but estimated)
    """
    # --- Try Spotify Audio Features first ---
    try:
        features_list = sp.audio_features([track_id]) or []
        features = features_list[0] if features_list else None
        if features:
            return features, None
    except Exception as exc:
        if not should_fallback_from_exception(exc):
            raise

    # --- Audio features unavailable; try analysing the Spotify preview clip ---
    preview_url = track.get("preview_url")
    if preview_url:
        preview_features = analyze_audio_preview(preview_url)
        if preview_features is not None:
            return preview_features, SPOTIFY_AUDIO_FEATURES_NOTE

    # --- No Spotify preview; try Deezer's public API for a preview ---
    track_name = track.get("name", "")
    artist_name = (track.get("artists") or [{}])[0].get("name", "")
    if track_name and artist_name:
        deezer_preview_url = fetch_deezer_preview(track_name, artist_name)
        if deezer_preview_url:
            deezer_features = analyze_audio_preview(deezer_preview_url)
            if deezer_features is not None:
                return deezer_features, DEEZER_AUDIO_PREVIEW_NOTE

    # --- No preview available anywhere; fall back to metadata ---
    return metadata_to_features(track, track_id), SPOTIFY_AUDIO_PREVIEW_FALLBACK_NOTE


def features_to_seed(features: dict, track_id: str) -> int:
    """
    Convert Spotify audio features into a deterministic Minecraft seed.

    Minecraft seeds are 64-bit signed integers (-2^63 to 2^63-1).
    Every feature is normalised to [0, 1], then all values are hashed
    together so that each dimension (energy, valence, tempo, key, mode,
    acousticness, danceability, loudness) contributes equally to the seed.
    The track ID is included to guarantee uniqueness between tracks that
    happen to share near-identical rounded feature values.
    """
    energy       = features.get("energy",       0.5)   # 0.0 – 1.0
    valence      = features.get("valence",       0.5)   # 0.0 – 1.0  (positivity)
    tempo        = features.get("tempo",         120)   # BPM
    key          = features.get("key",           0)     # 0–11 (C=0, C#=1 … B=11)
    mode         = features.get("mode",          1)     # 1=major, 0=minor
    acousticness = features.get("acousticness",  0.5)   # 0.0 – 1.0
    danceability = features.get("danceability",  0.5)   # 0.0 – 1.0
    loudness     = features.get("loudness",     -10)    # dB, typically -60 to 0

    # Normalise every feature to [0, 1] using its known real-world range.
    # Round to 4 d.p. so insignificant floating-point noise doesn't alter
    # the seed (Spotify reports features to at most 3 d.p. anyway).
    tempo_norm    = round(max(0.0, min(1.0, (tempo - 50) / 200)), 4)
    loudness_norm = round(max(0.0, min(1.0, (loudness + 60) / 60)), 4)
    key_norm      = round(key / 11.0, 4)

    # Hash all normalised features + the track ID into a 63-bit seed.
    # Every feature participates equally in the SHA-256 input, so a change
    # in any single dimension changes the seed unpredictably but repeatably.
    feature_str = "|".join([
        f"{round(energy, 4)}",
        f"{round(valence, 4)}",
        f"{tempo_norm}",
        f"{key_norm}",
        f"{mode}",
        f"{round(acousticness, 4)}",
        f"{round(danceability, 4)}",
        f"{loudness_norm}",
        track_id,
    ])
    digest = hashlib.sha256(feature_str.encode("utf-8")).digest()
    seed = int.from_bytes(digest[:8], "big") % (2**63)
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
            "analysis_source": (
                "audio-features" if not analysis_note else
                "audio-preview" if analysis_note in (SPOTIFY_AUDIO_FEATURES_NOTE, DEEZER_AUDIO_PREVIEW_NOTE) else
                "metadata-fallback"
            ),
        })

    except ValueError as e:
        return jsonify({"error": str(e)}), 400
    except Exception as e:
        return jsonify({"error": f"Spotify API error: {str(e)}"}), 500


if __name__ == "__main__":
    app.run(debug=True, host="127.0.0.1", port=5000)
