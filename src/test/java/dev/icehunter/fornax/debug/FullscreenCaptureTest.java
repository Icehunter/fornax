package dev.icehunter.fornax.debug;

import com.mojang.blaze3d.GpuFormat;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FullscreenCaptureTest {
    @TempDir Path game;
    @Test void requestCapturesOnlyOneFrameAndMissingPassesFailExplicitly() throws Exception {
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/fornax-capture.json"), "{\"passes\":[\"first\",\"second\"]}");
        FullscreenCapture.request();
        FullscreenCapture.beginFrame(game, Map.of());
        assertTrue(FullscreenCapture.isSelected("first"));
        assertTrue(FullscreenCapture.isSelected("second"));
        assertFalse(FullscreenCapture.isSelected("unselected"));
        FullscreenCapture.endFrame();
        assertFalse(FullscreenCapture.isSelected("first"));
        FullscreenCapture.beginFrame(game, Map.of());
        assertFalse(FullscreenCapture.isSelected("first"));
        try (var directories = Files.list(game.resolve("fornax-captures"))) {
            List<Path> captures = directories.toList();
            assertEquals(1, captures.size());
            String manifest = Files.readString(captures.getFirst().resolve("manifest.json"));
            assertTrue(manifest.contains("Selected pass did not execute in this graph frame"));
            assertTrue(manifest.contains("\"textureCaptureComplete\": false"));
            assertTrue(manifest.contains("\"replayComplete\": false"));
            assertTrue(manifest.contains("\"status\": \"failed\""));
        }
    }
    @Test void opaqueDepthSupportsRawDiagnosticCopies() throws Exception {
        // A unit test cannot allocate on the GPU, so pin the copy flag where it is set.
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pipeline/OpaqueDepth.java"));
        assertTrue(source.contains("GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC"));
    }
    @Test void configuredPassesPreserveOrderAndRejectDuplicates() {
        assertEquals(List.of("first", "second"), FullscreenCapture.parsePasses("{\"passes\":[\"first\",\"second\"]}"));
        assertThrows(IllegalArgumentException.class, () -> FullscreenCapture.parsePasses("{\"passes\":[\"first\",\"first\"]}"));
    }
    @Test void singlePassConfigurationRemainsSupported() {
        assertEquals(List.of("first"), FullscreenCapture.parsePasses("{\"pass\":\"first\"}"));
    }
    @Test void configurationIsBoundedAndCannotEscapeCaptureDirectory() {
        for (String json : List.of("{}", "{\"passes\":[]}", "{\"pass\":\"../out\"}",
                "{\"passes\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}")) {
            assertThrows(IllegalArgumentException.class, () -> FullscreenCapture.parsePasses(json));
        }
    }
    @Test void depthAndHalfFloatByteSizesAreRawUnconverted() {
        assertEquals(24, FullscreenCapture.textureBytes(GpuFormat.D32_FLOAT, 3, 2, 1));
        assertEquals(48, FullscreenCapture.textureBytes(GpuFormat.RGBA16_FLOAT, 3, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> FullscreenCapture.textureBytes(GpuFormat.D32_FLOAT_S8_UINT, 3, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> FullscreenCapture.textureBytes(GpuFormat.RGBA8_UNORM, 3, 2, 2));
    }
    @Test void totalBudgetFitsMeasuredNativeResolutionTwoPassCapture() {
        // Measured at 3456x2234: the pair of passes comes to 78 raw bytes per pixel.
        long measuredBytes = 3456L * 2234 * 78;
        assertEquals(602_214_912L, measuredBytes);
        assertTrue(measuredBytes <= FullscreenCapture.MAX_BYTES);
    }
    @Test void invalidAndOversizedAllocationsFailBeforeReadback() {
        assertThrows(IllegalArgumentException.class, () -> FullscreenCapture.textureBytes(GpuFormat.RGBA32_FLOAT, 16384, 16384, 1));
        assertThrows(IllegalArgumentException.class, () -> FullscreenCapture.textureBytes(GpuFormat.D32_FLOAT, 0, 2, 1));
    }
}
