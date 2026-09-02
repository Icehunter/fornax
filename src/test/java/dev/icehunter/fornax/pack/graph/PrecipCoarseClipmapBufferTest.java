package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract for the Vulkan-free, coarse nearby-precipitation field ABI. */
class PrecipCoarseClipmapBufferTest {

    @Test
    void theAbiCoversA512BlockSquareInFourBlockCells() {
        assertEquals("precipCoarseClipmap", PrecipCoarseClipmapBuffer.TARGET);
        assertEquals(128, PrecipCoarseClipmapBuffer.GRID);
        assertEquals(4, PrecipCoarseClipmapBuffer.CELL_STRIDE);
        assertEquals(16_384, PrecipCoarseClipmapBuffer.COLUMNS);
        // 128 cells of four blocks each cover the accepted 512-block nearby-weather window.
        assertEquals(512, PrecipCoarseClipmapBuffer.GRID * PrecipCoarseClipmapBuffer.CELL_STRIDE);
        // Four words per cell, one ivec4, the alignment rule SurfaceFluidClipmapBuffer set.
        assertEquals(4, PrecipCoarseClipmapBuffer.WORDS_PER_CELL);
        assertEquals(16, PrecipCoarseClipmapBuffer.BYTES_PER_CELL);
        assertEquals(262_144L, PrecipCoarseClipmapBuffer.BYTE_SIZE);
        assertEquals(0, PrecipCoarseClipmapBuffer.BYTE_SIZE % 4, "vkCmdFillBuffer needs a 4-byte multiple");
        assertEquals(0, PrecipCoarseClipmapBuffer.WORDS_PER_CELL % 4,
                "a cell must be a whole number of ivec4s or shader-side indexing misaligns");
    }

    @Test
    void aCellsFirstWordSitsAtItsSlotTimesTheWordsPerCell() {
        int cellX = -337;
        int cellZ = 22_000;
        assertEquals(PrecipCoarseClipmapBuffer.slotForCell(cellX, cellZ) * 4,
                PrecipCoarseClipmapBuffer.wordOffsetForCell(cellX, cellZ));
        // The last cell's last word must still fall inside the buffer.
        int lastOffset = PrecipCoarseClipmapBuffer.wordOffsetForCell(127, 127) + 3;
        assertTrue((long) (lastOffset + 1) * Integer.BYTES <= PrecipCoarseClipmapBuffer.BYTE_SIZE);
    }

    @Test
    void theClimateWordRoundTripsTemperatureDownfallAndTags() {
        // -0.7 is the coldest vanilla biome; 1.9 the hottest. Fixed point at 1/256 must keep both.
        int cold = PrecipCoarseClipmapBuffer.encodeClimate(-0.7f, 0.0f,
                PrecipCoarseClipmapBuffer.TAG_COLD | PrecipCoarseClipmapBuffer.TAG_MOUNTAIN);
        assertEquals(-0.7f, PrecipCoarseClipmapBuffer.decodeTemperature(cold), 1.0f / 256.0f);
        assertEquals(0.0f, PrecipCoarseClipmapBuffer.decodeDownfall(cold), 0.0f);
        assertEquals(PrecipCoarseClipmapBuffer.TAG_COLD | PrecipCoarseClipmapBuffer.TAG_MOUNTAIN,
                PrecipCoarseClipmapBuffer.decodeTags(cold));

        int hot = PrecipCoarseClipmapBuffer.encodeClimate(1.9f, 0.9f, PrecipCoarseClipmapBuffer.TAG_HOT
                | PrecipCoarseClipmapBuffer.TAG_WET | PrecipCoarseClipmapBuffer.TAG_JUNGLE);
        assertEquals(1.9f, PrecipCoarseClipmapBuffer.decodeTemperature(hot), 1.0f / 256.0f);
        assertEquals(0.9f, PrecipCoarseClipmapBuffer.decodeDownfall(hot), 1.0f / 255.0f);
        assertEquals(PrecipCoarseClipmapBuffer.TAG_HOT | PrecipCoarseClipmapBuffer.TAG_WET
                | PrecipCoarseClipmapBuffer.TAG_JUNGLE, PrecipCoarseClipmapBuffer.decodeTags(hot));

        // Height adjustment subtracts fractions of a degree per block; the scale must resolve it.
        int adjusted = PrecipCoarseClipmapBuffer.encodeClimate(0.15f - 0.00125f, 0.5f, 0);
        assertTrue(PrecipCoarseClipmapBuffer.decodeTemperature(adjusted) < 0.15f,
                "a temperature just below the snow threshold must not round up across it");
    }

    @Test
    void theBaseWordCarriesOnlyTheNominalTemperature() {
        int base = PrecipCoarseClipmapBuffer.encodeBase(0.8f);
        assertEquals(0.8f, PrecipCoarseClipmapBuffer.decodeTemperature(base), 1.0f / 256.0f);
        assertEquals(0, base >>> 16, "the upper half of word 2 is reserved and written zero");
    }

    @Test
    void theTagBitsAreDistinctAndFitTheByte() {
        int all = PrecipCoarseClipmapBuffer.TAG_HOT | PrecipCoarseClipmapBuffer.TAG_COLD
                | PrecipCoarseClipmapBuffer.TAG_WET | PrecipCoarseClipmapBuffer.TAG_DRY
                | PrecipCoarseClipmapBuffer.TAG_OCEAN | PrecipCoarseClipmapBuffer.TAG_JUNGLE
                | PrecipCoarseClipmapBuffer.TAG_BADLANDS | PrecipCoarseClipmapBuffer.TAG_MOUNTAIN;
        assertEquals(0xFF, all);
        assertEquals(8, Integer.bitCount(all));
    }

    @Test
    void blockCoordinatesFloorDivideIntoCellsAcrossTheOrigin() {
        // Java's / truncates -1 / 4 to zero; the cell west of the origin is cell -1.
        assertEquals(-2, PrecipCoarseClipmapBuffer.cellForBlock(-5));
        assertEquals(-1, PrecipCoarseClipmapBuffer.cellForBlock(-4));
        assertEquals(-1, PrecipCoarseClipmapBuffer.cellForBlock(-1));
        assertEquals(0, PrecipCoarseClipmapBuffer.cellForBlock(0));
        assertEquals(0, PrecipCoarseClipmapBuffer.cellForBlock(3));
        assertEquals(1, PrecipCoarseClipmapBuffer.cellForBlock(4));
    }

    @Test
    void eachCellSamplesItsRepresentativeCentreColumn() {
        // In a four-column cell, the selected centre-side column is two blocks from its low edge.
        assertEquals(-2, PrecipCoarseClipmapBuffer.representativeBlock(-1));
        assertEquals(2, PrecipCoarseClipmapBuffer.representativeBlock(0));
        assertEquals(6, PrecipCoarseClipmapBuffer.representativeBlock(1));
        assertEquals(-1, PrecipCoarseClipmapBuffer.cellForBlock(
                PrecipCoarseClipmapBuffer.representativeBlock(-1)));
    }

    @Test
    void theWindowIs512BlocksWideAndSnapsWithFloorDivision() {
        // A zero anchor spans blocks [-256, 255] through 128 four-block cells.
        assertEquals(-64, PrecipCoarseClipmapBuffer.windowBaseCell(0));
        assertEquals(-64, PrecipCoarseClipmapBuffer.windowBaseCell(15));
        assertEquals(-60, PrecipCoarseClipmapBuffer.windowBaseCell(16));
        assertEquals(-68, PrecipCoarseClipmapBuffer.windowBaseCell(-1));
        assertEquals(-68, PrecipCoarseClipmapBuffer.windowBaseCell(-16));
        assertEquals(-72, PrecipCoarseClipmapBuffer.windowBaseCell(-17));
        assertEquals(16, PrecipCoarseClipmapBuffer.ANCHOR_SNAP_BLOCKS);
    }

    @Test
    void aWindowFillsEveryToroidalSlotExactlyOnce() {
        boolean[] seen = new boolean[PrecipCoarseClipmapBuffer.COLUMNS];
        int baseX = PrecipCoarseClipmapBuffer.windowBaseCell(-19);
        int baseZ = PrecipCoarseClipmapBuffer.windowBaseCell(4_099);
        for (int z = 0; z < PrecipCoarseClipmapBuffer.GRID; z++) {
            for (int x = 0; x < PrecipCoarseClipmapBuffer.GRID; x++) {
                int slot = PrecipCoarseClipmapBuffer.slotForCell(baseX + x, baseZ + z);
                assertFalse(seen[slot], "duplicate slot " + slot);
                seen[slot] = true;
            }
        }
        for (boolean slotWasWritten : seen) {
            assertTrue(slotWasWritten);
        }
    }

    @Test
    void tagsRejectCellsThatAliasTheSameToroidalSlot() {
        int cellX = -337;
        int cellZ = 22_000;
        int encoded = PrecipCoarseClipmapBuffer.encodeCell(cellX, cellZ, 2);

        assertEquals(PrecipCoarseClipmapBuffer.slotForCell(cellX, cellZ),
                PrecipCoarseClipmapBuffer.slotForCell(cellX + PrecipCoarseClipmapBuffer.GRID, cellZ));
        assertNotEquals(PrecipCoarseClipmapBuffer.tagForCell(cellX, cellZ),
                PrecipCoarseClipmapBuffer.tagForCell(cellX + PrecipCoarseClipmapBuffer.GRID, cellZ));
        assertTrue(PrecipCoarseClipmapBuffer.describesCell(encoded, cellX, cellZ));
        assertFalse(PrecipCoarseClipmapBuffer.describesCell(encoded,
                cellX + PrecipCoarseClipmapBuffer.GRID, cellZ));
        assertFalse(PrecipCoarseClipmapBuffer.describesCell(encoded, cellX,
                cellZ + PrecipCoarseClipmapBuffer.GRID));
    }

    @Test
    void aZeroClearedWordIsUnknownEvenWhereTheTagWouldOtherwiseMatch() {
        // A full reset writes zero. The low type byte and upper tag alone cannot distinguish that
        // from a valid clear-weather sample in the tag-zero region, so bit eight carries validity.
        assertEquals(0x00000100, PrecipCoarseClipmapBuffer.VALID_MASK);
        assertFalse(PrecipCoarseClipmapBuffer.describesCell(0, 0, 0));
        assertFalse(PrecipCoarseClipmapBuffer.describesCell(0, 128, 128));

        int clear = PrecipCoarseClipmapBuffer.encodeCell(0, 0, 0);
        assertEquals(0, clear & 0xFF, "the low byte stays the precipitation type");
        assertTrue(PrecipCoarseClipmapBuffer.describesCell(clear, 0, 0),
                "an encoded NONE sample remains valid at the former ambiguous tag");
    }

    @Test
    void theSixteenBitTagHasAnExplicitFinitePerAxisHorizon() {
        // Eight retained tile bits per axis repeat after 128 * 256 cells, or 131,072 blocks.
        assertEquals(32_768, PrecipCoarseClipmapBuffer.TAG_PERIOD_CELLS);
        assertEquals(131_072, PrecipCoarseClipmapBuffer.TAG_PERIOD_BLOCKS);

        int cellX = -337;
        int cellZ = 22_000;
        int encoded = PrecipCoarseClipmapBuffer.encodeCell(cellX, cellZ, 2);
        int repeatedCellX = cellX + PrecipCoarseClipmapBuffer.TAG_PERIOD_CELLS;

        assertEquals(PrecipCoarseClipmapBuffer.slotForCell(cellX, cellZ),
                PrecipCoarseClipmapBuffer.slotForCell(repeatedCellX, cellZ));
        assertEquals(PrecipCoarseClipmapBuffer.tagForCell(cellX, cellZ),
                PrecipCoarseClipmapBuffer.tagForCell(repeatedCellX, cellZ));
        assertTrue(PrecipCoarseClipmapBuffer.describesCell(encoded, repeatedCellX, cellZ),
                "a discontinuous recenter must clear and refill before consumers trust this tag");
    }
}
