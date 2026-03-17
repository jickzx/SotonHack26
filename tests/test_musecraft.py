import importlib
import sys
import os
import types
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'scripts'))

import time
importlib.invalidate_caches()
musecraft = importlib.import_module("musecraft")
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
    assert "/genworld hiphop" in commands_sent
    assert "/weather clear" in commands_sent
    assert "/time set 6000" in commands_sent


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


def test_fetch_lyrics_returns_lines(monkeypatch):
    monkeypatch.setattr(
        musecraft,
        'syncedlyrics',
        types.SimpleNamespace(search=lambda q, **kw: "[00:01.00] Line one\n[00:05.00] Line two\n[00:09.00] Line three\n"),
    )
    lines = musecraft.fetch_lyrics("Stronger", "Kanye West")
    assert "Line one" in lines
    assert "Line two" in lines
    assert "Line three" in lines

def test_fetch_lyrics_returns_empty_on_failure(monkeypatch):
    monkeypatch.setattr(musecraft, 'syncedlyrics', types.SimpleNamespace(search=lambda q, **kw: None))
    lines = musecraft.fetch_lyrics("Unknown", "Unknown")
    assert lines == []


def test_fetch_lyrics_with_timestamps_returns_tuples(monkeypatch):
    monkeypatch.setattr(
        musecraft,
        'syncedlyrics',
        types.SimpleNamespace(search=lambda q, **kw:
            "[00:01.00] Line one\n[00:05.50] Line two\n[00:09.00] Line three\n"),
    )
    result = musecraft.fetch_lyrics_with_timestamps("Stronger", "Kanye West")
    assert len(result) == 3
    assert result[0] == (1000, "Line one")
    assert result[1] == (5500, "Line two")
    assert result[2] == (9000, "Line three")

def test_fetch_lyrics_with_timestamps_returns_empty_on_none(monkeypatch):
    monkeypatch.setattr(musecraft, 'syncedlyrics', types.SimpleNamespace(search=lambda q, **kw: None))
    result = musecraft.fetch_lyrics_with_timestamps("Unknown", "Unknown")
    assert result == []


def test_collect_intro_lyrics_falls_back_to_plain_lyrics(monkeypatch):
    monkeypatch.setattr(musecraft, 'fetch_lyrics_with_timestamps', lambda t, a: [])
    monkeypatch.setattr(musecraft, 'fetch_lyrics', lambda t, a: ["Line one", "Line two"])

    timed_lines, intro_lines = musecraft._collect_intro_lyrics("Stronger", "Kanye West")

    assert timed_lines == []
    assert intro_lines == ["Line one", "Line two"]


def test_collect_intro_lyrics_tops_up_synced_lines(monkeypatch):
    monkeypatch.setattr(
        musecraft,
        'fetch_lyrics_with_timestamps',
        lambda t, a: [(1000, "Line one"), (2000, "Line two")],
    )
    monkeypatch.setattr(
        musecraft,
        'fetch_lyrics',
        lambda t, a: ["Line one", "Line two", "Line three", "Line four"],
    )

    timed_lines, intro_lines = musecraft._collect_intro_lyrics("Stronger", "Kanye West")

    assert timed_lines == [(1000, "Line one"), (2000, "Line two")]
    assert intro_lines == ["Line one", "Line two", "Line three", "Line four"]


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

    def fake_rcon_say(host, password, port, message, timeout=5):
        commands_sent.append(message)

    monkeypatch.setattr(musecraft, '_rcon_say', fake_rcon_say)
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

    def fake_rcon_say(host, password, port, message, timeout=5):
        commands_sent.append(message)

    monkeypatch.setattr(musecraft, '_rcon_say', fake_rcon_say)
    monkeypatch.setattr(musecraft, 'get_spotify_position', lambda: 0)

    scroller = musecraft.SyncedScroller(
        timed_lines=[(50, "Line one"), (30000, "Line two")],
        rcon_host="127.0.0.1",
        rcon_password="test",
        rcon_port=25575,
    )
    scroller.start()
    time.sleep(0.2)
    scroller.stop()
    scroller._thread.join(timeout=2)
    assert any("Line one" in c for c in commands_sent)
    assert not any("Line two" in c for c in commands_sent)
