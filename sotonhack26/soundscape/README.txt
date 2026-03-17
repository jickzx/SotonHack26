╔══════════════════════════════════════════════════════╗
║              SOUNDSCAPE — Setup Guide                ║
║     Music → Minecraft Seed + Biome Music Mod         ║
╚══════════════════════════════════════════════════════╝

The full experience has two parts that work together:

  1. SEED GENERATOR (seed-generator/)
     A web app that takes a Spotify song link and generates
     a unique Minecraft world seed based on the song's audio DNA.

  2. FABRIC MOD (fabric-mod/)
     A Minecraft mod that plays genre-specific music in-game
     based on which biome you're currently in — matching the
     world profile predicted by the seed generator.

─────────────────────────────────────────────────────────
PART 1 — SEED GENERATOR SETUP
─────────────────────────────────────────────────────────

Requirements:
  - Python 3.10 or newer: https://www.python.org/downloads/

Step 1: Get Spotify API credentials (free)
  a. Go to https://developer.spotify.com/dashboard
  b. Log in and click "Create App"
  c. Fill in any name/description, set redirect URI to http://localhost
  d. Copy your Client ID and Client Secret

Step 2: Set up credentials
  a. Open seed-generator/
  b. Rename .env.example → .env
  c. Paste your Client ID and Client Secret into .env

Step 3: Install dependencies
  Open a terminal in `seed-generator/` and create a virtual environment, then install dependencies:
    python3 -m venv .venv
    source .venv/bin/activate
    pip install -r requirements.txt
  (On macOS you can also use the Homebrew Python executable: `/opt/homebrew/bin/python3`.)

Step 4: Run the web app
    python main.py

Step 5: Open in browser
    http://localhost:5000

  Paste any Spotify track link → click Generate →
  copy the seed number shown on screen.

─────────────────────────────────────────────────────────
PART 2 — FABRIC MOD SETUP
─────────────────────────────────────────────────────────

Requirements:
  - Java 21 JDK: https://adoptium.net/
  - IntelliJ IDEA (free Community edition):
    https://www.jetbrains.com/idea/download/

Step 1: Add your music files
  Songs must be in OGG Vorbis format.
  Convert with ffmpeg (free):
    ffmpeg -i mysong.mp3 -c:a libvorbis -q:a 4 track1.ogg

  Place files in the correct genre folders:
    fabric-mod/src/main/resources/assets/soundscape/sounds/
      country/track1.ogg      ← calm, twangy
      country/track2.ogg
      indipop/track1.ogg      ← beabadoobee / Laufey style
      indipop/track2.ogg
      metal/track1.ogg        ← rock / heavy metal
      metal/track2.ogg
      ambient/track1.ogg      ← atmospheric / eerie
      ambient/track2.ogg
      electronic/track1.ogg   ← synth / dance
      electronic/track2.ogg
      jazz/track1.ogg         ← upbeat / bright
      jazz/track2.ogg
      default/track1.ogg      ← fallback for unassigned biomes

Step 2: Open in IntelliJ
  1. Open IntelliJ IDEA
  2. Click "Open" and select the fabric-mod/ folder
  3. Wait for Gradle to finish syncing (bottom progress bar)
  4. In the terminal, run: gradlew genSources

Step 3: Build the mod
  In the terminal inside fabric-mod/:
    gradlew build

  Output: fabric-mod/build/libs/soundscape-1.0.0.jar

Step 4: Install Fabric + the mod
  1. Install Fabric Loader for Minecraft 1.21.4:
     https://fabricmc.net/use/installer/

  2. Download Fabric API for 1.21.4:
     https://modrinth.com/mod/fabric-api

  3. Copy both .jar files to your mods folder:
       Windows: %appdata%\.minecraft\mods\
       Mac:     ~/Library/Application Support/minecraft/mods/
       Linux:   ~/.minecraft/mods/

  4. Launch Minecraft with the Fabric 1.21.4 profile

─────────────────────────────────────────────────────────
THE FULL SOUNDSCAPE EXPERIENCE
─────────────────────────────────────────────────────────

  1. Open the web app → paste a Spotify link
  2. Get your world seed + see the predicted world profile
  3. Copy the seed → paste it into Minecraft world creation
  4. Explore — the mod plays genre music matching each biome
  5. The same song always makes the same world. Every time.

─────────────────────────────────────────────────────────
BIOME → GENRE REFERENCE
─────────────────────────────────────────────────────────

  Genre        Biomes
  ─────────── ──────────────────────────────────────────
  Metal        Nether (all), Deep Dark
  Indie Pop    Cherry Grove, Pale Garden
  Country      Windswept Hills, Peaks, Snowy Slopes
  Ambient      Dark Forest, Swamp, Old Growth, Caves, End
  Electronic   Badlands, Savanna, Desert
  Jazz         Meadow, Flower Forest, Jungle, Beach
  Default      Plains, Forest, Taiga, Ocean, River, etc.
