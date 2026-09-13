package dev.icehunter.fornax.pass.shadow;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.metalfx.rt.MetalRtShadowPass;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Owns the two render-resolution R8_UNORM targets the Metal ray-tracing sun-shadow trace copies
 * its output into: {@link #TARGET} ("rtSunVisibility", 0 = fully shadowed, 1 = fully lit, matching
 * the rasterized shadow map's own mask convention) and {@link #VALID_TARGET} ("rtSunValid", 1 = this
 * pixel was traced this frame, 0 = outside the exported voxel window or RT unavailable). Both are
 * pack-visible builtin input names, resolved read-only exactly like {@link ShadowMapManager#TARGET}
 * by {@code GraphValidator.checkInputRef} and {@code GraphInputResolver}: engine-owned, never a
 * pack-declared target, with no history slot (a pack reads only the current frame).
 *
 * <p>Static lifecycle, modeled directly on {@link ShadowMapManager}'s own shape: {@link #ensureSize}
 * (re)allocates both textures only when the requested size changes, and every engine-owned target is
 * cleared to zero immediately at allocation, since MoltenVK recycles garbage VRAM rather than
 * zero-filling it. Zero is the correct "off" content for both: {@code rtSunVisibility} = 0 reads as
 * fully shadowed, which only matters where {@code rtSunValid} = 0 says to ignore it entirely, and
 * {@code rtSunValid} = 0 is exactly the sentinel a consuming shader blends on. {@link #ensureSize} is
 * called unconditionally every frame the graph is active ({@code
 * SodiumWorldRendererOrchestrationMixin#fornax$ensureRtShadowResultTargets}), independent of the ray
 * tracing setting or platform support, so both names always resolve to an allocated target. A
 * resolve shader can declare either unconditionally with zero pack-side branching on whether ray
 * tracing is even available this session. {@link MetalRtShadowPass#run} copies the Metal trace's
 * two outputs into these two textures every frame the trace kernel dispatches, and explicitly
 * re-clears both back to this same zero sentinel on every frame or transition where it does not (no
 * instance structure yet, the pass going inactive, or a session-disabling failure: see that class's
 * own {@code clearRtShadowResultTargetsToInvalid}), so the alloc-time zero-fill here is only ever
 * the content either texture holds before the first such frame.
 *
 * <p>Sized to the live render resolution (unlike {@link ShadowMapManager}'s independently-configured
 * square resolution): same width/height basis as {@link dev.icehunter.fornax.pipeline.GBufferManager}
 * and {@link dev.icehunter.fornax.pass.water.WaterSurfaceManager}, since a screen-space trace result
 * is naturally one sample per rendered pixel.
 */
public final class RtShadowResult {
    /** Pack-visible builtin input name for {@link #getVisibilityView()}. */
    public static final String TARGET = "rtSunVisibility";

    /** Pack-visible builtin input name for {@link #getValidView()}. */
    public static final String VALID_TARGET = "rtSunValid";

    /** RGBA32F at the raster sun-map resolution: nearest forward depth (miss=1), finite
     * domain entry/exit depths, validity. Invalid (A=0) delegates the sample to raster. */
    public static final String DEPTH_TARGET = "rtSunDepth";
    private static GpuTexture depthTexture;
    private static GpuTextureView depthView;
    private static int sunResolution = -1;
    private static boolean sunDepthRequested;
    private static boolean legacyRequested;

    /** Declaration based: even a gated consumer must have a valid builtin descriptor. */
    public static void setRequestedInputs(java.util.stream.Stream<String> inputs) {
        var names = inputs.collect(java.util.stream.Collectors.toSet());
        sunDepthRequested = names.contains(DEPTH_TARGET);
        legacyRequested = names.contains(TARGET) || names.contains(VALID_TARGET);
    }
    public static boolean sunDepthRequested() { return sunDepthRequested; }
    public static boolean legacyRequested() { return legacyRequested; }
    public static GpuTexture getDepthTexture() { return depthTexture; }
    public static GpuTextureView getDepthView() { return depthView; }
    public static int sunResolution() { return sunResolution; }

    /** Allocated independently of hardware availability; no frame may sample uninitialized VRAM. */
    public static void ensureSunSize(int resolution) {
        if (depthTexture != null && sunResolution == resolution) return;
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) return;
        GpuTexture nextDepthTexture = null;
        GpuTextureView nextDepthView = null;
        try {
            nextDepthTexture = device.createTexture("Fornax RT Sun Depth",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA32_FLOAT, resolution, resolution, 1, 1);
            nextDepthView = device.createTextureView(nextDepthTexture);
            device.createCommandEncoder().clearColorTexture(nextDepthTexture, new Vector4f(0.0f, 0.0f, 0.0f, 0.0f));
        } catch (RuntimeException e) {
            if (nextDepthView != null) nextDepthView.close();
            if (nextDepthTexture != null) nextDepthTexture.close();
            throw e;
        }
        if (depthTexture != null) VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
        if (depthView != null) depthView.close();
        if (depthTexture != null) depthTexture.close();
        depthTexture = nextDepthTexture;
        depthView = nextDepthView;
        sunResolution = resolution;
    }

    /** True for either pack-visible name resolving to one of this class's two engine-owned
     * targets: every site that resolves, validates, or classifies a reference to them should go
     * through this, not a direct {@code .equals(TARGET)}/{@code .equals(VALID_TARGET)} check,
     * mirroring {@link ShadowMapManager#isShadowMapRef}. */
    public static boolean isRtShadowRef(String ref) {
        return ref.equals(TerrainShadowResult.TARGET) || isLegacyRtShadowRef(ref);
    }

    public static boolean isLegacyRtShadowRef(String ref) {
        return ref.equals(TARGET) || ref.equals(VALID_TARGET) || ref.equals(DEPTH_TARGET);
    }

    @Nullable
    private static volatile GpuTexture visibilityTexture;
    @Nullable
    private static volatile GpuTextureView visibilityView;
    @Nullable
    private static volatile GpuTexture validTexture;
    @Nullable
    private static volatile GpuTextureView validView;
    private static int width = -1;
    private static int height = -1;

    private RtShadowResult() {
    }

    /**
     * Ensures a {@code width x height} pair of R8_UNORM color texture+views is installed as the
     * current instance, (re)building both if the requested size differs from whatever is currently
     * allocated (or nothing is allocated yet). Safe to call every frame; a no-op once the requested
     * size matches the current instance.
     */
    public static void ensureSize(int width, int height) {
        if (visibilityTexture != null && RtShadowResult.width == width && RtShadowResult.height == height) {
            return;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            FornaxMod.LOGGER.warn("[RtShadowResult] Skipping (re)build: no GPU device available");
            return;
        }

        // USAGE_RENDER_ATTACHMENT is required by CommandEncoder.clearColorTexture (verified against
        // the decompiled Blaze3D CommandEncoder.verifyColorTexture, which throws
        // IllegalStateException without it) even though nothing ever renders into either texture
        // through a render pass: both are written only by a Vulkan copy from the Metal trace
        // output. USAGE_TEXTURE_BINDING is required to sample either as a graph builtin.
        // USAGE_COPY_DST is required both by that alloc-time clear and by the per-frame copy-back
        // that writes the traced result into these textures.
        GpuTexture nextVisibilityTexture = null;
        GpuTextureView nextVisibilityView = null;
        GpuTexture nextValidTexture = null;
        GpuTextureView nextValidView = null;
        try {
            nextVisibilityTexture = device.createTexture("Fornax RT Sun Visibility",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                            | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.R8_UNORM, width, height, 1, 1);
            nextVisibilityView = device.createTextureView(nextVisibilityTexture);

            nextValidTexture = device.createTexture("Fornax RT Sun Valid",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                            | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.R8_UNORM, width, height, 1, 1);
            nextValidView = device.createTextureView(nextValidTexture);

            // MoltenVK garbage-VRAM law: clear both to literal zero at allocation. Zero is the
            // correct "off" content for both lanes: see this class's own javadoc.
            device.createCommandEncoder().clearColorTexture(nextVisibilityTexture, new Vector4f(0.0f, 0.0f, 0.0f, 0.0f));
            device.createCommandEncoder().clearColorTexture(nextValidTexture, new Vector4f(0.0f, 0.0f, 0.0f, 0.0f));
        } catch (RuntimeException e) {
            if (nextValidView != null) nextValidView.close();
            if (nextValidTexture != null) nextValidTexture.close();
            if (nextVisibilityView != null) nextVisibilityView.close();
            if (nextVisibilityTexture != null) nextVisibilityTexture.close();
            throw e;
        }

        GpuTexture oldVisibilityTexture = visibilityTexture;
        GpuTextureView oldVisibilityView = visibilityView;
        GpuTexture oldValidTexture = validTexture;
        GpuTextureView oldValidView = validView;

        visibilityTexture = nextVisibilityTexture;
        visibilityView = nextVisibilityView;
        validTexture = nextValidTexture;
        validView = nextValidView;
        RtShadowResult.width = width;
        RtShadowResult.height = height;

        if (oldVisibilityView != null || oldVisibilityTexture != null || oldValidView != null || oldValidTexture != null) {
            // Live per-frame resize path (window resize / SSAA render-scale change), same crash-class
            // hazard as ShadowMapManager.ensureSize/WaterSurfaceManager.ensureSize on the SAME live
            // instance: see VulkanComputeBackend.waitForGpuIdleBeforeDestroy's own doc for the two
            // live MoltenVK crashes this guards against.
            VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
        }
        if (oldVisibilityView != null) {
            oldVisibilityView.close();
        }
        if (oldVisibilityTexture != null) {
            oldVisibilityTexture.close();
        }
        if (oldValidView != null) {
            oldValidView.close();
        }
        if (oldValidTexture != null) {
            oldValidTexture.close();
        }

        FornaxMod.LOGGER.info("[RtShadowResult] (Re)built at {}x{}", width, height);
    }

    @Nullable
    public static GpuTextureView getVisibilityView() {
        return visibilityView;
    }

    @Nullable
    public static GpuTexture getVisibilityTexture() {
        return visibilityTexture;
    }

    @Nullable
    public static GpuTextureView getValidView() {
        return validView;
    }

    @Nullable
    public static GpuTexture getValidTexture() {
        return validTexture;
    }

    /** Releases the current instance, if any, and resets to unallocated. */
    public static void close() {
        if (depthView != null) depthView.close();
        if (depthTexture != null) depthTexture.close();
        depthView = null;
        depthTexture = null;
        sunResolution = -1;
        sunDepthRequested = false;
        legacyRequested = false;
        GpuTextureView currentVisibilityView = visibilityView;
        GpuTexture currentVisibilityTexture = visibilityTexture;
        GpuTextureView currentValidView = validView;
        GpuTexture currentValidTexture = validTexture;
        visibilityView = null;
        visibilityTexture = null;
        validView = null;
        validTexture = null;
        width = -1;
        height = -1;

        if (currentVisibilityView != null) {
            currentVisibilityView.close();
        }
        if (currentVisibilityTexture != null) {
            currentVisibilityTexture.close();
        }
        if (currentValidView != null) {
            currentValidView.close();
        }
        if (currentValidTexture != null) {
            currentValidTexture.close();
        }
    }
}
