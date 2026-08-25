package dev.icehunter.fornax.pass.water;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Owns the engine's water-surface pre-pass targets: one RGBA16_SNORM color texture+view
 * ({@code waterNormal}: xyz = wave world-normal, a = water-present flag) and one D32_FLOAT depth
 * texture+view ({@code waterDepth}), both sized to the live RENDER resolution (unlike {@code
 * ShadowMapManager}'s independently-configured square resolution -- this target follows the same
 * width/height basis as {@link dev.icehunter.fornax.pipeline.GBufferManager}'s own G-buffer). A
 * {@code WATER_PREPASS}-identity terrain draw ({@code SodiumWorldRendererOrchestrationMixin
 * #fornax$renderWaterPrepass}) rasterizes water surface geometry into these targets during the
 * opaque stage; later graph passes (Water Round Task 2+) sample them as {@code builtin.waterNormal}/
 * {@code builtin.waterDepth}.
 *
 * <p>Deliberately NOT a {@code TargetRegistry} target, for the same reasons documented on {@link
 * dev.icehunter.fornax.pass.shadow.ShadowMapManager}: {@code TargetRegistry}'s {@code TargetFormat}
 * enum carries no depth format, and its allocation-time {@code clear()} runs a color-clear-only
 * render pass, illegal against a depth attachment. Static-lifecycle singleton, modeled directly on
 * {@code ShadowMapManager}'s own shape (paired color+depth textures, {@code ensureSize}/{@code
 * clear}/{@code close}).
 *
 * <p>Unlike {@code ShadowMapManager}'s dummy color attachment (never sampled, never cleared per
 * frame -- nothing ever reads it), {@code waterNormal} IS the real, sampled output of the pre-pass
 * and MUST be re-cleared to transparent zero every frame, not just at allocation: the pre-pass
 * shader {@code discard}s every non-water translucent fragment (glass, leaves), so any screen pixel
 * with no water this frame is never written by the draw at all. Without a per-frame clear, such a
 * pixel would keep reading last frame's stale {@code a >= 0.5} "water here" flag (or worse, a stale
 * water pixel from a camera angle that no longer has water there) forever. {@code waterDepth} needs
 * the same per-frame re-clear for the same reason (a analogous, though less visibly severe, staleness
 * hazard for whatever later reads it). Both live in the same {@link #clear()} call, mirroring {@code
 * ShadowMapManager#clear()}'s own per-frame (not just per-alloc) depth clear.
 *
 * <p>MoltenVK clear-at-alloc: newly (re)allocated VRAM is cleared immediately in {@link #ensureSize},
 * exactly like every other engine-owned target in this codebase -- never relying on the allocator
 * zeroing VRAM. {@code waterDepth} inherits the MAIN camera's reversed-Z convention (far = {@code
 * 0.0}), NOT {@code ShadowMapManager}'s forward-Z ortho substitution: {@code
 * ShaderChunkRendererDeferredPipelineMixin} deliberately leaves {@code fornax$shadowDepthStencilState}
 * untouched for {@code WATER_PREPASS}, so it keeps {@code DepthStencilState.DEFAULT} (reversed-Z
 * {@code GREATER_THAN_OR_EQUAL}), correct for a main-camera water surface.
 */
public final class WaterSurfaceManager {
    /**
     * Pack-visible builtin input name for {@link #getNormalView()} (xyz = wave world-normal, a =
     * water-present flag), resolved read-only by {@code GraphValidator.BUILTINS}/{@code
     * GraphInputResolver} exactly like {@code OpaqueDepth.NAME}/{@code ShadowMapManager.TARGET} --
     * except, unlike {@code OpaqueDepth.NAME} (captured mid-{@code GraphRunner.finish()}, so only
     * fresh for the TRANSLUCENT terrain sub-draw), this target is written during the OPAQUE stage
     * HEAD by {@code fornax$renderWaterPrepass} -- BEFORE Sodium's own SOLID/CUTOUT draws even start
     * -- so it is already final-for-frame for every geometry sub-draw AND every fullscreen pass, with
     * no {@code PassType}-based restriction needed.
     */
    public static final String NORMAL_NAME = "builtin.waterNormal";

    /** Pack-visible builtin input name for {@link #getDepthView()} -- see {@link #NORMAL_NAME}'s own
     * doc for the freshness argument, which applies identically here. */
    public static final String DEPTH_NAME = "builtin.waterDepth";

    @Nullable
    private static volatile GpuTexture normalTexture;
    @Nullable
    private static volatile GpuTextureView normalView;
    @Nullable
    private static volatile GpuTexture depthTexture;
    @Nullable
    private static volatile GpuTextureView depthView;
    private static int width = -1;
    private static int height = -1;

    private WaterSurfaceManager() {
    }

    /**
     * The water pre-pass is shared infrastructure, not an opaque-SSR implementation detail.
     * Fullscreen consumers such as the resolve, cloud composite, underwater refraction and tonemap
     * can require {@link #DEPTH_NAME} even when opaque screen-space reflections are disabled.
     */
    public static boolean shouldRenderPrepass(boolean graphActive, int waterMode) {
        return graphActive && waterMode > 1;
    }

    /**
     * Ensures a {@code width x height} RGBA16_SNORM color texture+view ({@code waterNormal}) and a
     * matching D32_FLOAT depth texture+view ({@code waterDepth}) are installed as the current
     * instance, (re)building both if the requested size differs from whatever is currently allocated
     * (or nothing is allocated yet). Safe to call every frame; a no-op once the requested size
     * matches the current instance.
     */
    public static void ensureSize(int width, int height) {
        if (normalTexture != null && WaterSurfaceManager.width == width && WaterSurfaceManager.height == height) {
            return;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            FornaxMod.LOGGER.warn("[WaterSurface] Skipping (re)build: no GPU device available");
            return;
        }

        // Same usage-flag set as GBufferManager's own color/depth textures: USAGE_COPY_SRC so a
        // later graph pass can sample or copy this as a regular input, USAGE_COPY_DST required by
        // clearColorTexture/clearDepthTexture (both the alloc-time clear below and every per-frame
        // clear() call).
        // Hoisted so a failure partway through this sequence doesn't orphan whatever already
        // succeeded, with no reference left anywhere to close it.
        GpuTexture nextNormalTexture = null;
        GpuTextureView nextNormalView = null;
        GpuTexture nextDepthTexture = null;
        GpuTextureView nextDepthView = null;
        try {
            nextNormalTexture = device.createTexture("Fornax Water Normal",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                            | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA16_SNORM, width, height, 1, 1);
            nextNormalView = device.createTextureView(nextNormalTexture);

            nextDepthTexture = device.createTexture("Fornax Water Depth",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                            | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.D32_FLOAT, width, height, 1, 1);
            nextDepthView = device.createTextureView(nextDepthTexture);

            // MoltenVK clear-at-alloc law: color -> transparent zero (a=0 == "no water" flag,
            // matching the per-frame clear() semantics the pre-pass shader's discard relies on),
            // depth -> the MAIN camera's reversed-Z far value 0.0 (see this class's own javadoc for
            // why this differs from ShadowMapManager's forward-Z 1.0f).
            device.createCommandEncoder().clearColorAndDepthTextures(
                    nextNormalTexture, new Vector4f(0.0f, 0.0f, 0.0f, 0.0f),
                    nextDepthTexture, RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);
        } catch (RuntimeException e) {
            if (nextDepthView != null) nextDepthView.close();
            if (nextDepthTexture != null) nextDepthTexture.close();
            if (nextNormalView != null) nextNormalView.close();
            if (nextNormalTexture != null) nextNormalTexture.close();
            throw e;
        }

        GpuTexture oldNormalTexture = normalTexture;
        GpuTextureView oldNormalView = normalView;
        GpuTexture oldDepthTexture = depthTexture;
        GpuTextureView oldDepthView = depthView;

        normalTexture = nextNormalTexture;
        normalView = nextNormalView;
        depthTexture = nextDepthTexture;
        depthView = nextDepthView;
        WaterSurfaceManager.width = width;
        WaterSurfaceManager.height = height;

        if (oldNormalView != null || oldNormalTexture != null || oldDepthView != null || oldDepthTexture != null) {
            // Live per-frame resize path (window resize / SSAA render-scale change), reached every
            // frame the deferred-water gate is open from a mixin HEAD inject
            // (SodiumWorldRendererOrchestrationMixin#fornax$renderWaterPrepass) on the SAME live
            // instance -- identical crash-class hazard to OpaqueDepth.ensureSize()/
            // TargetRegistry.reconcile() (see VulkanComputeBackend.waitForGpuIdleBeforeDestroy's own
            // doc for the two live MoltenVK crashes this guards against). GraphRunner.closeCurrent()'s
            // own wait-idle covers this class's close() below (called from closeCurrent() itself), but
            // NOT this per-frame rebuild path, so it needs its own guard here too.
            VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
        }
        if (oldNormalView != null) {
            oldNormalView.close();
        }
        if (oldNormalTexture != null) {
            oldNormalTexture.close();
        }
        if (oldDepthView != null) {
            oldDepthView.close();
        }
        if (oldDepthTexture != null) {
            oldDepthTexture.close();
        }

        FornaxMod.LOGGER.info("[WaterSurface] (Re)built at {}x{}", width, height);
    }

    /**
     * Per-frame clear before the water pre-pass draw re-rasterizes this frame's water geometry --
     * see this class's own javadoc for why BOTH targets need a real per-frame clear (not just the
     * alloc-time one), unlike {@code ShadowMapManager}'s never-sampled dummy color attachment.
     */
    public static void clear() {
        GpuTexture currentNormal = normalTexture;
        GpuTexture currentDepth = depthTexture;
        if (currentNormal == null || currentDepth == null) {
            return;
        }
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                currentNormal, new Vector4f(0.0f, 0.0f, 0.0f, 0.0f),
                currentDepth, RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);
    }

    @Nullable
    public static GpuTextureView getNormalView() {
        return normalView;
    }

    @Nullable
    public static GpuTextureView getDepthView() {
        return depthView;
    }

    /** Texture-level counterpart to {@link #getNormalView()}, for a copy-shaped consumer (mirrors
     * {@code OpaqueDepth.getTexture()}/{@code ShadowMapManager.getTexture()}) -- no current caller
     * needs it, but {@code GraphInputResolver.resolveBuiltinTexture} resolves every other engine-owned
     * builtin symmetrically with its view, so this keeps the two resolution paths in lockstep. */
    @Nullable
    public static GpuTexture getNormalTexture() {
        return normalTexture;
    }

    /** Texture-level counterpart to {@link #getDepthView()} -- see {@link #getNormalTexture()}'s own doc. */
    @Nullable
    public static GpuTexture getDepthTexture() {
        return depthTexture;
    }

    public static int getWidth() {
        return width;
    }

    public static int getHeight() {
        return height;
    }

    /**
     * Releases the current instance, if any, and resets to unallocated. Document-safe: no wait-idle
     * here by design -- this class's only caller (GraphRunner.closeCurrent()) already runs
     * VulkanComputeBackend.waitForGpuIdleBeforeDestroy() once at the top of that method, before this
     * or any other GPU resource it owns is freed -- see that method's own doc.
     */
    public static void close() {
        GpuTextureView currentNormalView = normalView;
        GpuTexture currentNormalTexture = normalTexture;
        GpuTextureView currentDepthView = depthView;
        GpuTexture currentDepthTexture = depthTexture;
        normalView = null;
        normalTexture = null;
        depthView = null;
        depthTexture = null;
        width = -1;
        height = -1;

        if (currentNormalView != null) {
            currentNormalView.close();
        }
        if (currentNormalTexture != null) {
            currentNormalTexture.close();
        }
        if (currentDepthView != null) {
            currentDepthView.close();
        }
        if (currentDepthTexture != null) {
            currentDepthTexture.close();
        }
    }
}
