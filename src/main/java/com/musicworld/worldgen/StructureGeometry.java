package com.musicworld.worldgen;

/**
 * Pure geometry helpers for structure placement. No Minecraft dependencies.
 */
public final class StructureGeometry {

    private StructureGeometry() {}

    /** Maximum blocks we will fill downward for terrain grounding. */
    public static final int MAX_GROUNDING_DEPTH = 8;

    /**
     * How many blocks to fill downward from {@code baseY} so the structure
     * doesn't float. Uses the lowest of the surrounding sampled heights.
     *
     * @param baseY              Y level where the structure floor sits
     * @param surroundingHeights sampled terrain heights at the structure corners/edges
     * @return depth to fill (0 = no fill needed), capped at MAX_GROUNDING_DEPTH
     */
    public static int groundingDepth(int baseY, int[] surroundingHeights) {
        int lowest = baseY;
        for (int h : surroundingHeights) {
            if (h < lowest) lowest = h;
        }
        int depth = baseY - lowest;
        return Math.min(depth, MAX_GROUNDING_DEPTH);
    }

    /**
     * Returns evenly-spaced window X-offsets along a wall of half-width {@code halfW}.
     * Windows are placed strictly inside the wall (not at corners).
     * Spacing is 2 blocks, centered on the wall.
     */
    public static int[] windowPositions(int halfW) {
        // Interior span: -(halfW-1) to (halfW-1), step 2
        int interior = halfW - 1;
        // Count: positions at -interior, -interior+2, ..., interior (step 2)
        int count = interior + 1; // interior/2 * 2 positions either side + center
        // Simpler: positions are every even offset from -(halfW-1) to +(halfW-1)
        int start = -(interior);
        int end = interior;
        int size = 0;
        for (int p = start; p <= end; p += 2) size++;
        int[] result = new int[size];
        int idx = 0;
        for (int p = start; p <= end; p += 2) result[idx++] = p;
        return result;
    }

    /**
     * Returns Y offsets (relative to tower base) where sea lanterns should be
     * placed in a tower of {@code towerHeight}, with {@code spacing} between levels.
     * First lantern at {@code spacing}, last before {@code towerHeight}.
     */
    public static int[] towerLightLevels(int towerHeight, int spacing) {
        int count = 0;
        for (int y = spacing; y < towerHeight; y += spacing) count++;
        int[] levels = new int[count];
        int idx = 0;
        for (int y = spacing; y < towerHeight; y += spacing) levels[idx++] = y;
        return levels;
    }
}
