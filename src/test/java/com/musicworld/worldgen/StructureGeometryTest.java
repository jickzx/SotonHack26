package com.musicworld.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructureGeometryTest {

    // -------------------------------------------------------------------------
    // groundingDepth: how many blocks to fill downward from base
    // -------------------------------------------------------------------------

    @Test
    void groundingDepth_flatTerrain_returnsZero() {
        // If all surrounding heights equal the base height, no fill needed
        int baseY = 64;
        int[] surroundingHeights = {64, 64, 64, 64};
        assertEquals(0, StructureGeometry.groundingDepth(baseY, surroundingHeights));
    }

    @Test
    void groundingDepth_slightSlope_returnsCorrectDepth() {
        // Base is at 64, lowest corner is at 60 → need to fill 4 down
        int baseY = 64;
        int[] surroundingHeights = {64, 62, 61, 60};
        assertEquals(4, StructureGeometry.groundingDepth(baseY, surroundingHeights));
    }

    @Test
    void groundingDepth_steepSlope_capsAtMaxGrounding() {
        // Extreme drop — should cap so we don't fill 50 blocks down
        int baseY = 64;
        int[] surroundingHeights = {64, 20, 15, 10};
        int depth = StructureGeometry.groundingDepth(baseY, surroundingHeights);
        assertTrue(depth <= StructureGeometry.MAX_GROUNDING_DEPTH,
            "Grounding depth should be capped at " + StructureGeometry.MAX_GROUNDING_DEPTH);
    }

    @Test
    void groundingDepth_baseHigherThanSurroundings_usesLowestSurrounding() {
        // Structure base at 70, surroundings at 65 → fill 5 blocks
        int baseY = 70;
        int[] surroundingHeights = {70, 68, 66, 65};
        assertEquals(5, StructureGeometry.groundingDepth(baseY, surroundingHeights));
    }

    @Test
    void groundingDepth_singleSurrounding_works() {
        int baseY = 64;
        int[] surroundingHeights = {62};
        assertEquals(2, StructureGeometry.groundingDepth(baseY, surroundingHeights));
    }

    // -------------------------------------------------------------------------
    // windowPositions: evenly-spaced wall window X offsets given half-width
    // -------------------------------------------------------------------------

    @Test
    void windowPositions_halfWidthThree_returnsThreePositions() {
        // Wall spans -3 to +3 (7 blocks). Windows at every other position,
        // excluding corners: expect positions like -2, 0, 2
        int[] positions = StructureGeometry.windowPositions(3);
        assertEquals(3, positions.length);
    }

    @Test
    void windowPositions_halfWidthFour_returnsFourPositions() {
        int[] positions = StructureGeometry.windowPositions(4);
        assertEquals(4, positions.length);
    }

    @Test
    void windowPositions_allPositionsWithinWall() {
        int halfW = 4;
        for (int pos : StructureGeometry.windowPositions(halfW)) {
            assertTrue(Math.abs(pos) < halfW,
                "Window position " + pos + " should be strictly inside wall (< " + halfW + ")");
        }
    }

    @Test
    void windowPositions_noTwoPositionsEqual() {
        int[] positions = StructureGeometry.windowPositions(5);
        for (int i = 0; i < positions.length; i++) {
            for (int j = i + 1; j < positions.length; j++) {
                assertNotEquals(positions[i], positions[j],
                    "Duplicate window position: " + positions[i]);
            }
        }
    }

    // -------------------------------------------------------------------------
    // towerLightLevels: Y offsets where sea lanterns appear in a tower
    // -------------------------------------------------------------------------

    @Test
    void towerLightLevels_returnsSpacedLevels() {
        int towerHeight = 24;
        int spacing = 6;
        int[] levels = StructureGeometry.towerLightLevels(towerHeight, spacing);
        // Expect lanterns at 6, 12, 18 (not at 0 or 24)
        assertEquals(3, levels.length);
    }

    @Test
    void towerLightLevels_allLevelsWithinHeight() {
        int towerHeight = 30;
        for (int level : StructureGeometry.towerLightLevels(towerHeight, 6)) {
            assertTrue(level > 0 && level < towerHeight,
                "Light level " + level + " out of bounds for height " + towerHeight);
        }
    }

    @Test
    void towerLightLevels_spacingRespected() {
        int[] levels = StructureGeometry.towerLightLevels(30, 5);
        for (int i = 1; i < levels.length; i++) {
            assertEquals(5, levels[i] - levels[i - 1],
                "Light levels not evenly spaced");
        }
    }
}
