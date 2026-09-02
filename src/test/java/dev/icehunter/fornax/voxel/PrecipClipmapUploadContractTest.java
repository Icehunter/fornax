package dev.icehunter.fornax.voxel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The harvest needs a live level, so this pins the uploader's source contract: the answer is
 * vanilla's own, unloaded columns stay unknown, and the upload never stalls the render thread.
 */
class PrecipClipmapUploadContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/voxel/PrecipClipmapUpload.java");

    @Test
    void theAnswerIsVanillasOwnAtTheColumnsSurface() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("level.hasChunk"), "unloaded columns must stay unknown");
        assertTrue(source.contains("Heightmap.Types.MOTION_BLOCKING"));
        assertTrue(source.contains("level.getPrecipitationAt(pos)"));
        assertTrue(source.contains("PrecipClipmapBuffer.describes(MIRROR[slot], worldX, worldZ)"),
                "an unloaded column keeps only an element that already describes it");
    }

    @Test
    void aLevelChangeOrDiscontinuousRecenterClearsTheBufferBeforeAnyRead() throws IOException {
        String source = Files.readString(SOURCE);

        // The tag identifies a column, not a world. Clearing the mirror alone leaves the old
        // world's elements on the GPU for a full sweep.
        assertTrue(source.contains("pendingClear = true"));
        assertTrue(source.contains("mirrorLevel != level"));
        assertTrue(source.contains("Math.abs(baseX - lastBaseX) >= GRID"),
                "a jump of a whole window is the only way a tag-period alias can appear");
        assertTrue(source.contains("EngineBufferUploadQueue.publish(PrecipClipmapBuffer.TARGET, pendingClear, ranges)"),
                "the clear rides the same queue entry as the rows that follow it");
    }

    @Test
    void uploaderNeverSubmitsOrWaitsOnTheRenderThread() throws IOException {
        String source = Files.readString(SOURCE);

        assertFalse(source.contains("vkWaitForFences"), "the queued path has no host fence wait");
        assertFalse(source.contains("vkQueueSubmit"));
        assertFalse(source.contains("SHARED_QUEUE_LOCK"));
        assertFalse(source.contains("VulkanComputeBackend"));
        assertTrue(source.contains("EngineBufferUploadQueue.publish"),
                "uploads ride the consuming compute pass's own command buffer");
    }

    @Test
    void freeingTheBufferDropsAnyQueuedUploadForIt() throws IOException {
        String buffer = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pack/graph/PrecipClipmapBuffer.java"));
        // A range queued for a buffer that was released would be recorded against a dead handle.
        assertTrue(buffer.contains("EngineBufferUploadQueue.discard(TARGET)"));
    }
}
