# Weather + Lyrics Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:executing-plans to implement this plan task-by-task.

**Goal:** Add reactive weather/time-of-day per genre, and scrolling song lyrics in Minecraft chat synced to the current Spotify track.

**Architecture:** Both features are pure additions to `scripts/musecraft.py`. Weather/time fires extra RCON commands on genre change. Lyrics fetch from Genius API via `lyricsgenius`, parse into lines, then scroll via a background thread sending `/say` RCON commands at a timed interval. Lyrics are gated by `ENABLE_LYRICS = True` for easy removal.

**Tech Stack:** Python 3, mcrcon, lyricsgenius (`pip install lyricsgenius`), threading (stdlib), existing osascript + Last.fm infrastructure.

---

### Task 0: Add weather + time of day on genre change

**Files:**
- Modify: `scripts/musecraft.py`
- Modify: `tests/test_musecraft.py`

**Step 1: Write failing tests**

In `tests/test_musecraft.py`, add:

```python
def test_genre_atmosphere_metal(monkeypatch):
    commands_sent = []

    class FakeMCRcon:
        def __init__(self, host, password, port): pass
        def __enter__(self): return self
        def __exit__(self, *a): pass
        def command(self, cmd):
            commands_sent.append(cmd)
            return ""

    monkeypatch.setattr(musecraft, 'MCRcon', FakeMCRcon)
    musecraft.send_genworld("metal", host="127.0.0.1", password="test", port=25575)
    assert "/genworld metal" in commands_sent
    assert "/weather thunder" in commands_sent
    assert "/time set 18000" in commands_sent

def test_genre_atmosphere_ambient_no_change(monkeypatch):
    commands_sent = []

    class FakeMCRcon:
        def __init__(self, host, password, port): pass
        def __enter__(self): return self
        def __exit__(self, *a): pass
        def command(self, cmd):
            commands_sent.append(cmd)
            return ""

    monkeypatch.setattr(musecraft, 'MCRcon', FakeMCRcon)
    musecraft.send_genworld("ambient", host="127.0.0.1", password="test", port=25575)
    assert "/genworld ambient" in commands_sent
    assert len([c for c in commands_sent if "/weather" in c]) == 0
    assert len([c for c in commands_sent if "/time" in c]) == 0
```

**Step 2: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_musecraft.py::test_genre_atmosphere_metal -v
```
Expected: FAIL

**Step 3: Add `GENRE_ATMOSPHERE` and update `send_genworld`**

In `scripts/musecraft.py`, add after `GENRE_KEYWORDS`:

```python
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
```

Update `send_genworld` to fire atmosphere commands:

```python
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
```

**Step 4: Run all tests**

```bash
.venv/bin/python -m pytest tests/test_musecraft.py -v
```
Expected: ALL PASS (existing 8 + 2 new = 10 total)

**Step 5: Commit**

```bash
git add scripts/musecraft.py tests/test_musecraft.py
git commit -m "feat: reactive weather and time of day per genre"
```

---

### Task 1: Lyrics — fetch and parse from Genius

**Files:**
- Modify: `scripts/musecraft.py`
- Modify: `tests/test_musecraft.py`

**Step 1: Install lyricsgenius**

```bash
.venv/bin/pip install lyricsgenius
pip install lyricsgenius
```

**Step 2: Add `ENABLE_LYRICS` flag and `fetch_lyrics` function**

At the top of `scripts/musecraft.py`, after the existing imports, add:

```python
ENABLE_LYRICS = True  # set to False to disable lyrics in chat
```

Add `fetch_lyrics` function after `detect_genre`:

```python
def fetch_lyrics(track, artist, api_key):
    """
    Fetch lyrics for track/artist from Genius. Returns list of non-empty lines
    with section headers removed, or empty list on failure.
    """
    try:
        import lyricsgenius
        genius = lyricsgenius.Genius(api_key, verbose=False, remove_section_headers=True)
        song = genius.search_song(track, artist)
        if not song:
            return []
        lines = [l.strip() for l in song.lyrics.split('\n') if l.strip()]
        # Remove the first line which is usually "Track TitleLyrics"
        if lines and lines[0].lower().endswith('lyrics'):
            lines = lines[1:]
        return lines
    except Exception:
        return []
```

**Step 3: Write failing tests**

In `tests/test_musecraft.py`, add:

```python
def test_fetch_lyrics_returns_lines(monkeypatch):
    class FakeSong:
        lyrics = "Line one\n[Verse 1]\nLine two\n\nLine three\n"

    class FakeGenius:
        def __init__(self, key, verbose, remove_section_headers): pass
        def search_song(self, track, artist): return FakeSong()

    import lyricsgenius
    monkeypatch.setattr(lyricsgenius, 'Genius', FakeGenius)
    lines = musecraft.fetch_lyrics("Stronger", "Kanye West", api_key="testkey")
    assert "Line one" in lines
    assert "Line two" in lines
    assert "Line three" in lines

def test_fetch_lyrics_returns_empty_on_failure(monkeypatch):
    class FakeGenius:
        def __init__(self, *a, **kw): pass
        def search_song(self, track, artist): raise Exception("network error")

    import lyricsgenius
    monkeypatch.setattr(lyricsgenius, 'Genius', FakeGenius)
    lines = musecraft.fetch_lyrics("Unknown", "Unknown", api_key="testkey")
    assert lines == []
```

**Step 4: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_musecraft.py::test_fetch_lyrics_returns_lines -v
```
Expected: FAIL

**Step 5: Run all tests after implementation**

```bash
.venv/bin/python -m pytest tests/test_musecraft.py -v
```
Expected: ALL PASS

**Step 6: Commit**

```bash
git add scripts/musecraft.py tests/test_musecraft.py
git commit -m "feat: fetch and parse lyrics from Genius API"
```

---

### Task 2: Lyrics — background scroll thread

**Files:**
- Modify: `scripts/musecraft.py`
- Modify: `tests/test_musecraft.py`

**Step 1: Add `LyricScroller` class**

In `scripts/musecraft.py`, add after `fetch_lyrics`:

```python
import threading

class LyricScroller:
    """
    Scrolls lyrics in Minecraft chat via RCON at a timed interval.
    One line at a time, replacing the previous.
    Stop it by calling stop().
    """

    def __init__(self, lines, interval, rcon_host, rcon_password, rcon_port):
        self._lines = lines
        self._interval = interval
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
        for line in self._lines:
            if self._stop_event.is_set():
                return
            try:
                with MCRcon(self._host, self._password, port=self._port) as mcr:
                    mcr.command(f"/say \u266a {line}")
            except Exception:
                pass
            self._stop_event.wait(self._interval)
```

**Step 2: Write failing test**

In `tests/test_musecraft.py`, add:

```python
def test_lyric_scroller_sends_lines(monkeypatch):
    commands_sent = []

    class FakeMCRcon:
        def __init__(self, host, password, port): pass
        def __enter__(self): return self
        def __exit__(self, *a): pass
        def command(self, cmd):
            commands_sent.append(cmd)
            return ""

    monkeypatch.setattr(musecraft, 'MCRcon', FakeMCRcon)
    scroller = musecraft.LyricScroller(
        lines=["Hello", "World"],
        interval=0.01,
        rcon_host="127.0.0.1",
        rcon_password="test",
        rcon_port=25575,
    )
    scroller.start()
    scroller._thread.join(timeout=1)
    assert any("Hello" in c for c in commands_sent)
    assert any("World" in c for c in commands_sent)

def test_lyric_scroller_stops_early(monkeypatch):
    commands_sent = []

    class FakeMCRcon:
        def __init__(self, host, password, port): pass
        def __enter__(self): return self
        def __exit__(self, *a): pass
        def command(self, cmd):
            commands_sent.append(cmd)
            return ""

    monkeypatch.setattr(musecraft, 'MCRcon', FakeMCRcon)
    scroller = musecraft.LyricScroller(
        lines=["Line1", "Line2", "Line3"],
        interval=10,  # long interval
        rcon_host="127.0.0.1",
        rcon_password="test",
        rcon_port=25575,
    )
    scroller.start()
    import time; time.sleep(0.05)
    scroller.stop()
    scroller._thread.join(timeout=1)
    # Should have sent Line1 but stopped before Line2/Line3
    assert len(commands_sent) == 1
```

**Step 3: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_musecraft.py::test_lyric_scroller_sends_lines -v
```
Expected: FAIL

**Step 4: Run all tests after implementation**

```bash
.venv/bin/python -m pytest tests/test_musecraft.py -v
```
Expected: ALL PASS

**Step 5: Commit**

```bash
git add scripts/musecraft.py tests/test_musecraft.py
git commit -m "feat: LyricScroller — background thread scrolling lyrics via RCON"
```

---

### Task 3: Wire lyrics into main loop

**Files:**
- Modify: `scripts/musecraft.py`

**Step 1: Update `CONFIG` to include Genius key**

In `scripts/musecraft.py`, update `CONFIG`:

```python
CONFIG = {
    "lastfm_api_key": os.getenv("LASTFM_API_KEY", ""),
    "genius_api_key": os.getenv("GENIUS_API_KEY", ""),
    "rcon_host":      os.getenv("RCON_HOST", "127.0.0.1"),
    "rcon_port":      int(os.getenv("RCON_PORT", "25575")),
    "rcon_password":  os.getenv("RCON_PASSWORD", ""),
    "poll_interval":  20,
}
```

**Step 2: Update `main()` to start/stop lyric scroller on track change**

Replace the `main()` function with:

```python
def main():
    print("musecraft starting. Ctrl+C to stop.")
    if not CONFIG["lastfm_api_key"]:
        print("ERROR: Set LASTFM_API_KEY env var.")
        return
    if not CONFIG["rcon_password"]:
        print("ERROR: Set RCON_PASSWORD env var.")
        return
    if ENABLE_LYRICS and not CONFIG["genius_api_key"]:
        print("WARNING: GENIUS_API_KEY not set — lyrics disabled.")

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

            # Stop previous lyric scroller
            if current_scroller:
                current_scroller.stop()
                current_scroller = None

            # Start lyrics for new track
            if ENABLE_LYRICS and CONFIG["genius_api_key"]:
                lines = fetch_lyrics(track, artist, CONFIG["genius_api_key"])
                if lines:
                    # Get track duration via osascript (seconds)
                    try:
                        dur_result = subprocess.run(
                            ['osascript', '-e',
                             'tell application "Spotify" to duration of current track'],
                            capture_output=True, text=True
                        )
                        # Duration comes back in milliseconds
                        duration_ms = int(dur_result.stdout.strip())
                        interval = max(1.5, (duration_ms / 1000) / len(lines))
                    except Exception:
                        interval = 3.0
                    current_scroller = LyricScroller(
                        lines=lines,
                        interval=interval,
                        rcon_host=CONFIG["rcon_host"],
                        rcon_password=CONFIG["rcon_password"],
                        rcon_port=CONFIG["rcon_port"],
                    )
                    current_scroller.start()
                    print(f"  Lyrics: {len(lines)} lines, {interval:.1f}s interval")
                else:
                    print("  Lyrics: not found")

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

        time.sleep(CONFIG["poll_interval"])
```

**Step 3: Run all tests**

```bash
.venv/bin/python -m pytest tests/test_musecraft.py -v
```
Expected: ALL PASS (no new tests needed — main() is tested via integration)

**Step 4: Manual smoke test**

With Spotify playing and server running:
```bash
GENIUS_API_KEY=yourkey cd ~/musecraft && python3 scripts/musecraft.py
```

Expected output:
```
Now playing: Kanye West — Stronger
  Lyrics: 42 lines, 4.2s interval
  Genre: None -> hiphop
  RCON '/genworld hiphop' -> ''
  RCON '/weather clear' -> ''
  RCON '/time set 6000' -> ''
```

**Step 5: Commit**

```bash
git add scripts/musecraft.py
git commit -m "feat: wire lyrics scroller into main loop with ENABLE_LYRICS flag"
```

---

### Task 4: Update README and push

**Files:**
- Modify: `README.md`
- Modify: `scripts/README.md`

**Step 1: Add Genius API key to both READMEs**

In `README.md`, under the `.env` section add:
```
GENIUS_API_KEY=your_genius_key   # optional, for lyrics in chat
```

In `scripts/README.md`, add under env vars:
```
GENIUS_API_KEY=your_genius_key   # optional — lyrics scroll in chat
```

Also add note:
```
- Set ENABLE_LYRICS = False in the script to disable lyrics entirely.
```

**Step 2: Commit and push**

```bash
git add README.md scripts/README.md
git commit -m "docs: add Genius API key instructions for lyrics feature"
git push
```

---

## Setup checklist for lyrics

- [ ] `pip install lyricsgenius` + `.venv/bin/pip install lyricsgenius`
- [ ] Get free Genius API key at genius.com/api-clients
- [ ] Add `GENIUS_API_KEY=yourkey` to `.env`
- [ ] Set `ENABLE_LYRICS = True` in `scripts/musecraft.py` (already default)
