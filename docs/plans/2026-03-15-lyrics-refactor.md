# Lyrics Refactor Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:executing-plans to implement this plan task-by-task.

**Goal:** Replace Genius API lyrics (broken) with `syncedlyrics`, remove the full-lyrics scroller, and have Gemini DJ send its 4 pun lines one at a time with 1.5s delay.

**Architecture:** Two changes to `scripts/musecraft.py` (swap fetch_lyrics, remove LyricScroller), one change to `scripts/gemini_dj.py` (add delay between lines), cleanup of config/README. `syncedlyrics` needs no API key.

**Tech Stack:** Python 3, `syncedlyrics` (pip install syncedlyrics), existing mcrcon/RCON, existing Gemini DJ module.

---

### Task 0: Swap fetch_lyrics to syncedlyrics, remove LyricScroller

**Files:**
- Modify: `scripts/musecraft.py`
- Modify: `tests/test_musecraft.py`

**Step 1: Install syncedlyrics**

```bash
.venv/bin/pip install syncedlyrics
pip install syncedlyrics
```

**Step 2: Write failing test for new fetch_lyrics**

In `tests/test_musecraft.py`, replace `test_fetch_lyrics_returns_lines` and `test_fetch_lyrics_returns_empty_on_failure` with:

```python
def test_fetch_lyrics_returns_lines(monkeypatch):
    import syncedlyrics
    monkeypatch.setattr(syncedlyrics, 'search', lambda q, **kw: "[00:01.00] Line one\n[00:05.00] Line two\n[00:09.00] Line three\n")
    lines = musecraft.fetch_lyrics("Stronger", "Kanye West")
    assert "Line one" in lines
    assert "Line two" in lines
    assert "Line three" in lines

def test_fetch_lyrics_returns_empty_on_failure(monkeypatch):
    import syncedlyrics
    monkeypatch.setattr(syncedlyrics, 'search', lambda q, **kw: None)
    lines = musecraft.fetch_lyrics("Unknown", "Unknown")
    assert lines == []
```

**Step 3: Run to verify failure**

```bash
.venv/bin/python -m pytest tests/test_musecraft.py::test_fetch_lyrics_returns_lines -v
```
Expected: FAIL

**Step 4: Update `fetch_lyrics` in `scripts/musecraft.py`**

Replace the existing `fetch_lyrics` function with:

```python
def fetch_lyrics(track, artist):
    """
    Fetch lyrics via syncedlyrics (no API key needed).
    Returns list of non-empty lyric lines with timestamps stripped, or [] on failure.
    """
    try:
        import syncedlyrics
        import re
        lrc = syncedlyrics.search(f"{track} {artist}", allow_plain_format=True)
        if not lrc:
            return []
        lines = []
        for line in lrc.split('\n'):
            # Strip LRC timestamps like [00:01.23]
            stripped = re.sub(r'\[\d+:\d+\.\d+\]', '', line).strip()
            if stripped:
                lines.append(stripped)
        return lines
    except Exception:
        return []
```

**Step 5: Remove LyricScroller class and all usage**

In `scripts/musecraft.py`:
- Delete the entire `LyricScroller` class
- Remove `import threading` (only used by LyricScroller)
- Remove `ENABLE_LYRICS` constant
- Remove `genius_api_key` from `CONFIG`
- In `main()`, remove:
  - The `current_scroller = None` variable
  - The `if current_scroller: current_scroller.stop()` block
  - The entire lyric scroller block (`if ENABLE_LYRICS and CONFIG["genius_api_key"]:`)
- Update the `lyrics_lines` fetch to call `fetch_lyrics(track, artist)` (no api_key arg)

**Step 6: Update all fetch_lyrics call sites**

The only remaining call to `fetch_lyrics` is in the Gemini DJ block in `main()`:
```python
lyrics_lines = fetch_lyrics(track, artist)
```
(Remove the `if CONFIG["genius_api_key"] else []` guard — no longer needed.)

**Step 7: Run all tests**

```bash
.venv/bin/python -m pytest tests/ -v
```
Expected: ALL PASS. Tests for `test_lyric_scroller_sends_lines` and `test_lyric_scroller_stops_early` must be deleted since LyricScroller is gone.

**Step 8: Commit**

```bash
git add scripts/musecraft.py tests/test_musecraft.py
git commit -m "feat: swap Genius lyrics for syncedlyrics, remove LyricScroller"
```

---

### Task 1: Add 1.5s delay between Gemini DJ lines

**Files:**
- Modify: `scripts/gemini_dj.py`
- Modify: `scripts/musecraft.py`

**Step 1: Update `gemini_dj.py` — remove RCON sending, just return lines**

`gemini_dj.py` currently only returns lines — RCON sending is in `musecraft.py`. No change needed to `gemini_dj.py` itself.

**Step 2: Add delay in `musecraft.py` DJ intro block**

In the DJ intro RCON block in `main()`, add `time.sleep(1.5)` between each line:

```python
with MCRcon(CONFIG["rcon_host"], CONFIG["rcon_password"],
            port=CONFIG["rcon_port"]) as mcr:
    for line in intro_lines:
        mcr.command(f"/say {line}")
        time.sleep(1.5)
```

**Step 3: Write failing test**

In `tests/test_musecraft.py`, add:

```python
def test_gemini_dj_lines_sent_with_delay(monkeypatch):
    import time
    sleep_calls = []
    monkeypatch.setattr(time, 'sleep', lambda s: sleep_calls.append(s))

    commands_sent = []
    class FakeMCRcon:
        def __init__(self, host, password, port): pass
        def __enter__(self): return self
        def __exit__(self, *a): pass
        def command(self, cmd):
            commands_sent.append(cmd)
            return ""

    monkeypatch.setattr(musecraft, 'MCRcon', FakeMCRcon)
    monkeypatch.setattr(musecraft, '_GEMINI_DJ_AVAILABLE', True)
    monkeypatch.setattr(musecraft, 'get_dj_intro', lambda **kw: [
        "♪ Now Playing: \"Creeper\" (Test - Artist)",
        "♪ Line one",
        "♪ Line two",
        "♪ Line three",
    ])
    monkeypatch.setattr(musecraft, 'fetch_lyrics', lambda t, a: ["Line one", "Line two", "Line three"])

    musecraft.CONFIG["gemini_api_key"] = "testkey"
    musecraft.CONFIG["rcon_password"] = "test"

    # Call the DJ intro block directly via a minimal main() simulation
    intro_lines = musecraft.get_dj_intro(
        track="Test", artist="Artist", first_lines=["Line one", "Line two", "Line three"],
        api_key="testkey"
    )
    if intro_lines:
        with FakeMCRcon("127.0.0.1", "test", 25575) as mcr:
            for line in intro_lines:
                mcr.command(f"/say {line}")
                time.sleep(1.5)

    assert len(commands_sent) == 4
    assert sleep_calls.count(1.5) == 4
```

Note: this test directly exercises the pattern, not main() itself.

**Step 4: Run to verify failure (should pass after Step 2)**

```bash
.venv/bin/python -m pytest tests/test_musecraft.py::test_gemini_dj_lines_sent_with_delay -v
```

**Step 5: Run all tests**

```bash
.venv/bin/python -m pytest tests/ -v
```
Expected: ALL PASS

**Step 6: Commit**

```bash
git add scripts/musecraft.py tests/test_musecraft.py
git commit -m "feat: send Gemini DJ lines with 1.5s delay between each"
```

---

### Task 2: Clean up config and README

**Files:**
- Modify: `README.md`

**Step 1: Remove Genius API key from README**

In `README.md`, remove `GENIUS_API_KEY=your_genius_key` from the `.env` example and remove the "Get a free Genius API key" note.

**Step 2: Remove ENABLE_LYRICS mention from README**

Remove the `Set ENABLE_LYRICS = False` line from README since that flag no longer exists.

**Step 3: Update syncedlyrics install instruction**

In the Spotify integration setup section, replace `pip install mcrcon` with:
```bash
pip install mcrcon syncedlyrics
```

**Step 4: Commit and push**

```bash
git add README.md
git commit -m "docs: remove Genius API references, add syncedlyrics to install"
git push
```
