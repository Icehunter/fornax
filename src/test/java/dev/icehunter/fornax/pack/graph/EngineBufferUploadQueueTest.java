package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EngineBufferUploadQueueTest {
    private static final String TARGET = "testEngineField";

    @AfterEach
    void clear() {
        EngineBufferUploadQueue.discard(TARGET);
    }

    @Test
    void aSkippedConsumerCannotLoseItsPendingClear() {
        EngineBufferUploadQueue.publish(TARGET, true, List.of());
        EngineBufferUploadQueue.publish(TARGET, false, List.of(
                new EngineBufferUploadQueue.Range(0, ByteBuffer.allocateDirect(4))));
        assertTrue(EngineBufferUploadQueue.hasPending(TARGET));
        assertTrue(EngineBufferUploadQueue.pendingClear(TARGET));
    }

    @Test
    void aRangePastTheInlineUpdateLimitIsRefusedByName() {
        // 65 536 is the Vulkan vkCmdUpdateBuffer maximum; one byte more must fail loudly here,
        // not silently at record time.
        assertEquals(65_536, EngineBufferUploadQueue.MAX_RANGE_BYTES);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                EngineBufferUploadQueue.publish(TARGET, false, List.of(
                        new EngineBufferUploadQueue.Range(0, ByteBuffer.allocateDirect(65_540)))));
        assertTrue(error.getMessage().contains(TARGET));
        assertFalse(EngineBufferUploadQueue.hasPending(TARGET), "a refused publication must not be queued");
        EngineBufferUploadQueue.publish(TARGET, false, List.of(
                new EngineBufferUploadQueue.Range(0, ByteBuffer.allocateDirect(65_536))));
        assertTrue(EngineBufferUploadQueue.hasPending(TARGET), "exactly the limit is allowed");
    }

    @Test
    void anUnalignedRangeIsRefusedByName() {
        assertThrows(IllegalArgumentException.class, () ->
                EngineBufferUploadQueue.publish(TARGET, false, List.of(
                        new EngineBufferUploadQueue.Range(0, ByteBuffer.allocateDirect(6)))));
        assertThrows(IllegalArgumentException.class, () ->
                EngineBufferUploadQueue.publish(TARGET, false, List.of(
                        new EngineBufferUploadQueue.Range(2, ByteBuffer.allocateDirect(4)))));
    }

    @Test
    void discardRemovesAnUnconsumedPublication() {
        EngineBufferUploadQueue.publish(TARGET, false, List.of());
        assertTrue(EngineBufferUploadQueue.hasPending(TARGET));
        EngineBufferUploadQueue.discard(TARGET);
        assertFalse(EngineBufferUploadQueue.hasPending(TARGET));
    }
}
