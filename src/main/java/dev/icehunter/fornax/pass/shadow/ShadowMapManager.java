package dev.icehunter.fornax.pass.shadow;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import org.jspecify.annotations.Nullable;

/**
 * Owns the engine's sun/moon shadow-map depth target: a single D32_FLOAT texture+view, square,
 * sized to a pack-configured resolution -- pack-visible as the builtin input name {@link #TARGET}
 * ("sunShadowMap"), resolved read-only exactly like {@code sceneHistory} by {@code
 * GraphValidator.checkInputRef} and {@code GraphInputResolver}. A later pass renders depth into
 * {@link #getView()} from {@link dev.icehunter.fornax.pass.shadow.ShadowCamera}'s light matrices; a
 * pack's resolve shader then samples it via {@code sunShadowMap}.
 *
 * <p>Deliberately NOT a {@code TargetRegistry} target, for three independent reasons: {@code
 * TargetRegistry}'s {@code TargetFormat} enum carries only color formats ({@code
 * TargetRegistry.gpuFormat}: rgba8/rgba16_snorm/rgba16f/rg16f/r8/r32f, no depth format); its sizing
 * is basis-derived ({@code round(renderSize * scale)} against render/output resolution), never a
 * fixed, independently-configured resolution like a shadow map's; and its allocation-time {@code
 * clear()} runs a color clear-only render pass (a {@code Vector4f} clear color against a
 * non-depth attachment), which is not legal against a depth attachment -- a depth texture is
 * cleared via {@code CommandEncoder.clearDepthTexture}, a completely different call. A depth,
 * fixed-size, engine-timed target does not fit that abstraction, so it gets its own
 * static-lifecycle manager instead, modeled on {@link dev.icehunter.fornax.pipeline.GBufferManager}
 * (same static-instance shape, same D32_FLOAT usage-flag set for its creation call).
 *
 * <p>Static lifecycle (module-level singleton, like GBufferManager): {@link #ensureSize} is called
 * with the pack's configured shadow resolution and only reallocates when that resolution actually
 * changes; freshly allocated VRAM is cleared immediately at allocation -- MoltenVK recycles garbage
 * VRAM rather than zero-filling it, the same format-level guarantee every other engine-managed
 * texture in this codebase already follows (see {@code TargetRegistry}'s class javadoc) -- and
 * {@link #clear} is called once per frame before the shadow draw pass re-rasterizes.
 *
 * <p>Also owns a small {@link #getDummyColorView() dummy color attachment}, same resolution as the
 * depth target, RGBA8_UNORM. This exists purely to satisfy {@code
 * com.mojang.blaze3d.systems.RenderPass#setPipeline}'s hard invariant that a render pass's color
 * attachment count equal its pipeline's color-target-state count: decompiling {@code
 * RenderPipeline.Builder.build()} (game jar) shows it silently substitutes a single {@code
 * ColorTargetState.DEFAULT} (RGBA8_UNORM) whenever zero {@code withColorTargetState} calls were
 * made -- there is no Builder API to force a genuinely empty color-target-state list, so the shadow
 * terrain pipeline always reports count 1, never 0. Decompiling {@code
 * CommandEncoder.createRenderPass(RenderPassDescriptor)} additionally shows it unconditionally
 * dereferences {@code colorAttachments.getFirst().textureView()} whenever the list is non-empty (no
 * null-guard when Java assertions are disabled, an {@code AssertionError} when they're enabled) --
 * so a {@code withUnusedColorAttachment()} placeholder is not a legal substitute either; the one
 * attachment must be a real, non-null texture view. Combined, a true zero/zero depth-only pass is
 * not achievable against this Blaze3D version, so this one real, unread dummy color attachment is
 * the sanctioned way to keep the count matched. Nothing ever samples or copies it, so it carries no
 * usage flags beyond {@code USAGE_RENDER_ATTACHMENT} and, unlike the depth texture, is not cleared
 * at allocation (matching {@code GBufferManager}'s own color attachments, which are also never
 * pre-cleared -- only depth gets that treatment in this codebase, and only because a stale depth
 * value is otherwise sampled by a later pass, which does not apply here).
 */
public final class ShadowMapManager {
    /**
     * Pack-visible builtin input name. {@code GraphValidator}/{@code GraphInputResolver} resolve
     * this exactly like {@code sceneHistory} -- read-only, engine-written -- except it has no
     * history slot: it is a single current-frame depth target the engine overwrites every frame,
     * so {@code "sunShadowMap.history"} is rejected rather than required.
     */
    public static final String TARGET = "sunShadowMap";

    /**
     * Second pack-visible name for the SAME depth target as {@link #TARGET} -- resolves to the
     * identical texture/view, differing only in which sampler {@code FullscreenPassRunner} binds it
     * with (see {@code FullscreenPassRunner#samplerKindFor}): {@link #TARGET} gets the hardware
     * comparison sampler {@code sunVisibility()}'s PCF path needs; {@link #RAW_TARGET} gets a plain
     * {@code sampler2D} for raw {@code texelFetch} reads (the shadow-wedge investigation's {@code
     * SHADOW_QUERY_3} and the full-screen {@code SHADOW_MAP_VIEW} debug view both need the actual
     * stored depth, which a {@code sampler2DShadow} comparison sampler can only ever return
     * pass/fail for, never the value itself).
     *
     * <p>Exists because a pass binding {@link #TARGET} twice under the same name previously got the
     * comparison sampler on BOTH bindings -- {@code FullscreenPassRunner} keyed the branch on the
     * input's NAME, so one binding was silently read non-Dref through a hardware comparison sampler.
     * That was mistaken for an invalid duplicate descriptor and the second binding was deleted
     * outright, which is the wrong fix: the pack legitimately needs two DIFFERENT views of one
     * texture, not one view bound twice. Two names make that intent explicit at the graph.toml call
     * site with no schema change and no risk of the sampler choice silently depending on bind order.
     */
    public static final String RAW_TARGET = "sunShadowMapRaw";

    /**
     * True for either pack-visible name resolving to this engine-owned depth target. {@link #TARGET}
     * and {@link #RAW_TARGET} share resolution, lifecycle, and history-slot rules (see {@link
     * #TARGET}'s own doc) -- every site that resolves, validates, or classifies a shadow-map input
     * reference (rather than picking its sampler) should go through this, not a direct {@code
     * .equals(TARGET)} check, or the raw alias will fail to resolve/validate even though it is a
     * legal input.
     */
    public static boolean isShadowMapRef(String ref) {
        return ref.equals(TARGET) || ref.equals(RAW_TARGET);
    }

    @Nullable
    private static volatile GpuTexture texture;
    @Nullable
    private static volatile GpuTextureView view;
    @Nullable
    private static volatile GpuTexture dummyColorTexture;
    @Nullable
    private static volatile GpuTextureView dummyColorView;
    private static int resolution = -1;

    private ShadowMapManager() {
    }

    /**
     * Ensures a {@code resolution x resolution} D32_FLOAT depth texture+view is installed as the
     * current instance, (re)building it if {@code resolution} differs from whatever is currently
     * allocated (or nothing is allocated yet). Safe to call every frame; a no-op once the
     * requested resolution matches the current instance.
     */
    public static void ensureSize(int resolution) {
        if (texture != null && ShadowMapManager.resolution == resolution) {
            return;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            FornaxMod.LOGGER.warn("[ShadowMap] Skipping (re)build: no GPU device available");
            return;
        }

        // Same usage-flag set and format as GBufferManager's own depth texture (GBufferManager.java,
        // the "Sodium GBuffer Depth" creation block): USAGE_COPY_DST is required by
        // CommandEncoder.clearDepthTexture (below and every per-frame clear() call); USAGE_COPY_SRC
        // and USAGE_TEXTURE_BINDING mirror that same precedent for a depth attachment that must also
        // be sampled by a later resolve pass.
        // Hoisted so a failure partway through this sequence doesn't orphan whatever already
        // succeeded, with no reference left anywhere to close it.
        GpuTexture nextTexture = null;
        GpuTextureView nextView = null;
        GpuTexture nextDummyColorTexture = null;
        GpuTextureView nextDummyColorView = null;
        try {
            nextTexture = device.createTexture("Fornax Sun Shadow Map",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                            | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.D32_FLOAT, resolution, resolution, 1, 1);
            nextView = device.createTextureView(nextTexture);

            // MoltenVK garbage-VRAM law: clear at allocation rather than relying on the first shadow
            // draw pass to fully cover every texel before anything ever samples it. This shadow map
            // is forward-Z [0,1] (ShadowCamera uses setOrtho(..., zZeroToOne=true), far = 1.0),
            // unlike the main camera's reversed-Z depth -- so empty/far must clear to literal 1.0f,
            // NOT RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE (that constant is 0.0, the reversed-Z far
            // value, which would make every un-rasterized texel read as an occluder at the light
            // itself).
            device.createCommandEncoder().clearDepthTexture(nextTexture, 1.0f);

            // See this class's javadoc: a real, unread RGBA8_UNORM color attachment matching the
            // shadow pipeline's Blaze3D-forced single ColorTargetState.DEFAULT, required only to
            // keep RenderPass.setPipeline's attachment-count invariant satisfied. Same resolution as
            // depth -- CommandEncoder.createRenderPass validates the render area against
            // colorAttachments[0]'s own dimensions whenever the list is non-empty, not the depth
            // attachment's, so it must be at least as large as the shadow render area. Not cleared
            // at allocation: nothing ever reads it, unlike the depth target a later pass samples.
            nextDummyColorTexture = device.createTexture("Fornax Shadow Dummy Color",
                    GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.RGBA8_UNORM, resolution, resolution, 1, 1);
            nextDummyColorView = device.createTextureView(nextDummyColorTexture);
        } catch (RuntimeException e) {
            if (nextDummyColorView != null) nextDummyColorView.close();
            if (nextDummyColorTexture != null) nextDummyColorTexture.close();
            if (nextView != null) nextView.close();
            if (nextTexture != null) nextTexture.close();
            throw e;
        }

        GpuTexture oldTexture = texture;
        GpuTextureView oldView = view;
        GpuTexture oldDummyColorTexture = dummyColorTexture;
        GpuTextureView oldDummyColorView = dummyColorView;
        texture = nextTexture;
        view = nextView;
        dummyColorTexture = nextDummyColorTexture;
        dummyColorView = nextDummyColorView;
        ShadowMapManager.resolution = resolution;

        if (oldView != null || oldTexture != null || oldDummyColorView != null || oldDummyColorTexture != null) {
            // Live per-frame resize path (SHADOW_RESOLUTION change, or the shadows-off 64x64
            // fallback), reached every frame this pack is active from a mixin HEAD inject
            // (SodiumWorldRendererOrchestrationMixin#fornax$renderShadowPass) on the SAME live
            // instance -- identical crash-class hazard to OpaqueDepth.ensureSize()/
            // TargetRegistry.reconcile() (see VulkanComputeBackend.waitForGpuIdleBeforeDestroy's own
            // doc for the two live MoltenVK crashes this guards against). GraphRunner.closeCurrent()'s
            // own wait-idle does not cover this live-resize call site; it only covers close() during
            // pack teardown.
            VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
        }
        if (oldView != null) {
            oldView.close();
        }
        if (oldTexture != null) {
            oldTexture.close();
        }
        if (oldDummyColorView != null) {
            oldDummyColorView.close();
        }
        if (oldDummyColorTexture != null) {
            oldDummyColorTexture.close();
        }

        FornaxMod.LOGGER.info("[ShadowMap] (Re)built at {}x{}", resolution, resolution);
    }

    /**
     * Per-frame depth clear before the shadow draw pass re-rasterizes this frame's geometry. Clears
     * to literal {@code 1.0f} -- this shadow map is forward-Z [0,1] (far = 1.0), unlike the main
     * camera's reversed-Z depth, so do NOT use {@code RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE} (0.0,
     * the reversed-Z far value) here.
     */
    public static void clear() {
        GpuTexture current = texture;
        if (current == null) {
            return;
        }
        RenderSystem.getDevice().createCommandEncoder()
                .clearDepthTexture(current, 1.0f);
    }

    @Nullable
    public static GpuTextureView getView() {
        return view;
    }

    @Nullable
    public static GpuTexture getTexture() {
        return texture;
    }

    /**
     * The dummy RGBA8_UNORM color attachment described in this class's javadoc -- same resolution as
     * {@link #getView()}, allocated and released alongside it. Never sampled or copied; exists only
     * to give the shadow terrain render pass a real, non-null color attachment matching its
     * pipeline's Blaze3D-forced single {@code ColorTargetState}.
     */
    @Nullable
    public static GpuTextureView getDummyColorView() {
        return dummyColorView;
    }

    /** Releases the current instance, if any, and resets to unallocated. */
    public static void close() {
        GpuTextureView currentView = view;
        GpuTexture currentTexture = texture;
        GpuTextureView currentDummyColorView = dummyColorView;
        GpuTexture currentDummyColorTexture = dummyColorTexture;
        view = null;
        texture = null;
        dummyColorView = null;
        dummyColorTexture = null;
        resolution = -1;

        if (currentView != null) {
            currentView.close();
        }
        if (currentTexture != null) {
            currentTexture.close();
        }
        if (currentDummyColorView != null) {
            currentDummyColorView.close();
        }
        if (currentDummyColorTexture != null) {
            currentDummyColorTexture.close();
        }
    }
}
