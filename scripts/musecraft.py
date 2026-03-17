import importlib

try:
    from mcrcon import MCRcon
except ImportError:
    MCRcon = None

try:
    syncedlyrics = importlib.import_module("syncedlyrics")
except ImportError:
    syncedlyrics = None

import os
import re
import subprocess
import threading
import time
import urllib.parse
import urllib.request
import json

try:
    from gemini_dj import get_dj_intro
    _GEMINI_DJ_AVAILABLE = True
except ImportError:
    get_dj_intro = None
    _GEMINI_DJ_AVAILABLE = False

# Load .env file if present
_env_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '.env')
if os.path.exists(_env_path):
    with open(_env_path) as _f:
        for _line in _f:
            _line = _line.strip()
            if _line and not _line.startswith('#') and '=' in _line:
                _k, _v = _line.split('=', 1)
                os.environ[_k.strip()] = _v.strip()

APPLESCRIPT = '''
tell application "Spotify"
    if player state is playing then
        set t to name of current track
        set a to artist of current track
        return t & "|" & a
    else
        return "not_playing"
    end if
end tell
'''


GENRE_KEYWORDS = {
    "metal":      ["metal", "heavy metal", "thrash", "doom", "death metal", "black metal", "hardcore", "punk"],
    "jazz":       ["jazz", "bebop", "swing", "blues", "soul", "funk", "bossa nova"],
    "classical":  ["classical", "orchestra", "symphony", "opera", "baroque", "chamber music", "piano"],
    "hiphop":     ["hip hop", "hiphop", "rap", "trap", "drill", "boom bap"],
    "electronic": ["electronic", "techno", "house", "edm", "drum and bass", "dnb", "dubstep", "synth"],
    "pop":        ["indie pop", "dance pop", "k-pop", "teen pop", "pop rock", "pop punk"],
    "ambient":    ["ambient", "chill", "lofi", "lo-fi", "meditation", "new age", "drone"],
}


def match_genre(tag):
    """Match a single tag string to a genre. Returns genre name or None."""
    tag_lower = tag.lower()
    for genre, keywords in GENRE_KEYWORDS.items():
        for kw in keywords:
            if kw in tag_lower:
                return genre
    return None


def _lastfm_tags(artist, track, api_key):
    """Fetch top tags for a track from Last.fm. Returns list of tag name strings."""
    url = (
        "https://ws.audioscrobbler.com/2.0/?"
        + urllib.parse.urlencode({
            "method": "track.getTopTags",
            "artist": artist,
            "track": track,
            "api_key": api_key,
            "format": "json",
            "autocorrect": 1,
        })
    )
    try:
        with urllib.request.urlopen(url, timeout=5) as resp:
            data = json.loads(resp.read())
        return [t["name"] for t in data.get("toptags", {}).get("tag", [])]
    except Exception:
        return []


def detect_genre(track, artist, api_key):
    """Look up Last.fm tags for track/artist and return matching genre or None."""
    tags = _lastfm_tags(artist, track, api_key)
    for tag in tags:
        genre = match_genre(tag)
        if genre:
            return genre
    return None


def fetch_lyrics(track, artist):
    """
    Fetch lyrics via syncedlyrics (no API key needed).
    Returns list of non-empty lyric lines with timestamps stripped, or [] on failure.
    """
    if syncedlyrics is None:
        return []
    try:
        lrc = syncedlyrics.search(f"{track} {artist}")
        if not lrc:
            return []
        lines = []
        for line in lrc.split('\n'):
            stripped = re.sub(r'\[\d+:\d+\.\d+\]', '', line).strip()
            if stripped:
                lines.append(stripped)
        return lines
    except Exception:
        return []


def fetch_lyrics_with_timestamps(track, artist):
    """
    Fetch synced lyrics via syncedlyrics.
    Returns list of (timestamp_ms: int, line: str) tuples, sorted by timestamp.
    Returns [] on failure or if no synced lyrics available.
    """
    if syncedlyrics is None:
        return []
    try:
        lrc = syncedlyrics.search(f"{track} {artist}", synced_only=True)
        if not lrc:
            return []
        results = []
        for line in lrc.split('\n'):
            m = re.match(r'\[(\d+):(\d+\.\d+)\](.*)', line)
            if m:
                minutes = int(m.group(1))
                seconds = float(m.group(2))
                text = m.group(3).strip()
                if text:
                    ts_ms = int((minutes * 60 + seconds) * 1000)
                    results.append((ts_ms, text))
        results.sort(key=lambda x: x[0])
        return results
    except Exception:
        return []


def _collect_intro_lyrics(track, artist, limit=10):
    """
    Build the best available lyric source for Gemini DJ.
    Prefer synced lyrics for timing, then top up from plain lyrics if needed.
    Returns (timed_lines, intro_lines).
    """
    timed_lines = fetch_lyrics_with_timestamps(track, artist)
    intro_lines = [line for _, line in timed_lines[:limit]]
    seen = set(intro_lines)

    if len(intro_lines) < limit:
        for line in fetch_lyrics(track, artist):
            stripped = line.strip()
            if not stripped or stripped in seen:
                continue
            intro_lines.append(stripped)
            seen.add(stripped)
            if len(intro_lines) >= limit:
                break

    return timed_lines, intro_lines[:limit]


# weather: "thunder" | "rain" | "clear" | None (no change)
# time: int (ticks) | None (no change)
GENRE_ATMOSPHERE = {
    "metal":      {"weather": "thunder", "time": 18000},
    "jazz":       {"weather": "clear",   "time": 0},
    "classical":  {"weather": "clear",   "time": 6000},
    "hiphop":     {"weather": "clear",   "time": 6000},
    "electronic": {"weather": "clear",   "time": 18000},
    "pop":        {"weather": "clear",   "time": 6000},
    "ambient":    {"weather": None,      "time": None},
}

def send_genworld(genre, host="127.0.0.1", password="", port=25575):
    """Send /genworld <genre> + weather/time RCON commands."""
    if MCRcon is None:
        print("mcrcon not installed. Run: pip install mcrcon")
        return
    atmosphere = GENRE_ATMOSPHERE.get(genre, {})
    commands = [f"/genworld {genre}"]
    if atmosphere.get("weather"):
        commands.append(f"/weather {atmosphere['weather']}")
    if atmosphere.get("time") is not None:
        commands.append(f"/time set {atmosphere['time']}")
    try:
        with MCRcon(host, password, port=port) as mcr:
            for cmd in commands:
                response = mcr.command(cmd)
                print(f"  RCON {cmd!r} -> {response!r}")
    except ConnectionRefusedError:
        print(f"  RCON: server offline or RCON not enabled on port {port}")
    except Exception as e:
        print(f"  RCON error: {e}")


def get_current_track():
    """Returns (track, artist) tuple or None if Spotify not playing."""
    result = subprocess.run(
        ['osascript', '-e', APPLESCRIPT],
        capture_output=True, text=True
    )
    output = result.stdout.strip()
    if not output or output == "not_playing":
        return None
    parts = output.split("|", 1)
    if len(parts) != 2:
        return None
    return parts[0].strip(), parts[1].strip()


def get_spotify_position():
    """Returns current Spotify playback position in milliseconds, or None on failure."""
    try:
        result = subprocess.run(
            ['osascript', '-e',
             'tell application "Spotify" to player position'],
            capture_output=True, text=True
        )
        return int(float(result.stdout.strip()) * 1000)
    except Exception:
        return None


def _rcon_say(host, password, port, message, timeout=5):
    """Send a /say command via raw RCON socket. Safe to call from any thread."""
    import socket as _socket
    import struct as _struct
    def _pack(req_id, req_type, payload):
        data = payload.encode('utf-8') + b'\x00\x00'
        return _struct.pack('<iii', len(data) + 8, req_id, req_type) + data
    def _recv(sock):
        raw = b''
        while len(raw) < 4:
            raw += sock.recv(4 - len(raw))
        length = _struct.unpack('<i', raw)[0]
        data = b''
        while len(data) < length:
            data += sock.recv(length - len(data))
        return _struct.unpack('<ii', data[:8])[0], data[8:-2].decode('utf-8')
    s = _socket.create_connection((host, port), timeout=timeout)
    try:
        s.sendall(_pack(1, 3, password))  # auth
        _recv(s)
        s.sendall(_pack(2, 2, f"/say {message}"))
        _recv(s)
    finally:
        s.close()


class SyncedScroller:
    """
    Scrolls timestamped lyric lines in Minecraft chat synced to Spotify playback.
    Takes list of (timestamp_ms, line) tuples. Fires each line at the right moment.
    Stop by calling stop().
    """

    def __init__(self, timed_lines: list[tuple[int, str]], rcon_host, rcon_password, rcon_port):
        self._lines = sorted(timed_lines, key=lambda x: x[0])
        self._host = rcon_host
        self._password = rcon_password
        self._port = rcon_port
        self._stop_event = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self._thread.start()

    def stop(self):
        self._stop_event.set()

    def _run(self):
        # NOTE: position is estimated by dead-reckoning from start_position.
        # Pausing, seeking, or slow RCON will cause drift.
        start_wall = time.time()
        start_position = get_spotify_position() or 0
        for ts_ms, line in self._lines:
            if self._stop_event.is_set():
                return
            elapsed_ms = (time.time() - start_wall) * 1000
            current_song_ms = start_position + elapsed_ms
            wait_ms = ts_ms - current_song_ms
            if wait_ms > 0:
                if self._stop_event.wait(wait_ms / 1000):
                    return
            if self._stop_event.is_set():
                return
            try:
                _rcon_say(self._host, self._password, self._port, f"♪ {line}")
            except Exception as e:
                print(f"  SyncedScroller RCON error: {e}")


CONFIG = {
    "lastfm_api_key": os.getenv("LASTFM_API_KEY", ""),
    "gemini_api_key": os.getenv("GEMINI_API_KEY", ""),
    "rcon_host":      os.getenv("RCON_HOST", "127.0.0.1"),
    "rcon_port":      int(os.getenv("RCON_PORT", "25575")),
    "rcon_password":  os.getenv("RCON_PASSWORD", ""),
    "poll_interval":  5,
}


def main():
    print("musecraft starting. Ctrl+C to stop.")
    if not CONFIG["lastfm_api_key"]:
        print("ERROR: Set LASTFM_API_KEY env var.")
        return
    if not CONFIG["rcon_password"]:
        print("ERROR: Set RCON_PASSWORD env var.")
        return

    current_genre = None
    last_track = None
    current_scroller = None

    while True:
        try:
            track_info = get_current_track()
            if not track_info:
                print("Spotify not playing.")
                time.sleep(CONFIG["poll_interval"])
                continue

            track, artist = track_info
            if (track, artist) == last_track:
                time.sleep(CONFIG["poll_interval"])
                continue

            last_track = (track, artist)
            print(f"Now playing: {artist} — {track}")

            # Gemini DJ intro + synced pun lyrics
            if _GEMINI_DJ_AVAILABLE and get_dj_intro is not None and CONFIG["gemini_api_key"]:
                timed_lines, lyrics_lines = _collect_intro_lyrics(track, artist)
                if not lyrics_lines:
                    print("  Gemini DJ: no lyrics found")
                else:
                    intro_lines = get_dj_intro(
                        track=track,
                        artist=artist,
                        first_lines=lyrics_lines,
                        api_key=CONFIG["gemini_api_key"],
                    )
                    if intro_lines:
                        now_playing = intro_lines[0]
                        pun_lines = intro_lines[1:]  # up to 10

                        # Stop any previous scroller
                        if current_scroller:
                            current_scroller.stop()
                            current_scroller = None

                        if MCRcon is None:
                            print("  Gemini DJ: mcrcon not installed")
                        else:
                            # Send "Now Playing" header immediately
                            try:
                                with MCRcon(CONFIG["rcon_host"], CONFIG["rcon_password"],
                                            port=CONFIG["rcon_port"]) as mcr:
                                    mcr.command(f"/say {now_playing}")
                            except Exception as e:
                                print(f"  Gemini DJ RCON error: {e}")

                            # Pair pun lines with timestamps and start scroller
                            if len(timed_lines) >= len(pun_lines) and pun_lines:
                                paired = list(zip(
                                    [ts for ts, _ in timed_lines[:len(pun_lines)]],
                                    pun_lines
                                ))
                                current_scroller = SyncedScroller(
                                    timed_lines=paired,
                                    rcon_host=CONFIG["rcon_host"],
                                    rcon_password=CONFIG["rcon_password"],
                                    rcon_port=CONFIG["rcon_port"],
                                )
                                current_scroller.start()
                                print(f"  Gemini DJ: now playing sent, scroller started ({len(paired)} lines)")
                            else:
                                # No timestamps — fall back to sending all pun lines with delay
                                try:
                                    with MCRcon(CONFIG["rcon_host"], CONFIG["rcon_password"],
                                                port=CONFIG["rcon_port"]) as mcr:
                                        for line in pun_lines:
                                            mcr.command(f"/say {line}")
                                            time.sleep(1.5)
                                except Exception as e:
                                    print(f"  Gemini DJ RCON error: {e}")
                    else:
                        print("  Gemini DJ: no intro generated")

            # Genre detection + genworld
            genre = detect_genre(track, artist, api_key=CONFIG["lastfm_api_key"])
            if not genre:
                print("  Genre unknown, keeping current.")
                time.sleep(CONFIG["poll_interval"])
                continue

            if genre != current_genre:
                print(f"  Genre: {current_genre} -> {genre}")
                send_genworld(
                    genre,
                    host=CONFIG["rcon_host"],
                    password=CONFIG["rcon_password"],
                    port=CONFIG["rcon_port"],
                )
                current_genre = genre
            else:
                print(f"  Genre unchanged: {genre}")

        except KeyboardInterrupt:
            print("\nStopped.")
            if current_scroller:
                current_scroller.stop()
            break
        except Exception as e:
            print(f"Error: {e}")
            if current_scroller:
                current_scroller.stop()
                current_scroller = None

        time.sleep(CONFIG["poll_interval"])


if __name__ == "__main__":
    main()
