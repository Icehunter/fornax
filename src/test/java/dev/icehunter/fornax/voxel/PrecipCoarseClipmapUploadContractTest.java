package dev.icehunter.fornax.voxel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A live level and Vulkan queue are required to exercise the precipitation harvest, so this pins
 * the bounded uploader's source contract: world data stays engine-owned and is made safe before a
 * consuming graph pass can dispatch.
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
        assertTrue(source.contains("ROW_BYTES = GRID * Integer.BYTES"),
                "each of the eight steady rows is exactly 512 bytes, for a 4 KiB upload");
        assertTrue(source.contains("PrecipCoarseClipmapBuffer.representativeBlock"));
        assertTrue(source.contains("PrecipCoarseClipmapBuffer.windowBaseCell"));
        assertTrue(source.contains("level.hasChunk"), "unloaded cells must stay unknown");
        assertTrue(source.contains("Heightmap.Types.MOTION_BLOCKING"));
        assertTrue(source.contains("level.getPrecipitationAt(pos)"));
        assertTrue(source.contains("raw categorical world data only"),
                "visual filtering remains pack policy rather than engine policy");
    }

    @Test
    void uploaderFullyClearsAndRefillsBeforeExposingAChangedLevelOrTeleport() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("PLAN.plan(level, baseCellX, baseCellZ)"));
        assertTrue(source.contains("clearMirror()"));
        assertTrue(source.contains("fillWholeWindow"));
        assertTrue(source.contains("if (uploadWhole(registry))"));
        assertTrue(source.contains("PLAN.commit(plan, level)"),
                "the new window must publish only after the complete upload succeeds");
        assertTrue(source.contains("return false;"),
                "a failed reset reports unready so GraphRunner can withhold consumers");
        assertTrue(source.contains("PrecipCoarseClipmapBuffer.BYTE_SIZE"),
                "a reset uploads all 64 KiB before the graph can consume it");
    }

    @Test
    void uploaderRecordsTransferVisibilityAndNeverWaitsAfterFailedSubmit() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("VK_ACCESS_TRANSFER_WRITE_BIT"));
        assertTrue(source.contains("VK_ACCESS_SHADER_READ_BIT"));
        assertTrue(source.contains("if (submitResult != VK13.VK_SUCCESS)"));
        assertTrue(source.contains("return false;\n                }\n                return VK13.vkWaitForFences"),
                "the fence wait must occur only after a successful queue submit");
        assertTrue(source.contains("== VK13.VK_SUCCESS"),
                "an unsuccessful fence wait must not publish the new window");
    }
}
