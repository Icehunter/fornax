package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelSourceWindowTest {
    static int word(ByteBuffer bytes, int index) { return bytes.getInt(index * Integer.BYTES); }
    static final class OneSection {
        SectionHarvester.Result data = VoxelEmitterPoolTest.result(7, 0, 4095);
        VoxelSectionState.Snapshot token = token(0, 0, 0, 9, 11);
        VoxelSourceWindow.Publication publish(VoxelSourceWindow window) {
            window.commit(3, data, token);
            return window.preparePublication();
        }
    }
    static VoxelSectionState.Snapshot token(int x, int y, int z, int geometry, int content) {
        return new VoxelSectionState.Snapshot(x, y, z, 5, geometry, content);
    }
    static VoxelSourceWindow inventory() {
        var window = new VoxelSourceWindow(); window.reset(5, 7); return window;
    }
    static int base(int row) { return VoxelSourceWindow.CELL_BASE + row * VoxelSourceWindow.CELL_WORDS; }

    @Test void emptySparseAbiPublishesAllRangesAndRowsAsZeroWithGenerationAndNoCameraOrigin() {
        var window = new VoxelSourceWindow(); window.reset(5, 0x1_00000002L);
        var publication = window.preparePublication(); var bytes = publication.bytes();
        assertEquals(418632, bytes.remaining()); // (16 + 33^3*2 + 4096*8) uints.
        assertEquals(71890, VoxelSourceWindow.CELL_BASE);
        assertEquals(2, word(bytes, 0)); assertEquals(4096, word(bytes, 1));
        assertEquals(0, word(bytes, 2)); assertEquals(5, word(bytes, 3));
        assertEquals(2, word(bytes, 4)); assertEquals(1, word(bytes, 5));
        assertTrue(word(bytes, 6) > 0);
        for (int lane = 8; lane < bytes.remaining() / 4; lane++) assertEquals(0, word(bytes, lane));
        window.markPublished(publication); assertNull(window.preparePublication());
    }

    @Test void sourceCellsAreGroupedBySlotAndKeepSignedWorldCoordinatesFarFromTheCamera() {
        var window = inventory();
        window.commit(9, VoxelEmitterPoolTest.result(7, 4095), token(40, -3, 15, 10, 12));
        window.commit(3, VoxelEmitterPoolTest.result(7, 0, 17), token(-30, 2, -4, 9, 11));
        var bytes = window.preparePublication().bytes();
        assertEquals(3, word(bytes, 2));
        assertEquals(0, word(bytes, 16 + 3 * 2)); assertEquals(2, word(bytes, 17 + 3 * 2));
        assertEquals(2, word(bytes, 16 + 9 * 2)); assertEquals(1, word(bytes, 17 + 9 * 2));
        assertEquals(-480, word(bytes, base(0))); assertEquals(32, word(bytes, base(0) + 1));
        assertEquals(-64, word(bytes, base(0) + 2));
        assertEquals(-479, word(bytes, base(1))); assertEquals(-63, word(bytes, base(1) + 2));
        assertEquals(655, word(bytes, base(2))); assertEquals(-33, word(bytes, base(2) + 1));
        assertEquals(255, word(bytes, base(2) + 2));
        assertEquals(3 * 96 + 1, word(bytes, base(0) + 3));
        assertEquals(63 | (7 << 8) | (63 << 16), word(bytes, base(0) + 4));
        assertEquals(9, word(bytes, base(0) + 5)); assertEquals(1, word(bytes, base(0) + 6));
        assertEquals(0, word(bytes, base(0) + 7));
    }

    @Test void unchangedGeometryAndLightmapOnlyCommitsDoNotRescanOrRepublishMembership() {
        var window = inventory(); var fixture = new OneSection();
        var first = fixture.publish(window); window.markPublished(first);
        assertNull(fixture.publish(window));
        fixture.data = fixture.data.withLightmap(new byte[4096]);
        fixture.token = token(0, 0, 0, 9, 12);
        assertNull(fixture.publish(window));
        fixture.token = token(0, 0, 0, 10, 13);
        var changed = fixture.publish(window);
        assertTrue(changed.stateVersion() > first.stateVersion());
        assertEquals(10, word(changed.bytes(), base(0) + 5));
    }

    @Test void addingEarlierSortedSourcesAtCapacityNeverDisplacesAdmittedWorldIdentities() {
        var window = new VoxelSourceWindow(2); window.reset(5, 7);
        window.commit(9, VoxelEmitterPoolTest.result(7, 1, 2), token(0, 0, 0, 9, 11));
        var first = window.preparePublication(); window.markPublished(first);
        window.commit(1, VoxelEmitterPoolTest.result(7, 0), token(-1, 0, 0, 10, 12));
        var full = window.preparePublication().bytes();
        assertEquals(2, word(full, 2)); assertEquals(1, word(full, 8));
        assertEquals(1, word(full, base(0))); assertEquals(2, word(full, base(1)));
        assertEquals(0, word(full, 17 + 1 * 2));
        window.invalidate(List.of(9));
        var refill = window.preparePublication().bytes();
        assertEquals(1, word(refill, 2)); assertEquals(0, word(refill, 8));
        assertEquals(-16, word(refill, base(0)));
    }

    @Test void sourceAdditionsInsideOneSectionPreserveItsExistingAdmissions() {
        var window = new VoxelSourceWindow(2); window.reset(5, 7);
        window.commit(3, VoxelEmitterPoolTest.result(7, 1, 2), token(0, 0, 0, 9, 11));
        window.commit(3, VoxelEmitterPoolTest.result(7, 0, 1, 2), token(0, 0, 0, 10, 12));
        var bytes = window.preparePublication().bytes();
        assertEquals(1, word(bytes, 8));
        assertEquals(1, word(bytes, base(0))); assertEquals(2, word(bytes, base(1)));
    }

    @Test void pendingGeometryStopsPublishingOnlyThatSectionAndReservesItsAdmissions() {
        var window = new VoxelSourceWindow(2); window.reset(5, 7);
        var original = VoxelEmitterPoolTest.result(7, 1);
        window.commit(3, original, token(0, 0, 0, 9, 11));
        window.commit(4, VoxelEmitterPoolTest.result(7, 2), token(1, 0, 0, 10, 12));
        window.pending(3);
        window.commit(1, VoxelEmitterPoolTest.result(7, 0), token(-1, 0, 0, 11, 13));
        var waiting = window.preparePublication().bytes();
        assertEquals(1, word(waiting, 2)); assertEquals(1, word(waiting, 8));
        assertEquals(18, word(waiting, base(0)));
        window.commit(3, original, token(0, 0, 0, 12, 14));
        var restored = window.preparePublication().bytes();
        assertEquals(2, word(restored, 2)); assertEquals(1, word(restored, 8));
        assertEquals(1, word(restored, base(0))); assertEquals(18, word(restored, base(1)));
    }

    @Test void unknownSectionDoesNotInvalidateAnIndependentKnownEmitter() {
        var window = inventory(); var fixture = new OneSection(); fixture.publish(window);
        var unknown = new SectionHarvester.Result(fixture.data.paletteIndices(), fixture.data.palette(),
                fixture.data.lightmap(), fixture.data.sourceSummary(), fixture.data.harvestGeneration(),
                VoxelSourceEvidence.UNAVAILABLE);
        window.commit(4, unknown, token(1, 0, 0, 10, 12));
        var bytes = window.preparePublication().bytes();
        assertEquals(2, word(bytes, 2)); assertEquals(4096, word(bytes, 9));
        assertEquals(1, word(bytes, base(0) + 6));
        assertEquals(0, word(bytes, 17 + 4 * 2));
    }

    @Test void ownerReuseAndResetRetireOldRowsAndLateAcknowledgementCannotHideReset() {
        var window = inventory(); var fixture = new OneSection();
        var first = fixture.publish(window); window.markPublished(first);
        fixture.token = token(1, 0, 0, 10, 12);
        assertEquals(16, word(fixture.publish(window).bytes(), base(0)));
        window.reset(6, 8);
        window.commit(3, fixture.data, fixture.token); // Retired storage/atlas token cannot re-enter.
        var reset = window.preparePublication();
        assertEquals(0, word(reset.bytes(), 2)); assertEquals(6, word(reset.bytes(), 3));
        window.markPublished(first); assertSame(reset, window.preparePublication());
    }

    @Test void stagingSnapshotIsImmutableAndUsesSevenAlignedInlineUpdateChunks() {
        var window = inventory(); var fixture = new OneSection();
        var first = fixture.publish(window); var bytes = first.bytes();
        assertTrue(bytes.isReadOnly());
        var chunks = VoxelEmitterPoolUpload.ranges(bytes);
        assertEquals(7, chunks.size());
        for (int i = 0; i < 6; i++) {
            assertEquals(i * 65536, chunks.get(i).offset());
            assertEquals(65536, chunks.get(i).bytes().remaining());
        }
        assertEquals(393216, chunks.getLast().offset()); assertEquals(25416, chunks.getLast().bytes().remaining());
        window.invalidate(List.of(3));
        assertEquals(2, word(bytes, 2)); assertEquals(0, word(window.preparePublication().bytes(), 2));
    }

    @Test void capacityAndSlotsRejectInvalidInputsBeforeWritingAnAbi() {
        assertThrows(IllegalArgumentException.class, () -> new VoxelSourceWindow(0));
        assertThrows(IllegalArgumentException.class, () -> new VoxelSourceWindow(4097));
        var window = inventory(); var fixture = new OneSection();
        assertThrows(IllegalArgumentException.class, () -> window.commit(-1, fixture.data, fixture.token));
        assertThrows(IllegalArgumentException.class, () -> window.commit(35937, fixture.data, fixture.token));
    }
}
