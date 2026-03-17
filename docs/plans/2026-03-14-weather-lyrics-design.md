# Weather + Lyrics Design

**Date:** 2026-03-14

## Feature 1: Reactive weather + time of day

Pure RCON additions to `scripts/musecraft.py`. No mod or datapack changes needed.

When genre changes, fire three RCON commands in sequence after `/genworld`:

| Genre | Weather | Time |
|---|---|---|
| metal | thunder | night (18000) |
| jazz | clear | dawn (0) |
| classical | clear | day (6000) |
| hiphop | clear | day (6000) |
| electronic | clear | midnight (18000) |
| pop | clear | day (6000) |
| ambient | (no change) | (no change) |

**Implementation:** Add `GENRE_ATMOSPHERE` dict to `musecraft.py`. In `send_genworld()`, after firing `/genworld`, fire `/weather <type>` and `/time set <value>` if defined for that genre.

## Feature 2: Lyrics in chat

Lyrics scroll in Minecraft chat in near-real-time as the song plays.

### Architecture

- `lyricsgenius` library fetches lyrics from Genius API when track changes
- Lyrics parsed into lines, stripped of section headers (`[Verse 1]` etc.)
- Duration estimated from Spotify track length via osascript
- Interval = `duration / line_count` seconds per line
- Background thread scrolls one line at a time via RCON `/say ♪ <line>`
- When track changes, current thread is cancelled via a `threading.Event` stop signal
- Feature gated by `ENABLE_LYRICS = True` at top of script — set to `False` to disable entirely

### Config

```
GENIUS_API_KEY=your_genius_key   # in .env
ENABLE_LYRICS = True              # in script
```

### Timing note

Genius provides no timestamps — interval is estimated from song duration. Accuracy varies. Designed to be easily disabled if too distracting.

## Out of scope

- Timestamped lyrics (future improvement)
- Gemini integration (future)
- Mod-side floating text
