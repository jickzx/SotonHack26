# Spotify Genre Switching Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:executing-plans to implement this plan task-by-task.

**Goal:** Poll Spotify every 20s, detect the genre of the current track via Last.fm tags, and fire `/genworld <genre>` via RCON when the genre changes.

**Architecture:** Standalone Python script (`scripts/musecraft.py`) on Mac. osascript reads track/artist from Spotify desktop app. Last.fm `track.getTopTags` maps to one of 7 genres via keyword matching. mcrcon sends `/genworld <genre>` to the local Minecraft server only when the genre changes.

**Tech Stack:** Python 3, osascript (macOS), Last.fm API (free key), mcrcon (`pip install mcrcon`), stdlib only otherwise.

---

### Task 0: Create scripts directory and test scaffold

**Files:**
- Create: `scripts/musecraft.py`
- Create: `tests/test_musecraft.py`

**Step 1: Create the scripts directory and an empty script**

```bash
mkdir -p scripts tests
touch scripts/musecraft.py tests/test_musecraft.py
```

**Step 2: Write the test scaffold**

In `tests/test_musecraft.py`:

```python
import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'scripts'))

import importlib
import musecraft
```

**Step 3: Verify import works**

Run: `python -c "import sys; sys.path.insert(0,'scripts'); import musecraft"`
Expected: no errors (empty module is fine at this stage)

**Step 4: Commit**

```bash
git add scripts/musecraft.py tests/test_musecraft.py
git commit -m "feat: scaffold musecraft spotify-genre script"
```

---

### Task 1: spotify_reader — read current track via osascript

**Files:**
- Modify: `scripts/musecraft.py`
- Modify: `tests/test_musecraft.py`

**Step 1: Write the failing test**

In `tests/test_musecraft.py`, add:

```python
from unittest.mock import patch

def test_get_current_track_returns_track_and_artist():
    fake_output = "Stronger|Kanye West"
    with patch('subprocess.run') as mock_run:
        mock_run.return_value.stdout = fake_output
        mock_run.return_value.returncode = 0
        track, artist = musecraft.get_current_track()
    assert track == "Stronger"
    assert artist == "Kanye West"

def test_get_current_track_returns_none_when_not_playing():
    with patch('subprocess.run') as mock_run:
        mock_run.return_value.stdout = "not_playing"
        mock_run.return_value.returncode = 0
        result = musecraft.get_current_track()
    assert result is None
```

**Step 2: Run to verify it fails**

```bash
python -m pytest tests/test_musecraft.py -v
```
Expected: FAIL — `AttributeError: module 'musecraft' has no attribute 'get_current_track'`

**Step 3: Implement `get_current_track`**

In `scripts/musecraft.py`:

```python
import subprocess

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
```

**Step 4: Run tests**

```bash
python -m pytest tests/test_musecraft.py -v
```
Expected: PASS

**Step 5: Commit**

```bash
git add scripts/musecraft.py tests/test_musecraft.py
git commit -m "feat: spotify_reader — read track/artist via osascript"
```

---

### Task 2: genre_detector — Last.fm tag lookup + keyword matching

**Files:**
- Modify: `scripts/musecraft.py`
- Modify: `tests/test_musecraft.py`

**Step 1: Write failing tests**

In `tests/test_musecraft.py`, add:

```python
def test_match_genre_hiphop():
    assert musecraft.match_genre("hip hop") == "hiphop"

def test_match_genre_metal():
    assert musecraft.match_genre("heavy metal") == "metal"

def test_match_genre_none():
    assert musecraft.match_genre("noise") is None

def test_detect_genre_from_lastfm(monkeypatch):
    def fake_lastfm(artist, track, api_key):
        return ["hip hop", "rap", "kanye"]
    monkeypatch.setattr(musecraft, '_lastfm_tags', fake_lastfm)
    genre = musecraft.detect_genre("Stronger", "Kanye West", api_key="testkey")
    assert genre == "hiphop"

def test_detect_genre_unknown_returns_none(monkeypatch):
    def fake_lastfm(artist, track, api_key):
        return ["noise", "experimental"]
    monkeypatch.setattr(musecraft, '_lastfm_tags', fake_lastfm)
    genre = musecraft.detect_genre("Unknown Track", "Unknown Artist", api_key="testkey")
    assert genre is None
```

**Step 2: Run to verify failure**

```bash
python -m pytest tests/test_musecraft.py -v
```
Expected: FAIL — `AttributeError: module 'musecraft' has no attribute 'match_genre'`

**Step 3: Implement genre detection**

In `scripts/musecraft.py`, add:

```python
import urllib.parse
import urllib.request
import json

GENRE_KEYWORDS = {
    "metal":      ["metal", "heavy metal", "thrash", "doom", "death metal", "black metal", "hardcore", "punk"],
    "jazz":       ["jazz", "bebop", "swing", "blues", "soul", "funk", "bossa nova"],
    "classical":  ["classical", "orchestra", "symphony", "opera", "baroque", "chamber music", "piano"],
    "hiphop":     ["hip hop", "hiphop", "rap", "trap", "drill", "boom bap"],
    "electronic": ["electronic", "techno", "house", "edm", "drum and bass", "dnb", "dubstep", "synth"],
    "pop":        ["pop", "indie pop", "dance pop", "k-pop", "teen pop"],
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
    """
    Look up Last.fm tags for track/artist and return matching genre or None.
    Tries tags in order — first match wins.
    """
    tags = _lastfm_tags(artist, track, api_key)
    for tag in tags:
        genre = match_genre(tag)
        if genre:
            return genre
    return None
```

**Step 4: Run tests**

```bash
python -m pytest tests/test_musecraft.py -v
```
Expected: PASS

**Step 5: Commit**

```bash
git add scripts/musecraft.py tests/test_musecraft.py
git commit -m "feat: genre_detector — Last.fm tags + keyword matching"
```

---

### Task 3: rcon_sender — send /genworld via RCON

**Files:**
- Modify: `scripts/musecraft.py`
- Modify: `tests/test_musecraft.py`

**Step 1: Write failing test**

In `tests/test_musecraft.py`, add:

```python
def test_send_genworld_calls_rcon(monkeypatch):
    commands_sent = []

    class FakeMCRcon:
        def __init__(self, host, password, port): pass
        def __enter__(self): return self
        def __exit__(self, *a): pass
        def command(self, cmd):
            commands_sent.append(cmd)
            return ""

    monkeypatch.setattr(musecraft, 'MCRcon', FakeMCRcon)
    musecraft.send_genworld("hiphop", host="127.0.0.1", password="test", port=25575)
    assert commands_sent == ["/genworld hiphop"]
```

**Step 2: Run to verify failure**

```bash
python -m pytest tests/test_musecraft.py::test_send_genworld_calls_rcon -v
```
Expected: FAIL — `AttributeError: module 'musecraft' has no attribute 'send_genworld'`

**Step 3: Implement `send_genworld`**

In `scripts/musecraft.py`, add at top:

```python
try:
    from mcrcon import MCRcon
except ImportError:
    MCRcon = None
```

And the function:

```python
def send_genworld(genre, host="127.0.0.1", password="", port=25575):
    """Send /genworld <genre> to Minecraft via RCON."""
    if MCRcon is None:
        print("mcrcon not installed. Run: pip install mcrcon")
        return
    try:
        with MCRcon(host, password, port=port) as mcr:
            response = mcr.command(f"/genworld {genre}")
            print(f"  RCON /genworld {genre} -> {response!r}")
    except ConnectionRefusedError:
        print(f"  RCON: server offline or RCON not enabled on port {port}")
    except Exception as e:
        print(f"  RCON error: {e}")
```

**Step 4: Run tests**

```bash
python -m pytest tests/test_musecraft.py -v
```
Expected: PASS

**Step 5: Commit**

```bash
git add scripts/musecraft.py tests/test_musecraft.py
git commit -m "feat: rcon_sender — send /genworld via mcrcon"
```

---

### Task 4: poll_loop — main loop wiring everything together

**Files:**
- Modify: `scripts/musecraft.py`

**Step 1: Implement `main`**

In `scripts/musecraft.py`, add:

```python
import os
import time

CONFIG = {
    "lastfm_api_key": os.getenv("LASTFM_API_KEY", ""),
    "rcon_host":      os.getenv("RCON_HOST", "127.0.0.1"),
    "rcon_port":      int(os.getenv("RCON_PORT", "25575")),
    "rcon_password":  os.getenv("RCON_PASSWORD", ""),
    "poll_interval":  20,
}

def main():
    print("musecraft starting. Ctrl+C to stop.")
    if not CONFIG["lastfm_api_key"]:
        print("ERROR: Set LASTFM_API_KEY env var. Get a free key at https://www.last.fm/api")
        return
    if not CONFIG["rcon_password"]:
        print("ERROR: Set RCON_PASSWORD env var.")
        return

    current_genre = None
    last_track = None

    while True:
        try:
            track_info = get_current_track()
            if not track_info:
                print("Spotify not playing.")
                time.sleep(CONFIG["poll_interval"])
                continue

            track, artist = track_info
            if (track, artist) == last_track:
                # Same song — no need to re-lookup
                time.sleep(CONFIG["poll_interval"])
                continue

            last_track = (track, artist)
            print(f"Now playing: {artist} — {track}")

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
            break
        except Exception as e:
            print(f"Error: {e}")

        time.sleep(CONFIG["poll_interval"])

if __name__ == "__main__":
    main()
```

**Step 2: Manual smoke test**

With Spotify playing a popular song (e.g. "Stronger" by Kanye West) and Minecraft server running with RCON enabled:

```bash
LASTFM_API_KEY=yourkey RCON_PASSWORD=yourpassword python scripts/musecraft.py
```

Expected output:
```
musecraft starting. Ctrl+C to stop.
Now playing: Kanye West — Stronger
  Genre: None -> hiphop
  RCON /genworld hiphop -> ''
```

**Step 3: Test genre detection manually without Minecraft**

```bash
python -c "
import sys; sys.path.insert(0,'scripts')
import musecraft
print(musecraft.detect_genre('Stronger', 'Kanye West', api_key='YOUR_KEY'))
print(musecraft.detect_genre('So What', 'Miles Davis', api_key='YOUR_KEY'))
print(musecraft.detect_genre('Enter Sandman', 'Metallica', api_key='YOUR_KEY'))
"
```
Expected: `hiphop`, `jazz`, `metal`

**Step 4: Commit**

```bash
git add scripts/musecraft.py
git commit -m "feat: poll_loop — main loop wiring spotify -> lastfm -> rcon"
```

---

### Task 5: README for the script

**Files:**
- Create: `scripts/README.md`

**Step 1: Write README**

In `scripts/README.md`:

```markdown
# musecraft.py

Automatically switches the MusicWorld Minecraft biome based on what you're playing in Spotify.

## How it works

1. Reads current track + artist from Spotify desktop app (macOS only, via osascript)
2. Looks up genre tags on Last.fm
3. Maps tags to one of 7 genres: metal, jazz, classical, hiphop, electronic, pop, ambient
4. Sends `/genworld <genre>` to your local Minecraft server via RCON when the genre changes

## Setup

### 1. Install dependency

```
pip install mcrcon
```

### 2. Get a free Last.fm API key

Go to https://www.last.fm/api and create an account + API application (takes 2 minutes).

### 3. Enable RCON in Minecraft

In your `server.properties`:

```
enable-rcon=true
rcon.port=25575
rcon.password=yourpassword
```

Restart the server after editing.

### 4. Run

```bash
export LASTFM_API_KEY=your_lastfm_key
export RCON_PASSWORD=yourpassword
python scripts/musecraft.py
```

Optional env vars (defaults shown):
- `RCON_HOST` — `127.0.0.1`
- `RCON_PORT` — `25575`

## Notes

- Works for popular artists. Obscure tracks may not have Last.fm tags — genre stays unchanged.
- Spotify desktop app must be running and playing.
- Polls every 20 seconds. Genre command only fires when genre changes.
```

**Step 2: Commit**

```bash
git add scripts/README.md
git commit -m "docs: add musecraft.py setup README"
```

---

## Setup Checklist (for the user)

Before running:
- [ ] `pip install mcrcon`
- [ ] Get Last.fm API key at https://www.last.fm/api
- [ ] Add to `server.properties`: `enable-rcon=true`, `rcon.port=25575`, `rcon.password=...`
- [ ] Restart Minecraft server
- [ ] Set `LASTFM_API_KEY` and `RCON_PASSWORD` env vars
