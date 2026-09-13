package dev.icehunter.fornax.voxel;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seeds committed CPU publication records; no fake GPU or world is required to test readiness. */
class VoxelGeometryReadinessTest {
    @SuppressWarnings("unchecked")
    @Test
    void completedEmptySectionsAreReadyButUnknownAndUncommittedSectionsAreNot() throws Exception {
        var original = VoxelWindow.currentState();
        Map<Integer, SectionPos> owners = (Map<Integer, SectionPos>) field("slotOwner").get(null);
        Map<Integer, SectionHarvester.Result> data = (Map<Integer, SectionHarvester.Result>) field("slotData").get(null);
        Map<Integer, Long> read = (Map<Integer, Long>) field("slotReadRevision").get(null);
        Map<Integer, Long> committed = (Map<Integer, Long>) field("slotCommittedReadRevision").get(null);
        Set<Integer> populated = (Set<Integer>) field("populatedSlots").get(null);
        Map<Integer, Object> changes = (Map<Integer, Object>) field("meshChanges").get(null);
        var oldOwners = new HashMap<>(owners);
        var oldData = new HashMap<>(data);
        var oldRead = new HashMap<>(read);
        var oldCommitted = new HashMap<>(committed);
        var oldPopulated = new HashSet<>(populated);
        var oldChanges = new HashMap<>(changes);
        Object oldRegistry = field("registry").get(null);
        try {
            field("registry").set(null, null);
            owners.clear(); data.clear(); read.clear(); committed.clear(); populated.clear(); changes.clear();
            // Radius four is the owner's live setting. The high center also models sections above
            // build height, which DirectSectionReader publishes as completed EMPTY_RESULTs.
            VoxelWindow.recenter(35, 30, -41, 4);
            var domain = VoxelWindow.currentState();
            assertTrue(VoxelWindow.hasPendingGeometry(domain), "unknown air is not certified empty");
            for (int y = 26; y <= 34; y++) for (int z = -45; z <= -37; z++) for (int x = 31; x <= 39; x++) {
                int slot = VoxelWindow.slotFor(x, y, z);
                owners.put(slot, SectionPos.of(x, y, z));
                data.put(slot, DirectSectionReader.EMPTY_RESULT);
                read.put(slot, 0L); committed.put(slot, 0L); populated.add(slot);
            }
            assertFalse(VoxelWindow.hasPendingGeometry(domain), "completed empty/out-of-height sections qualify");
            int center = VoxelWindow.slotFor(35, 30, -41);
            read.put(center, 1L);
            assertTrue(VoxelWindow.hasPendingGeometry(domain), "CPU harvest is pending until fenced upload commits");
            committed.put(center, 1L);
            var changeClass = Class.forName("dev.icehunter.fornax.voxel.VoxelWindow$MeshChange");
            var constructor = changeClass.getDeclaredConstructor(SectionPos.class, long.class);
            constructor.setAccessible(true);
            changes.put(center, constructor.newInstance(SectionPos.of(35, 30, -41), 1L));
            assertFalse(VoxelWindow.hasPendingGeometry(domain), "matching mesh/read/commit revision is ready");
            changes.put(center, constructor.newInstance(SectionPos.of(35, 30, -41), 2L));
            assertTrue(VoxelWindow.hasPendingGeometry(domain), "queued mesh edit invalidates before its CPU read");
            changes.clear(); read.put(center, 0L); committed.put(center, 0L); populated.remove(center);
            assertTrue(VoxelWindow.hasPendingGeometry(domain), "missing section cannot qualify from retained owner records");
        } finally {
            owners.clear(); owners.putAll(oldOwners); data.clear(); data.putAll(oldData);
            read.clear(); read.putAll(oldRead); committed.clear(); committed.putAll(oldCommitted);
            populated.clear(); populated.addAll(oldPopulated); changes.clear(); changes.putAll(oldChanges);
            field("registry").set(null, oldRegistry);
            VoxelWindow.recenter(original.centerX(), original.centerY(), original.centerZ(), original.radius());
        }
    }

    private static Field field(String name) throws Exception {
        Field field = VoxelWindow.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
