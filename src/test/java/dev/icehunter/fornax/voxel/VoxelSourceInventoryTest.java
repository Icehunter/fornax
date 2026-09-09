package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoxelSourceInventoryTest {
    @Test void headerAndSlotsHaveDistinctAlignedRanges() {
        // Eight header words plus 27 slots of eight words, four bytes per word.
        assertEquals(896, VoxelSourceInventory.bufferBytes(3));
        assertEquals(32, VoxelSourceInventory.slotByteOffset(0));
        assertEquals(64, VoxelSourceInventory.slotByteOffset(1));
        assertThrows(IllegalArgumentException.class, () -> VoxelSourceInventory.slotByteOffset(-1));
        ByteBuffer bytes = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        VoxelSourceInventory.packHeader(0x1_00000002L, bytes);
        int[] words = new int[8];
        bytes.asIntBuffer().get(words);
        assertArrayEquals(new int[] {2, 1, 1, 0, 0, 0, 0, 0}, words);
    }

    @Test void replacingCommittedSlotDoesNotDoubleCountAndInvalidatingSubtractsItsInventory() {
        var inventory = new VoxelSourceInventory();
        inventory.commit(1, new VoxelSourceSummary(8, 2, 3, 6, 4, 1, 10));
        inventory.commit(2, new VoxelSourceSummary(8, 5, 7, 9, 1, 0, 20));
        inventory.commit(1, new VoxelSourceSummary(8, 1, 0, 0, 2, 0, 5));
        var stats = inventory.stats();
        assertEquals(2, stats.committedSlots());
        assertEquals(6, stats.intrinsicCandidateCells());
        assertEquals(7, stats.authoredCandidateCells());
        assertEquals(9, stats.authoredCandidateFaces());
        assertEquals(3, stats.unknownCells());
        assertEquals(25, stats.nonemptyCells());
        assertEquals(0, stats.overflowSlots());
        assertEquals(3, stats.committedUploads());
        inventory.invalidate(List.of(2));
        stats = inventory.stats();
        assertEquals(1, stats.committedSlots());
        assertEquals(1, stats.intrinsicCandidateCells());
        assertEquals(5, stats.nonemptyCells());
        assertEquals(1, stats.clearedSlots());
        inventory.dropped();
        assertEquals(1, inventory.stats().staleUploads());
        inventory.reset();
        assertEquals(new VoxelSourceInventory.Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), inventory.stats());
    }
}
