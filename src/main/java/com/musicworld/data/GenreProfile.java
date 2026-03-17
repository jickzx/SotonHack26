package com.musicworld.data;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable data record describing the world generation parameters for a music genre.
 */
public class GenreProfile {

    public final float  terrainRoughness;
    public final int    baseHeight;
    public final float  mountainFrequency;
    public final float  caveFrequency;
    public final String primaryBlock;
    public final String secondaryBlock;
    public final String surfaceBlock;
    public final float  treeFrequency;
    public final int    waterLevel;
    public final String structureType;

    public GenreProfile(
            float  terrainRoughness,
            int    baseHeight,
            float  mountainFrequency,
            float  caveFrequency,
            String primaryBlock,
            String secondaryBlock,
            String surfaceBlock,
            float  treeFrequency,
            int    waterLevel,
            String structureType) {
        this.terrainRoughness  = terrainRoughness;
        this.baseHeight        = baseHeight;
        this.mountainFrequency = mountainFrequency;
        this.caveFrequency     = caveFrequency;
        this.primaryBlock      = primaryBlock;
        this.secondaryBlock    = secondaryBlock;
        this.surfaceBlock      = surfaceBlock;
        this.treeFrequency     = treeFrequency;
        this.waterLevel        = waterLevel;
        this.structureType     = structureType;
    }

    // -------------------------------------------------------------------------
    // Genre registry — keys are lowercase for case-insensitive lookup
    // -------------------------------------------------------------------------

    public static final Map<String, GenreProfile> GENRES = new HashMap<>();

    static {
        // metal: dramatic rugged mountains, dark stone bulk, obsidian veins, gravel surface
        GENRES.put("metal", new GenreProfile(
                0.8f, 75, 0.7f, 0.7f,
                "STONE", "BLACKSTONE", "GRAVEL",
                0.1f, 50, "PILLARS"));

        // jazz: gentle rolling hills, warm stone, grass surface, moderate water
        GENRES.put("jazz", new GenreProfile(
                0.5f, 65, 0.3f, 0.4f,
                "STONE", "SMOOTH_STONE", "GRASS_BLOCK",
                0.5f, 58, "BUILDINGS"));

        // classical: mild elevation, clean stone with quartz veins, grassy, many trees
        GENRES.put("classical", new GenreProfile(
                0.3f, 70, 0.2f, 0.2f,
                "STONE", "QUARTZ_BLOCK", "GRASS_BLOCK",
                0.6f, 60, "COLUMNS"));

        // hiphop: flat urban terrain, stone bulk, concrete accent, dirt surface
        GENRES.put("hiphop", new GenreProfile(
                0.5f, 66, 0.3f, 0.6f,
                "STONE", "GRAY_CONCRETE", "DIRT",
                0.2f, 55, "PLATFORMS"));

        // electronic: alien hills, end stone bulk, soul sand surface, no trees
        GENRES.put("electronic", new GenreProfile(
                0.7f, 72, 0.6f, 0.8f,
                "END_STONE", "END_STONE_BRICKS", "SOUL_SAND",
                0.0f, 55, "NONE"));

        // pop: very flat, bright stone, diorite accents, grassy, dense trees
        GENRES.put("pop", new GenreProfile(
                0.2f, 64, 0.1f, 0.2f,
                "STONE", "DIORITE", "GRASS_BLOCK",
                0.8f, 62, "GAZEBOS"));

        // ambient: near-flat marshland, stone base, clay pockets, moss surface
        GENRES.put("ambient", new GenreProfile(
                0.15f, 62, 0.05f, 0.1f,
                "STONE", "CLAY", "MOSS_BLOCK",
                0.4f, 60, "RUINS"));
    }
}
