package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real harvest/completion bookkeeping without a GPU. Completion explicitly models its fence. */
class VoxelSectionReadinessTest {
    private final SectionPos center = SectionPos.of(0, 4, 0);

    @BeforeEach void setup() {
        VoxelHarvestLifecycle.onModelsPublished();
        VoxelWindow.attachRegistry(TargetRegistry.create(new GraphSpec(new LinkedHashMap<>(), List.of()), Map.of()));
        VoxelWindow.recenter(0, 4, 0, 2);
    }

    @AfterEach void detach() { VoxelWindow.attachRegistry(null); }

    private BrickGridUpload.SlotUpload harvest(SectionPos owner) {
        // One light byte per cell, matching a section's 16 cubed cells; no occupied geometry.
        var result = DirectSectionReader.emptyResultWithLight(new byte[4096]);
        VoxelWindow.onSectionHarvested(owner, result);
        return new BrickGridUpload.SlotUpload(VoxelWindow.slotFor(owner.x(), owner.y(), owner.z()), result, true);
    }

    private boolean ready(SectionPos owner) {
        return VoxelWindow.isGeometryReady(owner);
    }

    private SectionHarvester.Result committedData(int slot, SectionPos owner) {
        return VoxelWindow.committedSectionData(slot, owner);
    }

    @Test void committedDataReturnsOnlyTheCurrentCompletedResultAtItsActualSourceSlot() {
        int slot = VoxelWindow.slotFor(center.x(), center.y(), center.z());
        assertNull(committedData(slot, center));
        var first = harvest(center);
        assertNull(committedData(slot, center));
        VoxelWindow.onSectionUploadCommitted(first);
        assertSame(first.result(), committedData(slot, center));
        assertNull(committedData(slot + 1, center), "an owner cannot certify a different source slot");
        assertNull(committedData(slot, SectionPos.of(1, 4, 0)), "a source slot cannot certify another owner");
        var second = harvest(center);
        assertNull(committedData(slot, center), "CPU replacement is not the committed metadata snapshot");
        VoxelWindow.onSectionUploadCommitted(second);
        assertSame(second.result(), committedData(slot, center));
    }

    @Test void unknownAndCpuOnlyAirStayUnknownUntilTheirUploadCompletes() {
        assertFalse(ready(center));
        var upload = harvest(center);
        assertFalse(ready(center));
        VoxelWindow.onSectionUploadCommitted(upload);
        assertTrue(ready(center), "zero-triangle sections are certified empty after completion");
        assertFalse(ready(SectionPos.of(1, 4, 0)), "neighboring unknown air remains unknown");
    }

    @Test void pendingMeshRevisionInvalidatesOnlyItsOwnSection() {
        SectionPos neighbor = SectionPos.of(1, 4, 0);
        VoxelWindow.onSectionUploadCommitted(harvest(center));
        VoxelWindow.onSectionUploadCommitted(harvest(neighbor));
        var pending = new ArrayDeque<Runnable>();
        var updates = new VoxelMeshUpdates(pending::add, (storage, generation, level, owner) -> {},
                (owner, failure) -> { throw new AssertionError(failure); });
        VoxelWindow.queueMeshTriggeredHarvest(null, neighbor, updates);
        assertTrue(ready(center), "a spatially unrelated edit must not affect this section");
        assertFalse(ready(neighbor), "queued work is stale before its CPU read starts");
        var upload = harvest(neighbor);
        assertFalse(ready(neighbor), "reading the new mesh revision does not commit it");
        VoxelWindow.onSectionUploadCommitted(upload);
        assertTrue(ready(neighbor));
    }

    @Test void sameRevisionHarvestCannotBorrowThePreviousUploadCompletion() {
        var first = harvest(center);
        VoxelWindow.onSectionUploadCommitted(first);
        assertTrue(ready(center));
        var second = harvest(center); // No queued mesh change: both reads have revision zero.
        assertFalse(ready(center), "new CPU content with the same revision still needs its own upload");
        VoxelWindow.onSectionUploadCommitted(first);
        assertFalse(ready(center), "a stale completion cannot publish a newer CPU result");
        VoxelWindow.onSectionUploadCommitted(second);
        assertTrue(ready(center));
    }

    @Test void wrappedOwnerCannotBorrowThePreviousSectionsCompletion() {
        var previous = harvest(center);
        VoxelWindow.onSectionUploadCommitted(previous);
        // Diameter five makes x=0 and x=5 alias the same toroidal slot.
        VoxelWindow.recenter(5, 4, 0, 2);
        SectionPos next = SectionPos.of(5, 4, 0);
        assertFalse(ready(center), "the old owner is outside the current window");
        assertFalse(ready(next), "old data cannot certify the new owner");
        var replacement = harvest(next);
        assertFalse(ready(next), "reowned CPU data still needs a successful upload");
        VoxelWindow.onSectionUploadCommitted(previous);
        assertFalse(ready(next));
        VoxelWindow.onSectionUploadCommitted(replacement);
        assertTrue(ready(next));
    }
}
