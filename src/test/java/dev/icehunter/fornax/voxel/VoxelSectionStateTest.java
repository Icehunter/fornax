package dev.icehunter.fornax.voxel;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CPU token and GPU record encoding only; no claim about Vulkan execution. */
class VoxelSectionStateTest {
    @Test void committedRecordPreservesSignedAbsoluteOwnerAndBothRevisionKinds() {
        VoxelSectionState states = new VoxelSectionState();
        states.reset(7);
        var first = states.geometry(3, SectionPos.of(-29, 4, 301));
        ByteBuffer bytes = ByteBuffer.allocate(VoxelSectionState.BYTES_PER_SLOT).order(ByteOrder.LITTLE_ENDIAN);
        VoxelSectionState.pack(first, bytes);
        int[] words = new int[8];
        bytes.asIntBuffer().get(words);
        // Three owner words, generation, geometry/content versions, committed flag, reserved.
        assertArrayEquals(new int[] {-29, 4, 301, 7, 1, 1, 1, 0}, words);
        assertEquals(32, bytes.remaining());
    }

    @Test void contentRefreshPreservesGeometryAndSupersedesTheQueuedGeometryToken() {
        VoxelSectionState states = new VoxelSectionState();
        states.reset(2);
        var geometry = states.geometry(11, SectionPos.of(3, -2, 9));
        var content = states.content(11);
        assertEquals(geometry.geometryRevision(), content.geometryRevision());
        assertEquals(geometry.contentRevision() + 1, content.contentRevision());
        assertFalse(states.isCurrent(11, geometry));
        assertTrue(states.isCurrent(11, content));
        var replacement = states.geometry(11, SectionPos.of(3, -2, 9));
        assertTrue(replacement.geometryRevision() > content.geometryRevision());
        assertTrue(replacement.contentRevision() > content.contentRevision());
        assertFalse(states.isCurrent(11, content));
    }

    @Test void recycledSlotAndStorageResetRejectTokensEvenIfAbsoluteOwnerRepeats() {
        VoxelSectionState states = new VoxelSectionState();
        states.reset(9);
        var first = states.geometry(0, SectionPos.of(0, 0, 0));
        states.invalidate(List.of(0));
        assertFalse(states.isCurrent(0, first));
        assertThrows(IllegalStateException.class, () -> states.content(0));
        var recycled = states.geometry(0, SectionPos.of(0, 0, 0));
        assertFalse(states.isCurrent(0, first));
        assertTrue(states.isCurrent(0, recycled));
        states.reset(10);
        var replacementStorage = states.geometry(0, SectionPos.of(0, 0, 0));
        assertFalse(states.isCurrent(0, recycled));
        assertEquals(10, replacementStorage.storageGeneration());
    }

    @Test void missingSnapshotEncodesOnlyPendingAndCannotAccidentallyCertifyOrigin() {
        ByteBuffer bytes = ByteBuffer.allocate(VoxelSectionState.BYTES_PER_SLOT).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < bytes.capacity(); i++) bytes.put(i, (byte) 0xff);
        VoxelSectionState.pack(null, bytes);
        assertArrayEquals(new byte[32], bytes.array());
        assertFalse(new VoxelSectionState().isCurrent(0, null));
    }

    @Test void generationCannotWrapIntoAPreviouslyValidShaderToken() {
        VoxelSectionState states = new VoxelSectionState();
        assertThrows(ArithmeticException.class, () -> states.reset((long) Integer.MAX_VALUE + 1));
        assertThrows(IllegalArgumentException.class, () -> states.reset(0));
    }
    @Test void supersedingQueuedOwnerCannotCancelTheClearRequiredByTheCommittedOwner() {
        VoxelSectionState states = new VoxelSectionState();
        states.reset(1);
        var ownerA = states.geometry(0, SectionPos.of(0, 0, 0));
        states.commit(0, ownerA);
        var pendingB = states.geometry(0, SectionPos.of(9, 0, 0));
        var newerB = states.geometry(0, SectionPos.of(9, 0, 0));
        assertTrue(states.needsLightClear(0, newerB));
        states.commit(0, pendingB);
        assertTrue(states.needsLightClear(0, newerB), "a stale completion cannot replace the committed owner");
        states.commit(0, newerB);
        assertFalse(states.needsLightClear(0, states.content(0)));
        states.invalidate(List.of(0));
        var pendingA = states.geometry(0, SectionPos.of(0, 0, 0));
        assertTrue(states.needsLightClear(0, pendingA), "slot clear leaves the propagated GPU light owner intact");
    }

    @Test void sourceReadersSeeOnlyCommittedGeometryAndIgnorePendingLightOnlyChanges() {
        var states = new VoxelSectionState(); states.reset(5);
        var first = states.geometry(3, SectionPos.of(0, 0, 0));
        org.junit.jupiter.api.Assertions.assertNull(states.committedGeometry(3));
        states.commit(3, first);
        assertEquals(first, states.committedGeometry(3));
        states.content(3);
        assertEquals(first, states.committedGeometry(3));
        states.geometry(3, SectionPos.of(0, 0, 0));
        org.junit.jupiter.api.Assertions.assertNull(states.committedGeometry(3));
        states.invalidate(java.util.List.of(3));
        org.junit.jupiter.api.Assertions.assertNull(states.committedGeometry(3));
    }

}
