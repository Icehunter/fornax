package dev.icehunter.fornax.pass.voxel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightReachClampTest {
    // 16 blocks per section. A representative ceiling value exercising clampSections' pure min/max
    // logic -- not required to track VoxelDebugRaymarchPass.RADIUS_CEILING's own live value (16 as
    // of 2026-07-26), since clampSections takes the ceiling as a plain parameter.
    private static final int CEIL = 12;

    @Test
    void convertsBlocksToSectionsRoundedUp() {
        // 96 blocks = 6 sections, render distance 32 sections, ceiling 12 -> 6.
        assertEquals(6, VoxelDebugRaymarchPass.clampSections(96.0f, 32, CEIL));
    }

    @Test
    void defaultReachReproducesPreOptionCeilingBehavior() {
        // 192 blocks (the declared default AND the option-absent fallback) = 12 sections = the
        // ceiling: a pack without u_LightReach behaves exactly as before the option existed.
        assertEquals(CEIL, VoxelDebugRaymarchPass.clampSections(192.0f, 32, CEIL));
        assertEquals(8, VoxelDebugRaymarchPass.clampSections(192.0f, 8, CEIL));
    }

    @Test
    void clampsToEngineCeiling() {
        // 512 blocks = 32 sections, but ceiling 12 wins.
        assertEquals(12, VoxelDebugRaymarchPass.clampSections(512.0f, 32, CEIL));
    }

    @Test
    void clampsToRenderDistanceWhenSmaller() {
        // 192 blocks = 12 sections, but render distance only 8 -> 8.
        assertEquals(8, VoxelDebugRaymarchPass.clampSections(192.0f, 8, CEIL));
    }

    @Test
    void neverBelowOne() {
        assertEquals(1, VoxelDebugRaymarchPass.clampSections(0.0f, 32, CEIL));
    }

    @Test
    void roundsUpPartialSection() {
        // 40 blocks = 2.5 sections -> ceil to 3.
        assertEquals(3, VoxelDebugRaymarchPass.clampSections(40.0f, 32, CEIL));
    }
}
