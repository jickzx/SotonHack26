package com.musicworld.worldgen;

/**
 * Pure math helpers for blending terrain parameters between two genre profiles
 * at a genre-switch boundary. No Minecraft dependencies — fully unit-testable.
 *
 * Blend model:
 *   - When the genre changes, we record the chunk-coordinate of the switch point
 *     (both X and Z — the player's position).
 *   - For each new column we compute the radial distance in chunks from that point.
 *   - Within BLEND_RADIUS chunks the parameters are lerped using a smooth-step
 *     curve. Beyond BLEND_RADIUS the new genre is fully applied.
 *   - Using radial (2D) distance means the transition forms a circle around the
 *     player rather than a straight vertical line, which looks far more natural.
 */
public final class TerrainBlend {

    private TerrainBlend() {}

    /** Radius of the transition zone, in chunks (16 blocks each). */
    public static final int BLEND_RADIUS = 8;

    /**
     * Smooth-step: maps t in [0,1] to a smooth S-curve.
     * t=0 → 0, t=1 → 1, zero derivative at both ends.
     */
    public static double smoothStep(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * Linear interpolation.
     */
    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * Compute the blend factor (0.0 = fully old genre, 1.0 = fully new genre)
     * for a world column at ({@code worldX}, {@code worldZ}), given that the
     * genre switched at chunk ({@code switchChunkX}, {@code switchChunkZ}).
     *
     * Uses radial distance so the transition is a circle, not a straight line.
     * Columns within BLEND_RADIUS chunks of the switch point blend old → new.
     * Columns beyond BLEND_RADIUS are fully new genre (factor = 1).
     */
    public static double blendFactor(int worldX, int worldZ, int switchChunkX, int switchChunkZ) {
        int colChunkX = Math.floorDiv(worldX, 16);
        int colChunkZ = Math.floorDiv(worldZ, 16);
        double dx = colChunkX - switchChunkX;
        double dz = colChunkZ - switchChunkZ;
        double radialDist = Math.sqrt(dx * dx + dz * dz);
        // t=0 at switch point (fully old), t=1 at BLEND_RADIUS (fully new)
        double t = radialDist / BLEND_RADIUS;
        return smoothStep(t);
    }

    /**
     * Blend a single terrain parameter (e.g. baseHeight, terrainRoughness)
     * between old and new values using the radial blend factor for this column.
     */
    public static double blendParam(double oldVal, double newVal,
                                    int worldX, int worldZ,
                                    int switchChunkX, int switchChunkZ) {
        double t = blendFactor(worldX, worldZ, switchChunkX, switchChunkZ);
        return lerp(oldVal, newVal, t);
    }
}
