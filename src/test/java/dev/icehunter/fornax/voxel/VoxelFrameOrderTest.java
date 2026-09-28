package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoxelFrameOrderTest {
    @Test void firstTransferWaitsOnlyTheAlreadySignaledInitialValue() {
        VoxelFrameOrder frame = new VoxelFrameOrder();
        assertEquals(0, frame.begin());
        assertEquals(1, frame.nextRelease());
    }
    @Test void nextTransferCannotStartWhileForwardReadersAreStillBeingRecorded() {
        VoxelFrameOrder frame = new VoxelFrameOrder();
        frame.begin();
        assertThrows(IllegalStateException.class, frame::begin);
        frame.published(1);
        assertEquals(1, frame.begin());
    }
    @Test void skippedUploadFramesStillProtectTheirReaders() {
        VoxelFrameOrder frame = new VoxelFrameOrder();
        for (int i = 0; i < 5; i++) {
            assertEquals(i, frame.begin());
            frame.published(i + 1);
        }
        assertEquals(5, frame.begin());
    }
    @Test void failedSignalCannotMakeAFutureWaitValueEligible() {
        VoxelFrameOrder frame = new VoxelFrameOrder();
        frame.begin();
        assertThrows(IllegalArgumentException.class, () -> frame.published(2));
        assertThrows(IllegalStateException.class, frame::begin);
        frame.published(1);
        assertEquals(1, frame.begin());
    }
}
