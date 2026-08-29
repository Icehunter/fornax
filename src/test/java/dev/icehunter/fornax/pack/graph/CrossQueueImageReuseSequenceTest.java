package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CrossQueueImageReuseSequenceTest {
    @Test
    void publishedGraphicsCompletionBecomesTheNextComputeWait() {
        CrossQueueImageReuseSequence sequence = new CrossQueueImageReuseSequence();

        CrossQueueImageReuseSequence.Ticket first = sequence.beginWrite();
        assertEquals(0L, first.waitValue());
        assertEquals(1L, first.releaseValue());
        sequence.publishGraphicsCompletion(first);

        CrossQueueImageReuseSequence.Ticket second = sequence.beginWrite();
        assertEquals(1L, second.waitValue());
        assertEquals(2L, second.releaseValue());
        sequence.publishGraphicsCompletion(second);

        CrossQueueImageReuseSequence.Ticket third = sequence.beginWrite();
        assertEquals(2L, third.waitValue());
        assertEquals(3L, third.releaseValue());
    }

    @Test
    void cancelledWriteLeavesTheSameWaitAndReleasePairAvailable() {
        CrossQueueImageReuseSequence sequence = new CrossQueueImageReuseSequence();
        CrossQueueImageReuseSequence.Ticket cancelled = sequence.beginWrite();

        sequence.cancel(cancelled);

        assertEquals(cancelled, sequence.beginWrite());
    }

    @Test
    void aSecondWriteCannotBeginBeforeGraphicsCompletionIsPublished() {
        CrossQueueImageReuseSequence sequence = new CrossQueueImageReuseSequence();
        sequence.beginWrite();

        assertThrows(IllegalStateException.class, sequence::beginWrite);
    }
}
