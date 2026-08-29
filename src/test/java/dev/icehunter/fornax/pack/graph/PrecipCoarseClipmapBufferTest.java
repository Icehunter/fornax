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
        assertEquals(65_536L, PrecipCoarseClipmapBuffer.BYTE_SIZE);
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
