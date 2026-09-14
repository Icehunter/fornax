package dev.icehunter.fornax.metalfx;

import dev.icehunter.fornax.pipeline.SkyReprojection;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native render-pass allocation and MetalFX encoding require a live GPU. Source contracts pin the
 * input routing, allocation lifecycle, and GLSL equations; device-free projection cases independently
 * check the UV/sign contract. These tests do not execute the shader or prove live image stability.
 */
class MetalFxSkyMotionPassTest {
    private static final Path JAVA = Path.of("src/main/java/dev/icehunter/fornax/metalfx/");
    private static final Path SHADER = Path.of(
            "src/main/resources/assets/fornax/shaders/post/metalfx_sky_motion.fsh");

    private static String shader() throws IOException {
        assertTrue(Files.exists(SHADER), "MetalFX must prepare missing sky motion before consuming gMotion");
        return Files.readString(SHADER);
    }

    @Test
    void preparesMotionBeforeTheInteropCopyWithoutOverwritingGeometry() throws IOException {
        String source = Files.readString(JAVA.resolve("MetalFxUpscalePass.java"));
        int prepare = source.indexOf("MetalFxSkyMotionPass.render(motionView, depthView, jitterNdc, inW, inH)");
        int copy = source.indexOf("VulkanMetalInterop.CmdRecorder copyIn");
        assertTrue(prepare >= 0 && prepare < copy, "sky motion preparation must precede Vulkan copy-in");
        assertTrue(source.contains("preparedMotionView.texture()"), "interop must consume prepared motion");
        assertTrue(source.contains("motionTex.vkImage(), VK13.VK_IMAGE_LAYOUT_GENERAL"));
        String glsl = shader();
        assertTrue(glsl.contains("texelFetch(u_Motion, pixel, 0).rg"));
        assertTrue(glsl.contains("if (depth > SKY_DEPTH_EPSILON)"));
        assertTrue(glsl.contains("fragMotion = texelFetch(u_Motion, pixel, 0).rg;\n        return;"),
                "every geometry texel must be passed through without filtering or sky arithmetic");
        assertFalse(glsl.contains("u_CameraDelta"), "infinite sky must not acquire finite-distance translation");
    }

    @Test
    void usesCurrentJitterFreeSkyMapAndAlignedJitterUniform() throws IOException {
        shader();
        String source = Files.readString(JAVA.resolve("MetalFxSkyMotionPass.java"));
        assertTrue(source.contains(".putMat4f(SkyReprojection.current())"));
        assertTrue(source.contains("20 * Float.BYTES"), "std140 mat4 plus padded jitter vec4 is twenty float lanes");
        assertTrue(source.contains("settings.rotate()"), "uniform storage must rotate across frames in flight");
        assertTrue(shader().contains("mat3(u_SkyReprojection) * vec3(jitterFreeUv * 2.0 - 1.0, 1.0)"));
        assertTrue(shader().contains("fragMotion = jitterFreeUv - previousUv;"), "motion is currentUV minus previousUV");
        String graph = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java"));
        assertTrue(graph.contains("SkyReprojection.commit(CameraJitter.currentUnjitteredProjection(), matrices.modelView())"));
    }

    @Test
    void allocatesClearedRg16fAndDoesNotDestroyPendingGpuResources() throws IOException {
        shader();
        String source = Files.readString(JAVA.resolve("MetalFxSkyMotionPass.java"));
        assertTrue(source.contains("GpuFormat.RG16_FLOAT"));
        assertTrue(source.contains("GpuTexture.USAGE_COPY_SRC"));
        assertTrue(source.contains("clearColorTexture(nextTexture, new Vector4f())"));
        assertTrue(source.contains("if (nextView != null) nextView.close();"));
        assertTrue(source.contains("if (nextTexture != null) nextTexture.close();"));
        int wait = source.indexOf("VulkanComputeBackend.waitForGpuIdleBeforeDestroy()");
        assertTrue(wait >= 0 && wait < source.indexOf("oldView.close()"));
        assertTrue(wait < source.indexOf("oldTexture.close()"));
        assertTrue(source.contains("width == requestedWidth && height == requestedHeight"));
    }

    @Test
    void rejectsBehindEyeOffscreenAndNonfiniteProjectionsWithFiniteOutOfBoundsMotion() throws IOException {
        String glsl = shader();
        assertTrue(glsl.contains("any(isnan(previousH))") && glsl.contains("any(isinf(previousH))"));
        assertTrue(glsl.contains("previousH.z <= 0.0"));
        assertTrue(glsl.contains("any(isnan(previousUv))") && glsl.contains("any(isinf(previousUv))"));
        assertTrue(glsl.contains("any(lessThan(previousUv, vec2(0.0)))"));
        assertTrue(glsl.contains("any(greaterThan(previousUv, vec2(1.0)))"));
        assertTrue(glsl.contains("fragMotion = texCoord + vec2(2.0);"));
        // Reprojection of this bounded sentinel is UV=-2 on both axes, outside every texture.
        Vector2f uv = new Vector2f(0.3f, 0.8f);
        Vector2f previous = MetalFxConventions.reprojectPreviousPixel(uv, new Vector2f(uv).add(2, 2),
                800, 600, false, new Vector2f());
        assertEquals(-1600, previous.x, 1e-3f);
        assertEquals(-1200, previous.y, 1e-3f);
    }

    @Test
    void cameraRotationProducesThePreviousSkyPixelWithMetalFxSigns() throws IOException {
        shader();
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 4f / 3f, 0.05f, 1000);
        float yaw = (float) Math.toRadians(5);
        Matrix3f map = SkyReprojection.homography(new Matrix4f(projection).rotateY(yaw), projection);
        Vector2f uv = new Vector2f(0.5f, 0.5f);
        Vector3f h = map.transform(new Vector3f(0, 0, 1));
        Vector2f previousUv = new Vector2f(h.x / h.z, h.y / h.z).mul(0.5f).add(0.5f, 0.5f);
        Vector2f motion = new Vector2f(uv).sub(previousUv);
        Vector2f previousPixel = MetalFxConventions.reprojectPreviousPixel(uv, motion, 800, 600, false, new Vector2f());
        // A centre ray rotated back by five degrees has x/z=tan(yaw), projected by focal length.
        float expectedX = (0.5f + 0.5f * projection.m00() * (float) Math.tan(yaw)) * 800;
        assertEquals(expectedX, previousPixel.x, 1e-3f);
        assertEquals(300, previousPixel.y, 1e-3f);
        assertTrue(Math.abs(previousPixel.x - 400) > 30,
                "the old zero-motion input misses this five-degree sky rotation by more than thirty pixels");
    }

    @Test
    void rasterJitterIsRemovedBeforeEvaluatingRotatedSkyMotion() throws IOException {
        String glsl = shader();
        assertTrue(glsl.contains("vec2 jitterFreeUv = texCoord - u_CurrentJitterNdc.xy * 0.5;"),
                "the homography consumes the jitter-free sample point, not its raster pixel centre");
        String source = Files.readString(JAVA.resolve("MetalFxSkyMotionPass.java"));
        assertTrue(source.contains(".putVec4(jitterNdc.x(), jitterNdc.y(), 0.0f, 0.0f)"));
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 4f / 3f, 0.05f, 1000);
        Matrix4f current = new Matrix4f(projection).rotateX((float) Math.toRadians(10))
                .rotateY((float) Math.toRadians(20));
        Matrix3f map = SkyReprojection.homography(current, projection);
        Vector2f rasterUv = new Vector2f(0.70f, 0.25f);
        // Quarter-pixel TAA offset at 800x600, expressed in UV units.
        Vector2f jitterUv = new Vector2f(0.25f / 800, -0.25f / 600);
        Vector2f freeUv = new Vector2f(rasterUv).sub(jitterUv);
        Vector3f direction = SkyReprojection.directionToScreen(current).invert()
                .transform(new Vector3f(freeUv.x * 2 - 1, freeUv.y * 2 - 1, 1));
        Vector3f previousClip = SkyReprojection.directionToScreen(projection).transform(direction);
        Vector2f previousUv = new Vector2f(previousClip.x / previousClip.z,
                previousClip.y / previousClip.z).mul(0.5f).add(0.5f, 0.5f);
        Vector3f mapped = map.transform(new Vector3f(freeUv.x * 2 - 1, freeUv.y * 2 - 1, 1));
        Vector2f prepared = new Vector2f(freeUv).sub(
                new Vector2f(mapped.x / mapped.z, mapped.y / mapped.z).mul(0.5f).add(0.5f, 0.5f));
        Vector2f geometry = new Vector2f(freeUv).sub(previousUv);
        assertEquals(geometry.x, prepared.x, 1e-6f);
        assertEquals(geometry.y, prepared.y, 1e-6f);
        Vector3f oldMapped = map.transform(new Vector3f(rasterUv.x * 2 - 1, rasterUv.y * 2 - 1, 1));
        Vector2f oldMotion = new Vector2f(rasterUv).sub(new Vector2f(oldMapped.x / oldMapped.z,
                oldMapped.y / oldMapped.z).mul(0.5f).add(0.5f, 0.5f));
        assertTrue(new Vector2f(oldMotion).sub(geometry).mul(800, 600).length() > 0.28f,
                "the old raster-basis evaluation misses this valid sky sample by over 0.28 pixels");
    }

    @Test
    void identityResetAndPureTranslationLeaveInfiniteSkyMotionZero() throws IOException {
        shader();
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 4f / 3f, 0.05f, 1000);
        for (Matrix3f map : new Matrix3f[] {new Matrix3f(),
                SkyReprojection.homography(new Matrix4f(projection).translate(4, 2, -3), projection)}) {
            Vector3f h = map.transform(new Vector3f(0.4f, -0.2f, 1));
            assertEquals(0.4f, h.x / h.z, 1e-6f);
            assertEquals(-0.2f, h.y / h.z, 1e-6f);
        }
    }
}
