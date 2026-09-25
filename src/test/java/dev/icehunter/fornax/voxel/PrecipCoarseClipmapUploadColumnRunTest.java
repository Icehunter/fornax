package dev.icehunter.fornax.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.icehunter.fornax.pack.graph.PrecipCoarseClipmapBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure behavioural contract for the run splitter fillColumnSlots and fillChunkSquare both share:
 * no world or buffer access, so every case here is checked without a Minecraft level. */
class PrecipCoarseClipmapUploadColumnRunTest {

    @Test
    void aStripThatWrapsTheGridSeamSplitsIntoExactlyTwoRuns() {
        int[] slots = {126, 127, 0, 1};
        List<PrecipCoarseClipmapUpload.ColumnRun> runs = PrecipCoarseClipmapUpload.columnRuns(0, 5, slots);

        assertEquals(2, runs.size(), "127 then 0 is not a continuation: word 127 and word 0 of a"
                + " row sit BYTES_PER_CELL * 127 apart, not adjacent");

        PrecipCoarseClipmapUpload.ColumnRun first = runs.get(0);
        assertEquals(0, first.startIndex());
        assertEquals((long) PrecipCoarseClipmapBuffer.wordOffsetForCell(126, 5) * Integer.BYTES, first.byteOffset());
        assertEquals(2 * PrecipCoarseClipmapBuffer.BYTES_PER_CELL, first.lengthBytes());

        PrecipCoarseClipmapUpload.ColumnRun second = runs.get(1);
        assertEquals(2, second.startIndex());
        assertEquals((long) PrecipCoarseClipmapBuffer.wordOffsetForCell(0, 5) * Integer.BYTES, second.byteOffset());
        assertEquals(2 * PrecipCoarseClipmapBuffer.BYTES_PER_CELL, second.lengthBytes());
    }

    @Test
    void aStripThatDoesNotWrapPublishesAsOneRun() {
        int[] slots = {4, 5, 6, 7};
        List<PrecipCoarseClipmapUpload.ColumnRun> runs = PrecipCoarseClipmapUpload.columnRuns(0, 9, slots);

        assertEquals(1, runs.size());
        assertEquals(0, runs.get(0).startIndex());
        assertEquals((long) PrecipCoarseClipmapBuffer.wordOffsetForCell(4, 9) * Integer.BYTES,
                runs.get(0).byteOffset());
        assertEquals(4 * PrecipCoarseClipmapBuffer.BYTES_PER_CELL, runs.get(0).lengthBytes());
    }

    @Test
    void aSingleSlotIsOneRunOfOneCell() {
        List<PrecipCoarseClipmapUpload.ColumnRun> runs =
                PrecipCoarseClipmapUpload.columnRuns(0, 0, new int[] {42});

        assertEquals(1, runs.size());
        assertEquals(0, runs.get(0).startIndex());
        assertEquals(PrecipCoarseClipmapBuffer.BYTES_PER_CELL, runs.get(0).lengthBytes());
    }

    @Test
    void anEmptyStripPublishesNothing() {
        assertEquals(List.of(), PrecipCoarseClipmapUpload.columnRuns(0, 0, new int[0]));
    }
}
