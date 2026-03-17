package com.musicworld.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainBlendTest {

    // -------------------------------------------------------------------------
    // smoothStep
    // -------------------------------------------------------------------------

    @Test
    void smoothStep_atZero_returnsZero() {
        assertEquals(0.0, TerrainBlend.smoothStep(0.0), 1e-9);
    }

    @Test
    void smoothStep_atOne_returnsOne() {
        assertEquals(1.0, TerrainBlend.smoothStep(1.0), 1e-9);
    }

    @Test
    void smoothStep_atHalf_returnsHalf() {
        assertEquals(0.5, TerrainBlend.smoothStep(0.5), 1e-9);
    }

    @Test
    void smoothStep_clampsBelowZero() {
        assertEquals(0.0, TerrainBlend.smoothStep(-5.0), 1e-9);
    }

    @Test
    void smoothStep_clampsAboveOne() {
        assertEquals(1.0, TerrainBlend.smoothStep(5.0), 1e-9);
    }

    @Test
    void smoothStep_isMonotonicallyIncreasing() {
        double prev = 0.0;
        for (int i = 1; i <= 10; i++) {
            double t = i / 10.0;
            double val = TerrainBlend.smoothStep(t);
            assertTrue(val > prev, "Expected monotonic increase at t=" + t);
            prev = val;
        }
    }

    // -------------------------------------------------------------------------
    // blendFactor — 2D radial from switch point
    // -------------------------------------------------------------------------

    @Test
    void blendFactor_atSwitchPoint_returnsZero() {
        // Exactly at the switch chunk → fully old genre (distance = 0)
        int sx = 50, sz = 50;
        double factor = TerrainBlend.blendFactor(sx * 16, sz * 16, sx, sz);
        assertEquals(0.0, factor, 1e-9);
    }

    @Test
    void blendFactor_farFromSwitchInAllDirections_returnsOne() {
        int sx = 0, sz = 0;
        int farDist = (TerrainBlend.BLEND_RADIUS + 5) * 16;
        // Far east
        assertEquals(1.0, TerrainBlend.blendFactor( farDist, 0,        sx, sz), 1e-9);
        // Far north
        assertEquals(1.0, TerrainBlend.blendFactor(0,         farDist, sx, sz), 1e-9);
        // Far diagonal
        assertEquals(1.0, TerrainBlend.blendFactor( farDist,  farDist, sx, sz), 1e-9);
    }

    @Test
    void blendFactor_closeToSwitchInAllDirections_isLessThanOne() {
        int sx = 0, sz = 0;
        int nearDist = (TerrainBlend.BLEND_RADIUS - 2) * 16;
        // Close east
        assertTrue(TerrainBlend.blendFactor(nearDist, 0, sx, sz) < 1.0);
        // Close north
        assertTrue(TerrainBlend.blendFactor(0, nearDist, sx, sz) < 1.0);
    }

    @Test
    void blendFactor_increaseWithDistanceFromSwitch() {
        int sx = 0, sz = 0;
        double prev = TerrainBlend.blendFactor(0, 0, sx, sz);
        for (int d = 1; d <= TerrainBlend.BLEND_RADIUS + 2; d++) {
            double factor = TerrainBlend.blendFactor(d * 16, 0, sx, sz);
            assertTrue(factor >= prev, "blend factor should increase with distance at d=" + d);
            prev = factor;
        }
    }

    @Test
    void blendFactor_symmetricAroundSwitchPoint() {
        int sx = 10, sz = 10;
        int offset = 3 * 16;
        double east  = TerrainBlend.blendFactor(sx * 16 + offset, sz * 16,          sx, sz);
        double west  = TerrainBlend.blendFactor(sx * 16 - offset, sz * 16,          sx, sz);
        double north = TerrainBlend.blendFactor(sx * 16,          sz * 16 + offset, sx, sz);
        double south = TerrainBlend.blendFactor(sx * 16,          sz * 16 - offset, sx, sz);
        assertEquals(east, west,  1e-9, "blend should be symmetric east/west");
        assertEquals(east, north, 1e-9, "blend should be symmetric east/north");
        assertEquals(east, south, 1e-9, "blend should be symmetric east/south");
    }

    // -------------------------------------------------------------------------
    // blendParam
    // -------------------------------------------------------------------------

    @Test
    void blendParam_atSwitchPoint_returnsOldValue() {
        // At the switch point, distance=0, so we get the old genre value
        int sx = 50, sz = 50;
        double result = TerrainBlend.blendParam(60.0, 80.0, sx * 16, sz * 16, sx, sz);
        assertEquals(60.0, result, 1e-6);
    }

    @Test
    void blendParam_farFromSwitch_returnsNewValue() {
        int sx = 0, sz = 0;
        int farX = (TerrainBlend.BLEND_RADIUS + 5) * 16;
        double result = TerrainBlend.blendParam(64.0, 80.0, farX, 0, sx, sz);
        assertEquals(80.0, result, 1e-6);
    }

    @Test
    void blendParam_staysWithinOldAndNewBounds() {
        int sx = 0, sz = 0;
        double oldVal = 64.0, newVal = 80.0;
        for (int d = -12; d <= 12; d++) {
            double result = TerrainBlend.blendParam(oldVal, newVal, d * 16, d * 16, sx, sz);
            assertTrue(result >= oldVal && result <= newVal,
                "blendParam out of bounds at d=" + d + ": " + result);
        }
    }
}
