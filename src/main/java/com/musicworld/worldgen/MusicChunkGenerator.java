package com.musicworld.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.musicworld.data.GenreProfile;
import com.musicworld.data.WorldGenConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.gen.feature.MiscPlacedFeatures;
import net.minecraft.world.gen.feature.OrePlacedFeatures;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.minecraft.world.gen.feature.UndergroundPlacedFeatures;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class MusicChunkGenerator extends ChunkGenerator {

    // -------------------------------------------------------------------------
    // CODEC
    // -------------------------------------------------------------------------

    public static final Codec<MusicChunkGenerator> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource)
            ).apply(instance, MusicChunkGenerator::new)
    );

    public MusicChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected Codec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    // -------------------------------------------------------------------------
    // World dimensions
    // -------------------------------------------------------------------------

    @Override
    public int getMinimumY() {
        return -64;
    }

    @Override
    public int getSeaLevel() {
        return WorldGenConfig.getActive().waterLevel;
    }

    @Override
    public int getWorldHeight() {
        return 384;
    }

    // -------------------------------------------------------------------------
    // Terrain height helpers
    // -------------------------------------------------------------------------

    private int computeHeight(int x, int z, GenreProfile p) {
        int switchChunkX = WorldGenConfig.getSwitchChunkX();

        // If no switch has happened yet, just use the active profile directly
        if (switchChunkX == Integer.MIN_VALUE) return computeHeightForProfile(x, z, p);

        // Blend terrain parameters between previous and active profile (radial from switch point)
        int switchChunkZ = WorldGenConfig.getSwitchChunkZ();
        GenreProfile prev = WorldGenConfig.getPrevious();
        double roughness = TerrainBlend.blendParam(prev.terrainRoughness,  p.terrainRoughness,  x, z, switchChunkX, switchChunkZ);
        double baseH     = TerrainBlend.blendParam(prev.baseHeight,        p.baseHeight,        x, z, switchChunkX, switchChunkZ);
        double mtnFreq   = TerrainBlend.blendParam(prev.mountainFrequency, p.mountainFrequency, x, z, switchChunkX, switchChunkZ);

        double s1 = OpenSimplex2S.noise2(42L, x * 0.005 * roughness, z * 0.005 * roughness)
                * roughness * 40.0;
        double s2 = OpenSimplex2S.noise2(99L, x * 0.02 * roughness, z * 0.02 * roughness)
                * roughness * 15.0 * mtnFreq;
        int h = (int) (baseH + s1 + s2);
        return Math.max(5, Math.min(230, h));
    }

    private int computeHeightForProfile(int x, int z, GenreProfile p) {
        double s1 = OpenSimplex2S.noise2(42L, x * 0.005 * p.terrainRoughness, z * 0.005 * p.terrainRoughness)
                * p.terrainRoughness * 40.0;
        double s2 = OpenSimplex2S.noise2(99L, x * 0.02 * p.terrainRoughness, z * 0.02 * p.terrainRoughness)
                * p.terrainRoughness * 15.0;
        int h = (int) (p.baseHeight + s1 + s2);
        return Math.max(5, Math.min(230, h));
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
        return computeHeight(x, z, WorldGenConfig.getActive());
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        GenreProfile p = WorldGenConfig.getActive();
        int minY = world.getBottomY();
        int height = world.getHeight();
        BlockState[] states = new BlockState[height];
        int finalH = computeHeight(x, z, p);

        for (int i = 0; i < height; i++) {
            int y = minY + i;
            states[i] = getBlockAt(x, y, z, finalH, p, Random.create(x * 31L + z));
        }
        return new VerticalBlockSample(minY, states);
    }

    // -------------------------------------------------------------------------
    // Main terrain population
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Chunk> populateNoise(
            Executor executor, Blender blender, NoiseConfig noiseConfig,
            StructureAccessor structureAccessor, Chunk chunk) {
        return CompletableFuture.supplyAsync(() -> {
            generateTerrain(chunk);
            return chunk;
        }, executor);
    }

    private void generateTerrain(Chunk chunk) {
        GenreProfile p = WorldGenConfig.getActive();
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        int minY = chunk.getBottomY();
        int maxY = chunk.getTopY();

        // Pre-compute raw heights for a 24x24 region (chunk + 4-block border)
        // so edge columns have enough neighbour data for a 5x5 smooth kernel.
        int pad = 4;
        int size = 16 + 2 * pad;
        int[][] raw = new int[size][size];
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
                raw[x][z] = computeHeight(startX - pad + x, startZ - pad + z, p);

        // 5x5 box-average smoothing — a wider kernel blurs more aggressively so
        // staircase steps at chunk edges are much less visible.
        int[][] smoothed = new int[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int sum = 0;
                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++)
                        sum += raw[x + pad + dx][z + pad + dz];
                smoothed[x][z] = (int) Math.round(sum / 25.0);
            }
        }

        BlockPos.Mutable mpos = new BlockPos.Mutable();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = startX + x;
                int worldZ = startZ + z;
                int finalH = smoothed[x][z];
                Random colRand = Random.create(worldX * 31L + worldZ);

                for (int y = minY; y < maxY; y++) {
                    mpos.set(worldX, y, worldZ);
                    BlockState state = getBlockAt(worldX, y, worldZ, finalH, p, colRand);
                    if (state != null && state != Blocks.AIR.getDefaultState()) {
                        chunk.setBlockState(mpos, state, false);
                    }
                }
            }
        }

        // Cave pass — 3D noise carving
        carveCaves(chunk, p, startX, startZ, minY, maxY);
    }

    private BlockState getBlockAt(int x, int y, int z, int finalH, GenreProfile p, Random colRand) {
        int waterLevel = p.waterLevel;

        if (y > finalH && y <= waterLevel) {
            return Blocks.WATER.getDefaultState();
        } else if (y == finalH) {
            return resolveBlock(p.surfaceBlock);
        } else if (y >= finalH - 3 && y < finalH) {
            return Blocks.DIRT.getDefaultState();
        } else if (y < finalH - 3 && y >= getMinimumY()) {
            // 20% chance to use secondary block — use a fresh draw per column per depth
            // colRand is seeded per column so it's deterministic
            boolean useSecondary = (Math.abs((x * 1000003L + y * 999983L + z) % 5) == 0);
            return useSecondary ? resolveBlock(p.secondaryBlock) : resolveBlock(p.primaryBlock);
        }
        return Blocks.AIR.getDefaultState();
    }

    // -------------------------------------------------------------------------
    // Cave carving
    // -------------------------------------------------------------------------

    private void carveCaves(Chunk chunk, GenreProfile p, int startX, int startZ, int minY, int maxY) {
        BlockPos.Mutable mpos = new BlockPos.Mutable();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = startX + x;
                int worldZ = startZ + z;
                int finalH = computeHeight(worldX, worldZ, p);

                for (int y = minY; y < maxY; y++) {
                    if (y < 8) continue;                      // never carve bedrock zone
                    if (y >= finalH - 1) continue;            // never carve surface layer

                    double noise = OpenSimplex2S.noise3_ImproveXZ(
                            7L,
                            worldX * 0.04,
                            y * 0.04,
                            worldZ * 0.04
                    );

                    if (noise > (1.0 - p.caveFrequency * 0.6)) {
                        mpos.set(worldX, y, worldZ);
                        if (y < 16) {
                            chunk.setBlockState(mpos, Blocks.LAVA.getDefaultState(), false);
                        } else {
                            chunk.setBlockState(mpos, Blocks.AIR.getDefaultState(), false);
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Surface building (no-op — we handle surface in populateNoise)
    // -------------------------------------------------------------------------

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures,
                             NoiseConfig noiseConfig, Chunk chunk) {
        // Surface blocks are placed in populateNoise
    }

    // -------------------------------------------------------------------------
    // Features: trees + ores + structures
    // -------------------------------------------------------------------------

    @Override
    public void generateFeatures(StructureWorldAccess world, Chunk chunk,
                                 StructureAccessor structureAccessor) {
        // Render vanilla structure pieces (villages, mineshafts, etc.) into blocks FIRST.
        // Without this call, structures are placed in setStructureStarts but never built.
        try {
            super.generateFeatures(world, chunk, structureAccessor);
        } catch (Exception e) {
            // Log and continue — never crash world gen over a vanilla feature error
            // (can happen if biome features reference blocks not in our world)
        }

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

    // -------------------------------------------------------------------------
    // Trees — directly placed, no biome/surface dependency
    // -------------------------------------------------------------------------

    private void placeVanillaTrees(StructureWorldAccess world, Chunk chunk,
                                   GenreProfile p, Random rand) {
        if (p.treeFrequency <= 0) return;

        int attempts = Math.max(1, (int) (p.treeFrequency * 12));
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();

        for (int i = 0; i < attempts; i++) {
            if (rand.nextFloat() > p.treeFrequency) continue;
            int wx = startX + rand.nextInt(16);
            int wz = startZ + rand.nextInt(16);
            int groundY = computeHeight(wx, wz, p);
            placeTree(world, wx, groundY + 1, wz, p, rand);
        }
    }

    /** Place a genre-appropriate tree at the given base position. */
    private void placeTree(StructureWorldAccess world, int x, int y, int z,
                           GenreProfile p, Random rand) {
        switch (p.structureType) {
            case "PILLARS"   -> placeSpruceTree(world, x, y, z, rand);   // metal: dark spruce
            case "BUILDINGS" -> placeOakTree(world, x, y, z, rand);      // jazz: oak
            case "COLUMNS"   -> {                                          // classical: mix oak + birch
                if (rand.nextBoolean()) placeOakTree(world, x, y, z, rand);
                else placeBirchTree(world, x, y, z, rand);
            }
            case "PLATFORMS" -> placeAcaciaTree(world, x, y, z, rand);   // hiphop: acacia
            case "GAZEBOS"   -> placeBirchTree(world, x, y, z, rand);    // pop: birch
            case "RUINS"     -> placeSpruceTree(world, x, y, z, rand);   // ambient: spruce
            default          -> placeOakTree(world, x, y, z, rand);
        }
    }

    /** Oak tree: trunk 4-6 high, round leaf blob */
    private void placeOakTree(StructureWorldAccess world, int x, int y, int z, Random rand) {
        int height = 4 + rand.nextInt(3);
        BlockState log = Blocks.OAK_LOG.getDefaultState();
        BlockState leaves = Blocks.OAK_LEAVES.getDefaultState();
        BlockPos.Mutable m = new BlockPos.Mutable();

        for (int dy = 0; dy < height; dy++) {
            m.set(x, y + dy, z);
            world.setBlockState(m, log, 3);
        }
        // Round leaf blob around top
        int top = y + height;
        for (int dy = -1; dy <= 2; dy++) {
            int radius = (dy <= 0) ? 2 : (dy == 1 ? 2 : 1);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0 && dy < 0) continue; // log position
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && rand.nextBoolean()) continue;
                    m.set(x + dx, top + dy, z + dz);
                    if (world.getBlockState(m).isAir()) world.setBlockState(m, leaves, 3);
                }
            }
        }
    }

    /** Birch tree: trunk 5-7 high, slightly narrower leaf blob */
    private void placeBirchTree(StructureWorldAccess world, int x, int y, int z, Random rand) {
        int height = 5 + rand.nextInt(3);
        BlockState log = Blocks.BIRCH_LOG.getDefaultState();
        BlockState leaves = Blocks.BIRCH_LEAVES.getDefaultState();
        BlockPos.Mutable m = new BlockPos.Mutable();

        for (int dy = 0; dy < height; dy++) {
            m.set(x, y + dy, z);
            world.setBlockState(m, log, 3);
        }
        int top = y + height;
        for (int dy = -1; dy <= 2; dy++) {
            int radius = (dy <= 0) ? 2 : (dy == 1 ? 1 : 1);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0 && dy < 0) continue;
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue; // clip corners
                    m.set(x + dx, top + dy, z + dz);
                    if (world.getBlockState(m).isAir()) world.setBlockState(m, leaves, 3);
                }
            }
        }
    }

    /** Spruce tree: trunk 6-10 high, layered cone leaves */
    private void placeSpruceTree(StructureWorldAccess world, int x, int y, int z, Random rand) {
        int height = 6 + rand.nextInt(5);
        BlockState log = Blocks.SPRUCE_LOG.getDefaultState();
        BlockState leaves = Blocks.SPRUCE_LEAVES.getDefaultState();
        BlockPos.Mutable m = new BlockPos.Mutable();

        for (int dy = 0; dy < height; dy++) {
            m.set(x, y + dy, z);
            world.setBlockState(m, log, 3);
        }
        // Cone: wide at bottom, tip at top
        int top = y + height;
        for (int layer = 0; layer < 5; layer++) {
            int radius = Math.max(0, 2 - layer / 2);
            int ly = top - layer;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && rand.nextBoolean()) continue;
                    m.set(x + dx, ly, z + dz);
                    if (world.getBlockState(m).isAir()) world.setBlockState(m, leaves, 3);
                }
            }
        }
        // Top cap
        m.set(x, top + 1, z);
        world.setBlockState(m, leaves, 3);
    }

    /** Acacia tree: forked trunk, flat leaf canopy */
    private void placeAcaciaTree(StructureWorldAccess world, int x, int y, int z, Random rand) {
        int height = 5 + rand.nextInt(3);
        BlockState log = Blocks.ACACIA_LOG.getDefaultState();
        BlockState leaves = Blocks.ACACIA_LEAVES.getDefaultState();
        BlockPos.Mutable m = new BlockPos.Mutable();

        // Main trunk
        for (int dy = 0; dy < height - 1; dy++) {
            m.set(x, y + dy, z);
            world.setBlockState(m, log, 3);
        }
        // Fork: two branches at angle
        int forkY = y + height - 1;
        int[][] forks = {{1, 0}, {-1, 0}};
        if (rand.nextBoolean()) forks = new int[][]{{0, 1}, {0, -1}};
        for (int[] fork : forks) {
            m.set(x + fork[0], forkY, z + fork[1]);
            world.setBlockState(m, log, 3);
            m.set(x + fork[0], forkY + 1, z + fork[1]);
            world.setBlockState(m, log, 3);
            // Flat leaf pad at fork top
            int lx = x + fork[0], lz = z + fork[1], topY = forkY + 1;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    m.set(lx + dx, topY + 1, lz + dz);
                    if (world.getBlockState(m).isAir()) world.setBlockState(m, leaves, 3);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Vanilla ores via PlacedFeature registry
    // -------------------------------------------------------------------------

    private void placeOres(StructureWorldAccess world, Chunk chunk,
                           GenreProfile p, Random rand) {
        Registry<PlacedFeature> registry = world.getRegistryManager().get(RegistryKeys.PLACED_FEATURE);
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        BlockPos origin = new BlockPos(startX + 8, 0, startZ + 8);

        for (RegistryKey<PlacedFeature> oreKey : getOreKeysForGenre(p)) {
            registry.getOrEmpty(oreKey).ifPresent(f ->
                    f.generate(world, this, rand, origin));
        }
    }

    private List<RegistryKey<PlacedFeature>> getOreKeysForGenre(GenreProfile p) {
        return switch (p.structureType) {
            case "PILLARS" ->   // metal: iron, coal, gold heavy
                    List.of(OrePlacedFeatures.ORE_IRON_UPPER, OrePlacedFeatures.ORE_IRON_MIDDLE,
                            OrePlacedFeatures.ORE_COAL_UPPER, OrePlacedFeatures.ORE_GOLD);
            case "BUILDINGS" -> // jazz: standard mix
                    List.of(OrePlacedFeatures.ORE_IRON_UPPER, OrePlacedFeatures.ORE_COAL_UPPER,
                            OrePlacedFeatures.ORE_COPPER);
            case "COLUMNS" ->   // classical: quartz feel — gold, diamond
                    List.of(OrePlacedFeatures.ORE_GOLD, OrePlacedFeatures.ORE_DIAMOND,
                            OrePlacedFeatures.ORE_EMERALD);
            case "PLATFORMS" -> // hiphop: coal, iron, redstone
                    List.of(OrePlacedFeatures.ORE_COAL_UPPER, OrePlacedFeatures.ORE_IRON_MIDDLE,
                            OrePlacedFeatures.ORE_REDSTONE);
            case "GAZEBOS" ->   // pop: light ores
                    List.of(OrePlacedFeatures.ORE_COAL_UPPER, OrePlacedFeatures.ORE_COPPER,
                            OrePlacedFeatures.ORE_IRON_UPPER);
            case "RUINS" ->     // ambient: clay, lapis, diamond rare
                    List.of(OrePlacedFeatures.ORE_LAPIS, OrePlacedFeatures.ORE_DIAMOND,
                            OrePlacedFeatures.ORE_IRON_MIDDLE);
            default ->          // electronic: redstone, lapis, gold
                    List.of(OrePlacedFeatures.ORE_REDSTONE, OrePlacedFeatures.ORE_LAPIS,
                            OrePlacedFeatures.ORE_GOLD);
        };
    }

    // -------------------------------------------------------------------------
    // Underground features per genre
    // -------------------------------------------------------------------------

    private void placeUndergroundFeatures(StructureWorldAccess world, Chunk chunk,
                                          GenreProfile p, Random rand) {
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        var registry = world.getRegistryManager().get(RegistryKeys.PLACED_FEATURE);

        // Helper: place a feature's ConfiguredFeature directly (bypasses BiomePlacementModifier)
        java.util.function.BiConsumer<RegistryKey<PlacedFeature>, Integer> tryPlace =
            (key, yHint) -> registry.getOrEmpty(key).ifPresent(f -> {
                try {
                    BlockPos pos = new BlockPos(
                        startX + rand.nextInt(16),
                        yHint,
                        startZ + rand.nextInt(16));
                    f.feature().value().generate(world, this, rand, pos);
                } catch (Exception ignored) { }
            });

        switch (p.structureType) {
            case "PILLARS" -> {  // metal: lava, dripstone, fossils, dungeons
                if (rand.nextInt(9) == 0)
                    tryPlace.accept(MiscPlacedFeatures.LAKE_LAVA_UNDERGROUND, -20 + rand.nextInt(30));
                if (rand.nextInt(3) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.DRIPSTONE_CLUSTER, -30 + rand.nextInt(20));
                if (rand.nextInt(64) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.FOSSIL_UPPER, 10 + rand.nextInt(40));
                if (rand.nextInt(8) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.MONSTER_ROOM, 10 + rand.nextInt(40));
            }
            case "BUILDINGS" -> {  // jazz: dungeons, cave vines, glow lichen
                if (rand.nextInt(8) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.MONSTER_ROOM, 10 + rand.nextInt(50));
                if (rand.nextInt(3) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.CAVE_VINES, 20 + rand.nextInt(40));
                if (rand.nextInt(4) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.GLOW_LICHEN, 10 + rand.nextInt(50));
            }
            case "COLUMNS" -> {  // classical: amethyst geodes, dripstone, spore blossoms
                if (rand.nextInt(24) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.AMETHYST_GEODE, 6 + rand.nextInt(24));
                if (rand.nextInt(3) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.DRIPSTONE_CLUSTER, -20 + rand.nextInt(40));
                if (rand.nextInt(2) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.SPORE_BLOSSOM, 20 + rand.nextInt(40));
            }
            case "PLATFORMS" -> {  // hiphop: dungeons, lava lakes, lava springs
                if (rand.nextInt(8) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.MONSTER_ROOM_DEEP, -20 + rand.nextInt(30));
                if (rand.nextInt(9) == 0)
                    tryPlace.accept(MiscPlacedFeatures.LAKE_LAVA_UNDERGROUND, -30 + rand.nextInt(20));
                if (rand.nextInt(4) == 0)
                    tryPlace.accept(MiscPlacedFeatures.SPRING_LAVA, -10 + rand.nextInt(30));
            }
            case "NONE" -> {  // electronic: sculk veins, large dripstone, deep sculk
                if (rand.nextInt(2) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.SCULK_VEIN, -20 + rand.nextInt(30));
                if (rand.nextInt(3) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.LARGE_DRIPSTONE, -10 + rand.nextInt(40));
                if (rand.nextInt(16) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.SCULK_PATCH_DEEP_DARK, -50 + rand.nextInt(10));
            }
            case "GAZEBOS" -> {  // pop: cave vines, azalea roots, lush clay
                if (rand.nextInt(3) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.CAVE_VINES, 10 + rand.nextInt(40));
                if (rand.nextInt(4) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.ROOTED_AZALEA_TREE, 10 + rand.nextInt(40));
                if (rand.nextInt(4) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.LUSH_CAVES_CLAY, 5 + rand.nextInt(30));
            }
            case "RUINS" -> {  // ambient: amethyst geodes, sculk veins, cave vines
                if (rand.nextInt(16) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.AMETHYST_GEODE, 6 + rand.nextInt(24));
                if (rand.nextInt(3) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.SCULK_VEIN, -30 + rand.nextInt(30));
                if (rand.nextInt(3) == 0)
                    tryPlace.accept(UndergroundPlacedFeatures.CAVE_VINES, 10 + rand.nextInt(40));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Surface decorations per genre
    // -------------------------------------------------------------------------

    private void placeSurfaceDecorations(StructureWorldAccess world, Chunk chunk,
                                          GenreProfile p, Random rand) {
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        BlockPos.Mutable m = new BlockPos.Mutable();

        switch (p.structureType) {
            case "PILLARS" -> {  // metal: lava drips at cliffs, bone blocks, soul fire
                // Lava waterfalls: find cliff edges (4+ block drops) and run lava down
                for (int i = 0; i < 3; i++) {
                    int wx = startX + rand.nextInt(16);
                    int wz = startZ + rand.nextInt(16);
                    int h = computeHeight(wx, wz, p);
                    int adjacent = computeHeight(wx + 1, wz, p);
                    if (h - adjacent >= 4) {
                        for (int y = adjacent + 1; y <= h; y++) {
                            m.set(wx, y, wz);
                            if (world.getBlockState(m).isAir())
                                world.setBlockState(m, Blocks.LAVA.getDefaultState(), 3);
                        }
                    }
                }
                // Bone block debris (fossil feel)
                for (int i = 0; i < 2 + rand.nextInt(3); i++) {
                    if (rand.nextInt(5) != 0) continue;
                    int wx = startX + rand.nextInt(16);
                    int wz = startZ + rand.nextInt(16);
                    int h = computeHeight(wx, wz, p);
                    m.set(wx, h, wz);
                    world.setBlockState(m, Blocks.BONE_BLOCK.getDefaultState(), 3);
                }
                // Soul fire (rare, spooky)
                if (rand.nextInt(8) == 0) {
                    int wx = startX + rand.nextInt(16);
                    int wz = startZ + rand.nextInt(16);
                    int h = computeHeight(wx, wz, p) + 1;
                    m.set(wx, h, wz);
                    if (world.getBlockState(m).isAir())
                        world.setBlockState(m, Blocks.SOUL_FIRE.getDefaultState(), 3);
                }
            }
            case "BUILDINGS" -> {  // jazz: mossy boulders, lanterns, vines
                if (rand.nextInt(6) == 0) {
                    int wx = startX + rand.nextInt(16);
                    int wz = startZ + rand.nextInt(16);
                    int h = computeHeight(wx, wz, p);
                    // Mossy cobblestone boulder cluster
                    for (int[] d : new int[][]{{0,0},{1,0},{-1,0},{0,1},{0,-1}}) {
                        if (d[0] != 0 && rand.nextBoolean()) continue;
                        int bx = wx + d[0], bz = wz + d[1];
                        m.set(bx, computeHeight(bx, bz, p) + 1, bz);
                        if (world.getBlockState(m).isAir())
                            world.setBlockState(m, Blocks.MOSSY_COBBLESTONE.getDefaultState(), 3);
                    }
                }
                // Lanterns
                for (int i = 0; i < rand.nextInt(3); i++) {
                    int wx = startX + rand.nextInt(16);
                    int wz = startZ + rand.nextInt(16);
                    int h = computeHeight(wx, wz, p) + 1;
                    m.set(wx, h, wz);
                    if (world.getBlockState(m).isAir())
                        world.setBlockState(m, Blocks.LANTERN.getDefaultState(), 3);
                }
            }
            case "COLUMNS" -> {  // classical: flower fields, torches
                BlockState[] flowers = {Blocks.DANDELION.getDefaultState(),
                    Blocks.POPPY.getDefaultState(), Blocks.BLUE_ORCHID.getDefaultState(),
                    Blocks.ALLIUM.getDefaultState(), Blocks.AZURE_BLUET.getDefaultState(),
                    Blocks.OXEYE_DAISY.getDefaultState(), Blocks.CORNFLOWER.getDefaultState()};
                for (int i = 0; i < 4 + rand.nextInt(8); i++) {
                    int wx = startX + rand.nextInt(16);
                    int wz = startZ + rand.nextInt(16);
                    int h = computeHeight(wx, wz, p) + 1;
                    m.set(wx, h, wz);
                    if (world.getBlockState(m).isAir())
                        world.setBlockState(m, flowers[rand.nextInt(flowers.length)], 3);
                }
                if (rand.nextInt(8) == 0) {
                    int wx = startX + rand.nextInt(16);
                    int wz = startZ + rand.nextInt(16);
                    m.set(wx, computeHeight(wx, wz, p) + 1, wz);
                    if (world.getBlockState(m).isAir())
                        world.setBlockState(m, Blocks.TORCH.getDefaultState(), 3);
                }
            }
            case "PLATFORMS" -> {  // hiphop: coloured concrete splashes, dead bushes
                BlockState[] concretes = {Blocks.CYAN_CONCRETE.getDefaultState(),
                    Blocks.MAGENTA_CONCRETE.getDefaultState(),
                    Blocks.YELLOW_CONCRETE.getDefaultState(),
                    Blocks.LIME_CONCRETE.getDefaultState(),
                    Blocks.ORANGE_CONCRETE.getDefaultState()};
                for (int i = 0; i < rand.nextInt(4); i++) {
                    int wx = startX + rand.nextInt(16);
                    int wz = startZ + rand.nextInt(16);
                    int h = computeHeight(wx, wz, p);
                    m.set(wx, h, wz);
                    world.setBlockState(m, concretes[rand.nextInt(concretes.length)], 3);
                }
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
            case "NONE" -> {  // electronic: end rods as antennae, glowstone blobs
                for (int i = 0; i < rand.nextInt(3); i++) {
                    int wx = startX + rand.nextInt(16);
                    int wz = startZ + rand.nextInt(16);
                    int h = computeHeight(wx, wz, p);
                    int rodH = 2 + rand.nextInt(5);
                    for (int y = 1; y <= rodH; y++) {
                        m.set(wx, h + y, wz);
                        world.setBlockState(m, Blocks.END_ROD.getDefaultState(), 3);
                    }
                }
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
            case "GAZEBOS" -> {  // pop: flower explosion, bee nests, sunflowers
                BlockState[] flowers2 = {Blocks.DANDELION.getDefaultState(),
                    Blocks.POPPY.getDefaultState(), Blocks.CORNFLOWER.getDefaultState(),
                    Blocks.PINK_TULIP.getDefaultState(), Blocks.WHITE_TULIP.getDefaultState(),
                    Blocks.ORANGE_TULIP.getDefaultState(), Blocks.RED_TULIP.getDefaultState()};
                for (int i = 0; i < 6 + rand.nextInt(12); i++) {
                    int wx = startX + rand.nextInt(16);
                    int wz = startZ + rand.nextInt(16);
                    int h = computeHeight(wx, wz, p) + 1;
                    m.set(wx, h, wz);
                    if (world.getBlockState(m).isAir())
                        world.setBlockState(m, flowers2[rand.nextInt(flowers2.length)], 3);
                }
                // Sunflower rows (distinctive, facing east)
                if (rand.nextInt(4) == 0) {
                    int wx = startX + rand.nextInt(12);
                    int wz = startZ + rand.nextInt(16);
                    int h = computeHeight(wx, wz, p) + 1;
                    for (int dx = 0; dx < 3 + rand.nextInt(3); dx++) {
                        m.set(wx + dx, h, wz);
                        if (world.getBlockState(m).isAir())
                            world.setBlockState(m, Blocks.SUNFLOWER.getDefaultState(), 3);
                        m.set(wx + dx, h + 1, wz);
                        if (world.getBlockState(m).isAir())
                            world.setBlockState(m, Blocks.SUNFLOWER.getDefaultState(), 3);
                    }
                }
            }
            case "RUINS" -> {  // ambient: huge mushrooms, sculk patches on surface
                if (rand.nextInt(12) == 0) {
                    int wx = startX + rand.nextInt(16);
                    int wz = startZ + rand.nextInt(16);
                    int h = computeHeight(wx, wz, p) + 1;
                    placeHugeMushroom(world, wx, h, wz, rand);
                }
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

    // NEON TOWER (Electronic): tall purpur/end stone tower, sea lantern bands, end rod antennae
    private void buildNeonTower(StructureWorldAccess world, int cx, int groundY, int cz, Random rand) {
        GenreProfile p = WorldGenConfig.getActive();
        int towerH = 20 + rand.nextInt(14);  // 20–33
        BlockPos.Mutable m = new BlockPos.Mutable();

        BlockState purpur   = Blocks.PURPUR_BLOCK.getDefaultState();
        BlockState endStone = Blocks.END_STONE_BRICKS.getDefaultState();
        BlockState lantern  = Blocks.SEA_LANTERN.getDefaultState();
        BlockState endRod   = Blocks.END_ROD.getDefaultState();
        BlockState amethyst = Blocks.AMETHYST_CLUSTER.getDefaultState();

        int baseY = groundStructure(world, cx, cz, groundY, 2, endStone, p);

        // 3x3 base pad of end stone bricks
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                m.set(cx + dx, baseY, cz + dz);
                world.setBlockState(m, endStone, 3);
            }

        // Tower shaft: 3x3, alternating purpur and end stone every 4 rows
        for (int y = 1; y <= towerH; y++) {
            BlockState mat = (y / 4) % 2 == 0 ? purpur : endStone;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    m.set(cx + dx, baseY + y, cz + dz);
                    world.setBlockState(m, mat, 3);
                }
        }

        // Sea lantern bands at regular intervals (inlaid in the outer shell)
        for (int lightY : StructureGeometry.towerLightLevels(towerH, 6)) {
            // Full ring of lanterns replacing the outer shell at this level
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    boolean isOuter = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                    if (!isOuter) continue;
                    m.set(cx + dx, baseY + lightY, cz + dz);
                    world.setBlockState(m, lantern, 3);
                }
        }

        // Amethyst clusters on random sides at mid-height
        int midY = baseY + towerH / 2;
        int[][] sides = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int[] side : sides) {
            if (rand.nextInt(3) == 0) {
                m.set(cx + side[0], midY + rand.nextInt(4) - 1, cz + side[1]);
                if (world.getBlockState(m).isAir())
                    world.setBlockState(m, amethyst, 3);
            }
        }

        // Top cap: sea lantern crown
        int topY = baseY + towerH + 1;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                m.set(cx + dx, topY, cz + dz);
                world.setBlockState(m, lantern, 3);
            }

        // End rod antennae (3–5 rods of varying height)
        int[][] antennaOffsets = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int antennaCount = 3 + rand.nextInt(3);
        for (int i = 0; i < antennaCount; i++) {
            int[] off = antennaOffsets[i];
            int rodH = 3 + rand.nextInt(6);
            for (int y = 1; y <= rodH; y++) {
                m.set(cx + off[0], topY + y, cz + off[1]);
                world.setBlockState(m, endRod, 3);
            }
        }
    }

    /** Huge brown mushroom: stem 5-9 high, 5x5 cap with random trim */
    private void placeHugeMushroom(StructureWorldAccess world, int x, int y, int z, Random rand) {
        int stemH = 5 + rand.nextInt(5);
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dy = 0; dy < stemH; dy++) {
            m.set(x, y + dy, z);
            world.setBlockState(m, Blocks.MUSHROOM_STEM.getDefaultState(), 3);
        }
        int capY = y + stemH;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) == 2 && Math.abs(dz) == 2 && rand.nextBoolean()) continue;
                m.set(x + dx, capY, z + dz);
                world.setBlockState(m, Blocks.BROWN_MUSHROOM_BLOCK.getDefaultState(), 3);
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                m.set(x + dx, capY + 1, z + dz);
                world.setBlockState(m, Blocks.BROWN_MUSHROOM_BLOCK.getDefaultState(), 3);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Structures — 10% of chunks, fully randomised per placement
    // -------------------------------------------------------------------------

    private void placeStructure(StructureWorldAccess world, GenreProfile p,
                                int startX, int startZ, int chunkX, int chunkZ, Random rand) {
        Random triggerRand = Random.create(chunkX * 1234567891L ^ chunkZ * 987654321L);
        if (triggerRand.nextInt(10) != 0) return;

        // Vary position within chunk so structures aren't always centred
        int cx = startX + 4 + rand.nextInt(8);
        int cz = startZ + 4 + rand.nextInt(8);
        int groundY = computeHeight(cx, cz, p);

        switch (p.structureType) {
            case "PILLARS"    -> buildPillar(world, cx, groundY, cz, rand);
            case "BUILDINGS"  -> buildBuilding(world, cx, groundY, cz, rand);
            case "COLUMNS"    -> buildColumns(world, cx, groundY, cz, rand);
            case "PLATFORMS"  -> buildPlatform(world, cx, groundY, cz, rand);
            case "GAZEBOS"    -> buildGazebo(world, cx, groundY, cz, rand);
            case "RUINS"      -> buildRuin(world, cx, groundY, cz, rand);
            case "NONE"       -> buildNeonTower(world, cx, groundY, cz, rand);
        }
    }

    // -------------------------------------------------------------------------
    // Shared: fill solid blocks downward so structures don't float
    // -------------------------------------------------------------------------

    /**
     * Fill solid blocks under the structure footprint so it sits flush on terrain.
     * Returns the highest terrain y found in the footprint — callers use this as
     * their floor level so walls/floors align with the filled base.
     */
    private int groundStructure(StructureWorldAccess world, int cx, int cz, int groundY,
                                int halfW, BlockState fill, GenreProfile p) {
        // Sample every column in the footprint to find the highest terrain point.
        int highest = groundY;
        for (int dx = -halfW; dx <= halfW; dx++)
            for (int dz = -halfW; dz <= halfW; dz++) {
                int h = computeHeight(cx + dx, cz + dz, p);
                if (h > highest) highest = h;
            }

        // Fill each column from its own terrain height up to `highest`,
        // capped at MAX_GROUNDING_DEPTH. This prevents fill from appearing
        // above the natural ground surface on lower-lying columns.
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -halfW; dx <= halfW; dx++) {
            for (int dz = -halfW; dz <= halfW; dz++) {
                int colH = computeHeight(cx + dx, cz + dz, p);
                int fillFrom = Math.max(colH, highest - StructureGeometry.MAX_GROUNDING_DEPTH);
                for (int y = fillFrom; y <= highest; y++) {
                    m.set(cx + dx, y, cz + dz);
                    world.setBlockState(m, fill, 3);
                }
            }
        }
        return highest;
    }

    // PILLARS (Metal): 3x3 obsidian core with blackstone brick cladding, chains, optional bridge
    private void buildPillar(StructureWorldAccess world, int cx, int groundY, int cz, Random rand) {
        GenreProfile p = WorldGenConfig.getActive();
        int height = 15 + rand.nextInt(16);  // 15–30
        boolean doublePillar = rand.nextInt(3) == 0;
        BlockState obsidian = Blocks.OBSIDIAN.getDefaultState();
        BlockState bsBrick  = Blocks.BLACKSTONE.getDefaultState();
        BlockState chain    = Blocks.CHAIN.getDefaultState();
        BlockState cap      = rand.nextBoolean() ? Blocks.CRYING_OBSIDIAN.getDefaultState()
                                                 : Blocks.LAVA.getDefaultState();
        BlockPos.Mutable m = new BlockPos.Mutable();

        // Ground the 5x5 base; use returned highest as actual floor y
        int floorY = groundStructure(world, cx, cz, groundY, 2, bsBrick, p);

        // 5x5 blackstone base pad
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                m.set(cx + dx, floorY, cz + dz);
                world.setBlockState(m, bsBrick, 3);
            }

        // 3x3 pillar: obsidian core, blackstone brick shell
        for (int y = 1; y <= height; y++) {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    m.set(cx + dx, floorY + y, cz + dz);
                    boolean isCore = dx == 0 && dz == 0;
                    world.setBlockState(m, isCore ? obsidian : bsBrick, 3);
                }
        }

        // Cap
        m.set(cx, floorY + height + 1, cz);
        world.setBlockState(m, cap, 3);

        // Chains hanging from cap (4 sides, random length 2–5)
        int[][] chainOffsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] off : chainOffsets) {
            int chainLen = 2 + rand.nextInt(4);
            for (int y = 0; y < chainLen; y++) {
                m.set(cx + off[0], floorY + height - y, cz + off[1]);
                if (world.getBlockState(m).isAir())
                    world.setBlockState(m, chain, 3);
            }
        }

        // Second pillar + optional chain bridge between them
        if (doublePillar) {
            int ox = cx + (rand.nextBoolean() ? 8 : -8);
            int oz = cz + (rand.nextBoolean() ? 8 : -8);
            int h2 = 10 + rand.nextInt(10);
            int gy2 = computeHeight(ox, oz, p);
            int fy2 = groundStructure(world, ox, oz, gy2, 1, bsBrick, p);
            for (int y = 1; y <= h2; y++) {
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) {
                        m.set(ox + dx, fy2 + y, oz + dz);
                        world.setBlockState(m, dx == 0 && dz == 0 ? obsidian : bsBrick, 3);
                    }
            }
            m.set(ox, fy2 + h2 + 1, oz);
            world.setBlockState(m, Blocks.LAVA.getDefaultState(), 3);

            // Chain bridge between pillars at the lower top
            int bridgeY = Math.min(floorY + height, fy2 + h2);
            int steps = Math.abs(ox - cx);
            for (int i = 1; i < steps; i++) {
                int bx = cx + (ox > cx ? i : -i);
                int bz = cz + (oz > cz ? i : -i);
                // Sag: parabolic droop — deepest in middle
                double t = (double) i / steps;
                int sag = (int) (3 * t * (1 - t) * 4);
                m.set(bx, bridgeY - sag, bz);
                world.setBlockState(m, chain, 3);
                // Trapdoor walkway on every other step
                if (i % 2 == 0) {
                    m.set(bx, bridgeY - sag - 1, bz);
                    if (world.getBlockState(m).isAir())
                        world.setBlockState(m, Blocks.DARK_OAK_TRAPDOOR.getDefaultState(), 3);
                }
            }
        }
    }

    // BUILDINGS (Jazz): layered materials, windows, chimney, hanging lantern
    private void buildBuilding(StructureWorldAccess world, int cx, int groundY, int cz, Random rand) {
        GenreProfile p = WorldGenConfig.getActive();
        int halfW = 3 + rand.nextInt(3);  // 3–5
        int wallH = 4 + rand.nextInt(3);  // 4–6
        BlockPos.Mutable m = new BlockPos.Mutable();

        // Material layers: cobblestone bottom third, stone brick upper, mossy patches random
        BlockState cobble     = Blocks.COBBLESTONE.getDefaultState();
        BlockState stoneBrick = Blocks.STONE_BRICKS.getDefaultState();
        BlockState mossy      = Blocks.MOSSY_STONE_BRICKS.getDefaultState();
        BlockState roof       = rand.nextBoolean() ? Blocks.DARK_OAK_PLANKS.getDefaultState()
                                                   : Blocks.SPRUCE_PLANKS.getDefaultState();
        BlockState roofSlab   = rand.nextBoolean() ? Blocks.DARK_OAK_SLAB.getDefaultState()
                                                   : Blocks.SPRUCE_SLAB.getDefaultState();

        // Ground the footprint; use returned highest as actual floor y
        int floorY = groundStructure(world, cx, cz, groundY, halfW, cobble, p);

        // Floor
        for (int dx = -halfW; dx <= halfW; dx++)
            for (int dz = -halfW; dz <= halfW; dz++) {
                m.set(cx + dx, floorY, cz + dz);
                world.setBlockState(m, stoneBrick, 3);
            }

        // Entrance side
        int entranceSide = rand.nextInt(4);
        int lowerThird   = Math.max(1, wallH / 3);

        for (int y = 1; y <= wallH; y++) {
            // Pick wall material: cobble lower third, stone brick above, mossy patch random
            BlockState mat;
            if (y <= lowerThird)       mat = cobble;
            else if (rand.nextInt(6) == 0) mat = mossy;
            else                           mat = stoneBrick;

            for (int dx = -halfW; dx <= halfW; dx++) {
                for (int dz = -halfW; dz <= halfW; dz++) {
                    boolean isWall = (dx == -halfW || dx == halfW || dz == -halfW || dz == halfW);
                    if (!isWall) continue;

                    // Entrance opening (2 blocks tall, 3 wide)
                    boolean isEntrance = switch (entranceSide) {
                        case 0 -> dz ==  halfW && dx >= -1 && dx <= 1 && y <= 2;
                        case 1 -> dz == -halfW && dx >= -1 && dx <= 1 && y <= 2;
                        case 2 -> dx ==  halfW && dz >= -1 && dz <= 1 && y <= 2;
                        default -> dx == -halfW && dz >= -1 && dz <= 1 && y <= 2;
                    };
                    if (isEntrance) continue;

                    // Windows: 2-block-tall cutouts at even offsets, rows 2–3
                    boolean isWindow = false;
                    if (y == 2 || y == 3) {
                        for (int wp : StructureGeometry.windowPositions(halfW)) {
                            if ((dx == -halfW || dx == halfW) && dz == wp) { isWindow = true; break; }
                            if ((dz == -halfW || dz == halfW) && dx == wp) { isWindow = true; break; }
                        }
                    }
                    if (isWindow) continue;

                    m.set(cx + dx, floorY + y, cz + dz);
                    world.setBlockState(m, mat, 3);
                }
            }
        }

        // Roof: solid plank layer + slab overhang one block outside
        int roofY = floorY + wallH + 1;
        for (int dx = -halfW; dx <= halfW; dx++)
            for (int dz = -halfW; dz <= halfW; dz++) {
                m.set(cx + dx, roofY, cz + dz);
                world.setBlockState(m, roof, 3);
            }
        // Slab overhang
        for (int dx = -(halfW + 1); dx <= halfW + 1; dx++)
            for (int dz = -(halfW + 1); dz <= halfW + 1; dz++) {
                boolean onEdge = Math.abs(dx) == halfW + 1 || Math.abs(dz) == halfW + 1;
                if (!onEdge) continue;
                m.set(cx + dx, roofY, cz + dz);
                if (world.getBlockState(m).isAir())
                    world.setBlockState(m, roofSlab, 3);
            }

        // Chimney (random corner of roof, 2–3 blocks tall)
        int[][] roofCorners = {{halfW - 1, halfW - 1}, {halfW - 1, -(halfW - 1)},
                               {-(halfW - 1), halfW - 1}, {-(halfW - 1), -(halfW - 1)}};
        int[] rc = roofCorners[rand.nextInt(4)];
        int chimneyH = 2 + rand.nextInt(2);
        for (int y = 1; y <= chimneyH; y++) {
            m.set(cx + rc[0], roofY + y, cz + rc[1]);
            world.setBlockState(m, stoneBrick, 3);
        }
        // Campfire smoke at chimney top
        m.set(cx + rc[0], roofY + chimneyH + 1, cz + rc[1]);
        if (world.getBlockState(m).isAir())
            world.setBlockState(m, Blocks.CAMPFIRE.getDefaultState(), 3);

        // Interior hanging lantern from ceiling center
        m.set(cx, floorY + wallH, cz);
        world.setBlockState(m, Blocks.CHAIN.getDefaultState(), 3);
        m.set(cx, floorY + wallH - 1, cz);
        world.setBlockState(m, Blocks.LANTERN.getDefaultState(), 3);
    }

    // COLUMNS (Classical): quartz pillars, varying height and spacing, optional interior floor
    private void buildColumns(StructureWorldAccess world, int cx, int groundY, int cz, Random rand) {
        GenreProfile p = WorldGenConfig.getActive();
        int colH = 8 + rand.nextInt(9);   // 8–16
        int spacing = rand.nextBoolean() ? 4 : 5;
        BlockPos.Mutable m = new BlockPos.Mutable();
        int[][] corners = {{-spacing, -spacing}, {-spacing, spacing},
                           {spacing, -spacing}, {spacing, spacing}};

        int floorY = groundStructure(world, cx, cz, groundY, spacing, Blocks.SMOOTH_QUARTZ.getDefaultState(), p);

        for (int[] c : corners) {
            for (int y = 0; y <= colH; y++) {
                m.set(cx + c[0], floorY + y, cz + c[1]);
                world.setBlockState(m, Blocks.QUARTZ_PILLAR.getDefaultState(), 3);
            }
        }

        int topY = floorY + colH;
        for (int dx = -spacing; dx <= spacing; dx++) {
            m.set(cx + dx, topY, cz - spacing);
            world.setBlockState(m, Blocks.QUARTZ_SLAB.getDefaultState(), 3);
            m.set(cx + dx, topY, cz + spacing);
            world.setBlockState(m, Blocks.QUARTZ_SLAB.getDefaultState(), 3);
        }
        for (int dz = -spacing; dz <= spacing; dz++) {
            m.set(cx - spacing, topY, cz + dz);
            world.setBlockState(m, Blocks.QUARTZ_SLAB.getDefaultState(), 3);
            m.set(cx + spacing, topY, cz + dz);
            world.setBlockState(m, Blocks.QUARTZ_SLAB.getDefaultState(), 3);
        }

        if (rand.nextInt(5) < 2) {
            for (int dx = -(spacing - 1); dx <= spacing - 1; dx++)
                for (int dz = -(spacing - 1); dz <= spacing - 1; dz++) {
                    m.set(cx + dx, floorY, cz + dz);
                    world.setBlockState(m, Blocks.SMOOTH_QUARTZ.getDefaultState(), 3);
                }
            m.set(cx, floorY + 1, cz);
            world.setBlockState(m, Blocks.GOLD_BLOCK.getDefaultState(), 3);
        }
    }

    // PLATFORMS (Hiphop): elevated concrete platform, varying size/height/edge material
    private void buildPlatform(StructureWorldAccess world, int cx, int groundY, int cz, Random rand) {
        GenreProfile p = WorldGenConfig.getActive();
        int halfW = 4 + rand.nextInt(3);       // 4–6
        int platformH = 6 + rand.nextInt(7);   // 6–12
        BlockPos.Mutable m = new BlockPos.Mutable();

        // Edge material: glowstone or sea lantern
        BlockState edgeMat = rand.nextBoolean() ? Blocks.GLOWSTONE.getDefaultState()
                                                : Blocks.SEA_LANTERN.getDefaultState();
        // Floor material: pick from concrete colours
        BlockState[] floors = {Blocks.GRAY_CONCRETE.getDefaultState(),
            Blocks.BLACK_CONCRETE.getDefaultState(), Blocks.CYAN_CONCRETE.getDefaultState()};
        BlockState floorMat = floors[rand.nextInt(floors.length)];

        int baseY = groundStructure(world, cx, cz, groundY, halfW, Blocks.GRAY_CONCRETE.getDefaultState(), p);
        int platformY = baseY + platformH;

        // Corner stilts (iron bars)
        int[][] stiltCorners = {{-halfW, -halfW}, {-halfW, halfW}, {halfW, -halfW}, {halfW, halfW}};
        for (int[] sc : stiltCorners)
            for (int y = 1; y < platformH; y++) {
                m.set(cx + sc[0], baseY + y, cz + sc[1]);
                world.setBlockState(m, Blocks.IRON_BARS.getDefaultState(), 3);
            }

        // Platform floor
        for (int dx = -halfW; dx <= halfW; dx++)
            for (int dz = -halfW; dz <= halfW; dz++) {
                boolean isBorder = (dx == -halfW || dx == halfW || dz == -halfW || dz == halfW);
                m.set(cx + dx, platformY, cz + dz);
                world.setBlockState(m, isBorder ? edgeMat : floorMat, 3);
            }

        // Optional ladder staircase on one random side
        int lx = cx + (rand.nextBoolean() ? halfW : -halfW);
        for (int y = 1; y < platformH; y++) {
            m.set(lx, baseY + y, cz);
            world.setBlockState(m, Blocks.LADDER.getDefaultState(), 3);
        }
    }

    // GAZEBOS (Pop): varied wood type, optional second floor, flower ring
    private void buildGazebo(StructureWorldAccess world, int cx, int groundY, int cz, Random rand) {
        GenreProfile p = WorldGenConfig.getActive();
        BlockPos.Mutable m = new BlockPos.Mutable();
        int halfW = 2 + rand.nextInt(2);   // 2–3

        // Wood type varies
        BlockState[] planks = {Blocks.OAK_PLANKS.getDefaultState(),
            Blocks.BIRCH_PLANKS.getDefaultState(), Blocks.JUNGLE_PLANKS.getDefaultState()};
        BlockState[] fences = {Blocks.OAK_FENCE.getDefaultState(),
            Blocks.BIRCH_FENCE.getDefaultState(), Blocks.JUNGLE_FENCE.getDefaultState()};
        BlockState[] slabs = {Blocks.OAK_SLAB.getDefaultState(),
            Blocks.BIRCH_SLAB.getDefaultState(), Blocks.JUNGLE_SLAB.getDefaultState()};
        int woodIdx = rand.nextInt(3);
        BlockState plank = planks[woodIdx], fence = fences[woodIdx], slab = slabs[woodIdx];

        int floorY = groundStructure(world, cx, cz, groundY, halfW, plank, p);

        // Floor
        for (int dx = -halfW; dx <= halfW; dx++)
            for (int dz = -halfW; dz <= halfW; dz++) {
                m.set(cx + dx, floorY, cz + dz);
                world.setBlockState(m, plank, 3);
            }

        // Fence posts at corners, height 2-3
        int postH = 2 + rand.nextInt(2);
        int[][] corners = {{-halfW, -halfW}, {-halfW, halfW}, {halfW, -halfW}, {halfW, halfW}};
        for (int[] c : corners)
            for (int y = 1; y <= postH; y++) {
                m.set(cx + c[0], floorY + y, cz + c[1]);
                world.setBlockState(m, fence, 3);
            }

        // Roof
        for (int dx = -halfW; dx <= halfW; dx++)
            for (int dz = -halfW; dz <= halfW; dz++) {
                m.set(cx + dx, floorY + postH + 1, cz + dz);
                world.setBlockState(m, slab, 3);
            }

        // Optional second floor (25% chance)
        if (rand.nextInt(4) == 0) {
            int upperY = floorY + postH + 2;
            for (int dx = -(halfW - 1); dx <= halfW - 1; dx++)
                for (int dz = -(halfW - 1); dz <= halfW - 1; dz++) {
                    m.set(cx + dx, upperY, cz + dz);
                    world.setBlockState(m, plank, 3);
                }
        }

        // Flower ring (random radius 2-4)
        BlockState[] flowers = {Blocks.DANDELION.getDefaultState(), Blocks.POPPY.getDefaultState(),
            Blocks.BLUE_ORCHID.getDefaultState(), Blocks.ALLIUM.getDefaultState(),
            Blocks.CORNFLOWER.getDefaultState(), Blocks.PINK_TULIP.getDefaultState()};
        int flowerR = 2 + rand.nextInt(3);
        for (int dx = -flowerR; dx <= flowerR; dx++)
            for (int dz = -flowerR; dz <= flowerR; dz++) {
                if (rand.nextInt(3) != 0) continue;
                int fx = cx + dx, fz = cz + dz;
                int fy = computeHeight(fx, fz, WorldGenConfig.getActive()) + 1;
                m.set(fx, fy, fz);
                if (world.getBlockState(m).isAir())
                    world.setBlockState(m, flowers[rand.nextInt(flowers.length)], 3);
            }
    }

    // RUINS (Ambient): crumbling walls, varying size/height, rubble scatter, optional water pool
    private void buildRuin(StructureWorldAccess world, int cx, int groundY, int cz, Random rand) {
        GenreProfile p = WorldGenConfig.getActive();
        BlockPos.Mutable m = new BlockPos.Mutable();
        int halfW = 3 + rand.nextInt(3);  // 3–5
        int wallH = 2 + rand.nextInt(4);  // 2-5
        float decayRate = 0.3f + rand.nextFloat() * 0.4f;  // 30-70% blocks removed

        // Wall material varies
        BlockState[] wallMats = {Blocks.MOSSY_COBBLESTONE.getDefaultState(),
            Blocks.COBBLESTONE.getDefaultState(), Blocks.STONE_BRICKS.getDefaultState(),
            Blocks.MOSSY_STONE_BRICKS.getDefaultState()};
        BlockState wallMat = wallMats[rand.nextInt(wallMats.length)];

        int floorY = groundStructure(world, cx, cz, groundY, halfW, Blocks.COBBLESTONE.getDefaultState(), p);

        // Walls
        for (int y = 0; y <= wallH; y++) {
            for (int dx = -halfW; dx <= halfW; dx++) {
                for (int dz = -halfW; dz <= halfW; dz++) {
                    boolean isWall = (dx == -halfW || dx == halfW || dz == -halfW || dz == halfW);
                    if (!isWall) continue;
                    if (rand.nextFloat() < decayRate) continue;
                    m.set(cx + dx, floorY + y, cz + dz);
                    world.setBlockState(m, wallMat, 3);
                }
            }
        }

        // Rubble scatter inside (25% of interior)
        for (int dx = -(halfW - 1); dx <= halfW - 1; dx++)
            for (int dz = -(halfW - 1); dz <= halfW - 1; dz++) {
                if (rand.nextInt(4) != 0) continue;
                m.set(cx + dx, floorY + 1, cz + dz);
                if (world.getBlockState(m).isAir())
                    world.setBlockState(m, Blocks.COBBLESTONE.getDefaultState(), 3);
            }

        // Water pool in interior (60% chance)
        if (rand.nextInt(5) < 3) {
            int poolR = Math.max(1, halfW - 2);
            for (int dx = -poolR; dx <= poolR; dx++)
                for (int dz = -poolR; dz <= poolR; dz++) {
                    m.set(cx + dx, floorY, cz + dz);
                    world.setBlockState(m, Blocks.WATER.getDefaultState(), 3);
                }
        }
    }

    // -------------------------------------------------------------------------
    // Carve / populateEntities
    // -------------------------------------------------------------------------

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig,
                      BiomeAccess biomeAccess, StructureAccessor structureAccessor,
                      Chunk chunk, GenerationStep.Carver carverStep) {
        // Cave carving is done in populateNoise
    }

    @Override
    public void populateEntities(ChunkRegion region) {
        // No-op: entity spawning handled by vanilla structure gen via setStructureStarts
    }

    // -------------------------------------------------------------------------
    // Debug HUD
    // -------------------------------------------------------------------------

    @Override
    public void getDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        GenreProfile p = WorldGenConfig.getActive();
        text.add("MusicWorld genre | base=" + p.baseHeight
                + " rough=" + p.terrainRoughness
                + " struct=" + p.structureType);
    }

    // -------------------------------------------------------------------------
    // Block name -> BlockState resolver
    // -------------------------------------------------------------------------

    private static BlockState resolveBlock(String name) {
        return switch (name) {
            case "BLACKSTONE"       -> Blocks.BLACKSTONE.getDefaultState();
            case "OBSIDIAN"         -> Blocks.OBSIDIAN.getDefaultState();
            case "GRAVEL"           -> Blocks.GRAVEL.getDefaultState();
            case "SMOOTH_STONE"     -> Blocks.SMOOTH_STONE.getDefaultState();
            case "STONE"            -> Blocks.STONE.getDefaultState();
            case "GRASS_BLOCK"      -> Blocks.GRASS_BLOCK.getDefaultState();
            case "QUARTZ_BLOCK"     -> Blocks.QUARTZ_BLOCK.getDefaultState();
            case "GRAY_CONCRETE"    -> Blocks.GRAY_CONCRETE.getDefaultState();
            case "COARSE_DIRT"      -> Blocks.COARSE_DIRT.getDefaultState();
            case "PURPUR_BLOCK"     -> Blocks.PURPUR_BLOCK.getDefaultState();
            case "END_STONE"        -> Blocks.END_STONE.getDefaultState();
            case "SOUL_SAND"        -> Blocks.SOUL_SAND.getDefaultState();
            case "DIORITE"          -> Blocks.DIORITE.getDefaultState();
            case "CLAY"             -> Blocks.CLAY.getDefaultState();
            case "MOSS_BLOCK"       -> Blocks.MOSS_BLOCK.getDefaultState();
            default                 -> Blocks.STONE.getDefaultState();
        };
    }

    // =========================================================================
    // Embedded OpenSimplex2S noise (public domain — KdotJPG)
    // Trimmed to the two methods we use: noise2 and noise3_ImproveXZ
    // =========================================================================

    static final class OpenSimplex2S {

        private static final int PSIZE = 2048;
        private static final int PMASK = 2047;

        private static final double N2 = 0.05481866495625118;
        private static final double N3 = 0.2781926117527186;

        private static final Grad2[] GRADIENTS_2D;
        private static final Grad3[] GRADIENTS_3D;

        static {
            // 2D gradients
            Grad2[] grad2 = {
                    new Grad2( 0.130526192220052,  0.99144486137381),
                    new Grad2( 0.38268343236509,   0.923879532511287),
                    new Grad2( 0.608761429008721,  0.793353340291235),
                    new Grad2( 0.793353340291235,  0.608761429008721),
                    new Grad2( 0.923879532511287,  0.38268343236509),
                    new Grad2( 0.99144486137381,   0.130526192220051),
                    new Grad2( 0.99144486137381,  -0.130526192220051),
                    new Grad2( 0.923879532511287, -0.38268343236509),
                    new Grad2( 0.793353340291235, -0.608761429008721),
                    new Grad2( 0.608761429008721, -0.793353340291235),
                    new Grad2( 0.38268343236509,  -0.923879532511287),
                    new Grad2( 0.130526192220052, -0.99144486137381),
                    new Grad2(-0.130526192220052, -0.99144486137381),
                    new Grad2(-0.38268343236509,  -0.923879532511287),
                    new Grad2(-0.608761429008721, -0.793353340291235),
                    new Grad2(-0.793353340291235, -0.608761429008721),
                    new Grad2(-0.923879532511287, -0.38268343236509),
                    new Grad2(-0.99144486137381,  -0.130526192220051),
                    new Grad2(-0.99144486137381,   0.130526192220051),
                    new Grad2(-0.923879532511287,  0.38268343236509),
                    new Grad2(-0.793353340291235,  0.608761429008721),
                    new Grad2(-0.608761429008721,  0.793353340291235),
                    new Grad2(-0.38268343236509,   0.923879532511287),
                    new Grad2(-0.130526192220052,  0.99144486137381)
            };
            GRADIENTS_2D = new Grad2[PSIZE];
            for (int i = 0; i < PSIZE; i++) GRADIENTS_2D[i] = grad2[i % grad2.length];

            // 3D gradients
            Grad3[] grad3 = {
                    new Grad3(-2.22474487139,  -2.22474487139, -1.0),
                    new Grad3(-2.22474487139,  -2.22474487139,  1.0),
                    new Grad3(-3.0862664687972017, -1.1721513422464978, 0.0),
                    new Grad3(-1.1721513422464978, -3.0862664687972017, 0.0),
                    new Grad3(-2.22474487139,  -1.0,           -2.22474487139),
                    new Grad3(-2.22474487139,   1.0,           -2.22474487139),
                    new Grad3(-1.1721513422464978, 0.0,        -3.0862664687972017),
                    new Grad3(-3.0862664687972017, 0.0,        -1.1721513422464978),
                    new Grad3(-2.22474487139,  -1.0,            2.22474487139),
                    new Grad3(-2.22474487139,   1.0,            2.22474487139),
                    new Grad3(-3.0862664687972017, 0.0,         1.1721513422464978),
                    new Grad3(-1.1721513422464978, 0.0,         3.0862664687972017),
                    new Grad3(-2.22474487139,   2.22474487139, -1.0),
                    new Grad3(-2.22474487139,   2.22474487139,  1.0),
                    new Grad3(-1.1721513422464978, 3.0862664687972017, 0.0),
                    new Grad3(-3.0862664687972017, 1.1721513422464978, 0.0),
                    new Grad3(-1.0,            -2.22474487139, -2.22474487139),
                    new Grad3( 1.0,            -2.22474487139, -2.22474487139),
                    new Grad3( 0.0,            -3.0862664687972017, -1.1721513422464978),
                    new Grad3( 0.0,            -1.1721513422464978, -3.0862664687972017),
                    new Grad3(-1.0,            -2.22474487139,  2.22474487139),
                    new Grad3( 1.0,            -2.22474487139,  2.22474487139),
                    new Grad3( 0.0,            -1.1721513422464978,  3.0862664687972017),
                    new Grad3( 0.0,            -3.0862664687972017,  1.1721513422464978),
                    new Grad3(-1.0,             2.22474487139, -2.22474487139),
                    new Grad3( 1.0,             2.22474487139, -2.22474487139),
                    new Grad3( 0.0,             1.1721513422464978, -3.0862664687972017),
                    new Grad3( 0.0,             3.0862664687972017, -1.1721513422464978),
                    new Grad3(-1.0,             2.22474487139,  2.22474487139),
                    new Grad3( 1.0,             2.22474487139,  2.22474487139),
                    new Grad3( 0.0,             3.0862664687972017,  1.1721513422464978),
                    new Grad3( 0.0,             1.1721513422464978,  3.0862664687972017),
                    new Grad3( 2.22474487139,  -2.22474487139, -1.0),
                    new Grad3( 2.22474487139,  -2.22474487139,  1.0),
                    new Grad3( 1.1721513422464978, -3.0862664687972017, 0.0),
                    new Grad3( 3.0862664687972017, -1.1721513422464978, 0.0),
                    new Grad3( 2.22474487139,  -1.0,           -2.22474487139),
                    new Grad3( 2.22474487139,   1.0,           -2.22474487139),
                    new Grad3( 3.0862664687972017, 0.0,        -1.1721513422464978),
                    new Grad3( 1.1721513422464978, 0.0,        -3.0862664687972017),
                    new Grad3( 2.22474487139,  -1.0,            2.22474487139),
                    new Grad3( 2.22474487139,   1.0,            2.22474487139),
                    new Grad3( 1.1721513422464978, 0.0,         3.0862664687972017),
                    new Grad3( 3.0862664687972017, 0.0,         1.1721513422464978),
                    new Grad3( 2.22474487139,   2.22474487139, -1.0),
                    new Grad3( 2.22474487139,   2.22474487139,  1.0),
                    new Grad3( 3.0862664687972017, 1.1721513422464978, 0.0),
                    new Grad3( 1.1721513422464978, 3.0862664687972017, 0.0)
            };
            GRADIENTS_3D = new Grad3[PSIZE];
            for (int i = 0; i < PSIZE; i++) GRADIENTS_3D[i] = grad3[i % grad3.length];
        }

        // Permutation table seeded per call
        private static int[] buildPerm(long seed) {
            int[] perm = new int[PSIZE];
            int[] source = new int[PSIZE];
            for (int i = 0; i < PSIZE; i++) source[i] = i;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            for (int i = PSIZE - 1; i >= 0; i--) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                int r = (int) ((seed + 31) % (i + 1));
                if (r < 0) r += (i + 1);
                perm[i] = source[r];
                source[r] = source[i];
            }
            return perm;
        }

        // 2D OpenSimplex2S
        public static double noise2(long seed, double x, double y) {
            int[] perm = buildPerm(seed);
            double s = 0.366025403784439 * (x + y);
            double xs = x + s, ys = y + s;
            return noise2_Base(perm, xs, ys);
        }

        private static double noise2_Base(int[] perm, double xs, double ys) {
            double value = 0;
            int xsb = fastFloor(xs), ysb = fastFloor(ys);
            double xsi = xs - xsb, ysi = ys - ysb;
            double a = xsi + ysi;
            int xsvp = xsb * 1723 + ysb * 3609;  // simple hash seed
            // unskew
            double t = (a - 1) * 0.5;
            double xi = xsi + t, yi = ysi + t;

            double attn = 2.0 / 3.0 - xi * xi - yi * yi;
            if (attn > 0) {
                int pxm = (xsvp + perm[ysb & PMASK]) & PMASK;
                Grad2 g = GRADIENTS_2D[perm[pxm]];
                value += attn * attn * attn * attn * (g.dx * xi + g.dy * yi);
            }

            // second point
            double t2 = t + (2.0 * (1.0 - 0.366025403784439) - 1.0);
            double xi2 = xi - (1.0 - 2.0 * 0.366025403784439);
            double yi2 = yi - (1.0 - 2.0 * 0.366025403784439);
            double attn2 = 2.0 / 3.0 - xi2 * xi2 - yi2 * yi2;
            if (attn2 > 0) {
                int pxm2 = (xsvp + 1723 + perm[(ysb + 1) & PMASK]) & PMASK;
                Grad2 g2 = GRADIENTS_2D[perm[pxm2]];
                value += attn2 * attn2 * attn2 * attn2 * (g2.dx * xi2 + g2.dy * yi2);
            }

            return value / N2;
        }

        // 3D OpenSimplex2S — XZ-improved orientation
        public static double noise3_ImproveXZ(long seed, double x, double y, double z) {
            int[] perm = buildPerm(seed);
            double xz = x + z;
            double s2 = xz * -0.211324865405187;
            double yy = y * 0.577350269189626;
            double xr = x + s2 + yy;
            double zr = z + s2 + yy;
            double yr = xz * -0.577350269189626 + yy;
            return noise3_Base(perm, xr, yr, zr);
        }

        private static double noise3_Base(int[] perm, double xr, double yr, double zr) {
            int xrb = fastFloor(xr), yrb = fastFloor(yr), zrb = fastFloor(zr);
            double xi = xr - xrb, yi = yr - yrb, zi = zr - zrb;
            double value = 0;

            // Evaluate 4 vertices of the simplex
            double t = (xi + yi + zi) * (1.0 / 6.0);
            double[] dxs = {xi - t, xi - t + (1.0/6.0), xi - t + (1.0/6.0), xi - t + (1.0/3.0)};
            double[] dys = {yi - t, yi - t + (1.0/6.0), yi - t - (1.0/3.0) + (1.0/6.0), yi - t + (1.0/3.0)};
            double[] dzs = {zi - t, zi - t - (1.0/3.0) + (1.0/6.0), zi - t + (1.0/6.0), zi - t + (1.0/3.0)};

            for (int i = 0; i < 4; i++) {
                double attn = 0.6 - dxs[i] * dxs[i] - dys[i] * dys[i] - dzs[i] * dzs[i];
                if (attn > 0) {
                    int idx = perm[(perm[(perm[xrb & PMASK] ^ (yrb & PMASK)) & PMASK]
                            ^ (zrb & PMASK)) & PMASK];
                    Grad3 g = GRADIENTS_3D[idx];
                    value += attn * attn * attn * attn * (g.dx * dxs[i] + g.dy * dys[i] + g.dz * dzs[i]);
                }
            }
            return value / N3;
        }

        private static int fastFloor(double x) {
            int xi = (int) x;
            return x < xi ? xi - 1 : xi;
        }

        private record Grad2(double dx, double dy) {}
        private record Grad3(double dx, double dy, double dz) {}
    }
}
