# Vanilla Structures + Visual Overhaul Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:executing-plans to implement this plan task-by-task.

**Goal:** Make each genre world visually distinct and impressive — vanilla structures (villages, mansions, strongholds, etc.) appear naturally, underground is full of geodes/dripstone/sculk, surface has genre-specific decorations, and custom structures are randomised so no two chunks look the same.

**Architecture:** Replace `FixedBiomeSource` with a new `MusicBiomeSource` that returns all 5 genre biomes from `biomeStream()` (unlocking structure sets) and the active genre's biome from `getBiome()` (controlling which variant spawns). Fix `generateFeatures()` to call `super` first so vanilla structure pieces render into blocks. Add genre-specific underground + surface decoration using `ConfiguredFeature` generation and direct block placement.

**Tech Stack:** Fabric 1.20.1, Yarn mappings 1.20.1+build.10, Java 17, Fabric API 0.83.0+1.20.1

---

## Randomisation Strategy (read before every task)

Every custom structure or decoration must use a **chunk-seeded `Random`** so results are deterministic but vary per chunk:

```java
Random rand = Random.create(chunkX * 341873128712L ^ chunkZ * 132897987541L ^ seed);
```

Never repeat the same structure at the same size/orientation every time. Use `rand` to vary:
- Height (±3–5 blocks)
- Rotation (0/90/180/270 degrees via dx/dz swap)
- Material choice (pick from a small array)
- Whether sub-elements appear (e.g. 60% chance for a detail)
- Offset within the chunk (+0–8 blocks from chunk start)

---

## Task 1: Create `MusicBiomeSource`

**Files:**
- Create: `src/main/java/com/musicworld/worldgen/MusicBiomeSource.java`

**What to build:**

```java
package com.musicworld.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.musicworld.data.WorldGenConfig;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.registry.RegistryKey;

import java.util.List;
import java.util.stream.Stream;

public class MusicBiomeSource extends BiomeSource {

    // All biomes we might ever return — must be complete so StructurePlacementCalculator
    // enables all relevant structure sets at world load.
    public static final List<RegistryKey<Biome>> ALL_GENRE_BIOMES = List.of(
        RegistryKey.of(RegistryKeys.BIOME, new Identifier("minecraft", "plains")),
        RegistryKey.of(RegistryKeys.BIOME, new Identifier("minecraft", "savanna")),
        RegistryKey.of(RegistryKeys.BIOME, new Identifier("minecraft", "dark_forest")),
        RegistryKey.of(RegistryKeys.BIOME, new Identifier("minecraft", "swamp")),
        RegistryKey.of(RegistryKeys.BIOME, new Identifier("minecraft", "deep_dark"))
    );

    public static final Codec<MusicBiomeSource> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Biome.REGISTRY_CODEC.listOf().fieldOf("biomes")
                .forGetter(s -> s.allEntries)
        ).apply(instance, MusicBiomeSource::new)
    );

    private final List<RegistryEntry<Biome>> allEntries;

    public MusicBiomeSource(List<RegistryEntry<Biome>> allEntries) {
        this.allEntries = allEntries;
    }

    @Override
    protected Codec<? extends BiomeSource> getCodec() {
        return CODEC;
    }

    @Override
    protected Stream<RegistryEntry<Biome>> biomeStream() {
        return allEntries.stream();
    }

    @Override
    public RegistryEntry<Biome> getBiome(int x, int y, int z,
            MultiNoiseUtil.MultiNoiseSampler noise) {
        // Map active genre's structureType to the biome that unlocks the right structures.
        String structureType = WorldGenConfig.getActive().structureType;
        String biomeId = switch (structureType) {
            case "PLATFORMS" -> "savanna";   // hiphop: savanna village
            case "RUINS"     -> "swamp";     // ambient: swamp hut
            case "BUILDINGS" -> "dark_forest"; // jazz: woodland mansion
            default          -> "plains";    // metal/classical/electronic/pop
        };
        RegistryKey<Biome> target = RegistryKey.of(RegistryKeys.BIOME,
            new Identifier("minecraft", biomeId));
        return allEntries.stream()
            .filter(e -> e.matchesKey(target))
            .findFirst()
            .orElse(allEntries.get(0));
    }
}
```

**Step 1: Create the file** with the code above.

**Step 2: Register in `MusicWorldMod.onInitialize()`**

Add to the top of `onInitialize()`, before the chunk generator registration:
```java
import com.musicworld.worldgen.MusicBiomeSource;
import net.minecraft.registry.Registries;

// Register custom biome source codec
Registry.register(
    Registries.BIOME_SOURCE,
    new Identifier(MOD_ID, "music_biome_source"),
    MusicBiomeSource.CODEC
);
```

**Step 3: Build and verify it compiles**
```bash
./gradlew compileJava 2>&1 | tail -20
```
Expected: no errors.

**Step 4: Commit**
```bash
git add src/main/java/com/musicworld/worldgen/MusicBiomeSource.java
git add src/main/java/com/musicworld/MusicWorldMod.java
git commit -m "feat: add MusicBiomeSource for vanilla structure unlock"
```

---

## Task 2: Wire `MusicBiomeSource` into the datapack

**Files:**
- Modify: `datapack/data/minecraft/dimension/overworld.json`

**What to change:**

Replace the current biome_source block. The `"biomes"` list must match what the codec expects — a list of biome registry entries. In 1.20.1 datapack JSON, `Biome.REGISTRY_CODEC` encodes as a string identifier:

```json
{
  "type": "minecraft:overworld",
  "generator": {
    "type": "musicworld:music_generator",
    "biome_source": {
      "type": "musicworld:music_biome_source",
      "biomes": [
        "minecraft:plains",
        "minecraft:savanna",
        "minecraft:dark_forest",
        "minecraft:swamp",
        "minecraft:deep_dark"
      ]
    }
  }
}
```

**Step 1: Edit `overworld.json`** to the JSON above.

**Step 2: Build full jar**
```bash
./gradlew build 2>&1 | tail -10
```

**Step 3: Commit**
```bash
git add datapack/data/minecraft/dimension/overworld.json
git commit -m "feat: wire MusicBiomeSource in overworld datapack"
```

---

## Task 3: Fix `generateFeatures()` — call super first

**Files:**
- Modify: `src/main/java/com/musicworld/worldgen/MusicChunkGenerator.java`

**The bug:** The current `generateFeatures()` override never calls `super.generateFeatures()`, so vanilla structure pieces (villages, mansions, etc.) are placed in `setStructureStarts` but never rendered into blocks.

**Fix:** Call `super.generateFeatures()` as the first line:

```java
@Override
public void generateFeatures(StructureWorldAccess world, Chunk chunk,
                             StructureAccessor structureAccessor) {
    // Render vanilla structure pieces (villages, mineshafts, etc.) into blocks FIRST
    super.generateFeatures(world, chunk, structureAccessor);

    GenreProfile p = WorldGenConfig.getActive();
    int chunkX = chunk.getPos().x;
    int chunkZ = chunk.getPos().z;
    int startX = chunk.getPos().getStartX();
    int startZ = chunk.getPos().getStartZ();

    Random chunkRand = Random.create(chunkX * 341873128712L ^ chunkZ * 132897987541L);

    placeVanillaTrees(world, chunk, p, chunkRand);
    placeOres(world, chunk, p, chunkRand);
    placeUndergroundFeatures(world, chunk, p, chunkRand);
    placeSurfaceDecorations(world, chunk, p, chunkRand);
    placeStructure(world, p, startX, startZ, chunkX, chunkZ, chunkRand);
}
```

Also fix `populateEntities` to call super so structure mob spawning works:
```java
@Override
public void populateEntities(ChunkRegion region) {
    super.populateEntities(region);
}
```

**Step 1: Edit `generateFeatures()` method** — add `super.generateFeatures(world, chunk, structureAccessor);` as first line, add calls to `placeUndergroundFeatures` and `placeSurfaceDecorations` (stubs for now), and fix `populateEntities`.

**Step 2: Add stub methods** (implement in later tasks):
```java
private void placeUndergroundFeatures(StructureWorldAccess world, Chunk chunk,
                                       GenreProfile p, Random rand) { }
private void placeSurfaceDecorations(StructureWorldAccess world, Chunk chunk,
                                      GenreProfile p, Random rand) { }
```

**Step 3: Build**
```bash
./gradlew build 2>&1 | tail -10
```

**Step 4: Commit**
```bash
git add src/main/java/com/musicworld/worldgen/MusicChunkGenerator.java
git commit -m "fix: call super.generateFeatures so vanilla structures render into blocks"
```

---

## Task 4: Underground features per genre

**Files:**
- Modify: `src/main/java/com/musicworld/worldgen/MusicChunkGenerator.java`

**Goal:** Fill `placeUndergroundFeatures()` with genre-appropriate underground features. Use `ConfiguredFeature` directly (bypasses the `BiomePlacementModifier` that would otherwise block non-biome features):

```java
private void placeUndergroundFeatures(StructureWorldAccess world, Chunk chunk,
                                       GenreProfile p, Random rand) {
    int startX = chunk.getPos().getStartX();
    int startZ = chunk.getPos().getStartZ();
    // Use chunk centre as origin for underground features
    BlockPos origin = new BlockPos(startX + 8, 0, startZ + 8);

    var registry = world.getRegistryManager().get(RegistryKeys.PLACED_FEATURE);

    // Helper: try to place a feature at a random underground y
    java.util.function.BiConsumer<RegistryKey<PlacedFeature>, Integer> tryPlace =
        (key, yHint) -> registry.getOrEmpty(key).ifPresent(f -> {
            BlockPos pos = new BlockPos(
                startX + rand.nextInt(16),
                yHint,
                startZ + rand.nextInt(16)
            );
            // Call the ConfiguredFeature directly, skipping placement modifiers
            f.value().feature().value().generate(
                world, this, rand, pos
            );
        });

    switch (p.structureType) {
        case "PILLARS" -> {  // metal: lava, dripstone, fossils
            if (rand.nextInt(9) == 0)
                tryPlace.accept(MiscPlacedFeatures.LAKE_LAVA_UNDERGROUND, -20 + rand.nextInt(30));
            if (rand.nextInt(3) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.DRIPSTONE_CLUSTER, -30 + rand.nextInt(20));
            if (rand.nextInt(64) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.FOSSIL_UPPER, 10 + rand.nextInt(40));
            // Dungeons for extra danger
            if (rand.nextInt(8) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.MONSTER_ROOM, 10 + rand.nextInt(40));
        }
        case "BUILDINGS" -> {  // jazz: dungeons, cave vines, lush feel
            if (rand.nextInt(8) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.MONSTER_ROOM, 10 + rand.nextInt(50));
            if (rand.nextInt(3) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.CAVE_VINES, 20 + rand.nextInt(40));
            if (rand.nextInt(4) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.GLOW_LICHEN, 10 + rand.nextInt(50));
        }
        case "COLUMNS" -> {  // classical: crystal geodes, spore blossoms, dripstone
            if (rand.nextInt(24) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.AMETHYST_GEODE, 6 + rand.nextInt(24));
            if (rand.nextInt(3) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.DRIPSTONE_CLUSTER, -20 + rand.nextInt(40));
            if (rand.nextInt(2) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.SPORE_BLOSSOM, 20 + rand.nextInt(40));
        }
        case "PLATFORMS" -> {  // hiphop: dungeons, lava pools, redstone springs
            if (rand.nextInt(8) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.MONSTER_ROOM_DEEP, -20 + rand.nextInt(30));
            if (rand.nextInt(9) == 0)
                tryPlace.accept(MiscPlacedFeatures.LAKE_LAVA_UNDERGROUND, -30 + rand.nextInt(20));
            if (rand.nextInt(4) == 0)
                tryPlace.accept(MiscPlacedFeatures.SPRING_LAVA, -10 + rand.nextInt(30));
        }
        case "NONE" -> {  // electronic: sculk, large dripstone, eerie
            if (rand.nextInt(2) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.SCULK_VEIN, -20 + rand.nextInt(30));
            if (rand.nextInt(3) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.LARGE_DRIPSTONE, -10 + rand.nextInt(40));
            if (rand.nextInt(16) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.SCULK_PATCH_DEEP_DARK, -50 + rand.nextInt(10));
        }
        case "GAZEBOS" -> {  // pop: lush, cave vines, azalea, clay
            if (rand.nextInt(3) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.CAVE_VINES, 10 + rand.nextInt(40));
            if (rand.nextInt(4) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.ROOTED_AZALEA_TREE, 10 + rand.nextInt(40));
            if (rand.nextInt(4) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.LUSH_CAVES_CLAY, 5 + rand.nextInt(30));
        }
        case "RUINS" -> {  // ambient: geodes, sculk, cave vines
            if (rand.nextInt(16) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.AMETHYST_GEODE, 6 + rand.nextInt(24));
            if (rand.nextInt(3) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.SCULK_VEIN, -30 + rand.nextInt(30));
            if (rand.nextInt(3) == 0)
                tryPlace.accept(UndergroundPlacedFeatures.CAVE_VINES, 10 + rand.nextInt(40));
        }
    }
}
```

**Imports to add:**
```java
import net.minecraft.world.gen.feature.MiscPlacedFeatures;
import net.minecraft.world.gen.feature.UndergroundPlacedFeatures;
```

**Step 1: Add imports.**

**Step 2: Replace the stub `placeUndergroundFeatures`** with the full method above.

**Step 3: Build**
```bash
./gradlew build 2>&1 | tail -20
```
If any `RegistryKey` field doesn't exist (names vary slightly), check `net.minecraft.world.gen.feature.MiscPlacedFeatures` and `UndergroundPlacedFeatures` source and substitute the correct name.

**Step 4: Commit**
```bash
git add src/main/java/com/musicworld/worldgen/MusicChunkGenerator.java
git commit -m "feat: genre-specific underground features (geodes, dripstone, sculk, dungeons)"
```

---

## Task 5: Surface decorations per genre

**Files:**
- Modify: `src/main/java/com/musicworld/worldgen/MusicChunkGenerator.java`

**Goal:** Fill `placeSurfaceDecorations()` with genre-specific surface scatter — direct block placement (no biome filter issues) + selected `PlacedFeature` keys that work on any solid surface.

```java
private void placeSurfaceDecorations(StructureWorldAccess world, Chunk chunk,
                                      GenreProfile p, Random rand) {
    int startX = chunk.getPos().getStartX();
    int startZ = chunk.getPos().getStartZ();
    BlockPos.Mutable m = new BlockPos.Mutable();

    switch (p.structureType) {
        case "PILLARS" -> {  // metal: lava drips at cliff edges, bone blocks, fire
            // Lava falls: find high columns and drip lava down the face
            for (int i = 0; i < 3; i++) {
                int wx = startX + rand.nextInt(16);
                int wz = startZ + rand.nextInt(16);
                int h = computeHeight(wx, wz, p);
                // Place lava source at cliff edge if there's a 4+ block drop nearby
                int adjacent = computeHeight(wx + 1, wz, p);
                if (h - adjacent >= 4) {
                    for (int y = adjacent + 1; y <= h; y++) {
                        m.set(wx, y, wz);
                        if (world.getBlockState(m).isAir())
                            world.setBlockState(m, Blocks.LAVA.getDefaultState(), 3);
                    }
                }
            }
            // Scattered bone blocks on surface (fossil debris)
            for (int i = 0; i < 2 + rand.nextInt(3); i++) {
                if (rand.nextInt(5) != 0) continue;
                int wx = startX + rand.nextInt(16);
                int wz = startZ + rand.nextInt(16);
                int h = computeHeight(wx, wz, p);
                m.set(wx, h, wz);
                world.setBlockState(m, Blocks.BONE_BLOCK.getDefaultState(), 3);
            }
            // Soul fire patches on gravel
            for (int i = 0; i < rand.nextInt(3); i++) {
                int wx = startX + rand.nextInt(16);
                int wz = startZ + rand.nextInt(16);
                int h = computeHeight(wx, wz, p) + 1;
                m.set(wx, h, wz);
                if (world.getBlockState(m).isAir())
                    world.setBlockState(m, Blocks.SOUL_FIRE.getDefaultState(), 3);
            }
        }
        case "BUILDINGS" -> {  // jazz: forest rocks, dead bushes, mushrooms
            // Forest rocks (mossy cobblestone boulders)
            if (rand.nextInt(6) == 0) {
                int wx = startX + rand.nextInt(16);
                int wz = startZ + rand.nextInt(16);
                int h = computeHeight(wx, wz, p);
                m.set(wx, h + 1, wz);
                world.setBlockState(m, Blocks.MOSSY_COBBLESTONE.getDefaultState(), 3);
                // Small cluster around it
                for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    if (rand.nextBoolean()) {
                        m.set(wx + d[0], computeHeight(wx + d[0], wz + d[1], p) + 1, wz + d[1]);
                        if (world.getBlockState(m).isAir())
                            world.setBlockState(m, Blocks.COBBLESTONE.getDefaultState(), 3);
                    }
                }
            }
            // Lanterns on surface
            for (int i = 0; i < rand.nextInt(2); i++) {
                int wx = startX + rand.nextInt(16);
                int wz = startZ + rand.nextInt(16);
                int h = computeHeight(wx, wz, p) + 1;
                m.set(wx, h, wz);
                if (world.getBlockState(m).isAir())
                    world.setBlockState(m, Blocks.LANTERN.getDefaultState(), 3);
            }
        }
        case "COLUMNS" -> {  // classical: flower patches, ice crystals
            // Flower scatter
            BlockState[] flowers = {Blocks.DANDELION.getDefaultState(),
                Blocks.POPPY.getDefaultState(), Blocks.BLUE_ORCHID.getDefaultState(),
                Blocks.ALLIUM.getDefaultState(), Blocks.AZURE_BLUET.getDefaultState(),
                Blocks.OXEYE_DAISY.getDefaultState()};
            for (int i = 0; i < 4 + rand.nextInt(8); i++) {
                int wx = startX + rand.nextInt(16);
                int wz = startZ + rand.nextInt(16);
                int h = computeHeight(wx, wz, p) + 1;
                m.set(wx, h, wz);
                if (world.getBlockState(m).isAir())
                    world.setBlockState(m, flowers[rand.nextInt(flowers.length)], 3);
            }
            // Occasional torch on top of quartz columns
            if (rand.nextInt(8) == 0) {
                int wx = startX + rand.nextInt(16);
                int wz = startZ + rand.nextInt(16);
                int h = computeHeight(wx, wz, p) + 1;
                m.set(wx, h, wz);
                if (world.getBlockState(m).isAir())
                    world.setBlockState(m, Blocks.TORCH.getDefaultState(), 3);
            }
        }
        case "PLATFORMS" -> {  // hiphop: dead bushes, graffiti (concrete patches)
            BlockState[] concretes = {Blocks.CYAN_CONCRETE.getDefaultState(),
                Blocks.MAGENTA_CONCRETE.getDefaultState(),
                Blocks.YELLOW_CONCRETE.getDefaultState(),
                Blocks.LIME_CONCRETE.getDefaultState()};
            for (int i = 0; i < rand.nextInt(3); i++) {
                int wx = startX + rand.nextInt(16);
                int wz = startZ + rand.nextInt(16);
                int h = computeHeight(wx, wz, p);
                m.set(wx, h, wz);
                world.setBlockState(m, concretes[rand.nextInt(concretes.length)], 3);
            }
            // Dead bushes scattered on coarse dirt
            for (int i = 0; i < 2 + rand.nextInt(4); i++) {
                if (rand.nextInt(3) != 0) continue;
                int wx = startX + rand.nextInt(16);
                int wz = startZ + rand.nextInt(16);
                int h = computeHeight(wx, wz, p) + 1;
                m.set(wx, h, wz);
                if (world.getBlockState(m).isAir())
                    world.setBlockState(m, Blocks.DEAD_BUSH.getDefaultState(), 3);
            }
        }
        case "NONE" -> {  // electronic: end rods, glowstone, chorus clusters
            // End rods as alien antennae
            for (int i = 0; i < rand.nextInt(3); i++) {
                int wx = startX + rand.nextInt(16);
                int wz = startZ + rand.nextInt(16);
                int h = computeHeight(wx, wz, p);
                int rodH = 2 + rand.nextInt(4);
                for (int y = 1; y <= rodH; y++) {
                    m.set(wx, h + y, wz);
                    world.setBlockState(m, Blocks.END_ROD.getDefaultState(), 3);
                }
            }
            // Glowstone blobs
            if (rand.nextInt(4) == 0) {
                int wx = startX + rand.nextInt(16);
                int wz = startZ + rand.nextInt(16);
                int h = computeHeight(wx, wz, p);
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (rand.nextBoolean()) continue;
                        m.set(wx + dx, h + 1, wz + dz);
                        if (world.getBlockState(m).isAir())
                            world.setBlockState(m, Blocks.GLOWSTONE.getDefaultState(), 3);
                    }
            }
        }
        case "GAZEBOS" -> {  // pop: flowers everywhere, lily pads, bee nests
            BlockState[] flowers2 = {Blocks.DANDELION.getDefaultState(),
                Blocks.POPPY.getDefaultState(), Blocks.CORNFLOWER.getDefaultState(),
                Blocks.PINK_TULIP.getDefaultState(), Blocks.WHITE_TULIP.getDefaultState(),
                Blocks.SUNFLOWER.getDefaultState()};
            for (int i = 0; i < 6 + rand.nextInt(10); i++) {
                int wx = startX + rand.nextInt(16);
                int wz = startZ + rand.nextInt(16);
                int h = computeHeight(wx, wz, p) + 1;
                m.set(wx, h, wz);
                if (world.getBlockState(m).isAir())
                    world.setBlockState(m, flowers2[rand.nextInt(flowers2.length)], 3);
            }
        }
        case "RUINS" -> {  // ambient: huge mushrooms, sculk on surface, vines
            // Huge brown mushroom (hand-placed)
            if (rand.nextInt(12) == 0) {
                int wx = startX + rand.nextInt(16);
                int wz = startZ + rand.nextInt(16);
                int h = computeHeight(wx, wz, p) + 1;
                placeHugeMushroom(world, wx, h, wz, rand);
            }
            // Surface sculk blobs
            if (rand.nextInt(6) == 0) {
                int wx = startX + rand.nextInt(16);
                int wz = startZ + rand.nextInt(16);
                int h = computeHeight(wx, wz, p);
                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++) {
                        if (rand.nextInt(3) == 0) continue;
                        m.set(wx + dx, h, wz + dz);
                        world.setBlockState(m, Blocks.SCULK.getDefaultState(), 3);
                    }
            }
        }
    }
}

/** Huge brown mushroom: stem 5-8 high, 5x5 cap */
private void placeHugeMushroom(StructureWorldAccess world, int x, int y, int z, Random rand) {
    int stemH = 5 + rand.nextInt(4);
    BlockPos.Mutable m = new BlockPos.Mutable();
    for (int dy = 0; dy < stemH; dy++) {
        m.set(x, y + dy, z);
        world.setBlockState(m, Blocks.MUSHROOM_STEM.getDefaultState(), 3);
    }
    int capY = y + stemH;
    for (int dx = -2; dx <= 2; dx++) {
        for (int dz = -2; dz <= 2; dz++) {
            if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
            m.set(x + dx, capY, z + dz);
            world.setBlockState(m, Blocks.BROWN_MUSHROOM_BLOCK.getDefaultState(), 3);
        }
    }
    // Second cap layer (slightly smaller)
    for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
            m.set(x + dx, capY + 1, z + dz);
            world.setBlockState(m, Blocks.BROWN_MUSHROOM_BLOCK.getDefaultState(), 3);
        }
    }
}
```

**Imports to add:**
```java
// (no new imports needed — all Blocks.* already available)
```

**Step 1: Replace stub `placeSurfaceDecorations`** with full method above, add `placeHugeMushroom` helper.

**Step 2: Build**
```bash
./gradlew build 2>&1 | tail -20
```

**Step 3: Commit**
```bash
git add src/main/java/com/musicworld/worldgen/MusicChunkGenerator.java
git commit -m "feat: genre-specific surface decorations (lava falls, flowers, sculk, mushrooms)"
```

---

## Task 6: Randomise existing custom structures

**Files:**
- Modify: `src/main/java/com/musicworld/worldgen/MusicChunkGenerator.java`

**Goal:** Make all 6 hand-built structures (pillars, buildings, columns, platforms, gazebos, ruins) vary so no two placements look the same. Key changes:

**PILLARS (metal):** Vary height (15–28), vary 2nd pillar offset, random chance of 2 pillars per chunk.
```java
private void buildPillar(StructureWorldAccess world, int cx, int groundY, int cz, Random rand) {
    int height = 15 + rand.nextInt(14); // was fixed 15+0-10, now 15-28
    int count = rand.nextInt(3) == 0 ? 2 : 1; // 33% chance of 2 pillars
    BlockState capBlock = rand.nextBoolean() ? Blocks.LAVA.getDefaultState()
                                             : Blocks.CRYING_OBSIDIAN.getDefaultState();
    // ... build main pillar ...
    if (count == 2) {
        int ox = cx + (rand.nextBoolean() ? 4 : -4);
        int oz = cz + (rand.nextBoolean() ? 4 : -4);
        int h2 = 10 + rand.nextInt(10);
        // ... build second shorter pillar ...
    }
}
```

**BUILDINGS (jazz):** Vary footprint (5x5 to 9x9), material (stone bricks vs mossy stone bricks), roof style (flat planks vs stair-stepped).

**COLUMNS (classical):** Vary height (8–16), column spacing (4 or 6), add chance of interior floor (40%).

**PLATFORMS (hiphop):** Vary platform size (8x8 to 12x12), height (6–12), edge material (glowstone vs sea lantern), random staircase on one side.

**GAZEBOS (pop):** Vary floor pattern (oak/birch/jungle mix), chance of second floor (25%), vary flower ring radius (2–4).

**RUINS (ambient):** Vary ruin shape (square vs L-shape), wall height (2–5), rubble scatter radius.

**Step 1:** Update each `buildX()` method to use `rand` for the variations described above. The `rand` is already passed in from `placeStructure()`; just use it more aggressively.

Key principle: every dimension that was a literal number becomes `base + rand.nextInt(range)`.

**Step 2: Build**
```bash
./gradlew build 2>&1 | tail -10
```

**Step 3: Commit**
```bash
git add src/main/java/com/musicworld/worldgen/MusicChunkGenerator.java
git commit -m "feat: randomise custom structures so each placement is unique"
```

---

## Task 7: Version bump + package deliverables

**Files:**
- Modify: `gradle.properties`
- Package: `musicworld-datapack-v7.zip`

**Step 1:** Bump `mod_version` from `5.0.0` to `6.0.0` in `gradle.properties`.

**Step 2: Build**
```bash
./gradlew build 2>&1 | tail -10
```
Expected jar: `build/libs/musicworld-6.0.0.jar`

**Step 3: Package datapack**
```bash
cd datapack && zip -r ../musicworld-datapack-v7.zip . && cd ..
```

**Step 4: Commit + push**
```bash
git add gradle.properties musicworld-datapack-v7.zip
git commit -m "v6: vanilla structures, underground features, surface decorations, randomised landmarks"
git push
```

---

## Testing Checklist (after all tasks)

Generate a new world with each genre. For each, confirm:

- [ ] **metal** — jagged terrain, obsidian pillars (varying heights), ruined portal / pillager outpost visible within 200 blocks, lava falls on cliffs, bone blocks on surface, dripstone underground
- [ ] **jazz** — rolling hills with oak trees, stone brick buildings, woodland mansion visible within 500 blocks, mossy boulders, dungeons underground
- [ ] **classical** — quartz columns (varying heights), flower fields, plains village visible within 300 blocks, amethyst geodes underground, spore blossoms in caves
- [ ] **hiphop** — acacia trees, elevated platforms (varying sizes), savanna village visible within 300 blocks, coloured concrete scatter, dungeons underground
- [ ] **electronic** — alien purple/end terrain, end rod antennae, no structures except stronghold (deep), sculk underground, glowstone blobs
- [ ] **pop** — birch trees, gazebos (varying), plains village visible, flower explosion on surface
- [ ] **ambient** — mossy terrain, varied ruins, swamp hut visible, huge mushrooms, sculk+geodes underground

Use `/locate structure minecraft:village` in-game to confirm structures are registering.

---

## Notes

- `super.generateFeatures()` can throw if the biome source can't resolve features for the biome. If this happens, wrap in try/catch and log — do not crash.
- `ConfiguredFeature.generate()` is the safe bypass for underground features — it skips `BiomePlacementModifier` so the feature always attempts to generate regardless of biome.
- Floating islands (metal + electronic) are a stretch goal — skip if the above tasks already look impressive enough.
- Ancient city for ambient is a stretch goal — deep_dark biome + ancient city structure may conflict with custom cave carving.
