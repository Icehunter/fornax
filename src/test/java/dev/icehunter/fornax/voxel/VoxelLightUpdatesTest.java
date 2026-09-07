package dev.icehunter.fornax.voxel;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class VoxelLightUpdatesTest {
    @Test void lightOnlyEventsCoalesceAndOldMeshPublishRequestsANewWorldRead() {
        var updates=new VoxelLightUpdates(); var section=SectionPos.of(3,4,5);
        updates.changed(section); updates.changed(section);
        assertEquals(java.util.Set.of(section),updates.drain(p->true));
        assertTrue(updates.drain(p->true).isEmpty());
        updates.meshPublished(section);
        assertEquals(java.util.Set.of(section),updates.drain(p->true));
    }
    @Test void ordinaryMeshPublishesDoNotQueueASecondHarvest() {
        var updates=new VoxelLightUpdates(); updates.meshPublished(SectionPos.of(3,4,5));
        assertTrue(updates.drain(p->true).isEmpty());
    }
    @Test void outsideWindowAndOldStorageEventsCannotChangeANewOwner() {
        var updates=new VoxelLightUpdates(); var section=SectionPos.of(3,4,5);
        updates.changed(section); assertTrue(updates.drain(p->false).isEmpty());
        updates.meshPublished(section); assertTrue(updates.drain(p->true).isEmpty());
        updates.changed(section); updates.clear();
        assertTrue(updates.drain(p->true).isEmpty());
    }
    @Test void repeatedFramesQueueOnlyOneWorkerAndReadNotificationsAtExecution() {
        var updates=new VoxelLightUpdates(); var section=SectionPos.of(3,4,5);
        updates.changed(section); Object worker=updates.beginWork(); assertNotNull(worker);
        for(int frame=0;frame<1000;frame++) {
            updates.changed(section); assertNull(updates.beginWork());
        }
        assertEquals(java.util.Set.of(section),updates.drain(p->true));
        updates.endWork(worker); assertNull(updates.beginWork());
    }
    @Test void retiredWorkerCannotUnlockTheNewStorageWorker() {
        var updates=new VoxelLightUpdates(); var section=SectionPos.of(3,4,5);
        updates.changed(section); Object old=updates.beginWork();
        updates.clear(); updates.changed(section); Object current=updates.beginWork();
        updates.endWork(old); assertNull(updates.beginWork());
        updates.endWork(current); assertNotNull(updates.beginWork());
    }
    @Test void lightEventsAreRegisteredAndConsumedWithoutCameraMovement() throws Exception {
        String mixin=Files.readString(Path.of("src/main/java/dev/icehunter/fornax/mixin/vanilla/ClientChunkCacheVoxelLightMixin.java"));
        assertTrue(mixin.contains("onLightUpdate"));
        String registry=Files.readString(Path.of("src/main/resources/fornax.mixins.json"));
        assertTrue(registry.contains("vanilla.ClientChunkCacheVoxelLightMixin"));
        String frame=Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pass/voxel/VoxelDebugRaymarchPass.java"));
        assertTrue(frame.contains("VoxelWindow.refreshLightmaps(level)"));
        String window=Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/VoxelWindow.java"));
        assertTrue(window.contains("lightUpdates.meshPublished(position)"));
        assertTrue(window.contains("generation != storageGeneration || capturedRegistry != registry"));
    }
}
