package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-level queue dependency contract: a headless suite cannot execute Vulkan command buffers.
 * It pins the scopes and placement, not driver scheduling or the separate graphics handoff. */
class VoxelMetadataBarrierContractTest {
    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/BrickGridUpload.java"));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "Missing upload method " + signature);
        int open = source.indexOf('{', start);
        int depth = 1;
        int end = open + 1;
        while (depth > 0) {
            char value = source.charAt(end++);
            if (value == '{') depth++;
            else if (value == '}') depth--;
        }
        return source.substring(open, end);
    }

    @Test void priorMetadataComputeReadsCompleteBeforeTransferOverwritesThem() throws Exception {
        String barrier = method(source(), "private static void recordComputeReadToUploadBarrier");
        assertTrue(barrier.contains(".srcAccessMask(VK13.VK_ACCESS_SHADER_READ_BIT)"));
        assertTrue(barrier.contains(".dstAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT)"));
        assertTrue(barrier.contains("VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT"));
        assertFalse(barrier.contains("vkQueueSubmit"));
        assertFalse(barrier.contains("vkWaitForFences"));
    }

    @Test void everyMetadataMutationWaitsBeforeItsFirstWriteAndReleasesAfterward() throws Exception {
        String source = source();
        for (String signature : new String[] {
                "public static void invalidateSectionStates(TargetRegistry registry, long atlasGeneration)",
                "private static void uploadBatchLocked",
                "private static void clearOccupancySlotsLocked"}) {
            String body = method(source, signature);
            int acquire = body.indexOf("recordComputeReadToUploadBarrier(cmd, stack)");
            var writes = Pattern.compile("VK13\\.vkCmd(?:Update|Fill)Buffer\\(").matcher(body);
            assertTrue(writes.find(), "Missing metadata writes in " + signature);
            assertTrue(acquire >= 0 && acquire < writes.start(),
                    "Prior dispatches must finish reading before any range is changed in " + signature);
            int lastWrite = writes.start();
            while (writes.find()) lastWrite = writes.start();
            assertTrue(body.indexOf("recordUploadToComputeReadBarrier(cmd, stack)") > lastWrite,
                    "The final transfer-write to compute-read dependency is still required in " + signature);
        }
    }

    @Test void ordinaryUploadsAndClearsDoNotAddTheOptionalMetadataDependency() throws Exception {
        String source = source();
        for (String signature : new String[] {"private static void uploadBatchLocked",
                "private static void clearOccupancySlotsLocked"}) {
            String body = method(source, signature);
            assertTrue(body.contains("if (sectionStateBuffer != -1L || sourceSummaryBuffer != -1L)\n"),
                    "Only an allocated metadata buffer enables the extra dependency");
        }
    }
}
