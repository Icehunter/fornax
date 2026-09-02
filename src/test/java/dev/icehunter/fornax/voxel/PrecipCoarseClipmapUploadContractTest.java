package dev.icehunter.fornax.voxel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A live level is required to exercise the climate harvest, so this pins the bounded uploader's
 * source contract: world data stays engine-owned, every lane comes from the biome the game itself
 * resolves at the column's surface, and the upload never stalls the render thread.
 */
class PrecipCoarseClipmapUploadContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/voxel/PrecipCoarseClipmapUpload.java");

    @Test
    void uploaderUsesTheCoarseAbiAndBoundsSteadyStateWork() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("PrecipCoarseClipmapUploadPlan.ROWS_PER_FRAME"),
                "the Vulkan-free plan owns the eight-row bound");
        assertTrue(source.contains("PrecipCoarseClipmapBuffer.GRID"));
        assertTrue(source.contains("PrecipCoarseClipmapBuffer.BYTE_SIZE"));
        assertTrue(source.contains("ROW_BYTES = GRID * PrecipCoarseClipmapBuffer.BYTES_PER_CELL"),
                "each of the eight steady rows is 128 cells of one ivec4, 2 KiB, for a 16 KiB upload");
        assertTrue(source.contains("PrecipCoarseClipmapBuffer.wordOffsetForCell"),
                "cells are addressed by their first word, never by a bare slot");
        assertTrue(source.contains("PrecipCoarseClipmapBuffer.representativeBlock"));
        assertTrue(source.contains("PrecipCoarseClipmapBuffer.windowBaseCell"));
        assertTrue(source.contains("level.hasChunk"), "unloaded cells must stay unknown");
        assertTrue(source.contains("Heightmap.Types.MOTION_BLOCKING"));
        assertTrue(source.contains("raw categorical world data only"),
                "visual filtering remains pack policy rather than engine policy");
    }

    @Test
    void everyClimateLaneComesFromTheBiomeTheGameResolvesAtTheSurface() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("level.getBiome(pos)"), "one biome resolution per cell, at the surface");
        assertTrue(source.contains("biome.getPrecipitationAt(pos, seaLevel)"));
        assertTrue(source.contains("biome.getTemperature(pos, seaLevel)"),
                "the height-adjusted temperature the classification thresholded, same pos, same sea level");
        assertTrue(source.contains("biome.climateSettings.downfall()"));
        assertTrue(source.contains("biome.getBaseTemperature()"));
        assertTrue(source.contains("ConventionalBiomeTags.IS_HOT") && source.contains("BiomeTags.IS_MOUNTAIN"),
                "tags come from the biome's own declarations, not from a Fornax classification");
        assertTrue(source.contains("WORD_RESERVED] = 0"), "reserved words are written zero, never left");
    }

    @Test
    void uploaderFullyClearsAndRefillsBeforeExposingAChangedLevelOrTeleport() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("PLAN.plan(level, baseCellX, baseCellZ)"));
        assertTrue(source.contains("clearMirror()"));
        assertTrue(source.contains("fillWholeWindow"));
        assertTrue(source.contains("EngineBufferUploadQueue.publish(PrecipCoarseClipmapBuffer.TARGET, true, wholeWindowRanges())"),
                "a reset queues a clear ahead of the refill, so no old word survives it");
        assertTrue(source.contains("EngineBufferUploadQueue.MAX_RANGE_BYTES"),
                "the 256 KiB refill is split at the inline-update limit");
        assertTrue(source.contains("PLAN.commit(plan, level)"),
                "the new window publishes only once its clear and refill are queued");
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
}
