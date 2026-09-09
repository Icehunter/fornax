package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import dev.icehunter.fornax.pack.PackTomlLoader;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pack.material.MaterialScalars;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelSourceCandidatePublicationTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach void restore() {
        VoxelSourceSummary.setEnabled(false);
        VoxelWindow.attachRegistry(null);
        VoxelHarvestLifecycle.onModelsPublished();
    }

    private static SectionHarvester.Result air(boolean diagnostics) {
        VoxelSourceSummary.setEnabled(diagnostics);
        VoxelHarvestLifecycle.onModelsPublished();
        var cells = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        return SectionHarvester.harvest(cells, MaterialScalars.build(List.of()));
    }

    private static VoxelSourceEvidence evidence(int intrinsic) {
        var builder = new VoxelSourceEvidence.Builder();
        var faces = new java.util.ArrayList<>(java.util.Collections.nCopies(6,
                MaterialSourceIndex.unavailable(MaterialSourceIndex.MISSING_MAP)));
        faces.set(2, MaterialSourceIndex.unavailable(MaterialSourceIndex.UNREADABLE));
        builder.add(true, intrinsic, faces);
        for (int cell = 0; cell < 4096; cell++) builder.addCell(true, 0);
        return builder.finish(false);
    }

    private static SectionHarvester.Result result(VoxelSourceEvidence evidence) {
        return new SectionHarvester.Result(new byte[4096], new SectionPalette(List.of()), new byte[4096],
                new VoxelSourceSummary(0, 4096, 0, 0, 4096, 0, 4096), VoxelHarvestLifecycle.generation(), evidence);
    }

    @Test void diagnosticsOffUsesTheSharedUnavailableEvidenceWithoutChangingLegacyConstructors() {
        assertSame(VoxelSourceEvidence.UNAVAILABLE, air(false).sourceEvidence());
        var old = new SectionHarvester.Result(new byte[4096], new SectionPalette(List.of()));
        assertSame(VoxelSourceEvidence.UNAVAILABLE, old.sourceEvidence());
        assertSame(VoxelSourceEvidence.UNAVAILABLE, DirectSectionReader.emptyResultWithLight(new byte[4096]).sourceEvidence());
    }

    @Test void enabledAirHarvestAndOutsideHeightResultCarryKnownEmptyEvidence() {
        var harvested = air(true).sourceEvidence();
        assertTrue(harvested.available());
        assertEquals(1, harvested.paletteSize());
        assertEquals(0, harvested.eligibleFaces());
        assertEquals(0, harvested.unsupportedFaces());
        assertSame(VoxelSourceEvidence.EMPTY, DirectSectionReader.emptyResultWithLight(new byte[4096]).sourceEvidence());
    }

    @Test void lightOnlyReplacementPreservesEvidenceAndBothCapturedGenerations() {
        var previous = result(evidence(7));
        var updated = previous.withLightmap(new byte[4096]);
        assertSame(previous.sourceEvidence(), updated.sourceEvidence());
        assertSame(previous.sourceSummary(), updated.sourceSummary());
        assertSame(previous.paletteIndices(), updated.paletteIndices());
        assertEquals(previous.harvestGeneration(), updated.harvestGeneration());
    }

    @Test void committedCounterReplacementInvalidationAndResetDoNotRetainOldContributions() {
        var inventory = new VoxelSourceInventory();
        var first = result(evidence(7));
        var second = result(evidence(0));
        inventory.commit(1, first.sourceSummary(), first.sourceEvidence());
        assertEquals(4096 * 5, inventory.stats().eligibleFaces());
        assertEquals(4096, inventory.stats().unsupportedFaces());
        inventory.commit(1, second.sourceSummary(), second.sourceEvidence());
        assertEquals(0, inventory.stats().eligibleFaces());
        assertEquals(4096, inventory.stats().unsupportedFaces());
        inventory.invalidate(List.of(1));
        assertEquals(0, inventory.stats().unsupportedFaces());
        inventory.commit(2, first.sourceSummary(), first.sourceEvidence());
        inventory.reset();
        assertEquals(0, inventory.stats().eligibleFaces());
        assertEquals(0, inventory.stats().unsupportedFaces());
    }

    @SuppressWarnings("unchecked")
    @Test void onlyTheCurrentCompletionPublishesCountersAndStorageRetirementRemovesThem() throws Exception {
        VoxelHarvestLifecycle.onModelsPublished();
        var graph = PackTomlLoader.loadGraph(new StringReader("[targets.voxelSourceSummary]\nkind = \"buffer\"\n"), "graph.toml");
        var registry = TargetRegistry.create(graph, Map.of());
        VoxelWindow.attachRegistry(registry);
        VoxelWindow.synchronizeSourceGeneration(registry, 0);
        VoxelWindow.recenter(0, 0, 0, 1);
        var current = result(evidence(7));
        VoxelWindow.onSectionHarvested(SectionPos.of(0, 0, 0), current);
        assertEquals(0, VoxelWindow.sourceInventoryStats().eligibleFaces(), "empty registry performs no GPU commit");
        int slot = VoxelWindow.slotFor(0, 0, 0);
        var field = VoxelWindow.class.getDeclaredField("sectionStates");
        field.setAccessible(true);
        var latest = VoxelSectionState.class.getDeclaredField("latest");
        latest.setAccessible(true);
        var token = ((Map<Integer, VoxelSectionState.Snapshot>) latest.get(field.get(null))).get(slot);
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            // Models a successful fence callback; this headless test cannot prove actual GPU writes.
            VoxelWindow.onSectionUploadCommitted(new BrickGridUpload.SlotUpload(slot, current, true, token));
        }
        assertEquals(4096 * 5, VoxelWindow.sourceInventoryStats().eligibleFaces());
        assertEquals(4096, VoxelWindow.sourceInventoryStats().unsupportedFaces());
        var replacement = result(evidence(0));
        VoxelWindow.onSectionHarvested(SectionPos.of(0, 0, 0), replacement);
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            VoxelWindow.onSectionUploadCommitted(new BrickGridUpload.SlotUpload(slot, current, true, token));
            assertEquals(4096 * 5, VoxelWindow.sourceInventoryStats().eligibleFaces(), "stale completion cannot replace committed totals");
            var replacementToken = ((Map<Integer, VoxelSectionState.Snapshot>) latest.get(field.get(null))).get(slot);
            VoxelWindow.onSectionUploadCommitted(new BrickGridUpload.SlotUpload(slot, replacement, false, replacementToken));
        }
        assertEquals(0, VoxelWindow.sourceInventoryStats().eligibleFaces());
        VoxelWindow.invalidateModelData();
        assertEquals(0, VoxelWindow.sourceInventoryStats().eligibleFaces());
        assertEquals(0, VoxelWindow.sourceInventoryStats().unsupportedFaces());
    }

    /** Headless model resolution and F10 cannot execute here; pin their real production wiring. */
    @Test void productionHarvestRefreshAndF10ShareTheEvidenceAndCounterPaths() throws Exception {
        String harvest = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/SectionHarvester.java"));
        assertTrue(harvest.contains("sourceDiagnostics ? new VoxelSourceEvidence.Builder() : null"));
        assertTrue(harvest.contains("sourceEvidence.addCell(!state.isAir(), index == null ? -1 : index)"));
        assertTrue(harvest.contains("sourceEvidence.add(true, state.getLightEmission(), sourceFaces.summaries())"));
        String window = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/VoxelWindow.java"));
        assertTrue(window.contains("previous.withLightmap(sample.getValue())"));
        String debug = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pass/voxel/VoxelDebugRaymarchPass.java"));
        assertTrue(debug.contains("profiler.recordValue(\"source_eligible_faces\", sources.eligibleFaces())"));
        assertTrue(debug.contains("profiler.recordValue(\"source_unsupported_faces\", sources.unsupportedFaces())"));
        assertEquals(8, VoxelSourceSummary.WORDS_PER_SLOT);
        assertEquals(7, VoxelFaceTexture.FACE_WORDS);
    }
}
