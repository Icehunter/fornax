package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises CPU light-owner bookkeeping and the real completion callback explicitly.
 * Invoking completion models a successful fence; the empty registry cannot prove GPU writes. */
class VoxelLightClearDebtTest {
    private VoxelSectionState states;
    private boolean metadata;

    private void setup(boolean metadata) throws Exception {
        this.metadata = metadata;
        VoxelHarvestLifecycle.onModelsPublished();
        var graph = metadata ? dev.icehunter.fornax.pack.PackTomlLoader.loadGraph(new java.io.StringReader(
                "[targets.voxelSectionState]\nkind = \"buffer\"\n"), "graph.toml")
                : new GraphSpec(new LinkedHashMap<>(), List.of());
        VoxelWindow.attachRegistry(TargetRegistry.create(graph, Map.of()));
        VoxelWindow.recenter(0, 4, 0, 1);
        var field = VoxelWindow.class.getDeclaredField("sectionStates");
        field.setAccessible(true);
        states = (VoxelSectionState) field.get(null);
    }

    @AfterEach void detach() { VoxelWindow.attachRegistry(null); }

    private BrickGridUpload.SlotUpload publish(int x) throws Exception {
        SectionPos owner = SectionPos.of(x, 4, 0);
        int slot = VoxelWindow.slotFor(x, 4, 0);
        var result = DirectSectionReader.emptyResultWithLight(new byte[4096]);
        var record = VoxelWindow.class.getDeclaredMethod("recordHarvest", int.class,
                SectionPos.class, SectionHarvester.Result.class);
        record.setAccessible(true);
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            boolean clear = (boolean) record.invoke(null, slot, owner, result);
            var snapshot = metadata ? states.geometry(slot, owner) : null;
            if (snapshot != null) clear |= states.needsLightClear(slot, snapshot);
            return new BrickGridUpload.SlotUpload(slot, result, clear, snapshot);
        }
    }

    private void complete(BrickGridUpload.SlotUpload item) {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            VoxelWindow.onSectionUploadCommitted(item);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void newerSameOwnerKeepsTheClearOwedToTheSuccessfullyUploadedPreviousOwner(boolean metadata) throws Exception {
        setup(metadata);
        complete(publish(0)); // P is the actual completed owner, not merely the latest CPU record.
        VoxelWindow.recenter(3, 4, 0, 1); // The same slot now belongs to A.
        var first = publish(3);
        var second = publish(3);
        assertTrue(first.clearLight());
        assertTrue(second.clearLight(), "A2 must inherit the P-to-A clear from unsubmitted A1");
        complete(first); // A stale completion must not acknowledge the outstanding clear.
        var third = publish(3);
        assertTrue(third.clearLight(), "stale A1 cannot settle A2's light-clear debt");
        BrickGridUpload.uploadSlots(VoxelWindow.attachedRegistry(), List.of(third));
        var fourth = publish(3);
        assertTrue(fourth.clearLight(), "an unallocated/skipped upload cannot acknowledge completion");
        complete(fourth);
        assertFalse(publish(3).clearLight(), "stable successfully uploaded ownership preserves settled light");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void publicationWithoutItsOwedClearDoesNotAcknowledgeTheDebt(boolean metadata) throws Exception {
        setup(metadata);
        complete(publish(0));
        VoxelWindow.recenter(3, 4, 0, 1);
        var replacement = publish(3);
        complete(new BrickGridUpload.SlotUpload(replacement.slot(), replacement.result(), false,
                replacement.sectionState()));
        assertTrue(publish(3).clearLight(), "geometry publication alone cannot settle a missing clear");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void returningToTheActualCompletedOwnerPreservesItsSettledLight(boolean metadata) throws Exception {
        setup(metadata);
        complete(publish(0));
        VoxelWindow.recenter(3, 4, 0, 1);
        assertTrue(publish(3).clearLight()); // A has not reached the GPU.
        VoxelWindow.recenter(0, 4, 0, 1);
        assertFalse(publish(0).clearLight(), "the GPU still belongs to P, so P needs no light reset");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void aContentOnlyReplacementInheritsAndSettlesTheSameClearDebt(boolean metadata) throws Exception {
        setup(metadata);
        complete(publish(0));
        VoxelWindow.recenter(3, 4, 0, 1);
        var geometry = publish(3);
        var previous = geometry.result();
        var updated = new SectionHarvester.Result(previous.paletteIndices(), previous.palette(), new byte[4096],
                previous.sourceSummary(), previous.harvestGeneration());
        var data = VoxelWindow.class.getDeclaredField("slotData");
        data.setAccessible(true);
        var needsClear = VoxelWindow.class.getDeclaredMethod("needsLightClear", int.class, SectionPos.class);
        needsClear.setAccessible(true);
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            ((Map<Integer, SectionHarvester.Result>) data.get(null)).put(geometry.slot(), updated);
            var snapshot = metadata ? states.content(geometry.slot()) : null;
            boolean clear = (boolean) needsClear.invoke(null, geometry.slot(), SectionPos.of(3, 4, 0));
            if (snapshot != null) clear |= states.needsLightClear(geometry.slot(), snapshot);
            assertTrue(clear, "replacing only world light must retain the unsubmitted geometry's clear");
            complete(new BrickGridUpload.SlotUpload(geometry.slot(), updated, clear, snapshot));
        }
        assertFalse(publish(3).clearLight(), "successful content publication settles the same light-owner debt");
    }

    @Test void unknownLightOwnershipConservativelyRequiresAClear() throws Exception {
        setup(false);
        assertTrue(publish(0).clearLight(), "no CPU owner does not prove the light allocation was zeroed");
        complete(publish(0));
        setup(false);
        VoxelWindow.recenter(3, 4, 0, 1);
        assertTrue(publish(3).clearLight(), "reset bookkeeping cannot certify retained GPU light");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void modelInvalidationCannotForgetLightInRetainedStorage(boolean metadata) throws Exception {
        setup(metadata);
        complete(publish(0));
        VoxelWindow.invalidateModelData(); // Retires CPU ownership without replacing the GPU buffers.
        VoxelWindow.recenter(3, 4, 0, 1);
        assertTrue(publish(3).clearLight(), "unknown ownership after model invalidation still owes a clear");
        complete(publish(3));
        assertFalse(publish(3).clearLight(), "the successful clear establishes the new owner");
    }

    @Test void allPublicationPathsShareCompletedLightOwnershipAndRejectInvalidClearRanges() throws Exception {
        String window = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/VoxelWindow.java"));
        int mesh = window.indexOf("public static void onSectionHarvested");
        int arrival = window.indexOf("public static void onChunkLoaded", mesh);
        String meshPath = window.substring(mesh, arrival);
        assertFalse(meshPath.contains("BrickGridUpload.uploadSlot("),
                "ordinary mesh publication must share the fenced batch completion boundary");
        int refresh = window.indexOf("public static void refreshLightmaps");
        String refreshPath = window.substring(refresh, window.indexOf("public static void recenterAndResync", refresh));
        assertTrue(refreshPath.contains("needsLightClear(slot, position)"),
                "content-only replacements must inherit the same clear debt");
        String upload = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/BrickGridUpload.java"));
        int batch = upload.indexOf("private static void uploadBatchLocked");
        String batchPath = upload.substring(batch, upload.indexOf("public static void clearLightSlot", batch));
        assertFalse(batchPath.contains("committed != null"), "completion is independent of diagnostic targets");
        int lightBounds = batchPath.indexOf("!fitsInBuffer(lightOffset");
        int firstWrite = batchPath.indexOf("VK13.vkCmdUpdateBuffer");
        assertTrue(lightBounds >= 0 && lightBounds < firstWrite,
                "an invalid required clear must reject the entry before any write or acknowledgement");
        assertTrue(batchPath.indexOf("VoxelWindow.onSectionUploadCommitted(item)")
                > batchPath.indexOf("// execute returns only after"), "completion follows a successful fence");
    }
}
