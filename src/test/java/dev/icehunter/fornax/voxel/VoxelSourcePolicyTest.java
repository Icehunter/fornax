package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import static dev.icehunter.fornax.voxel.VoxelSourceWindowTest.word;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelSourcePolicyTest {
    private static SectionHarvester.Result withPolicy(SectionHarvester.Result data, VoxelSourcePolicy policy) {
        return new SectionHarvester.Result(data.paletteIndices(), data.palette(), data.lightmap(),
                data.sourceSummary(), data.harvestGeneration(), data.sourceEvidence(), policy);
    }

    @Test void excludedEmitterPublishesKnownZeroWithoutChangingRawEvidenceSelfEmissionOrGeometry() {
        var lookup = new VoxelSourceWindowTest.OneSection();
        var original = lookup.data;
        lookup.data = withPolicy(original, new VoxelSourcePolicy(0, 0, 2, true));
        var window = new VoxelSourceWindow(); window.reset(5, 7);
        var bytes = lookup.publish(window).bytes();
        assertEquals(0, word(bytes, 2), "an opted-out emitter must not enter the local source set");
        assertSame(original.palette(), lookup.data.palette());
        assertSame(original.sourceEvidence(), lookup.data.sourceEvidence());
        assertSame(original.sourceSummary(), lookup.data.sourceSummary());
        assertEquals(7, lookup.data.sourceEvidence().intrinsicEmission(1));
        assertEquals(63, lookup.data.sourceEvidence().eligibleMask(1)); // All six original source faces remain diagnostic evidence.
    }

    @Test void policyChangeRepublishesAtAnUnchangedCameraWhileLightmapOnlyChangesDoNot() {
        var lookup = new VoxelSourceWindowTest.OneSection();
        var window = new VoxelSourceWindow(); window.reset(5, 7);
        var first = lookup.publish(window); window.markPublished(first);
        lookup.data = withPolicy(lookup.data, new VoxelSourcePolicy(0, 0, 2, true));
        var excluded = lookup.publish(window);
        assertNotNull(excluded);
        assertTrue(excluded.stateVersion() > first.stateVersion());
        assertEquals(0, word(excluded.bytes(), 2));
        window.markPublished(excluded);
        var policy = lookup.data.sourcePolicy();
        lookup.data = lookup.data.withLightmap(new byte[VoxelLightmap.BYTES_PER_SLOT]);
        assertSame(policy, lookup.data.sourcePolicy());
        assertNull(lookup.publish(window));
        lookup.data = withPolicy(lookup.data, new VoxelSourcePolicy(2, 0, 2, true));
        var selected = lookup.publish(window);
        assertEquals(63 | (7 << 8) | (63 << 16), word(selected.bytes(), VoxelSourceWindow.CELL_BASE + 4));
    }

    @Test void excludedUnsupportedGeometryIsKnownZeroButPaletteAliasingNeverClaimsExclusion() {
        var lookup = new VoxelSourceWindowTest.OneSection();
        var evidence = new VoxelSourceEvidence.Builder();
        evidence.add(false, 0, List.of());
        evidence.add(true, 7, Collections.nCopies(6, MaterialSourceIndex.unavailable(MaterialSourceIndex.UNSUPPORTED_GEOMETRY)));
        lookup.data = new SectionHarvester.Result(lookup.data.paletteIndices(), lookup.data.palette(),
                lookup.data.lightmap(), lookup.data.sourceSummary(), lookup.data.harvestGeneration(), evidence.finish(false),
                new VoxelSourcePolicy(0, 0, 2, true));
        var window = new VoxelSourceWindow(); window.reset(5, 7);
        assertEquals(0, word(lookup.publish(window).bytes(), 2));
        lookup.data = withPolicy(lookup.data, new VoxelSourcePolicy(0, 0, 2, false));
        assertTrue(word(lookup.publish(window).bytes(), 9) > 0);
    }

    @Test void paletteMaskSpansBothWordsAndMarksOverflowOrUnmappedCellsIncomplete() {
        var policy = new VoxelSourcePolicy.Builder();
        for (int i = 0; i < SectionHarvester.MAX_PALETTE_ENTRIES; i++) policy.add(i == 0 || i == 63 || i == 64 || i == 95);
        var complete = policy.finish(false);
        for (int i = 0; i < SectionHarvester.MAX_PALETTE_ENTRIES; i++)
            assertEquals(i == 0 || i == 63 || i == 64 || i == 95, complete.allows(i));
        assertFalse(complete.allows(-1)); assertFalse(complete.allows(96));
        assertTrue(complete.complete()); assertFalse(policy.finish(true).complete());
        policy.markIncomplete(); assertFalse(policy.finish(false).complete());
    }

    /** Harvesting a baked model needs the client; pin capture outside the optional diagnostics branch. */
    @Test void everyHarvestCapturesPolicySeparatelyFromWorldAndMaterialEvidence() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/SectionHarvester.java"));
        assertTrue(source.contains("sourcePolicy.add(materialScalars.voxelLighting(BlockMaterials.idForState(state)))"));
        assertTrue(source.contains("if (index == null) sourcePolicy.markIncomplete()"));
        assertTrue(source.contains("sourcePolicy.finish(overflowLogged[0])"));
    }
    @SuppressWarnings("unchecked")
    @Test void packStorageRetirementDropsOldPolicyUntilReplacementGeometryCommits() throws Exception {
        VoxelHarvestLifecycle.onModelsPublished();
        var graph = dev.icehunter.fornax.pack.PackTomlLoader.loadGraph(new java.io.StringReader("""
                [targets.voxelSourceWindow]
                kind = "buffer"
                [targets.voxelSectionState]
                kind = "buffer"
                """), "graph.toml");
        var oldRegistry = dev.icehunter.fornax.pack.graph.TargetRegistry.create(graph, java.util.Map.of());
        var newRegistry = dev.icehunter.fornax.pack.graph.TargetRegistry.create(graph, java.util.Map.of());
        var data = VoxelEmitterPoolTest.result(7, 0);
        var windowField = VoxelWindow.class.getDeclaredField("sourceWindow"); windowField.setAccessible(true);
        var statesField = VoxelWindow.class.getDeclaredField("sectionStates"); statesField.setAccessible(true);
        var latestField = VoxelSectionState.class.getDeclaredField("latest"); latestField.setAccessible(true);
        try {
            VoxelWindow.attachRegistry(oldRegistry);
            VoxelWindow.synchronizeSourceGeneration(oldRegistry, 7);
            VoxelWindow.recenter(0, 0, 0, 1);
            VoxelWindow.onSectionHarvested(net.minecraft.core.SectionPos.of(0, 0, 0), data);
            int slot = VoxelWindow.slotFor(0, 0, 0);
            var oldToken = ((java.util.Map<Integer, VoxelSectionState.Snapshot>)latestField.get(statesField.get(null))).get(slot);
            synchronized (dev.icehunter.fornax.pass.compute.VulkanComputeBackend.SHARED_QUEUE_LOCK) {
                VoxelWindow.onSectionUploadCommitted(new BrickGridUpload.SlotUpload(slot, data, true, oldToken));
            }
            var oldWindow = (VoxelSourceWindow)windowField.get(null);
            assertEquals(63 | (7 << 8) | (63 << 16), word(oldWindow.preparePublication().bytes(), VoxelSourceWindow.CELL_BASE + 4));
            // GraphRunner.closeCurrent() performs this detach before resolving the new manifest.
            VoxelWindow.attachRegistry(null);
            VoxelWindow.attachRegistry(newRegistry);
            VoxelWindow.synchronizeSourceGeneration(newRegistry, 7);
            VoxelWindow.recenter(0, 0, 0, 1);
            var newWindow = (VoxelSourceWindow)windowField.get(null);
            assertEquals(0, word(newWindow.preparePublication().bytes(), 2));
            var excluded = withPolicy(data, new VoxelSourcePolicy(0, 0, 2, true));
            VoxelWindow.onSectionHarvested(net.minecraft.core.SectionPos.of(0, 0, 0), excluded);
            int newSlot = VoxelWindow.slotFor(0, 0, 0);
            var newToken = ((java.util.Map<Integer, VoxelSectionState.Snapshot>)latestField.get(statesField.get(null))).get(newSlot);
            assertTrue(newToken.storageGeneration() > oldToken.storageGeneration());
            synchronized (dev.icehunter.fornax.pass.compute.VulkanComputeBackend.SHARED_QUEUE_LOCK) {
                // An old upload cannot commit over the replacement owner/generation.
                VoxelWindow.onSectionUploadCommitted(new BrickGridUpload.SlotUpload(slot, data, true, oldToken));
                assertEquals(0, word(newWindow.preparePublication().bytes(), 2));
                VoxelWindow.onSectionUploadCommitted(new BrickGridUpload.SlotUpload(newSlot, excluded, true, newToken));
            }
            var bytes = newWindow.preparePublication().bytes();
            assertEquals(0, word(bytes, 2));
            assertTrue(VoxelWindow.hasValidData(0, 0, 0), "source exclusion preserves harvested geometry");
        } finally {
            VoxelWindow.attachRegistry(null);
        }
    }

}
