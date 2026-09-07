package dev.icehunter.fornax.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Testing real presentation needs a live surface and Minecraft's own mixins applied, so these are
 * source contracts instead. They check that the code only watches the existing calls, counts only
 * after success, and needs an explicit opt-in for the diagnostic, all without acquiring images,
 * sending GPU work, or claiming to know when a frame actually reaches the screen.
 */
class PresentationBaselineContractTest {
    private static final Path READBACK = Path.of(
            "src/main/java/dev/icehunter/fornax/pipeline/GBufferReadbackDiagnostic.java");
    private static final Path SEAM = Path.of(
            "src/main/java/dev/icehunter/fornax/mixin/vanilla/PresentSeamMixin.java");
    private static final Path PRESENTER = Path.of(
            "src/main/java/dev/icehunter/fornax/pass/FrameGenPresenter.java");

    @Test
    void periodicReadbackRequiresExplicitJvmOptInAndF10StillBypassesThatGate() throws IOException {
        String source = Files.readString(READBACK);
        assertTrue(source.contains("Boolean.getBoolean(\"fornax.debug.gbufferReadback\")"),
                "periodic GPU readbacks must require an explicit diagnostic JVM property");
        assertFalse(source.contains("profilerOverlay"),
                "opening the timing HUD must never turn on synchronous diagnostic readbacks");
        String request = body(source, "public static void requestDump()");
        assertTrue(request.contains("dumpRequested = true;"));
        assertTrue(request.contains("FullscreenCapture.request();"));
        String periodic = body(source, "public static void maybeLog(GBuffer gbuffer)");
        assertOrdered(periodic, "if (dumpRequested)", "dumpAll(gbuffer);", "if (!PERIODIC_READBACK)",
                "frameCounter++;");
        assertTrue(source.contains("INTERVAL_FRAMES = 30;"),
                "the existing first-frame/every-30-frames cadence is preserved when opted in");
        assertTrue(periodic.contains("frameCounter != 1 && frameCounter % INTERVAL_FRAMES != 0"));
    }

    @Test
    void realPresentCallsTheOriginalOnceAndCountsOnlyAfterSuccessfulCompletion() throws IOException {
        String source = Files.readString(SEAM);
        assertTrue(source.contains("Lcom/mojang/blaze3d/systems/GpuSurface;present()V"));
        String present = body(source, "private void fornax$measureRealPresent(");
        assertEquals(1, occurrences(present, "original.call(surface);"));
        assertOrdered(present, "boolean active = GraphRunner.isActive();", "System.nanoTime()",
                "original.call(surface);", "if (active)", "recordPresentation(false)",
                "\"surface present CPU (real)\"");
        assertFalse(present.contains("finally"), "a throwing present must never increment success counts");
        assertFalse(present.contains("catch"), "vanilla exceptions must retain their propagation");
        assertTrue(present.contains("* 1e-6"), "System.nanoTime deltas are nanoseconds; record expects milliseconds");
        assertNoNewGpuWork(present);
        String mixins = Files.readString(Path.of("src/main/resources/fornax.mixins.json"));
        assertTrue(mixins.contains("\"vanilla.PresentSeamMixin\""));
    }

    @Test
    void realAcquireMeasuresItsExistingCallWithoutCountingAPresentation() throws IOException {
        String source = Files.readString(SEAM);
        assertTrue(source.contains("Lcom/mojang/blaze3d/systems/GpuSurface;acquireNextTexture()V"));
        String acquire = body(source, "private void fornax$measureRealAcquire(");
        assertEquals(1, occurrences(acquire, "original.call(surface);"));
        assertOrdered(acquire, "boolean active = GraphRunner.isActive();", "System.nanoTime()",
                "original.call(surface);", "if (active)", "\"surface acquire CPU (real)\"");
        assertFalse(acquire.contains("recordPresentation("));
        assertFalse(acquire.contains("catch"));
        assertFalse(acquire.contains("finally"));
        assertTrue(acquire.contains("* 1e-6"));
        assertNoNewGpuWork(acquire);
    }

    @Test
    void generatedPresentRetainsItsSubmitPresentAcquireSequenceAndCountsAfterSuccess() throws IOException {
        String presenter = body(Files.readString(PRESENTER),
                "public static void presentGeneratedIfReady(");
        assertEquals(1, occurrences(presenter, "encoder.submit();"),
                "instrumentation must not submit any additional GPU work");
        assertEquals(1, occurrences(presenter, "surface.present();"));
        assertEquals(1, occurrences(presenter, "surface.acquireNextTexture();"));
        assertEquals(1, occurrences(presenter, "recordPresentation(true)"));
        assertOrdered(presenter, "surface.blitFromTexture(", "encoder.submit();", "surface.present();",
                "FrameGenPass.markFailed(\"present seam (present G)\", t);", "return;",
                "recordPresentation(true)", "\"surface present CPU (generated)\"", "recordPresented();",
                "surface.acquireNextTexture();", "\"surface acquire CPU (generated)\"");
        assertFalse(presenter.contains("recordPresentation(false)"));
        assertFalse(presenter.contains("finally"), "failed presents must leave success counts unchanged");
        assertTrue(presenter.contains("* 1e-6"));
    }

    private static void assertNoNewGpuWork(String method) {
        assertFalse(method.contains("submit("));
        assertFalse(method.contains("wait("));
        assertFalse(method.contains("sleep("));
        assertFalse(method.contains("synchronized"));
    }

    private static int occurrences(String text, String token) {
        return (text.length() - text.replace(token, "").length()) / token.length();
    }

    private static void assertOrdered(String text, String... tokens) {
        int cursor = 0;
        for (String token : tokens) {
            int next = text.indexOf(token, cursor);
            assertTrue(next >= cursor, "missing or reordered seam operation: " + token);
            cursor = next + token.length();
        }
    }

    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing method: " + signature);
        int opening = source.indexOf('{', start);
        int depth = 1;
        for (int end = opening + 1; end < source.length(); end++) {
            char c = source.charAt(end);
            depth += c == '{' ? 1 : c == '}' ? -1 : 0;
            if (depth == 0) {
                return source.substring(opening + 1, end);
            }
        }
        throw new AssertionError("unclosed method: " + signature);
    }
}
