import importlib
import re

try:
    genai = importlib.import_module("google.genai")
except ImportError:
    genai = None


PROMPT_TEMPLATE = """\
You are a witty Minecraft DJ. Given a song, produce exactly {expected_count} lines:
1. A "Now Playing" line with the song title rewritten as a Minecraft pun, formatted as:
   ♪ Now Playing: "<Minecraft pun title>" ({track} - {artist})
2-{last_line_number}. Rewrite each of the {lyric_count} lyric lines below with Minecraft references
   (mobs, blocks, items, biomes). Keep the same order. Each line starts with ♪

Original title: {track} by {artist}
Lyrics to rewrite:
{lyrics}

Output exactly {expected_count} lines, nothing else."""


def _extract_output_lines(text, expected_count):
    lines = []
    started = False

    for raw_line in text.replace("♪", "\n♪").splitlines():
        original = raw_line.strip()
        if not original or original.startswith("```"):
            continue

        cleaned = re.sub(r"^\s*(?:[-*]|\d+[.)])\s*", "", original).strip()
        if not cleaned:
            continue

        looks_like_output = (
            cleaned.startswith("♪")
            or "Now Playing" in cleaned
            or cleaned != original
        )
        if not looks_like_output and not started:
            continue

        started = True
        if not cleaned.startswith("♪"):
            cleaned = f"♪ {cleaned.lstrip('♪ ').strip()}"

        lines.append(cleaned)
        if len(lines) >= expected_count:
            break

    return lines if len(lines) >= expected_count else None


def get_dj_intro(track, artist, first_lines, api_key):
    """
    Returns list of chat lines (pun title + rewritten lyrics), or None on failure.
    """
    lyric_lines = [line.strip() for line in first_lines[:10] if line and line.strip()]

    if not api_key or genai is None or not lyric_lines:
        return None

    try:
        client = genai.Client(api_key=api_key)
        expected_count = len(lyric_lines) + 1
        prompt = PROMPT_TEMPLATE.format(
            track=track,
            artist=artist,
            lyric_count=len(lyric_lines),
            last_line_number=expected_count,
            expected_count=expected_count,
            lyrics="\n".join(lyric_lines),
        )
        response = client.models.generate_content(model="gemini-2.5-flash", contents=prompt)

        try:
            response_text = response.text or ""
        except Exception:
            response_text = ""

        parsed_lines = _extract_output_lines(response_text, expected_count)
        return parsed_lines
    except Exception as e:
        print(f"  Gemini DJ error: {e}")
        return None
