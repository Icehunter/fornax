package dev.icehunter.fornax.voxel;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source checks only. Live Vulkan uploads and whether a chunk is loaded need a real game session. */
class VoxelCoverageValidityContractTest {
    @Test
    void freshSummaryUsesPendingSentinel() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/BrickGridUpload.java"));
        assertTrue(source.contains("slotCount * BRICK_SUMMARY_BYTES_PER_SLOT, SUMMARY_PENDING)"),
                "A fresh slot must not claim harvested-empty before any harvest");
        String registry = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/TargetRegistry.java"));
        assertTrue(registry.contains("clearBuffer(backend, vkBuffer, sizeBytes, initialWord)"));
        assertTrue(registry.contains("vkCmdFillBuffer(cmd, vkBuffer, 0, sizeBytes, initialWord)"));
    }

    @Test
    void missingChunksDoNotBecomeEmptyPlaceholders() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/DirectSectionReader.java"));
        assertTrue(source.contains("ChunkStatus.FULL, false)"),
                "The client returns its empty placeholder when the create flag is true");
    }
}
