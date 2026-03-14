# Biome Music Mod — Setup Guide

## Prerequisites
- Java 21 (JDK): https://adoptium.net/
- IntelliJ IDEA (Community is free): https://www.jetbrains.com/idea/

---

## Step 1 — Add Your Music Files

Convert your songs to .ogg format using ffmpeg:
  ffmpeg -i mysong.mp3 -c:a libvorbis -q:a 4 track1.ogg

Place them in the correct folders:
  src/main/resources/assets/biomemusic/sounds/country/track1.ogg
  src/main/resources/assets/biomemusic/sounds/country/track2.ogg
  src/main/resources/assets/biomemusic/sounds/indipop/track1.ogg
  src/main/resources/assets/biomemusic/sounds/metal/track1.ogg
  src/main/resources/assets/biomemusic/sounds/ambient/track1.ogg
  src/main/resources/assets/biomemusic/sounds/default/track1.ogg

---

## Step 2 — Open in IntelliJ

1. Open IntelliJ IDEA
2. Click "Open" and select this biomemusic/ folder
3. Wait for Gradle to sync (bottom progress bar)
4. Run the Gradle task: gradlew genSources (sets up Minecraft mappings)

---

## Step 3 — Build the Mod

In IntelliJ's terminal (or your system terminal inside the biomemusic/ folder):
  gradlew build

Your .jar file will appear at:
  build/libs/biomemusic-1.0.0.jar

---

## Step 4 — Install the Mod

1. Install Fabric Loader for Minecraft 1.21.4:
   https://fabricmc.net/use/installer/

2. Copy your .jar into your mods folder:
   Windows: %appdata%\.minecraft\mods\
   Mac:     ~/Library/Application Support/minecraft/mods/
   Linux:   ~/.minecraft/mods/

3. Also copy fabric-api into that same mods folder:
   https://modrinth.com/mod/fabric-api

4. Launch Minecraft with the Fabric 1.21.4 profile

---

## Adding More Biomes / Genres

Edit BiomeMusicHandler.java:

  // Add a new genre
  GENRE_TRACKS.put("jazz", new String[]{
      "biomemusic:jazz.track1"
  });

  // Map it to a biome
  BIOME_GENRE_MAP.put(BiomeKeys.MUSHROOM_FIELDS, "jazz");

Then add the matching entry in sounds.json and drop the .ogg file in the sounds folder.

---

## Biome → Genre Map (default)

  pale_garden       → country
  cherry_grove      → indipop (beabadoobee / Laufey vibes)
  deep_dark         → ambient
  nether_wastes     → metal
  soul_sand_valley  → metal
  crimson_forest    → metal
  warped_forest     → metal
  basalt_deltas     → metal
  everything else   → default
