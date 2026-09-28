package dev.icehunter.fornax.voxel;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelUploadQueueTest {
    @Test void independentSlotsSurviveWhileTheNewestPayloadWinsWithinOneSlot() {
        var queue = new VoxelUploadQueue<String>(0);
        queue.publish(0, 2, "old", false);
        queue.publish(0, 5, "other", false);
        queue.publish(0, 2, "new", false);
        assertEquals(List.of(new VoxelUploadQueue.Entry<>(2, "new", false, false),
                new VoxelUploadQueue.Entry<>(5, "other", false, false)), queue.snapshot(2).entries());
    }

    @Test void everyClearRunsEvenWhenTheUploadBudgetIsExhausted() {
        var queue = new VoxelUploadQueue<String>(0);
        queue.publish(0, 1, "first", false);
        queue.clear(0, 2);
        queue.publish(0, 2, "second", false);
        queue.clearLight(0, 3);
        var snapshot = queue.snapshot(1);
        assertEquals(List.of(new VoxelUploadQueue.Entry<>(1, "first", false, false),
                new VoxelUploadQueue.Entry<String>(2, null, true, false),
                new VoxelUploadQueue.Entry<String>(3, null, false, true)), snapshot.entries());
        queue.acknowledge(snapshot);
        assertEquals(List.of(new VoxelUploadQueue.Entry<>(2, "second", false, false)), queue.snapshot(1).entries());
    }

    @Test void occupancyClearDiscardsOldDataWithoutDiscardingItsPendingLightReset() {
        var queue = new VoxelUploadQueue<String>(0);
        queue.publish(0, 4, "old owner", true);
        queue.clear(0, 4);
        assertEquals(List.of(new VoxelUploadQueue.Entry<String>(4, null, true, true)), queue.snapshot(0).entries());
    }

    @Test void lightOnlyPublicationPreservesDataAndOccupancyInvalidation() {
        var queue = new VoxelUploadQueue<String>(0);
        queue.clear(0, 4);
        queue.publish(0, 4, "new owner", false);
        queue.clearLight(0, 4);
        assertEquals(List.of(new VoxelUploadQueue.Entry<>(4, "new owner", true, true)), queue.snapshot(1).entries());
    }

    @Test void anUnacknowledgedTransferRetainsAllWorkForRetry() {
        var queue = new VoxelUploadQueue<String>(0);
        queue.invalidateMetadata(0, 7);
        queue.publish(0, 4, "data", true);
        var failed = queue.snapshot(1);
        assertEquals(failed.entries(), queue.snapshot(1).entries());
        assertTrue(queue.snapshot(1).invalidateMetadata());
        queue.acknowledge(failed);
        assertFalse(queue.hasPending());
    }

    @Test void acknowledgementDoesNotEraseNewerDataOrNewerClears() {
        var queue = new VoxelUploadQueue<String>(0);
        queue.publish(0, 4, "old", true);
        var first = queue.snapshot(1);
        queue.publish(0, 4, "new", true);
        queue.clear(0, 5);
        queue.acknowledge(first);
        assertEquals(List.of(new VoxelUploadQueue.Entry<>(4, "new", false, true),
                new VoxelUploadQueue.Entry<String>(5, null, true, false)), queue.snapshot(1).entries());
        var second = queue.snapshot(1);
        queue.clear(0, 4);
        queue.acknowledge(second);
        assertEquals(List.of(new VoxelUploadQueue.Entry<String>(4, null, true, false)), queue.snapshot(1).entries());
    }

    @Test void acknowledgingAnOldClearKeepsNewDataButAcknowledgingOldDataKeepsANewClear() {
        var queue = new VoxelUploadQueue<String>(0);
        queue.clear(0, 4);
        var clear = queue.snapshot(0);
        queue.publish(0, 4, "new", false);
        queue.acknowledge(clear);
        assertEquals("new", queue.snapshot(1).entries().getFirst().data());
        var data = queue.snapshot(1);
        queue.clear(0, 4);
        queue.acknowledge(data);
        assertNull(queue.snapshot(0).entries().getFirst().data());
        assertTrue(queue.snapshot(0).entries().getFirst().clearOccupancy());
    }

    @Test void metadataChangesRemainMandatoryAndCannotBeAcknowledgedByAnOlderSnapshot() {
        var queue = new VoxelUploadQueue<String>(0);
        queue.invalidateMetadata(0, 7);
        var old = queue.snapshot(0);
        queue.invalidateMetadata(0, 8);
        queue.acknowledge(old);
        var next = queue.snapshot(0);
        assertTrue(next.invalidateMetadata());
        assertEquals(8, next.atlasGeneration());
        queue.acknowledge(next);
        queue.invalidateMetadata(0, 8);
        assertFalse(queue.hasPending());
    }

    @Test void generationReplacementRejectsStaleWorkersAndRequeuesKnownMetadata() {
        var queue = new VoxelUploadQueue<String>(0);
        queue.invalidateMetadata(0, 7);
        queue.publish(0, 4, "old", false);
        var old = queue.snapshot(1);
        queue.replaceGeneration(1);
        queue.publish(1, 4, "new", false);
        assertFalse(queue.publish(0, 5, "stale", false));
        assertFalse(queue.clear(0, 4));
        assertFalse(queue.clearLight(0, 4));
        assertFalse(queue.invalidateMetadata(0, 8));
        queue.acknowledge(old);
        var next = queue.snapshot(1);
        assertEquals(1, next.generation());
        assertEquals(7, next.atlasGeneration());
        assertTrue(next.invalidateMetadata());
        assertEquals(List.of(new VoxelUploadQueue.Entry<>(4, "new", false, false)), next.entries());
    }

    @Test void repeatedGenerationDoesNotDropWorkAndOlderGenerationsCannotReplaceIt() {
        var queue = new VoxelUploadQueue<String>(3);
        queue.publish(3, 4, "data", false);
        queue.replaceGeneration(3);
        assertTrue(queue.hasPending());
        assertThrows(IllegalArgumentException.class, () -> queue.replaceGeneration(2));
    }

    @Test void aSnapshotFromAnotherQueueCannotAcknowledgeCoincidentallyEqualRevisions() {
        var first = new VoxelUploadQueue<String>(0);
        var second = new VoxelUploadQueue<String>(0);
        first.publish(0, 4, "same", false);
        second.publish(0, 4, "same", false);
        assertThrows(IllegalArgumentException.class, () -> second.acknowledge(first.snapshot(1)));
        assertTrue(second.hasPending());
    }

    @Test void closingDropsPendingWorkAndRejectsFurtherPublications() {
        var queue = new VoxelUploadQueue<String>(0);
        queue.publish(0, 4, "data", false);
        queue.close();
        queue.close();
        assertFalse(queue.hasPending());
        assertThrows(IllegalStateException.class, () -> queue.publish(0, 4, "later", false));
        assertThrows(IllegalStateException.class, () -> queue.clear(0, 4));
        assertThrows(IllegalStateException.class, () -> queue.clearLight(0, 4));
        assertThrows(IllegalStateException.class, () -> queue.invalidateMetadata(0, 1));
    }

    @Test void invalidInputsFailBeforeMutatingTheQueueAndSnapshotsAreImmutable() {
        var queue = new VoxelUploadQueue<String>(0);
        assertThrows(IllegalArgumentException.class, () -> queue.publish(0, -1, "data", false));
        assertThrows(NullPointerException.class, () -> queue.publish(0, 1, null, false));
        assertThrows(IllegalArgumentException.class, () -> queue.clear(0, -1));
        assertThrows(IllegalArgumentException.class, () -> queue.clearLight(0, -1));
        assertThrows(IllegalArgumentException.class, () -> queue.snapshot(-1));
        assertFalse(queue.hasPending());
        queue.publish(0, 4, "data", false);
        assertThrows(UnsupportedOperationException.class, () -> queue.snapshot(1).entries().clear());
    }
}
