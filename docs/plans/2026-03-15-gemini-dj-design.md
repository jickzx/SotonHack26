# Gemini DJ Design

**Date:** 2026-03-15

## Feature: Minecraft Pun DJ Intro

On each track change, Gemini rewrites the song title as a Minecraft pun and rewrites the first 3 lyric lines with Minecraft references. Sent to Minecraft chat immediately when a new track starts, before the regular lyric scroll.

## Architecture

**New file:** `scripts/gemini_dj.py` — fully self-contained. Deleted = feature gone.

**Integration in `musecraft.py`:**
```python
try:
    from gemini_dj import get_dj_intro
    GEMINI_ENABLED = True
except ImportError:
    GEMINI_ENABLED = False
```

On track change, if `GEMINI_ENABLED` and lyrics available:
```python
intro_lines = get_dj_intro(track, artist, lyrics[:3])
if intro_lines:
    for line in intro_lines:
        mcr.command(f"/say {line}")
```

## `gemini_dj.py`

- `get_dj_intro(track, artist, first_lines) -> list[str] | None`
- Uses `google-generativeai` SDK (`pip install google-generativeai`)
- Reads `GEMINI_API_KEY` from env — returns `None` silently if missing
- One API call per track change
- Returns 4 lines:
  1. `♪ Now Playing: "<Minecraft pun title>" (<original> - <artist>)`
  2-4. First 3 lyrics rewritten with Minecraft references

## Output Example

```
♪ Now Playing: "Creeper Kingdom" (Stronger - Kanye West)
♪ Work it, mine it, dig it, craft it
♪ More than ever, hour after hour
♪ Work is never over (till the Endermen come)
```

## Config

`GEMINI_API_KEY` in `.env` — loaded automatically by musecraft's existing .env loader.

## Reversibility

Delete `scripts/gemini_dj.py` → feature is completely gone. No logic changes in `musecraft.py` beyond the try/except import block.

## Out of Scope

- Gemini rewriting all lyrics (just first 3)
- Caching responses
- Streaming output
