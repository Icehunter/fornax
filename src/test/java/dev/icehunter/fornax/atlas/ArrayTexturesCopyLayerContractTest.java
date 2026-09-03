package dev.icehunter.fornax.atlas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code copyLayer} needs a live GPU device to exercise directly, so this pins its source instead
 * (like {@code ArrayTexturesLeakContractTest}/{@code BuiltinResolutionContractTest}): every
 * barrier stays GENERAL-to-GENERAL with queue-family ownership {@code IGNORED}
 * (docs/ARCHITECTURE.md §12), the copy is spliced into the current submission via {@code execute}
 * rather than flushed and host-waited, and out-of-range layers fail loudly instead of silently
 * landing on layer 0.
 */
class ArrayTexturesCopyLayerContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/atlas/ArrayTextures.java");

    private static String copyLayerMethod() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public static void copyLayer(");
        assertTrue(methodStart >= 0, "copyLayer must still exist");
        int braceDepth = 0;
        int i = source.indexOf('{', methodStart);
        int bodyStart = i;
        do {
            char c = source.charAt(i);
            if (c == '{') {
                braceDepth++;
            } else if (c == '}') {
                braceDepth--;
            }
            i++;
        } while (braceDepth > 0);
        return source.substring(bodyStart, i);
    }

    @Test
    void everyBarrierStaysInGeneralLayout() throws IOException {
        String method = copyLayerMethod();
        assertTrue(method.contains("srcImage, VK13.VK_IMAGE_LAYOUT_GENERAL")
                        && method.contains("dstImage, VK13.VK_IMAGE_LAYOUT_GENERAL"),
                "vkCmdCopyImage must read and write GENERAL on both sides");
        for (String forbidden : new String[] {"TRANSFER_SRC_OPTIMAL", "TRANSFER_DST_OPTIMAL", "UNDEFINED"}) {
            assertTrue(!method.contains(forbidden),
                    "copyLayer must never transition layout (found " + forbidden + ")");
        }
    }

    @Test
    void noQueueFamilyOwnershipTransfer() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("private static void copyBarrier(");
        assertTrue(methodStart >= 0, "copyBarrier must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));
        assertTrue(method.contains("VK13.VK_QUEUE_FAMILY_IGNORED"),
                "copyBarrier must leave queue-family ownership IGNORED");
    }

    @Test
    void neverFlushesOrHostWaits() throws IOException {
        String method = copyLayerMethod();
        assertTrue(method.contains("encoder.execute(cmd);"),
                "the copy must be spliced into the current submission via execute(), not flushed");
        assertTrue(!method.contains("createFence()") && !method.contains("awaitCompletion"),
                "a per-frame fence wait here would be a mid-frame stall");
    }

    @Test
    void outOfRangeLayerFailsLoudlyBeforeTouchingTheGpu() throws IOException {
        String method = copyLayerMethod();
        int checkIndex = method.indexOf("if (layer < 0 || layer >= destTexture.getDepthOrLayers())");
        int throwIndex = method.indexOf("throw new IllegalArgumentException");
        int deviceIndex = method.indexOf("RenderSystem.tryGetDevice()");
        assertTrue(checkIndex >= 0 && throwIndex > checkIndex,
                "an out-of-range layer must throw, not silently clamp to a valid layer");
        assertTrue(checkIndex < deviceIndex,
                "the range check must run before any GPU device is touched");
    }

    @Test
    void zeroFillsEveryLayerAtAllocationSinceArraysCannotUseTheRenderPassClear() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public static Allocation create(");
        assertTrue(methodStart >= 0, "create must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));
        assertTrue(method.contains("zeroFillLayers("),
                "create must zero-fill every layer: array textures can't use the render-pass clear");
    }
}
