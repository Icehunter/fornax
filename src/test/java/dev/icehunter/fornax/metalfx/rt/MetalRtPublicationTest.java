package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.voxel.VoxelWindow;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Source contracts cover GPU frame orchestration without launching the client; mapping is real math. */
class MetalRtPublicationTest {
    @Test
    void graphProducesTheRtResultBeforeItsFirstConsumer() throws Exception {
        String graph = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java"));
        String finish = graph.substring(graph.indexOf("public static void finish(ChunkRenderMatrices"));
        int trace = finish.indexOf("MetalRtShadowPass.runIfEnabled(");
        assertTrue(trace >= 0 && trace < finish.indexOf("for (PassSpec p : pack.graph().passes())"),
                "RT must publish before the current frame graph samples its screen-space mask");
        String end = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/mixin/vanilla/GameRendererMixin.java"));
        assertFalse(end.contains("MetalRtShadowPass.runIfEnabled("), "end-frame only presents the completed result");
        assertTrue(end.contains("MetalRtDebugPass.presentIfEnabled("));
    }

    @Test
    void noMetadataCopyBatchCanExceedTheBlasRebuildBatch() throws Exception {
        Field limit = MetalRtGeometry.class.getDeclaredField("MAX_SLOT_COPIES_PER_CALL");
        limit.setAccessible(true);
        assertEquals(MetalRtAcceleration.MAX_DIRTY_PER_FRAME, limit.getInt(null),
                "copied palettes must never outrun their BLAS primitive-data generation");
    }

    @Test
    void exportedMembershipFollowsTheCameraAtLargeAndNegativeCoordinates() throws Exception {
        Field occupancy = MetalRtGeometry.class.getDeclaredField("occupancy");
        occupancy.setAccessible(true);
        Field diameter = MetalRtGeometry.class.getDeclaredField("allocatedDiameter");
        diameter.setAccessible(true);
        var oldWindow = VoxelWindow.currentState();
        Object oldOccupancy = occupancy.get(null);
        int oldDiameter = diameter.getInt(null);
        try {
            occupancy.set(null, new MetalRtGeometry.ExportedBuffer(1, 1, 1, 1));
            diameter.setInt(null, 17);
            VoxelWindow.recenter(1200, 70, -2200, 32);
            int cameraSlot = VoxelWindow.slotFor(1200, 70, -2200);
            assertTrue(cameraSlot > MetalRtGeometry.allocatedSlotCount(), "fixture exposes the raw-prefix defect");
            assertTrue(MetalRtGeometry.slotInExportedWindow(cameraSlot), "the camera's own section must export");
            assertTrue(MetalRtGeometry.slotInExportedWindow(VoxelWindow.slotFor(1192, 62, -2208)));
            assertFalse(MetalRtGeometry.slotInExportedWindow(VoxelWindow.slotFor(1191, 70, -2200)));
        } finally {
            occupancy.set(null, oldOccupancy);
            diameter.setInt(null, oldDiameter);
            VoxelWindow.recenter(oldWindow.centerX(), oldWindow.centerY(), oldWindow.centerZ(), oldWindow.radius());
        }
    }

    @Test
    void traceRejectsAnIncompleteSnapshotRatherThanCallingEveryAllocationValid() throws Exception {
        String source = Files.readString(Path.of("src/main/resources/assets/fornax/shaders_engine/rt_trace.metal"));
        assertTrue(source.contains("constants.snapshotReady == 0u"),
                "receiver allocation alone cannot certify that missing blockers are absent");
    }
    @Test
    void compactMappingIsBijectiveAndOverlappingSectionsKeepTheirDestinationAfterRecenter() {
        var source = new VoxelWindow.WindowState(-19, 6, 130, 32, 65);
        var next = new VoxelWindow.WindowState(-18, 6, 130, 32, 65);
        java.util.Set<Integer> destinations = new java.util.HashSet<>();
        for (int y = -2; y <= 14; y++) for (int z = 122; z <= 138; z++) for (int x = -27; x <= -11; x++) {
            int slot = (Math.floorMod(y, 65) * 65 + Math.floorMod(z, 65)) * 65 + Math.floorMod(x, 65);
            int destination = MetalRtGeometry.exportedSlot(source, slot);
            assertTrue(destination >= 0 && destination < 17 * 17 * 17);
            assertTrue(destinations.add(destination), "two source sections cannot share exported metadata");
            if (x >= -26) assertEquals(destination, MetalRtGeometry.exportedSlot(next, slot),
                    "overlapping sections must keep a stable compact offset across a wrap seam");
        }
        assertEquals(17 * 17 * 17, destinations.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void zeroTrianglePublicationsBecomeReadyOnlyAfterEverySelectedBatchCompletes() throws Exception {
        Field occupancy = MetalRtGeometry.class.getDeclaredField("occupancy"); occupancy.setAccessible(true);
        Field diameter = MetalRtGeometry.class.getDeclaredField("allocatedDiameter"); diameter.setAccessible(true);
        Field published = MetalRtGeometry.class.getDeclaredField("publishedOwners"); published.setAccessible(true);
        var records = (java.util.Map<Integer, net.minecraft.core.SectionPos>) published.get(null);
        var oldRecords = new java.util.HashMap<>(records);
        Object oldOccupancy = occupancy.get(null); int oldDiameter = diameter.getInt(null);
        var oldWindow = VoxelWindow.currentState();
        try {
            MetalRtGeometry.setActive(false); records.clear();
            occupancy.set(null, new MetalRtGeometry.ExportedBuffer(1, 1, 1, 1)); diameter.setInt(null, 9);
            VoxelWindow.recenter(35, 4, -41, 4);
            java.util.Map<Integer, net.minecraft.core.SectionPos> owners = new java.util.HashMap<>();
            for (int y = 0; y <= 8; y++) for (int z = -45; z <= -37; z++) for (int x = 31; x <= 39; x++) {
                owners.put(VoxelWindow.slotFor(x, y, z), net.minecraft.core.SectionPos.of(x, y, z));
            }
            owners = MetalRtGeometry.prepareSnapshot(owners);
            assertFalse(MetalRtGeometry.snapshotReady(owners));
            int copied = 0;
            while (copied < owners.size()) {
                var batch = MetalRtGeometry.selectDirtyBatch();
                assertTrue(batch.size() <= MetalRtAcceleration.MAX_DIRTY_PER_FRAME);
                assertFalse(MetalRtGeometry.snapshotReady(owners), "selected but not published is still pending");
                MetalRtGeometry.markPublished(batch, owners); // zero triangles still complete the publication
                copied += batch.size();
            }
            assertTrue(MetalRtGeometry.snapshotReady(owners), "known-empty BLAS results need no nonzero structure");
            MetalRtGeometry.setActive(true);
            MetalRtGeometry.markDirty(owners.keySet().iterator().next());
            assertFalse(MetalRtGeometry.snapshotReady(owners), "an edit invalidates the current snapshot immediately");
        } finally {
            MetalRtGeometry.setActive(false); records.clear(); records.putAll(oldRecords);
            occupancy.set(null, oldOccupancy); diameter.setInt(null, oldDiameter);
            VoxelWindow.recenter(oldWindow.centerX(), oldWindow.centerY(), oldWindow.centerZ(), oldWindow.radius());
        }
    }

}
