package dev.icehunter.fornax.debug;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the F2 hook and the order of calls. Only a game session checks the GPU data. */
class ScreenshotCaptureContractTest {
    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax");

    @Test
    void ordinaryScreenshotHasAnIndependentRegisteredNonCancellingHook() throws Exception {
        String registry = Files.readString(Path.of("src/main/resources/fornax.mixins.json"));
        assertTrue(registry.contains("vanilla.ScreenshotCaptureMixin"), "normal F2 must reach capture");
        String source = Files.readString(SOURCE.resolve("mixin/vanilla/ScreenshotCaptureMixin.java"));
        assertTrue(source.contains("grab(Lnet/minecraft/client/Minecraft;Z)V"));
        assertTrue(source.contains("@At(\"HEAD\")"));
        assertTrue(source.contains("ScreenshotCapture.capture(minecraft)"));
        assertTrue(!source.contains("cancellable") && !source.contains("cancel()"));
        assertTrue(!source.contains("FullscreenCapture.request()"));
    }

    @Test
    void completedFrameMetadataPrecedesHistorySwapAndIsInvalidatedBeforeReallocation() throws Exception {
        String source = Files.readString(SOURCE.resolve("pack/graph/GraphRunner.java"));
        int metadata = source.indexOf("ScreenshotCapture.completedFrame(");
        int swap = source.indexOf("r.swapHistory();");
        assertTrue(metadata >= 0 && metadata < swap, "snapshot must preserve the producing frame phase");
        int prepare = source.indexOf("public static void prepare(");
        int invalidate = source.indexOf("ScreenshotCapture.invalidateCompletedFrame()", prepare);
        int resize = source.indexOf("r.ensureSize(", prepare);
        assertTrue(invalidate > prepare && invalidate < resize, "failed frames cannot retain old metadata");
        int close = source.indexOf("private static void closeCurrent()");
        assertTrue(source.indexOf("ScreenshotCapture.invalidateCompletedFrame()", close) > close);
    }

    @Test
    void nativePresentationPromotesTheSnapshotBeforeJitterAdvance() throws Exception {
        String source = Files.readString(SOURCE.resolve("mixin/vanilla/GameRendererMixin.java"));
        int tail = source.indexOf("private void fornax$endFrame(");
        int restore = source.indexOf("this.fornax$restoreNativeTarget();", tail);
        int promote = source.indexOf("ScreenshotCapture.presentedFrame(", tail);
        int advance = source.indexOf("CameraJitter.advanceFrame();", tail);
        assertTrue(tail >= 0 && restore > tail && promote > restore && advance > promote);
    }

    @Test
    void onlyAnF2RequestAllocatesReadbacksAndMissingCopyUsageIsExplicit() throws Exception {
        String source = Files.readString(SOURCE.resolve("debug/ScreenshotCapture.java"));
        int capture = source.indexOf("public static void capture(");
        int configGate = source.indexOf("if (!Files.isRegularFile(config)) return;", capture);
        int directory = source.indexOf("Files.createDirectories(directory);", capture);
        assertTrue(configGate > capture && directory > configGate);
        assertTrue(source.indexOf("createBuffer(") > capture);
        assertTrue(source.contains("GpuTexture.USAGE_COPY_SRC"));
        assertTrue(source.contains("Texture has no COPY_SRC usage:"));
        assertTrue(source.contains("session.pending = plans.size();"));
        assertTrue(source.indexOf("session.pending = plans.size();") < source.indexOf("session.queue(plan)"));
        String runner = Files.readString(SOURCE.resolve("pack/graph/GraphRunner.java"));
        int adapter = runner.indexOf("public static GpuTextureView screenshotCaptureView(");
        int end = runner.indexOf("return GraphInputResolver.resolveView(reference, registry, mipchainTargets);", adapter);
        assertTrue(end > adapter);
        assertTrue(runner.substring(adapter, end).contains("!target.hasHistory()"));
        assertTrue(runner.substring(adapter, end).contains("registry.isStorageTexture(reference)"));
    }
}
