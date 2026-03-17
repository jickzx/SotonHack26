# MusicWorld

A Minecraft Fabric mod that generates custom terrain based on music genres. Run `/genworld <genre>` to switch the terrain style for newly generated chunks. Optionally, use the Spotify integration to switch genres automatically based on what you're listening to.

**Genres:** metal, jazz, classical, hiphop, electronic, pop, ambient

---

## Setup

### 1. Minecraft client (CurseForge)

1. Open CurseForge, go to Minecraft → Create Custom Profile
2. Select version **1.20.1**, loader **Fabric**
3. Once created, right-click the instance → **Open Folder**
4. Drop both jars into the `mods/` folder:
   - `musicworld-6.0.0.jar`
   - `fabric-api-0.92.7+1.20.1.jar` (download from modrinth.com/mod/fabric-api/versions?g=1.20.1)
5. Launch the instance

### 2. Minecraft server (local)

**Requirements:** Java 17 ([adoptium.net](https://adoptium.net))

1. Create a server folder, e.g. `~/minecraft-server/`
2. Download the Fabric server launcher for 1.20.1 / loader 0.16.10:
   ```bash
   curl -L "https://meta.fabricmc.net/v2/versions/loader/1.20.1/0.16.10/1.1.1/server/jar" -o ~/minecraft-server/fabric-server.jar
   ```
3. Run it once to generate files:
   ```bash
   cd ~/minecraft-server
   /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java -jar fabric-server.jar nogui
   ```
4. Accept the EULA:
   ```bash
   echo "eula=true" > ~/minecraft-server/eula.txt
   ```
5. Create a `mods/` folder and add the jars:
   ```bash
   mkdir -p ~/minecraft-server/mods
   cp musicworld-6.0.0.jar ~/minecraft-server/mods/
   cp fabric-api-0.92.7+1.20.1.jar ~/minecraft-server/mods/
   ```
6. Add the datapack:
   ```bash
   mkdir -p ~/minecraft-server/world/datapacks
   cp musicworld-datapack-v7.zip ~/minecraft-server/world/datapacks/
   ```
7. Edit `~/minecraft-server/server.properties` and add:
   ```
   enable-rcon=true
   rcon.port=25575
   rcon.password=yourpassword
   gamemode=creative
   ```
8. Start the server:
   ```bash
   /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java -jar ~/minecraft-server/fabric-server.jar nogui
   ```
9. Connect in Minecraft: Multiplayer → Add Server → `localhost`
10. Once in-game, run `/reload` then `/genworld metal` (or any genre) and explore new chunks

---

## Spotify integration

Automatically runs `/genworld <genre>` when the genre of your current Spotify track changes.

**Requirements:**
- macOS (uses osascript to read Spotify)
- Python 3
- Spotify desktop app
- A running Minecraft server with RCON enabled (see above)

### Setup

1. Install dependencies:
   ```bash
   pip install mcrcon syncedlyrics google-genai
   ```
2. Get a free Last.fm API key at [last.fm/api](https://www.last.fm/api) (takes 2 minutes)
3. Create a `.env` file in the repo root:
   ```
   LASTFM_API_KEY=your_lastfm_key
   RCON_PASSWORD=yourpassword
   ```
4. Start the script:
   ```bash
   python3 scripts/musecraft.py
   ```

The script polls every 20 seconds. When it detects a genre change it fires `/genworld <genre>` automatically. Works best with popular artists — obscure tracks with no Last.fm tags will keep the current genre.


### Gemini DJ (optional)

On each track change, Gemini rewrites the song title as a Minecraft pun and the available lyric lines with Minecraft references, then sends them to chat. If synced lyrics are available, the pun lines scroll in time with the song; otherwise the script falls back to plain lyrics and sends them with a short delay.

1. Get a free Gemini API key at [aistudio.google.com](https://aistudio.google.com)
2. Install the SDK if you did not already: `pip install google-genai`
3. Add to `.env`:
   ```
   GEMINI_API_KEY=your_gemini_key
   ```

To disable: delete `scripts/gemini_dj.py`.

---

## Building the mod

```bash
./gradlew build
```

Output: `build/libs/musicworld-6.0.0.jar`
