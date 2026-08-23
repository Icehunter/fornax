package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-JVM contract for the engine-owned surface-fluid field. */
class SurfaceFluidClipmapBufferTest {

    @Test
    void abiIsA64SquareGridOfExactFourWordRecords() {
        assertEquals("surfaceFluidClipmap", SurfaceFluidClipmapBuffer.TARGET);
        assertEquals(64, SurfaceFluidClipmapBuffer.GRID);
        assertEquals(4096, SurfaceFluidClipmapBuffer.COLUMNS);
        assertEquals(16, SurfaceFluidClipmapBuffer.BYTES_PER_COLUMN);
        assertEquals(65536L, SurfaceFluidClipmapBuffer.BYTE_SIZE);
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
                SurfaceFluidClipmapBuffer.FLUID_WATER);

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
        SurfaceFluidClipmapBuffer.writeRecord(words, 0, 3, -7, 0.0f,
                SurfaceFluidClipmapBuffer.FLUID_DRY);
        assertTrue(SurfaceFluidClipmapBuffer.describes(words, 0, 3, -7));
        assertEquals(SurfaceFluidClipmapBuffer.FLUID_DRY,
                SurfaceFluidClipmapBuffer.fluidKind(words, 0));
        assertFalse(SurfaceFluidClipmapBuffer.describes(new int[4], 0, 3, -7));
        assertFalse(SurfaceFluidClipmapBuffer.describes(new int[4], 0, 0, 0),
                "zero-cleared VRAM must be unknown even at the world origin");
    }

    @Test
    void tierPoliciesHaveBoundedSearchAndRefreshCosts() {
        assertEquals(0, SurfaceFluidClipmapBuffer.rowsPerFrame(SurfaceFluidClipmapBuffer.TIER_OFF));
        assertArrayEquals(new int[] {0, -1, 1},
                SurfaceFluidClipmapBuffer.verticalOffsets(SurfaceFluidClipmapBuffer.TIER_STANDARD));
        assertEquals(8, SurfaceFluidClipmapBuffer.rowsPerFrame(SurfaceFluidClipmapBuffer.TIER_STANDARD));
        assertEquals(3_072, SurfaceFluidClipmapBuffer.maxFluidStateReadsPerFrame(
                SurfaceFluidClipmapBuffer.TIER_STANDARD));

        assertArrayEquals(new int[] {0, -1, 1, -2, 2, -3, 3, -4, 4},
                SurfaceFluidClipmapBuffer.verticalOffsets(SurfaceFluidClipmapBuffer.TIER_QUALITY));
        assertEquals(16, SurfaceFluidClipmapBuffer.rowsPerFrame(SurfaceFluidClipmapBuffer.TIER_QUALITY));
        assertEquals(18_432, SurfaceFluidClipmapBuffer.maxFluidStateReadsPerFrame(
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
