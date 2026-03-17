# Spotify → Minecraft Genre Auto-Switching — Design

**Date:** 2026-03-14

## Goal

Automatically run `/genworld <genre>` in Minecraft when the user switches to a song matching one of the 7 mod genres (metal, jazz, classical, hiphop, electronic, pop, ambient).

## Architecture

Standalone Python script (`scripts/musecraft.py`) running on Mac alongside Spotify and Minecraft. No dependency on the mod itself. Polls every 20 seconds and fires RCON only when genre changes.

## Components

| Component | Responsibility |
|---|---|
| `spotify_reader` | osascript reads current track name + artist from Spotify desktop app |
| `genre_detector` | Last.fm `track.getTopTags` → keyword match to one of 7 genres |
| `rcon_sender` | Sends `/genworld <genre>` via mcrcon to local Minecraft server |
| `poll_loop` | 20s interval, only fires RCON on genre change |

## Data Flow

```
[Spotify desktop] --osascript--> track + artist
    --> Last.fm API (track.getTopTags)
    --> keyword match --> genre (or None)
    --> if genre changed: mcrcon --> /genworld <genre>
```

If Last.fm returns no matching genre, keep last known genre and do nothing.

## Genre Keyword Map

| Genre | Keywords |
|---|---|
| metal | metal, heavy, thrash, doom, hardcore, punk |
| jazz | jazz, bebop, swing, blues, soul, funk, bossa |
| classical | classical, orchestra, symphony, opera, baroque, piano |
| hiphop | hip hop, hiphop, rap, trap, drill |
| electronic | electronic, techno, house, edm, drum and bass, dubstep, synth |
| pop | pop, indie pop, dance pop, k-pop |
| ambient | ambient, chill, lofi, meditation, new age, drone |

## Configuration

Env vars (or edit top of script):
- `LASTFM_API_KEY` — free key from last.fm/api
- `RCON_PASSWORD` — matches server.properties
- `RCON_PORT` — default 25575

## Setup (one-time)

1. `pip install mcrcon`
2. Get free Last.fm API key at last.fm/api
3. Add to `server.properties`: `enable-rcon=true`, `rcon.port=25575`, `rcon.password=yourpassword`
4. Set env vars, run `python scripts/musecraft.py`

## Constraints

- Mac only (osascript)
- Works for popular songs — Last.fm tags sparse for obscure artists (acceptable)
- Spotify desktop app must be running
- Minecraft server must have RCON enabled

## Out of Scope (v1)

- Weather/time-of-day changes (add later once base works)
- Playlist name matching
- Spotify OAuth / Web API
