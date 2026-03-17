# Synced Pun Lyrics Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:executing-plans to implement this plan task-by-task.

**Goal:** When a track starts, Gemini rewrites 10 lyrics as Minecraft puns. Send "Now Playing" header immediately, then scroll the 10 pun lines synced to their LRC timestamps as the song plays — in a background thread so the main loop keeps running.

**Architecture:** Two changes: (1) `fetch_lyrics_with_timestamps` — new function returning `(timestamp_ms, line)` tuples from LRC data; (2) `get_dj_intro` in `gemini_dj.py` updated to take 10 lines and return 11 lines (title + 10 puns); (3) `SyncedScroller` — background thread in `musecraft.py` that reads Spotify playback position via osascript and fires each line at the right moment; (4) main loop wired to start/stop SyncedScroller on track change.

**Tech Stack:** Python 3, syncedlyrics (already installed), threading (stdlib), osascript for playback position, existing mcrcon/RCON, existing Gemini DJ module.

---

### Task 0: Add `fetch_lyrics_with_timestamps` and update `gemini_dj.py` for 10 lines

**Files:**
- Modify: `scripts/musecraft.py`
- Modify: `scripts/gemini_dj.py`
- Modify: `tests/test_musecraft.py`
- Modify: `tests/test_gemini_dj.py`

**Step 1: Add `fetch_lyrics_with_timestamps` to `musecraft.py`**

Add this function after `fetch_lyrics`:

```python
def fetch_lyrics_with_timestamps(track, artist):
    """
    Fetch synced lyrics via syncedlyrics.
    Returns list of (timestamp_ms: int, line: str) tuples, sorted by timestamp.
    Returns [] on failure or if no synced lyrics available.
    """
    if syncedlyrics is None:
        return []
    try:
        lrc = syncedlyrics.search(f"{track} {artist}")
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
        return results
    except Exception:
        return []
```

**Step 2: Write failing test for `fetch_lyrics_with_timestamps`**

In `tests/test_musecraft.py`, add:

```python
def test_fetch_lyrics_with_timestamps_returns_tuples(monkeypatch):
    import syncedlyrics
    monkeypatch.setattr(syncedlyrics, 'search', lambda q, **kw:
        "[00:01.00] Line one\n[00:05.50] Line two\n[00:09.00] Line three\n")
    result = musecraft.fetch_lyrics_with_timestamps("Stronger", "Kanye West")
    assert len(result) == 3
    assert result[0] == (1000, "Line one")
    assert result[1] == (5500, "Line two")
    assert result[2] == (9000, "Line three")

def test_fetch_lyrics_with_timestamps_returns_empty_on_none(monkeypatch):
    import syncedlyrics
    monkeypatch.setattr(syncedlyrics, 'search', lambda q, **kw: None)
    result = musecraft.fetch_lyrics_with_timestamps("Unknown", "Unknown")
    assert result == []
```

**Step 3: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_musecraft.py::test_fetch_lyrics_with_timestamps_returns_tuples -v
```
Expected: FAIL

**Step 4: Update `gemini_dj.py` to rewrite 10 lines**

Update `PROMPT_TEMPLATE` to request 11 lines (title + 10 puns):

```python
PROMPT_TEMPLATE = """\
You are a witty Minecraft DJ. Given a song, produce exactly 11 lines:
1. A "Now Playing" line with the song title rewritten as a Minecraft pun, formatted as:
   ♪ Now Playing: "<Minecraft pun title>" ({track} - {artist})
2-11. The first 10 lyrics rewritten with Minecraft references (mobs, blocks, items, biomes).
   Each line starts with ♪

Original title: {track} by {artist}
First 10 lyrics:
{lyrics}

Output exactly 11 lines, nothing else."""
```

Update `get_dj_intro` to accept up to 10 lines and return 11:

```python
def get_dj_intro(track, artist, first_lines, api_key):
    """
    Returns list of 11 chat lines (pun title + 10 rewritten lyrics), or None on failure.
    """
    if not api_key or genai is None:
        return None
    try:
        client = genai.Client(api_key=api_key)
        lyrics_text = "\n".join(first_lines[:10]) if first_lines else "(no lyrics)"
        prompt = PROMPT_TEMPLATE.format(
            track=track,
            artist=artist,
            lyrics=lyrics_text,
        )
        response = client.models.generate_content(model="gemini-2.5-flash", contents=prompt)
        lines = [l.strip() for l in response.text.strip().split("\n") if l.strip()]
        return lines[:11] if len(lines) >= 11 else None
    except Exception as e:
        print(f"  Gemini DJ error: {e}")
        return None
```

**Step 5: Update `tests/test_gemini_dj.py` to expect 11 lines**

Update `test_get_dj_intro_returns_four_lines` → rename to `test_get_dj_intro_returns_eleven_lines` and update the fake response to have 11 lines and assert `len(lines) == 11`.

**Step 6: Run all tests**

```bash
.venv/bin/python -m pytest tests/ -v
```
Expected: ALL PASS

**Step 7: Commit**

```bash
git add scripts/musecraft.py scripts/gemini_dj.py tests/test_musecraft.py tests/test_gemini_dj.py
git commit -m "feat: fetch timestamped lyrics, update Gemini DJ to rewrite 10 lines"
```

---

### Task 1: Add `SyncedScroller` and `get_spotify_position`

**Files:**
- Modify: `scripts/musecraft.py`
- Modify: `tests/test_musecraft.py`

**Step 1: Add `get_spotify_position` helper**

Add after `get_current_track`:

```python
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
```

**Step 2: Add `SyncedScroller` class**

Add after `get_spotify_position`, before `CONFIG`:

```python
import threading

class SyncedScroller:
    """
    Scrolls timestamped lyric lines in Minecraft chat synced to Spotify playback.
    Takes list of (timestamp_ms, line) tuples. Fires each line at the right moment.
    Stop by calling stop().
    """

    def __init__(self, timed_lines, rcon_host, rcon_password, rcon_port):
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
        start_wall = time.time()
        start_position = get_spotify_position() or 0
        for ts_ms, line in self._lines:
            if self._stop_event.is_set():
                return
            # How many ms into the song should this line fire
            target_song_ms = ts_ms
            # Current song position = start_position + elapsed wall time
            elapsed_ms = (time.time() - start_wall) * 1000
            current_song_ms = start_position + elapsed_ms
            wait_ms = target_song_ms - current_song_ms
            if wait_ms > 0:
                if self._stop_event.wait(wait_ms / 1000):
                    return
            if self._stop_event.is_set():
                return
            try:
                with MCRcon(self._host, self._password, port=self._port) as mcr:
                    mcr.command(f"/say ♪ {line}")
            except Exception:
                pass
```

**Step 3: Write failing tests**

In `tests/test_musecraft.py`, add:

```python
def test_get_spotify_position_returns_ms(monkeypatch):
    with patch('subprocess.run') as mock_run:
        mock_run.return_value.stdout = "30.5\n"
        mock_run.return_value.returncode = 0
        pos = musecraft.get_spotify_position()
    assert pos == 30500

def test_get_spotify_position_returns_none_on_failure(monkeypatch):
    with patch('subprocess.run') as mock_run:
        mock_run.return_value.stdout = "not a number"
        result = musecraft.get_spotify_position()
    assert result is None

def test_synced_scroller_sends_lines(monkeypatch):
    commands_sent = []

    class FakeMCRcon:
        def __init__(self, host, password, port): pass
        def __enter__(self): return self
        def __exit__(self, *a): pass
        def command(self, cmd):
            commands_sent.append(cmd)
            return ""

    monkeypatch.setattr(musecraft, 'MCRcon', FakeMCRcon)
    monkeypatch.setattr(musecraft, 'get_spotify_position', lambda: 0)

    scroller = musecraft.SyncedScroller(
        timed_lines=[(50, "Line one"), (100, "Line two")],
        rcon_host="127.0.0.1",
        rcon_password="test",
        rcon_port=25575,
    )
    scroller.start()
    scroller._thread.join(timeout=2)
    assert any("Line one" in c for c in commands_sent)
    assert any("Line two" in c for c in commands_sent)

def test_synced_scroller_stops_early(monkeypatch):
    commands_sent = []

    class FakeMCRcon:
        def __init__(self, host, password, port): pass
        def __enter__(self): return self
        def __exit__(self, *a): pass
        def command(self, cmd):
            commands_sent.append(cmd)
            return ""

    monkeypatch.setattr(musecraft, 'MCRcon', FakeMCRcon)
    monkeypatch.setattr(musecraft, 'get_spotify_position', lambda: 0)

    scroller = musecraft.SyncedScroller(
        timed_lines=[(50, "Line one"), (30000, "Line two")],
        rcon_host="127.0.0.1",
        rcon_password="test",
        rcon_port=25575,
    )
    scroller.start()
    import time as t; t.sleep(0.2)
    scroller.stop()
    scroller._thread.join(timeout=2)
    assert any("Line one" in c for c in commands_sent)
    assert not any("Line two" in c for c in commands_sent)
```

**Step 4: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_musecraft.py::test_synced_scroller_sends_lines -v
```
Expected: FAIL

**Step 5: Run all tests after implementation**

```bash
.venv/bin/python -m pytest tests/ -v
```
Expected: ALL PASS

**Step 6: Commit**

```bash
git add scripts/musecraft.py tests/test_musecraft.py
git commit -m "feat: SyncedScroller and get_spotify_position for timestamp-synced lyrics"
```

---

### Task 2: Wire SyncedScroller into main loop

**Files:**
- Modify: `scripts/musecraft.py`

**Step 1: Update main() to use SyncedScroller**

In `main()`:

1. Add `current_scroller = None` to the variables before the while loop.

2. In the Gemini DJ block, after fetching `lyrics_lines`, also fetch `timed_lines`:

```python
if _GEMINI_DJ_AVAILABLE and CONFIG["gemini_api_key"]:
    timed_lines = fetch_lyrics_with_timestamps(track, artist)
    lyrics_lines = [line for _, line in timed_lines]
    intro_lines = get_dj_intro(
        track=track,
        artist=artist,
        first_lines=lyrics_lines[:10],
        api_key=CONFIG["gemini_api_key"],
    )
    if intro_lines:
        # intro_lines[0] is the "Now Playing" header — send immediately
        # intro_lines[1:] are the 10 pun lines — pair with timestamps
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
            if timed_lines and pun_lines:
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
                # No timestamps — fall back to sending all lines with delay
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
```

3. In the `KeyboardInterrupt` handler, add:
```python
if current_scroller:
    current_scroller.stop()
```

**Step 2: Run all tests**

```bash
.venv/bin/python -m pytest tests/ -v
```
Expected: ALL PASS (no new tests — main() integration tested implicitly)

**Step 3: Commit and push**

```bash
git add scripts/musecraft.py
git commit -m "feat: wire SyncedScroller into main loop with timestamp-synced pun lyrics"
git push
```
