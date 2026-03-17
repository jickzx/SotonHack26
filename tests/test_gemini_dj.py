import importlib
import sys
import os
import types
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'scripts'))

gemini_dj = importlib.import_module("gemini_dj")


def test_get_dj_intro_returns_eleven_lines(monkeypatch):
    class FakeModels:
        def generate_content(self, model, contents):
            class Resp:
                text = '\n'.join([
                    '♪ Now Playing: "Creeper Kingdom" (Stronger - Kanye West)',
                    '♪ Line 1', '♪ Line 2', '♪ Line 3', '♪ Line 4',
                    '♪ Line 5', '♪ Line 6', '♪ Line 7', '♪ Line 8',
                    '♪ Line 9', '♪ Line 10',
                ])
            return Resp()

    class FakeClient:
        def __init__(self, api_key): self.models = FakeModels()

    monkeypatch.setattr(gemini_dj, 'genai', types.SimpleNamespace(Client=FakeClient))

    lines = gemini_dj.get_dj_intro(
        track="Stronger",
        artist="Kanye West",
        first_lines=["Line " + str(i) for i in range(10)],
        api_key="testkey",
    )
    assert lines is not None
    assert len(lines) == 11
    assert "Now Playing" in lines[0] or "Creeper" in lines[0]


def test_get_dj_intro_matches_available_lyrics(monkeypatch):
    class FakeModels:
        def generate_content(self, model, contents):
            class Resp:
                text = '\n'.join([
                    '♪ Now Playing: "Creeper Kingdom" (Stronger - Kanye West)',
                    '♪ Line 1',
                    '♪ Line 2',
                    '♪ Line 3',
                ])
            return Resp()

    class FakeClient:
        def __init__(self, api_key): self.models = FakeModels()

    monkeypatch.setattr(gemini_dj, 'genai', types.SimpleNamespace(Client=FakeClient))

    lines = gemini_dj.get_dj_intro(
        track="Stronger",
        artist="Kanye West",
        first_lines=["Line 1", "Line 2", "Line 3"],
        api_key="testkey",
    )
    assert lines == [
        '♪ Now Playing: "Creeper Kingdom" (Stronger - Kanye West)',
        '♪ Line 1',
        '♪ Line 2',
        '♪ Line 3',
    ]


def test_get_dj_intro_parses_numbered_output(monkeypatch):
    class FakeModels:
        def generate_content(self, model, contents):
            class Resp:
                text = '\n'.join([
                    'Here you go:',
                    '1. Now Playing: "Creeper Kingdom" (Stronger - Kanye West)',
                    '2. Built it stronger',
                    '3. Mine it faster',
                    '4. Smelt it better',
                ])
            return Resp()

    class FakeClient:
        def __init__(self, api_key): self.models = FakeModels()

    monkeypatch.setattr(gemini_dj, 'genai', types.SimpleNamespace(Client=FakeClient))

    lines = gemini_dj.get_dj_intro(
        track="Stronger",
        artist="Kanye West",
        first_lines=["Line 1", "Line 2", "Line 3"],
        api_key="testkey",
    )
    assert lines == [
        '♪ Now Playing: "Creeper Kingdom" (Stronger - Kanye West)',
        '♪ Built it stronger',
        '♪ Mine it faster',
        '♪ Smelt it better',
    ]


def test_get_dj_intro_returns_none_on_failure(monkeypatch):
    class RaisingClient:
        def __init__(self, api_key):
            raise Exception("API error")

    monkeypatch.setattr(gemini_dj, 'genai', types.SimpleNamespace(Client=RaisingClient))

    lines = gemini_dj.get_dj_intro(
        track="Stronger",
        artist="Kanye West",
        first_lines=["Work it harder"],
        api_key="testkey",
    )
    assert lines is None


def test_get_dj_intro_returns_none_without_api_key():
    lines = gemini_dj.get_dj_intro(
        track="Stronger",
        artist="Kanye West",
        first_lines=["Work it harder"],
        api_key="",
    )
    assert lines is None


def test_get_dj_intro_returns_none_without_lyrics(monkeypatch):
    lines = gemini_dj.get_dj_intro(
        track="Stronger",
        artist="Kanye West",
        first_lines=[],
        api_key="testkey",
    )
    assert lines is None
