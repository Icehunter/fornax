package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.graph.EngineBufferUploadQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelEmitterPoolUploadTest {
    @AfterEach void discard() { EngineBufferUploadQueue.discard(VoxelEmitterPool.TARGET); }

    @Test void fullPublicationSplitsIntoFiveContiguousAlignedInlineRanges() {
        var ranges = VoxelEmitterPoolUpload.ranges(ByteBuffer.allocateDirect(VoxelEmitterPool.BYTE_SIZE));
        assertEquals(5, ranges.size());
        long next = 0;
        for (var range : ranges) {
            assertEquals(next, range.offset());
            assertTrue(range.bytes().remaining() <= EngineBufferUploadQueue.MAX_RANGE_BYTES);
            assertEquals(0, range.bytes().remaining() & 3);
            next += range.bytes().remaining();
        }
        assertEquals(VoxelEmitterPool.BYTE_SIZE, next);
        assertEquals(64, ranges.getLast().bytes().remaining());
    }

    @Test void successfulQueuePublicationAcknowledgesOnlyThatVersionAndResetPublishesEmpty() {
        var pool = new VoxelEmitterPool();
        pool.reset(5, 7);
        assertTrue(VoxelEmitterPoolUpload.publish(pool));
        assertEquals(1, pool.stats().publications());
        assertFalse(VoxelEmitterPoolUpload.publish(pool));
        pool.reset(6, 8);
        assertTrue(VoxelEmitterPoolUpload.publish(pool));
        assertEquals(0, pool.stats().stored());
        assertEquals(2, pool.stats().publications());
    }
}
