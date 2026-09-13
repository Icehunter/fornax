package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.voxel.BrickGridUpload;
import dev.icehunter.fornax.voxel.SectionHarvester;
import dev.icehunter.fornax.voxel.SectionPalette;
import dev.icehunter.fornax.voxel.VoxelHarvestLifecycle;
import dev.icehunter.fornax.voxel.VoxelShapeKind;
import dev.icehunter.fornax.voxel.VoxelWindow;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs real CPU publications and compact mapping; native buffer allocation and BLAS completion
 * are explicitly seeded. This proves the readiness contract, not execution on the Metal queue. */
class MetalRtSectionReadinessTest {
    private final SectionPos center = SectionPos.of(1200, 70, -2200);
    private Object oldOccupancy;
    private int oldDiameter;

    @BeforeEach void setup() throws Exception {
        VoxelHarvestLifecycle.onModelsPublished();
        VoxelWindow.attachRegistry(TargetRegistry.create(new GraphSpec(new LinkedHashMap<>(), List.of()), Map.of()));
        VoxelWindow.recenter(center.x(), center.y(), center.z(), 32);
        MetalRtGeometry.setActive(false);
        oldOccupancy = field("occupancy").get(null);
        oldDiameter = field("allocatedDiameter").getInt(null);
        // Only buffer presence and the radius-eight compact allocation are used by CPU mapping.
        field("occupancy").set(null, new MetalRtGeometry.ExportedBuffer(1, 1, 1, 1));
        field("allocatedDiameter").setInt(null, 17);
        published().clear();
        MetalRtGeometry.prepareSnapshot(Map.of());
        MetalRtGeometry.setActive(true);
    }

    @AfterEach void detach() throws Exception {
        VoxelWindow.attachRegistry(null);
        MetalRtGeometry.setActive(false);
        published().clear();
        field("occupancy").set(null, oldOccupancy);
        field("allocatedDiameter").setInt(null, oldDiameter);
    }

    @SuppressWarnings("unchecked") private Map<Integer, SectionPos> published() throws Exception {
        return (Map<Integer, SectionPos>) field("publishedOwners").get(null);
    }

    private static Field field(String name) throws Exception {
        Field field = MetalRtGeometry.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private int sourceSlot(SectionPos owner) {
        return VoxelWindow.slotFor(owner.x(), owner.y(), owner.z());
    }

    private int destination(SectionPos owner) {
        // Independent GPU ABI expectation: x is fastest, then z, then y; absolute modulo 17.
        return (Math.floorMod(owner.y(), 17) * 17 + Math.floorMod(owner.z(), 17)) * 17
                + Math.floorMod(owner.x(), 17);
    }

    private BrickGridUpload.SlotUpload harvest(SectionPos owner) {
        var result = new SectionHarvester.Result(new byte[4096], new SectionPalette(List.of(
                new SectionPalette.Entry(VoxelShapeKind.EMPTY, List.of(), new int[6], 0.0, false, 0))));
        VoxelWindow.onSectionHarvested(owner, result);
        return new BrickGridUpload.SlotUpload(sourceSlot(owner), result, true);
    }

    private void complete(BrickGridUpload.SlotUpload upload) {
        assertDoesNotThrow(() -> {
            var method = VoxelWindow.class.getDeclaredMethod("onSectionUploadCommitted", BrickGridUpload.SlotUpload.class);
            method.setAccessible(true);
            method.invoke(null, upload);
        });
    }

    private Map<Integer, SectionPos> publish(SectionPos... owners) {
        for (var owner : owners) complete(harvest(owner));
        var selected = MetalRtGeometry.prepareSnapshot(VoxelWindow.populatedSlotSections());
        MetalRtGeometry.markPublished(MetalRtGeometry.selectDirtyBatch(), selected);
        return selected;
    }

    private int[] readiness(Map<Integer, SectionPos> owners) {
        return MetalRtGeometry.sectionReadiness(owners);
    }

    @Test void aPublishedEmptySectionIsReadyEvenWhenTheRestOfTheCubeIsUnknown() {
        var owners = publish(center);
        int[] snapshot = readiness(owners);
        assertTrue(sourceSlot(center) > snapshot.length, "source offset must not index the compact buffer");
        assertEquals(4913, snapshot.length, "radius eight has 17 cubed compact slots");
        assertEquals(1, snapshot[destination(center)]);
        assertEquals(1, java.util.Arrays.stream(snapshot).sum(), "only committed known air is certified");
        snapshot[destination(center)] = 0;
        assertEquals(1, readiness(owners)[destination(center)], "the returned snapshot is detached");
    }

    @Test void oneSectionCyclesThroughReadCopyAndPublicationWithoutInvalidatingItsNeighbor() {
        var neighbor = SectionPos.of(1201, 70, -2200);
        var owners = publish(center, neighbor);
        var upload = harvest(neighbor);
        assertEquals(1, readiness(owners)[destination(center)]);
        assertEquals(0, readiness(owners)[destination(neighbor)], "CPU content has no upload completion yet");
        complete(upload);
        MetalRtGeometry.markDirty(sourceSlot(neighbor));
        assertEquals(0, readiness(owners)[destination(neighbor)]);
        var batch = MetalRtGeometry.selectDirtyBatch();
        assertEquals(0, readiness(owners)[destination(neighbor)], "selected old-owner BLAS remains unpublished");
        assertEquals(1, readiness(owners)[destination(center)]);
        MetalRtGeometry.markPublished(batch, owners);
        assertEquals(1, readiness(owners)[destination(neighbor)]);
    }

    @Test void anUploadAfterBatchSelectionRemainsDirtyAfterTheOlderBatchPublishes() {
        var owners = publish(center);
        MetalRtGeometry.markDirty(sourceSlot(center));
        var batch = MetalRtGeometry.selectDirtyBatch();
        MetalRtGeometry.markDirty(sourceSlot(center));
        MetalRtGeometry.markPublished(batch, owners);
        assertEquals(0, readiness(owners)[destination(center)]);
        MetalRtGeometry.markPublished(MetalRtGeometry.selectDirtyBatch(), owners);
        assertEquals(1, readiness(owners)[destination(center)]);
    }

    @Test void staleOwnerMapsCannotCertifyAnotherSourcesCompactSlot() {
        var owners = publish(center);
        var malformed = new HashMap<Integer, SectionPos>();
        // This valid source slot belongs to a different world section than the supplied owner.
        int wrongSource = sourceSlot(SectionPos.of(1201, 70, -2200));
        malformed.put(wrongSource, center);
        MetalRtGeometry.markPublished(List.of(wrongSource), malformed);
        assertEquals(0, java.util.Arrays.stream(readiness(malformed)).sum());
        assertEquals(1, readiness(owners)[destination(center)]);
    }

    @Test void recenterPreservesOverlapAndRejectsReusedCompactOwnersUntilPublication() {
        var overlap = SectionPos.of(1200, 70, -2200);
        var exiting = SectionPos.of(1192, 70, -2200);
        var oldOwners = publish(overlap, exiting);
        // A one-section move makes entering x=1209 reuse exiting x=1192's compact slot.
        VoxelWindow.recenter(1201, 70, -2200, 32);
        var entering = SectionPos.of(1209, 70, -2200);
        complete(harvest(entering));
        var nextOwners = MetalRtGeometry.prepareSnapshot(VoxelWindow.populatedSlotSections());
        assertEquals(1, readiness(nextOwners)[destination(overlap)]);
        assertEquals(0, readiness(oldOwners)[destination(entering)]);
        assertEquals(0, readiness(nextOwners)[destination(entering)]);
        MetalRtGeometry.markPublished(MetalRtGeometry.selectDirtyBatch(), nextOwners);
        assertEquals(1, readiness(nextOwners)[destination(entering)]);
    }

    @Test void aSourceResizeOrUnallocatedExportCannotReuseThePreviousPublication() throws Exception {
        var owners = publish(center);
        VoxelWindow.recenter(center.x(), center.y(), center.z(), 16);
        assertEquals(0, java.util.Arrays.stream(readiness(owners)).sum());
        field("occupancy").set(null, null);
        assertEquals(0, readiness(owners).length);
    }
}
