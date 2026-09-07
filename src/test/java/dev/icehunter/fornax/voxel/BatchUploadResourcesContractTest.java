package dev.icehunter.fornax.voxel;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Native batch wiring cannot execute without the client device; lifetime behavior has separate tests. */
class BatchUploadResourcesContractTest {
    @Test
    void batchesReuseRegistryOwnedResourcesInsteadOfAllocatingEachTime() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/BrickGridUpload.java"));
        int start = source.indexOf("public static void uploadSlots(");
        String body = source.substring(start, source.indexOf("\n    }", start));
        assertTrue(body.contains("registry.voxelUploadResources()"));
        assertFalse(body.contains("MemoryUtil.memAlloc"));
        assertFalse(body.contains("MemoryUtil.memCalloc"));
        assertFalse(body.contains("VulkanComputeBackend.tryCreate()"));
        assertTrue(body.indexOf("uploads.isEmpty()") < body.indexOf("registry.voxelUploadResources()"));
        assertTrue(body.indexOf("synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK)")
                < body.indexOf("registry.voxelUploadResources()"));
    }

    @Test
    void registryClosesItsUploadResourcesAlongsideItsBuffers() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/TargetRegistry.java"));
        assertTrue(source.contains("voxelUploadResources.close()"));
        assertTrue(source.contains("voxelUploadsClosed = true"));
    }
}
