package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-JVM contract for the engine-owned surface-fluid field. */
class SurfaceFluidClipmapBufferTest {

    @Test
    void abiIsA64SquareGridOfExactFourWordRecords() {
        assertEquals("surfaceFluidClipmap", SurfaceFluidClipmapBuffer.TARGET);
        assertEquals(64, SurfaceFluidClipmapBuffer.GRID);
        assertEquals(4096, SurfaceFluidClipmapBuffer.COLUMNS);
        assertEquals(32, SurfaceFluidClipmapBuffer.BYTES_PER_COLUMN);
        assertEquals(131072L, SurfaceFluidClipmapBuffer.BYTE_SIZE);
        assertEquals(0, SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN % 4,
                "a column must be a whole number of ivec4s or shader-side indexing misaligns");
        assertEquals(0L, SurfaceFluidClipmapBuffer.BYTE_SIZE % 4L);
    }

    @Test
    void oneWindowFillsEveryToroidalSlotExactlyOnceIncludingNegativeCoordinates() {
        boolean[] seen = new boolean[SurfaceFluidClipmapBuffer.COLUMNS];
        int baseX = SurfaceFluidClipmapBuffer.windowBase(-19);
        int baseZ = SurfaceFluidClipmapBuffer.windowBase(4099);
        for (int dz = 0; dz < SurfaceFluidClipmapBuffer.GRID; dz++) {
            for (int dx = 0; dx < SurfaceFluidClipmapBuffer.GRID; dx++) {
                int slot = SurfaceFluidClipmapBuffer.slotFor(baseX + dx, baseZ + dz);
                assertFalse(seen[slot], "duplicate slot " + slot);
                seen[slot] = true;
            }
        }
        for (boolean value : seen) assertTrue(value);
    }

    @Test
    void recordRoundTripsExactCoordinatesHeightAndFluidKind() {
        int[] words = new int[SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN];
        SurfaceFluidClipmapBuffer.writeRecord(words, 0, -1_337, 88_000, 63.875f,
                SurfaceFluidClipmapBuffer.NO_SOLID_TOP, SurfaceFluidClipmapBuffer.FLUID_WATER,
                0.0, 0.0);

        assertTrue(SurfaceFluidClipmapBuffer.describes(words, 0, -1_337, 88_000));
        assertFalse(SurfaceFluidClipmapBuffer.describes(words, 0, -1_337 + 64, 88_000));
        assertFalse(SurfaceFluidClipmapBuffer.describes(words, 0, -1_337, 88_000 + 64));
        assertEquals(63.875f, SurfaceFluidClipmapBuffer.surfaceY(words, 0));
        assertEquals(SurfaceFluidClipmapBuffer.FLUID_WATER,
                SurfaceFluidClipmapBuffer.fluidKind(words, 0));
    }

    @Test
    void loadedDryIsDistinctFromUnknown() {
        int[] words = new int[SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN];
        SurfaceFluidClipmapBuffer.writeRecord(words, 0, 3, -7, 64.0f, 64.0f,
                SurfaceFluidClipmapBuffer.FLUID_DRY, 0.0, 0.0);
        assertTrue(SurfaceFluidClipmapBuffer.describes(words, 0, 3, -7));
        assertEquals(SurfaceFluidClipmapBuffer.FLUID_DRY,
                SurfaceFluidClipmapBuffer.fluidKind(words, 0));
        assertFalse(SurfaceFluidClipmapBuffer.describes(new int[4], 0, 3, -7));
        assertFalse(SurfaceFluidClipmapBuffer.describes(new int[4], 0, 0, 0),
                "zero-cleared VRAM must be unknown even at the world origin");
    }

    @Test
    void dryColumnsCarryTheirTerrainTopSoOvertopIsAnswerable() {
        int[] words = new int[SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN];
        SurfaceFluidClipmapBuffer.writeRecord(words, 0, 523, -869, 63.0f, 63.0f,
                SurfaceFluidClipmapBuffer.FLUID_DRY, 0.0, 0.0);
        assertEquals(63.0f, SurfaceFluidClipmapBuffer.surfaceY(words, 0),
                "a dry column publishing 0 makes a wet neighbour's crest uncomparable");
        assertFalse(SurfaceFluidClipmapBuffer.isFlowing(words, 0));
    }

    @Test
    void flowSurvivesQuantizationWithSignAndTheFlowingFlagTracksIt() {
        int[] words = new int[SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN];
        SurfaceFluidClipmapBuffer.writeRecord(words, 0, 1, 2, 62.9f,
                SurfaceFluidClipmapBuffer.NO_SOLID_TOP, SurfaceFluidClipmapBuffer.FLUID_WATER,
                -1.0, 0.5);
        assertEquals(-1.0f, SurfaceFluidClipmapBuffer.flowX(words, 0), 1.0f / 127.0f);
        assertEquals(0.5f, SurfaceFluidClipmapBuffer.flowZ(words, 0), 1.0f / 127.0f);
        assertTrue(SurfaceFluidClipmapBuffer.isFlowing(words, 0));
        assertEquals(SurfaceFluidClipmapBuffer.FLUID_WATER,
                SurfaceFluidClipmapBuffer.fluidKind(words, 0));
        assertEquals(62.9f, SurfaceFluidClipmapBuffer.surfaceY(words, 0),
                "flow bits must not reach the height word");

        SurfaceFluidClipmapBuffer.writeRecord(words, 0, 1, 2, 62.9f,
                SurfaceFluidClipmapBuffer.NO_SOLID_TOP, SurfaceFluidClipmapBuffer.FLUID_WATER,
                0.0, 0.0);
        assertFalse(SurfaceFluidClipmapBuffer.isFlowing(words, 0),
                "a still source must not read as flowing");

        // Out-of-range input clamps rather than wrapping into the kind or valid bits.
        SurfaceFluidClipmapBuffer.writeRecord(words, 0, 1, 2, 62.9f,
                SurfaceFluidClipmapBuffer.NO_SOLID_TOP, SurfaceFluidClipmapBuffer.FLUID_LAVA,
                -9.0, 9.0);
        assertEquals(-1.0f, SurfaceFluidClipmapBuffer.flowX(words, 0), 1.0f / 127.0f);
        assertEquals(1.0f, SurfaceFluidClipmapBuffer.flowZ(words, 0), 1.0f / 127.0f);
        assertEquals(SurfaceFluidClipmapBuffer.FLUID_LAVA,
                SurfaceFluidClipmapBuffer.fluidKind(words, 0));
        assertTrue(SurfaceFluidClipmapBuffer.describes(words, 0, 1, 2));
    }

    @Test
    void twoBodiesAtUnrelatedElevationsBothResolveInOneWindow() {
        // A lake surface at y=62 and a canal surface at y=70, each searched from its own top.
        int lake = SurfaceFluidClipmapBuffer.findExposedFluidY(63, 4,
                y -> y <= 62 ? SurfaceFluidClipmapBuffer.FLUID_WATER
                        : SurfaceFluidClipmapBuffer.FLUID_DRY);
        int canal = SurfaceFluidClipmapBuffer.findExposedFluidY(71, 4,
                y -> y <= 70 ? SurfaceFluidClipmapBuffer.FLUID_WATER
                        : SurfaceFluidClipmapBuffer.FLUID_DRY);
        assertEquals(62, lake);
        assertEquals(70, canal, "elevation must be per column, not measured against one reference");
    }

    @Test
    void searchReachesFluidUnderAnOverhangAndStopsAtTheTierDepth() {
        // Dock planks at y=63 over water whose surface is y=62, so the heightmap top is 64.
        IntUnaryOperator dock = y -> y <= 62 ? SurfaceFluidClipmapBuffer.FLUID_WATER
                : SurfaceFluidClipmapBuffer.FLUID_DRY;
        assertEquals(62, SurfaceFluidClipmapBuffer.findExposedFluidY(64, 4, dock));
        assertEquals(SurfaceFluidClipmapBuffer.NO_SURFACE,
                SurfaceFluidClipmapBuffer.findExposedFluidY(64, 1, dock),
                "depth 1 sees only the deck and must report the column dry");
        assertEquals(SurfaceFluidClipmapBuffer.NO_SURFACE,
                SurfaceFluidClipmapBuffer.findExposedFluidY(64, 0, dock));
    }

    @Test
    void onlyTheTopmostSurfaceOfAStackIsReported() {
        // Water from y=58 to y=62 inclusive: every cell but the top has its own kind above it.
        int found = SurfaceFluidClipmapBuffer.findExposedFluidY(63, 8,
                y -> y >= 58 && y <= 62 ? SurfaceFluidClipmapBuffer.FLUID_WATER
                        : SurfaceFluidClipmapBuffer.FLUID_DRY);
        assertEquals(62, found);
    }

    @Test
    void aDeckOverWaterPublishesBothItsFluidSurfaceAndTheTopItPresentsToAWave() {
        // The dock in the reported scene: planks at y=62 over lake water capped at y=61.
        int[] deck = new int[SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN];
        SurfaceFluidClipmapBuffer.writeRecord(deck, 0, 523, -869, 61.889f, 63.0f,
                SurfaceFluidClipmapBuffer.FLUID_WATER, 0.0, 0.0);
        assertEquals(SurfaceFluidClipmapBuffer.FLUID_WATER,
                SurfaceFluidClipmapBuffer.fluidKind(deck, 0),
                "the lake does continue under the deck, so the column is water by kind");
        assertEquals(63.0f, SurfaceFluidClipmapBuffer.solidTopY(deck, 0),
                "and a wave still has to clear the planks, which only solidTopY records");

        // Open water beside it: nothing solid stands over the surface.
        int[] lake = new int[SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN];
        SurfaceFluidClipmapBuffer.writeRecord(lake, 0, 524, -869, 62.889f,
                SurfaceFluidClipmapBuffer.NO_SOLID_TOP, SurfaceFluidClipmapBuffer.FLUID_WATER,
                0.0, 0.0);
        assertTrue(SurfaceFluidClipmapBuffer.solidTopY(lake, 0)
                        < SurfaceFluidClipmapBuffer.surfaceY(lake, 0) - 1.0f,
                "open water must fail a solid-top-at-the-water-line test without a separate flag");
        assertTrue(SurfaceFluidClipmapBuffer.solidTopY(deck, 0)
                        > SurfaceFluidClipmapBuffer.surfaceY(lake, 0),
                "while the deck passes it");
    }

    @Test
    void reservedWordsAreZeroSoALaterFieldCannotInheritStaleBits() {
        int[] words = new int[SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN];
        java.util.Arrays.fill(words, -1);
        SurfaceFluidClipmapBuffer.writeRecord(words, 0, 1, 2, 3.0f, 4.0f,
                SurfaceFluidClipmapBuffer.FLUID_DRY, 0.0, 0.0);
        assertEquals(0, words[5]);
        assertEquals(0, words[6]);
        assertEquals(0, words[7]);
    }

    @Test
    void tierPoliciesHaveBoundedSearchAndRefreshCosts() {
        assertEquals(0, SurfaceFluidClipmapBuffer.rowsPerFrame(SurfaceFluidClipmapBuffer.TIER_OFF));
        assertEquals(0, SurfaceFluidClipmapBuffer.searchDepth(SurfaceFluidClipmapBuffer.TIER_OFF));

        assertEquals(4, SurfaceFluidClipmapBuffer.searchDepth(SurfaceFluidClipmapBuffer.TIER_STANDARD));
        assertEquals(8, SurfaceFluidClipmapBuffer.rowsPerFrame(SurfaceFluidClipmapBuffer.TIER_STANDARD));
        assertEquals(8_192, SurfaceFluidClipmapBuffer.maxFluidStateReadsPerFrame(
                SurfaceFluidClipmapBuffer.TIER_STANDARD));

        assertEquals(8, SurfaceFluidClipmapBuffer.searchDepth(SurfaceFluidClipmapBuffer.TIER_QUALITY));
        assertEquals(16, SurfaceFluidClipmapBuffer.rowsPerFrame(SurfaceFluidClipmapBuffer.TIER_QUALITY));
        assertEquals(24_576, SurfaceFluidClipmapBuffer.maxFluidStateReadsPerFrame(
                SurfaceFluidClipmapBuffer.TIER_QUALITY));
    }

    @Test
    void windowSnapsWithFloorDivisionAndAlwaysContainsAnchor() {
        assertEquals(-32, SurfaceFluidClipmapBuffer.windowBase(0));
        assertEquals(-48, SurfaceFluidClipmapBuffer.windowBase(-1));
        assertEquals(-48, SurfaceFluidClipmapBuffer.windowBase(-16));
        assertEquals(-64, SurfaceFluidClipmapBuffer.windowBase(-17));
        for (int anchor = -5000; anchor <= 5000; anchor += 7) {
            int base = SurfaceFluidClipmapBuffer.windowBase(anchor);
            assertTrue(anchor >= base && anchor < base + SurfaceFluidClipmapBuffer.GRID);
        }
    }
}
