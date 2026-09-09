package dev.icehunter.fornax.debug;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
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
    @Test void unreadableInputKeepsItsSlotAndDoesNotBlockCopyableTextures() {
        var colour = texture(GpuFormat.RGBA16_FLOAT, 3, 2, 1, true);
        var noise = texture(GpuFormat.RGBA8_UNORM, 3, 2, 1, false);
        var depth = texture(GpuFormat.D32_FLOAT, 3, 2, 1, true);
        // Two half-float colour images and one depth image: (8 + 8 + 4) * 3 * 2.
        var plan = FullscreenCapture.planTextures(colour, List.of(colour, noise, depth, noise), 120);
        assertEquals(120, plan.bytes());
        assertEquals(4, plan.inputs().size());
        assertEquals(48, plan.inputs().get(0).bytes());
        assertEquals(0, plan.inputs().get(1).bytes());
        assertTrue(plan.inputs().get(1).unavailableReason().contains("COPY_SRC"));
        assertEquals(24, plan.inputs().get(2).bytes());
        assertEquals(0, plan.inputs().get(3).bytes());
        assertTrue(plan.inputs().get(3).unavailableReason().contains("COPY_SRC"));
        assertThrows(IllegalArgumentException.class,
                () -> FullscreenCapture.planTextures(colour, List.of(colour, noise, depth, noise), 119));
    }

    @Test void unavailableInputsDoNotHideInvalidTexturesOrUnreadableOutputs() {
        var output = texture(GpuFormat.RGBA16_FLOAT, 3, 2, 1, true);
        var closed = texture(GpuFormat.RGBA8_UNORM, 3, 2, 1, false);
        closed.close();
        for (var input : List.of(closed,
                texture(GpuFormat.RGBA8_UNORM, 3, 2, 2, false),
                texture(GpuFormat.D32_FLOAT_S8_UINT, 3, 2, 1, false),
                texture(GpuFormat.RGBA32_FLOAT, 16384, 16384, 1, false))) {
            assertThrows(IllegalArgumentException.class,
                    () -> FullscreenCapture.planTextures(output, List.of(input), FullscreenCapture.MAX_BYTES));
        }
        assertThrows(IllegalArgumentException.class, () -> FullscreenCapture.planTextures(
                texture(GpuFormat.RGBA8_UNORM, 3, 2, 1, false), List.of(output), FullscreenCapture.MAX_BYTES));
    }

    @Test
    @SuppressWarnings("unchecked")
    void finishedPartialCaptureIsIncompleteAndKeepsSamplerSlots() throws Exception {
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/fornax-capture.json"), "{\"pass\":\"first\"}");
        FullscreenCapture.request();
        FullscreenCapture.beginFrame(game, Map.of());
        // Complete the CPU-side callback records without allocating a live GPU. The actual
        // manifest writer must distinguish unavailable input data from pending copies or failure.
        var activeField = FullscreenCapture.class.getDeclaredField("active");
        activeField.setAccessible(true);
        Object session = activeField.get(null);
        var passesField = session.getClass().getDeclaredField("passes");
        passesField.setAccessible(true);
        var capture = (FullscreenCapture.Capture) ((Map<?, ?>) passesField.get(session)).get("first");
        setCaptureField(capture, "started", true);
        setCaptureField(capture, "drawn", true);
        setCaptureField(capture, "expectedInputs", 3);
        var inputs = (List<Map<String, Object>>) captureField(capture, "inputs");
        inputs.add(capturedInput(0));
        var unavailable = FullscreenCapture.Capture.class.getDeclaredMethod("unavailableTexture",
                GpuTextureView.class, String.class, int.class, String.class, String.class);
        unavailable.setAccessible(true);
        unavailable.invoke(capture, texture(GpuFormat.RGBA8_UNORM, 3, 2, 1, false),
                "noise", 1, "linear", "Texture is not allocated with COPY_SRC");
        inputs.add(capturedInput(2));
        ((List<Map<String, Object>>) captureField(capture, "outputs")).add(Map.of("status", "captured"));
        capture.sampler(2, "nearest");
        FullscreenCapture.endFrame();
        try (var directories = Files.list(game.resolve("fornax-captures"))) {
            var manifest = JsonParser.parseString(Files.readString(
                    directories.findFirst().orElseThrow().resolve("manifest.json"))).getAsJsonObject();
            assertEquals("incomplete", manifest.get("status").getAsString());
            assertFalse(manifest.get("textureCaptureComplete").getAsBoolean());
            assertFalse(manifest.get("replayComplete").getAsBoolean());
            assertEquals(0, manifest.get("pendingCallbacks").getAsInt());
            var pass = manifest.getAsJsonArray("passes").get(0).getAsJsonObject();
            assertEquals("incomplete", pass.get("status").getAsString());
            var entries = pass.getAsJsonArray("inputs");
            assertEquals("unavailable", entries.get(1).getAsJsonObject().get("status").getAsString());
            assertEquals(0, entries.get(1).getAsJsonObject().get("capturedMipLevels").getAsInt());
            assertFalse(entries.get(1).getAsJsonObject().has("file"));
            assertEquals("linear", entries.get(1).getAsJsonObject().get("sampler").getAsString());
            assertEquals("nearest", entries.get(2).getAsJsonObject().get("sampler").getAsString());
        }
    }

    private static Map<String, Object> capturedInput(int index) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("index", index);
        entry.put("status", "captured");
        return entry;
    }

    private static Object captureField(FullscreenCapture.Capture capture, String name) throws Exception {
        var field = FullscreenCapture.Capture.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(capture);
    }

    private static void setCaptureField(FullscreenCapture.Capture capture, String name, Object value) throws Exception {
        var field = FullscreenCapture.Capture.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(capture, value);
    }

    private static GpuTextureView texture(GpuFormat format, int width, int height, int layers, boolean copyable) {
        var texture = new GpuTexture(copyable ? GpuTexture.USAGE_COPY_SRC : GpuTexture.USAGE_TEXTURE_BINDING,
                "test input", format, width, height, layers, 1) {
            private boolean closed;
            @Override public void close() { closed = true; }
            @Override public boolean isClosed() { return closed; }
        };
        return new GpuTextureView(texture, 0, 1) {
            private boolean closed;
            @Override public void close() { closed = true; }
            @Override public boolean isClosed() { return closed; }
        };
    }
}
