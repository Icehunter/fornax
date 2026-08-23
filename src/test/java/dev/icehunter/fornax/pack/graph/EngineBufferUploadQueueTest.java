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
    void discardRemovesAnUnconsumedPublication() {
        EngineBufferUploadQueue.publish(TARGET, false, List.of());
        assertTrue(EngineBufferUploadQueue.hasPending(TARGET));
        EngineBufferUploadQueue.discard(TARGET);
        assertFalse(EngineBufferUploadQueue.hasPending(TARGET));
    }
}
