package dev.icehunter.fornax.mixin.sodium;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins two things about the harvest hook: the gate runs before anything is queued, and the harvest
 * itself never runs inline on Sodium's own meshing thread. {@code SectionHarvester.harvestCurrent}
 * resolves real baked models: a per-{@code BlockState} query into the model manager, through
 * whatever model provider is installed, including a connected-texture mod's. Calling it directly
 * from this mixin, inline on Sodium's own chunk-build worker thread, puts that query in the same
 * task that later resolves the section's real per-position quads through the same mod, which breaks
 * the mod's output for the real render on every freshly (re)meshed section. See
 * {@code docs/ARCHITECTURE.md} section 12 and {@code VoxelWindow.queueMeshTriggeredHarvest}'s own
 * doc.
 */
final class ChunkBuilderMeshingTaskMixinContractTest {
    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax/mixin/sodium/"
            + "ChunkBuilderMeshingTaskMixin.java");

    @Test
    void mixinIsRegistered() throws IOException {
        String mixins = Files.readString(Path.of("src/main/resources/fornax.mixins.json"));
        assertTrue(mixins.contains("sodium.ChunkBuilderMeshingTaskMixin"),
                "ChunkBuilderMeshingTaskMixin must be listed in fornax.mixins.json or it never applies");
    }

    @Test
    void needsHarvestGateRunsBeforeQueuingTheHarvest() throws IOException {
        String source = Files.readString(SOURCE);
        int gateAt = source.indexOf("VoxelWindow.needsHarvest()");
        int queueAt = source.indexOf("VoxelWindow.queueMeshTriggeredHarvest(");
        assertTrue(gateAt >= 0, "expected a VoxelWindow.needsHarvest() check in the harvest hook");
        assertTrue(queueAt >= 0, "expected a VoxelWindow.queueMeshTriggeredHarvest(...) call in the harvest hook");
        assertTrue(gateAt < queueAt,
                "the needsHarvest() gate must run before queuing the harvest: checking after has "
                        + "already queued the model/texel walk, which defeats the point of the gate");
    }

    @Test
    void harvestNeverRunsInlineOnSodiumsOwnMeshingThread() throws IOException {
        String source = Files.readString(SOURCE);
        assertFalse(source.contains("SectionHarvester"),
                "this mixin must never call SectionHarvester directly: that resolves real baked "
                        + "models inline on Sodium's own meshing thread, in the same task that is "
                        + "about to resolve the same section's real quads through whatever model "
                        + "provider is installed (a connected-texture mod's, for example); route "
                        + "through VoxelWindow.queueMeshTriggeredHarvest instead, which runs on a "
                        + "dedicated background thread apart from Sodium's meshing task");
    }
}
