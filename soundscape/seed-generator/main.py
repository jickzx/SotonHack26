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
import logging
import os
import re
import tempfile
import unicodedata
from difflib import SequenceMatcher
from flask import Flask, render_template, request, jsonify
import spotipy
from spotipy.oauth2 import SpotifyClientCredentials
from dotenv import load_dotenv
import requests as http_requests
import numpy as np

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
load_dotenv(os.path.join(BASE_DIR, ".env"))

app = Flask(__name__)

# --- Spotify credentials ---
# Create a free app at https://developer.spotify.com/dashboard
SPOTIFY_CLIENT_ID     = os.getenv("SPOTIFY_CLIENT_ID")
SPOTIFY_CLIENT_SECRET = os.getenv("SPOTIFY_CLIENT_SECRET")
SPOTIFY_AUDIO_FEATURES_MODE = os.getenv("SPOTIFY_AUDIO_FEATURES_MODE", "off").strip().lower()

if not SPOTIFY_CLIENT_ID or not SPOTIFY_CLIENT_SECRET:
    raise RuntimeError(
        "Set SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET in environment or soundscape/seed-generator/.env"
    )

sp = spotipy.Spotify(auth_manager=SpotifyClientCredentials(
    client_id=SPOTIFY_CLIENT_ID,
    client_secret=SPOTIFY_CLIENT_SECRET
))

if SPOTIFY_AUDIO_FEATURES_MODE not in {"off", "auto", "force"}:
    SPOTIFY_AUDIO_FEATURES_MODE = "off"

logging.getLogger("spotipy.client").setLevel(logging.CRITICAL)

spotify_audio_features_blocked = False

SPOTIFY_AUDIO_FEATURES_NOTE = (
    "Spotify's Audio Features API is unavailable, so Soundscape analysed "
    "the track's audio preview using multi-method signal processing to "
    "estimate BPM, key, energy, and other features from the waveform."
)

DEEZER_AUDIO_PREVIEW_NOTE = (
    "Spotify's Audio Features API is unavailable and no Spotify preview "
    "was found, so Soundscape sourced a 30-second preview from Deezer "
    "and analysed it using multi-method signal processing to estimate "
    "BPM, key, energy, and other features."
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


def normalized_text(value: str | None) -> str:
    """Normalise user-facing music metadata for cross-service matching."""
    if not value:
        return ""

    value = unicodedata.normalize("NFKD", value)
    value = value.encode("ascii", "ignore").decode("ascii")
    value = value.lower().replace("&", " and ")
    value = re.sub(r"\([^)]*\)|\[[^\]]*\]", " ", value)
    value = re.sub(r"\b(feat|featuring|ft)\.?\b.*", " ", value)
    value = re.sub(
        r"\s*-\s*(live|remaster(?:ed)?(?: \d+)?|radio edit|edit|mix|version|mono|stereo)\b.*",
        " ",
        value,
    )
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return re.sub(r"\s+", " ", value).strip()


def text_similarity(left: str | None, right: str | None) -> float:
    """Return a loose similarity score for two metadata strings."""
    left_norm = normalized_text(left)
    right_norm = normalized_text(right)
    if not left_norm or not right_norm:
        return 0.0
    if left_norm == right_norm:
        return 1.0
    return SequenceMatcher(None, left_norm, right_norm).ratio()


def score_deezer_result(result: dict, track: dict) -> float:
    """Rank Deezer candidates so we use the preview for the right recording."""
    candidate_title = " ".join(
        part for part in [result.get("title"), result.get("title_version")] if part
    )
    candidate_artists = [(result.get("artist") or {}).get("name", "")]
    for contributor in result.get("contributors") or []:
        name = contributor.get("name")
        if name:
            candidate_artists.append(name)

    spotify_artists = [artist.get("name", "") for artist in track.get("artists") or []]
    title_score = text_similarity(track.get("name", ""), candidate_title)
    artist_score = max(
        (
            text_similarity(spotify_artist, candidate_artist)
            for spotify_artist in spotify_artists
            for candidate_artist in candidate_artists
        ),
        default=0.0,
    )

    duration_score = 0.0
    spotify_duration_ms = track.get("duration_ms")
    deezer_duration = result.get("duration")
    if spotify_duration_ms and deezer_duration:
        duration_delta = abs((spotify_duration_ms / 1000) - float(deezer_duration))
        duration_score = max(0.0, 1.0 - (duration_delta / 35.0))

    spotify_isrc = ((track.get("external_ids") or {}).get("isrc") or "").upper()
    deezer_isrc = (result.get("isrc") or "").upper()
    isrc_score = 1.0 if spotify_isrc and spotify_isrc == deezer_isrc else 0.0

    return (
        0.55 * title_score +
        0.30 * artist_score +
        0.10 * duration_score +
        0.05 * isrc_score
    )


# Krumhansl-Kessler key profiles for major and minor keys.
# Used to correlate chroma vectors against known tonal profiles.
_MAJOR_PROFILE = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09,
                           2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
_MINOR_PROFILE = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53,
                           2.54, 4.75, 3.98, 2.69, 3.34, 3.17])


def analyze_audio_preview(preview_url: str) -> dict | None:
    """
    Download a 30-second preview and extract audio features using librosa.

    Uses multiple analysis methods per feature and cross-validates results
    to produce estimates closer to Spotify's original Audio Features values.

    Returns a dict matching Spotify's audio_features schema or None on failure.
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

        # ─── Tempo (BPM) with octave-error correction ───────────────────
        # Method 1: beat_track (default)
        tempo_result = librosa.beat.beat_track(y=y, sr=sr)
        raw_tempo = tempo_result[0]
        tempo_beat = float(np.atleast_1d(raw_tempo)[0])

        # Method 2: onset-autocorrelation based tempo estimation
        onset_env = librosa.onset.onset_strength(y=y, sr=sr)
        tempo_oac = float(np.atleast_1d(
            librosa.feature.tempo(onset_envelope=onset_env, sr=sr)
        )[0])

        # Method 3: percussive-source tempo (isolate drums/rhythm)
        y_perc = librosa.effects.percussive(y, margin=3.0)
        onset_perc = librosa.onset.onset_strength(y=y_perc, sr=sr)
        tempo_perc = float(np.atleast_1d(
            librosa.feature.tempo(onset_envelope=onset_perc, sr=sr)
        )[0])

        # Octave-error correction: if two methods agree within 10% but the
        # third is roughly half or double, trust the majority.
        candidates = [tempo_beat, tempo_oac, tempo_perc]
        # Normalise all candidates to 70-180 BPM range (most music lives here)
        normalised = []
        for t in candidates:
            while t > 0 and t < 55:
                t *= 2
            while t > 210:
                t /= 2
            normalised.append(t)

        # Pick the median of normalised tempos (robust to one outlier)
        normalised.sort()
        tempo = normalised[1]  # median of 3

        # ─── Key and mode via chroma correlation ────────────────────────
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

        # ─── Energy (multi-feature) ─────────────────────────────────────
        # Combine RMS loudness, spectral bandwidth, and spectral rolloff
        # for a more robust energy estimate.
        rms_vals = librosa.feature.rms(y=y)[0]
        rms_mean = float(rms_vals.mean())
        rms_energy = min(1.0, rms_mean / 0.06)

        # Spectral bandwidth: wider bandwidth = more energetic
        bandwidth = librosa.feature.spectral_bandwidth(y=y, sr=sr)[0]
        bw_energy = min(1.0, float(bandwidth.mean()) / 3500.0)

        # Spectral rolloff: higher rolloff = more high-frequency content = energy
        rolloff = librosa.feature.spectral_rolloff(y=y, sr=sr, roll_percent=0.85)[0]
        rolloff_energy = min(1.0, float(rolloff.mean()) / 8000.0)

        # Zero crossing rate: higher = noisier/more energetic
        zcr = librosa.feature.zero_crossing_rate(y)[0]
        zcr_energy = min(1.0, float(zcr.mean()) / 0.15)

        # Dynamic range: energy tends to be higher when the signal is
        # consistently loud rather than having quiet passages
        rms_std = float(rms_vals.std())
        rms_peak = float(rms_vals.max())
        dynamic_factor = 1.0 - min(1.0, rms_std / (rms_peak + 1e-6))

        energy = clamp(
            0.35 * rms_energy +
            0.20 * bw_energy +
            0.15 * rolloff_energy +
            0.15 * zcr_energy +
            0.15 * dynamic_factor
        )

        # ─── Loudness (dB, matching Spotify's typical range) ────────────
        loudness = float(librosa.amplitude_to_db(np.array([rms_mean]), ref=1.0)[0])

        # ─── Danceability (rhythm regularity + tempo stability + bass) ──
        onset_mean = float(onset_env.mean())

        # Rhythmic regularity via onset autocorrelation
        onset_ac = librosa.autocorrelate(onset_env, max_size=int(sr // 512))
        if len(onset_ac) > 1:
            regularity = float(onset_ac[1] / (onset_ac[0] + 1e-6))
        else:
            regularity = 0.0

        # Tempo stability: low variance in beat intervals = more danceable
        _, beats = librosa.beat.beat_track(y=y, sr=sr)
        if len(beats) > 2:
            beat_times = librosa.frames_to_time(beats, sr=sr)
            intervals = np.diff(beat_times)
            if len(intervals) > 1:
                tempo_cv = float(intervals.std() / (intervals.mean() + 1e-6))
                tempo_stability = max(0.0, 1.0 - tempo_cv * 3.0)
            else:
                tempo_stability = 0.5
        else:
            tempo_stability = 0.3

        # Bass energy: danceable music tends to have strong low frequencies
        S = np.abs(librosa.stft(y))
        freq_bins = librosa.fft_frequencies(sr=sr)
        bass_mask = freq_bins < 250
        if bass_mask.any():
            bass_power = float(S[bass_mask].mean())
            total_power = float(S.mean()) + 1e-6
            bass_ratio = min(1.0, bass_power / total_power)
        else:
            bass_ratio = 0.5

        # Tempo sweet spot: 95-130 BPM is the most danceable range
        if 95 <= tempo <= 130:
            tempo_dance = 1.0
        elif 80 <= tempo <= 150:
            tempo_dance = 0.7
        else:
            tempo_dance = 0.4

        danceability = clamp(
            0.25 * min(1.0, onset_mean / 6.0) +
            0.25 * regularity +
            0.20 * tempo_stability +
            0.15 * bass_ratio +
            0.15 * tempo_dance
        )

        # ─── Acousticness (timbral analysis) ────────────────────────────
        # Use multiple timbral features rather than just spectral centroid.

        # Spectral flatness: higher = more noise-like (electronic), lower = tonal (acoustic)
        flatness = librosa.feature.spectral_flatness(y=y)[0]
        flatness_acoustic = max(0.0, 1.0 - float(flatness.mean()) * 15.0)

        # Spectral centroid: lower = warmer/more acoustic
        centroid = float(librosa.feature.spectral_centroid(y=y, sr=sr)[0].mean())
        centroid_acoustic = max(0.0, min(1.0, 1.0 - (centroid - 800) / 3500))

        # Zero crossing rate: acoustic instruments tend to have lower ZCR
        zcr_acoustic = max(0.0, 1.0 - float(zcr.mean()) / 0.12)

        # Harmonic-to-percussive ratio: acoustic music is often more harmonic
        y_harm = librosa.effects.harmonic(y)
        harm_rms = float(librosa.feature.rms(y=y_harm)[0].mean())
        perc_rms = float(librosa.feature.rms(y=y_perc)[0].mean())
        harm_ratio = harm_rms / (harm_rms + perc_rms + 1e-6)
        # Very harmonic content suggests acoustic; very percussive suggests electronic
        harm_acoustic = min(1.0, harm_ratio * 1.3)

        acousticness = clamp(
            0.30 * flatness_acoustic +
            0.25 * centroid_acoustic +
            0.20 * zcr_acoustic +
            0.25 * harm_acoustic
        )

        # ─── Valence / Positivity (multi-signal) ───────────────────────
        # Valence is the hardest feature to estimate from audio alone.
        # We combine mode (major/minor), spectral brightness, tempo,
        # harmonic complexity, and dynamic range.

        # Major mode → happier
        mode_valence = 0.65 if mode == 1 else 0.35

        # Faster tempo → slightly happier
        tempo_factor = min(1.0, max(0.0, (tempo - 70) / 120))

        # Spectral brightness via contrast: brighter = happier
        contrast = librosa.feature.spectral_contrast(y=y, sr=sr)
        brightness = float(contrast.mean())
        bright_valence = min(1.0, max(0.0, (brightness + 20) / 45))

        # High-frequency energy ratio: bright, sparkly production = happier
        if len(freq_bins) > 0:
            high_mask = freq_bins > 4000
            if high_mask.any():
                high_ratio = float(S[high_mask].mean()) / (float(S.mean()) + 1e-6)
                high_valence = min(1.0, high_ratio * 2.0)
            else:
                high_valence = 0.5
        else:
            high_valence = 0.5

        valence = clamp(
            0.30 * mode_valence +
            0.20 * tempo_factor +
            0.25 * bright_valence +
            0.15 * high_valence +
            0.10 * (1.0 - min(1.0, acousticness * 0.6))
        )

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


def fetch_deezer_preview(track: dict) -> str | None:
    """
    Search Deezer's public API (no auth required) for the track and return
    its 30-second preview URL, or None if not found.
    """
    track_name = track.get("name", "")
    artist_name = (track.get("artists") or [{}])[0].get("name", "")
    if not track_name or not artist_name:
        return None

    try:
        search_queries = [
            {"q": f'track:"{track_name}" artist:"{artist_name}"', "limit": 10},
            {"q": f"{track_name} {artist_name}", "limit": 10},
        ]
        best_preview = None
        best_score = 0.0

        for params in search_queries:
            resp = http_requests.get("https://api.deezer.com/search", params=params, timeout=10)
            if resp.status_code != 200:
                continue

            for result in resp.json().get("data") or []:
                preview = result.get("preview")
                if not preview:
                    continue

                score = score_deezer_result(result, track)
                if score > best_score:
                    best_score = score
                    best_preview = preview

        if best_score >= 0.62:
            return best_preview
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


def should_try_spotify_audio_features() -> bool:
    """Decide if the app should attempt Spotify's restricted endpoint."""
    if SPOTIFY_AUDIO_FEATURES_MODE == "off":
        return False
    if SPOTIFY_AUDIO_FEATURES_MODE == "force":
        return True
    return not spotify_audio_features_blocked


def fetch_track_features(track_id: str, track: dict) -> tuple[dict, str | None]:
    """
    Get audio features using the best available source:
      1. Spotify Audio Features API when explicitly enabled
      2. Librosa analysis of the Spotify 30-second preview (real audio analysis)
      3. Librosa analysis of a Deezer 30-second preview (real audio analysis)
      4. Metadata-based fallback (deterministic but estimated)
    """
    global spotify_audio_features_blocked

    # --- Try Spotify Audio Features first when this app is allowed to use them ---
    if should_try_spotify_audio_features():
        try:
            features_list = sp.audio_features([track_id]) or []
            features = features_list[0] if features_list else None
            if features:
                return features, None
        except Exception as exc:
            if not should_fallback_from_exception(exc):
                raise
            spotify_audio_features_blocked = True

    # --- Audio features unavailable; try analysing the Spotify preview clip ---
    preview_url = track.get("preview_url")
    if preview_url:
        preview_features = analyze_audio_preview(preview_url)
        if preview_features is not None:
            return preview_features, SPOTIFY_AUDIO_FEATURES_NOTE

    # --- No Spotify preview; try Deezer's public API for a preview ---
    deezer_preview_url = fetch_deezer_preview(track)
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


# ──── World Profile Scoring Engine ─────────────────────────────────────
#
# Every decision (biome, terrain, atmosphere, genre) is independently
# scored against ALL audio features.  For each category a set of target
# profiles is defined — each with ideal feature values and a weight
# vector controlling how much each feature matters.  The profile with
# the highest weighted similarity wins.
#
# Feature vector order:
#   [energy, valence, tempo_norm, acousticness, danceability, is_major]
#
# where  tempo_norm = clamp((bpm - 60) / 140)   → 0 at 60 BPM, 1 at 200
#        is_major   = 1.0 if major mode, else 0.0

def _score_profile(features: list[float], targets: list[float],
                   weights: list[float]) -> float:
    """Weighted similarity: sum of weight_i * (1 - |feature_i - target_i|)."""
    return sum(w * (1.0 - abs(f - t)) for f, t, w in zip(features, targets, weights))


def _best_match(features: list[float], profiles: list[dict]) -> dict:
    """Return the profile dict with the highest score."""
    return max(profiles, key=lambda p: _score_profile(features, p["t"], p["w"]))


#                     energy  valence  tempo  acoustic  dance  major
_BIOME_PROFILES = [
    # High energy + dark + minor mode → hostile underworld
    {"label": "Nether / Deep Dark — dark, hostile, dangerous",
     "t": [0.90, 0.15, 0.60, 0.10, 0.35, 0.0],
     "w": [0.20, 0.30, 0.10, 0.15, 0.10, 0.15]},

    # Soft + happy + acoustic + slow → dreamy garden
    {"label": "Cherry Grove / Pale Garden — soft, dreamlike, lush",
     "t": [0.30, 0.80, 0.30, 0.75, 0.40, 1.0],
     "w": [0.15, 0.25, 0.15, 0.20, 0.10, 0.15]},

    # Low energy + dark + very acoustic + very slow → ancient forest
    {"label": "Old Growth Forest / Swamp — dense, organic, ancient",
     "t": [0.25, 0.30, 0.15, 0.85, 0.20, 0.0],
     "w": [0.20, 0.15, 0.20, 0.25, 0.15, 0.05]},

    # High energy + fast + danceable + non-acoustic → open energetic
    {"label": "Badlands / Savanna — open, bright, energetic",
     "t": [0.80, 0.65, 0.75, 0.15, 0.85, 1.0],
     "w": [0.15, 0.10, 0.25, 0.15, 0.25, 0.10]},

    # Moderate + very happy + major mode → cheerful meadow
    {"label": "Meadow / Flower Forest — cheerful, open, peaceful",
     "t": [0.45, 0.80, 0.40, 0.50, 0.50, 1.0],
     "w": [0.10, 0.30, 0.15, 0.10, 0.10, 0.25]},

    # High energy + moderate-fast + danceable → vibrant jungle
    {"label": "Jungle / Lush Caves — vibrant, rhythmic, alive",
     "t": [0.75, 0.55, 0.55, 0.45, 0.75, 1.0],
     "w": [0.15, 0.10, 0.25, 0.15, 0.25, 0.10]},

    # Very low everything → frozen wasteland
    {"label": "Frozen Peaks / Snowy Taiga — cold, sparse, atmospheric",
     "t": [0.15, 0.20, 0.15, 0.55, 0.15, 0.0],
     "w": [0.25, 0.25, 0.15, 0.10, 0.15, 0.10]},

    # Moderate + non-acoustic + moderate dance → dry hypnotic terrain
    {"label": "Desert / Warm Ocean — sparse, sun-baked, hypnotic",
     "t": [0.55, 0.45, 0.50, 0.15, 0.60, 1.0],
     "w": [0.15, 0.15, 0.20, 0.25, 0.20, 0.05]},

    # Moderate-high energy + SLOW tempo + moderate acoustic → moody intimate
    {"label": "Dark Forest / Mangrove Swamp — moody, warm, intimate",
     "t": [0.65, 0.55, 0.20, 0.60, 0.60, 1.0],
     "w": [0.15, 0.15, 0.25, 0.15, 0.15, 0.15]},

    # Moderate + happy + moderate-fast + quirky → otherworldly
    {"label": "Mushroom Fields / End Islands — surreal, otherworldly, unique",
     "t": [0.50, 0.65, 0.55, 0.35, 0.65, 1.0],
     "w": [0.15, 0.20, 0.20, 0.15, 0.20, 0.10]},
]

_TERRAIN_PROFILES = [
    {"label": "Extreme — jagged mountains, deep ravines, volatile terrain",
     "t": [0.90, 0.20, 0.65, 0.15, 0.40, 0.0],
     "w": [0.30, 0.25, 0.15, 0.10, 0.10, 0.10]},

    {"label": "Mountainous — towering peaks, dramatic valleys, expansive views",
     "t": [0.70, 0.50, 0.50, 0.40, 0.35, 1.0],
     "w": [0.30, 0.15, 0.20, 0.15, 0.15, 0.05]},

    {"label": "Varied — rolling hills, mixed elevations, caves common",
     "t": [0.50, 0.50, 0.40, 0.50, 0.50, 1.0],
     "w": [0.25, 0.15, 0.20, 0.15, 0.20, 0.05]},

    {"label": "Gentle — flat plains, shallow valleys, calm landscape",
     "t": [0.20, 0.70, 0.25, 0.65, 0.30, 1.0],
     "w": [0.30, 0.20, 0.15, 0.15, 0.10, 0.10]},
]

_ATMOSPHERE_PROFILES = [
    {"label": "Melancholic and brooding — overcast skies and sparse life",
     "t": [0.35, 0.15, 0.30, 0.50, 0.25, 0.0],
     "w": [0.15, 0.35, 0.10, 0.10, 0.15, 0.15]},

    {"label": "Mysterious and tense — fog-draped, unpredictable encounters",
     "t": [0.60, 0.30, 0.40, 0.40, 0.45, 0.0],
     "w": [0.15, 0.25, 0.15, 0.15, 0.15, 0.15]},

    {"label": "Neutral and grounded — neither too harsh nor too inviting",
     "t": [0.50, 0.50, 0.45, 0.45, 0.50, 1.0],
     "w": [0.20, 0.20, 0.20, 0.15, 0.20, 0.05]},

    {"label": "Warm and alive — abundant nature, bright colours, welcoming",
     "t": [0.55, 0.75, 0.50, 0.55, 0.60, 1.0],
     "w": [0.10, 0.35, 0.15, 0.10, 0.15, 0.15]},

    {"label": "Electric and vibrant — dynamic weather, constant motion",
     "t": [0.80, 0.65, 0.70, 0.20, 0.80, 1.0],
     "w": [0.20, 0.15, 0.20, 0.15, 0.25, 0.05]},
]

_GENRE_PROFILES = [
    {"key": "metal",
     "t": [0.90, 0.15, 0.60, 0.10, 0.35, 0.0],
     "w": [0.25, 0.25, 0.10, 0.15, 0.10, 0.15]},

    {"key": "indipop",
     "t": [0.40, 0.75, 0.35, 0.70, 0.45, 1.0],
     "w": [0.15, 0.25, 0.15, 0.20, 0.10, 0.15]},

    {"key": "country",
     "t": [0.35, 0.70, 0.30, 0.90, 0.45, 1.0],
     "w": [0.20, 0.15, 0.15, 0.20, 0.10, 0.20]},

    {"key": "ambient",
     "t": [0.20, 0.35, 0.15, 0.80, 0.20, 0.0],
     "w": [0.25, 0.10, 0.20, 0.25, 0.15, 0.05]},

    {"key": "electronic",
     "t": [0.80, 0.55, 0.65, 0.10, 0.80, 1.0],
     "w": [0.15, 0.10, 0.25, 0.20, 0.25, 0.05]},

    {"key": "jazz",
     "t": [0.45, 0.65, 0.30, 0.60, 0.50, 1.0],
     "w": [0.15, 0.15, 0.20, 0.20, 0.15, 0.15]},

    {"key": "hiphop",
     "t": [0.65, 0.50, 0.20, 0.35, 0.70, 1.0],
     "w": [0.10, 0.10, 0.30, 0.10, 0.25, 0.15]},

    {"key": "pop",
     "t": [0.70, 0.70, 0.50, 0.25, 0.75, 1.0],
     "w": [0.10, 0.15, 0.25, 0.15, 0.25, 0.10]},

    {"key": "classical",
     "t": [0.30, 0.50, 0.30, 0.90, 0.15, 1.0],
     "w": [0.15, 0.10, 0.15, 0.35, 0.20, 0.05]},

    {"key": "rock",
     "t": [0.80, 0.45, 0.55, 0.25, 0.45, 1.0],
     "w": [0.25, 0.15, 0.20, 0.15, 0.15, 0.10]},
]


def build_world_profile(features: dict) -> dict:
    """
    Describe the kind of Minecraft world this song will generate,
    based on its audio fingerprint.

    Every decision (biome, terrain, atmosphere, genre) is scored against
    ALL audio features using weighted target profiles, so different
    feature combinations always produce distinct results.
    """
    energy       = features.get("energy",       0.5)
    valence      = features.get("valence",       0.5)
    tempo        = features.get("tempo",         120)
    mode         = features.get("mode",          1)
    acousticness = features.get("acousticness",  0.5)
    danceability = features.get("danceability",  0.5)

    # Normalise tempo to [0, 1]:  60 BPM → 0.0,  200 BPM → 1.0
    tempo_norm = clamp((tempo - 60) / 140)
    is_major   = 1.0 if mode == 1 else 0.0

    fv = [energy, valence, tempo_norm, acousticness, danceability, is_major]

    # --- Score every category independently against all features ---
    biome      = _best_match(fv, _BIOME_PROFILES)
    terrain    = _best_match(fv, _TERRAIN_PROFILES)
    atmosphere = _best_match(fv, _ATMOSPHERE_PROFILES)
    genre      = _best_match(fv, _GENRE_PROFILES)

    # --- Hostility (weighted formula across all features) ---
    hostility_score = (
        energy * 0.35 +
        (1 - valence) * 0.25 +
        (1 - acousticness) * 0.15 +
        tempo_norm * 0.15 +
        (1 - is_major) * 0.10
    )
    if hostility_score > 0.65:
        hostility = "High — prepare for frequent mob encounters"
    elif hostility_score > 0.40:
        hostility = "Moderate — balanced survival experience"
    else:
        hostility = "Low — peaceful exploration encouraged"

    return {
        "terrain":        terrain["label"],
        "dominant_biome": biome["label"],
        "atmosphere":     atmosphere["label"],
        "hostility":      hostility,
        "mod_genre":      genre["key"],
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
        track = sp.track(track_id)
        if not track:
            return jsonify({"error": "Track not found. Check the URL and try again."}), 404

        features, analysis_note = fetch_track_features(track_id, track)

        seed    = features_to_seed(features, track_id)
        profile = build_world_profile(features)

        # Safely extract metadata with fallbacks.
        track_name = track.get("name", "Unknown Track")
        artists    = track.get("artists") or []
        artist     = artists[0]["name"] if artists else "Unknown Artist"
        album      = track.get("album") or {}
        images     = album.get("images") or []
        album_art  = images[0]["url"] if images else None

        return jsonify({
            "track_name":  track_name,
            "artist":      artist,
            "album_art":   album_art,
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
