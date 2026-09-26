package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure gate {@link PlayerMirrorTargets#shouldAllocateTargets}, and a source-text
 * contract that texture creation cites {@link GBufferManager}'s named format constants rather
 * than retyped {@code GpuFormat} literals. This is the same style {@code GBufferFormatLockTest}
 * already uses for things a headless test cannot exercise on a live GPU device.
 */
class PlayerMirrorTargetsTest {

    @Test
    void allocatesOnlyWhenTheGraphIsActiveAndTheSlotIsClaimed() {
        assertTrue(PlayerMirrorTargets.shouldAllocateTargets(true, true));
        assertFalse(PlayerMirrorTargets.shouldAllocateTargets(true, false), "slot not claimed");
        assertFalse(PlayerMirrorTargets.shouldAllocateTargets(false, true), "no active pack");
        assertFalse(PlayerMirrorTargets.shouldAllocateTargets(false, false));
    }

    @Test
    void textureCreationReusesGBufferManagersNamedFormatsRatherThanLiterals() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pipeline/PlayerMirrorTargets.java"));

        assertTrue(source.contains("GBufferManager.NORMAL_FORMAT"),
                "the mirror's normal lane must cite GBufferManager.NORMAL_FORMAT by reference, not a"
                        + " retyped GpuFormat literal: a mismatch here is an empty mirror image with"
                        + " no error anywhere");
        assertTrue(source.contains("GBufferManager.ALBEDO_FORMAT"),
                "the mirror's albedo lane must cite GBufferManager.ALBEDO_FORMAT");
        assertTrue(source.contains("GBufferManager.MATERIAL_FORMAT"),
                "the mirror's material lane must cite GBufferManager.MATERIAL_FORMAT");
        assertTrue(source.contains("GBufferManager.DEPTH_FORMAT"),
                "the mirror's depth target must cite GBufferManager.DEPTH_FORMAT, the same D32_FLOAT"
                        + " the main G-buffer and OpaqueDepth already share");
        assertTrue(source.contains("RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE"),
                "the mirror draws under the CAMERA's own projection, so its depth clear must be the"
                        + " main camera's reversed-Z far value, not ShadowMapManager's forward-Z"
                        + " substitution");

        assertFalse(source.contains("GpuFormat.RGBA16_SNORM") || source.contains("GpuFormat.RGBA8_UNORM")
                        || source.contains("GpuFormat.D32_FLOAT"),
                "no GpuFormat literal should appear in this class at all: every format must come"
                        + " from a GBufferManager constant, so the two can never drift silently");
    }

    /**
     * Every one of the four {@code createTexture} calls in this class must request {@code
     * USAGE_COPY_DST}, checked against {@link dev.icehunter.fornax.pass.water.WaterSurfaceManager}'s
     * identical usage-flag expression, the class this pattern is copied from. {@link
     * GBufferManager} is not used for comparison: its colour and depth textures never call {@code
     * CommandEncoder.clearColorTexture} or {@code clearColorAndDepthTextures} at all, since its
     * first writer clears every attachment through the render pass's load ops instead, so it never
     * needed the flag and proves nothing either way.
     *
     * <p>This is a source-text test rather than a live-GPU one because {@code
     * CommandEncoder.verifyColorTexture} and {@code verifyDepthTexture} (Blaze3D; both require
     * {@code USAGE_COPY_DST}) only run against a real device, inside a real {@code
     * clearColorTexture} or {@code clearColorAndDepthTextures} call, which this headless suite
     * cannot make: there is no fixture that exercises the Vulkan backend. A missing flag here would
     * surface only as {@code IllegalStateException: Color texture must have USAGE_COPY_DST} from
     * {@code PlayerMirrorTargets.ensureSize}, thrown regardless of whether water was ever in view,
     * since allocation keys on the pack's slot claim. This test cannot reach that crash directly; it
     * locks the one fact that causes it, so the next clearable target class copies the right four
     * textures.
     */
    @Test
    void everyClearableTextureRequestsUsageCopyDstMatchingWaterSurfaceManagersOwnPattern()
            throws IOException {
        String mirror = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pipeline/PlayerMirrorTargets.java"));
        String water = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pass/water/WaterSurfaceManager.java"));

        // Captures everything between the debug-name string and the trailing ", width, height, 1,
        // 1)" that every createTexture call in both classes ends with: flags and format constant
        // together. That is fine, since no format constant's name can contain "USAGE_COPY_DST".
        Pattern createTexture = Pattern.compile(
                "device\\.createTexture\\(\"[^\"]*\",\\s*(.*?),\\s*width,\\s*height,\\s*1,\\s*1\\)",
                Pattern.DOTALL);

        List<String> mirrorFlagExpressions = flagExpressionsOf(createTexture, mirror);
        assertEquals(4, mirrorFlagExpressions.size(),
                "expected exactly 4 createTexture calls in PlayerMirrorTargets (normal, albedo, "
                        + "material, depth)");
        for (String flags : mirrorFlagExpressions) {
            assertTrue(flags.contains("USAGE_COPY_DST"),
                    "createTexture flags '" + flags + "' must include USAGE_COPY_DST: Blaze3D's "
                            + "CommandEncoder.verifyColorTexture/verifyDepthTexture both require it "
                            + "before clearColorTexture/clearColorAndDepthTextures will run, and this "
                            + "class calls both (alloc-time here, and every frame from clear())");
        }

        List<String> waterFlagExpressions = flagExpressionsOf(createTexture, water);
        assertTrue(waterFlagExpressions.size() >= 2,
                "expected at least 2 createTexture calls in WaterSurfaceManager to compare against");
        for (String flags : waterFlagExpressions) {
            assertTrue(flags.contains("USAGE_COPY_DST"),
                    "WaterSurfaceManager's own createTexture flags '" + flags + "' must still "
                            + "include USAGE_COPY_DST: this test's whole premise is that this "
                            + "class already carries the flag PlayerMirrorTargets was missing");
        }
    }

    private static List<String> flagExpressionsOf(Pattern pattern, String source) {
        List<String> flags = new java.util.ArrayList<>();
        Matcher m = pattern.matcher(source);
        while (m.find()) {
            flags.add(m.group(1).replaceAll("\\s+", " ").trim());
        }
        return flags;
    }
}
