package dev.icehunter.fornax.pass.ssaa;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.AaMethod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.FornaxSettings;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import org.jspecify.annotations.Nullable;

/**
 * Owns the scaled-size off-screen RenderTarget the active {@link AaMethod} renders into --
 * generalized beyond SSAA to any render scale the method drives, per-dimension {@code scaleFactor}
 * running above 1.0 for SSAA (supersample), at exactly 1.0 for TAA (native, but still off-screen --
 * the temporal reconstruct needs a texture distinct from the display target), and below 1.0 for TAAU
 * (render below native, upscale later). The class name and the "SSAA" prefix on several members
 * predate this generalization and are kept as-is to limit blast radius; the javadoc here is the
 * source of truth for what the class actually does today.
 *
 * <p>Deliberately does NOT touch GameRenderer.mainRenderTarget directly -- that field is private
 * final in real Minecraft, so only GameRendererMixin itself (via its own @Shadow @Mutable field)
 * can legally read/write it. This class only manages the scaled target's lifecycle (and the
 * captured true-native window size, for callers that need it independent of whatever
 * mainRenderTarget currently points at); GameRendererMixin performs the actual swap using
 * ensureScaledTarget's return value.
 */
public final class SsaaManager {
    private static volatile float scaleFactor = 1.0f;
    private static volatile boolean frameActive = false;

    @Nullable
    private static RenderTarget scaledTarget;
    private static int scaledWidth;
    private static int scaledHeight;

    private static volatile int nativeWidth;
    private static volatile int nativeHeight;

    private SsaaManager() {
    }

    /**
     * True whenever the render scale is anything other than 1:1 -- above 1.0 for SSAA, below 1.0
     * for TAAU. Was {@code scaleFactor > 1.0f} (supersampling-only) before render-scale
     * generalization; {@link #needsOffscreenTarget()} is the separate question of whether the
     * active method needs an off-screen target at all (true even at scale 1.0, for TAA).
     */
    public static boolean isActive() {
        return scaleFactor != 1.0f;
    }

    /**
     * Whether the active {@link AaMethod} needs an off-screen render target this frame -- every
     * method except {@code OFF}, including {@code TAA} at scale 1.0: the temporal reconstruct
     * pass cannot read and write the same texture, so TAA still renders into a distinct off-screen
     * target even though its scale never differs from native. Distinct from {@link #isActive()},
     * which only reflects whether the scale itself is non-unity.
     */
    public static boolean needsOffscreenTarget() {
        return FornaxConfig.get().aaMethod.needsOffscreenTarget();
    }

    /**
     * Records this frame's true native window size (physical framebuffer pixels), captured by
     * {@code GameRendererMixin} at the HEAD of {@code renderLevel} before any off-screen swap and
     * before {@code WindowMixin}'s scaled-size override can apply -- so later readers (the graph
     * interpreter's output-basis target sizing) always see the real native size for this frame,
     * never whatever {@code mainRenderTarget} happens to be swapped to mid-frame.
     */
    public static void setNativeSize(int width, int height) {
        nativeWidth = width;
        nativeHeight = height;
    }

    /** This frame's true native window width, as captured by {@link #setNativeSize}. */
    public static int nativeWidth() {
        return nativeWidth;
    }

    /** This frame's true native window height, as captured by {@link #setNativeSize}. */
    public static int nativeHeight() {
        return nativeHeight;
    }

    /**
     * True only between GameRendererMixin's HEAD and RETURN injections into renderLevel while the
     * active method needs an off-screen target for this frame -- i.e. only while
     * GameRenderer.mainRenderTarget is actually swapped to the scaled target (gated on {@link
     * #needsOffscreenTarget()}, not {@link #isActive()}: TAA swaps in a same-size off-screen target
     * too). Distinct from isActive() (which only reflects whether the scale itself is non-unity):
     * WindowMixin's getWidth()/getHeight() override must key off this flag rather than isActive(),
     * since those methods are also queried by GUI/options-screen layout code that runs outside
     * renderLevel at native resolution.
     */
    public static boolean isFrameActive() {
        return frameActive;
    }

    public static void setFrameActive(boolean active) {
        frameActive = active;
    }

    public static float getScaleFactor() {
        return scaleFactor;
    }

    /** Test-only: bypasses the real preset enum to exercise the bookkeeping in isolation. */
    static void setScaleFactorForTesting(float factor) {
        scaleFactor = factor;
    }

    static void setScaleFactor(float factor) {
        scaleFactor = factor;
    }

    /**
     * Derives this frame's render scale from the active {@link AaMethod}: {@code SSAA} uses the
     * configured {@link SsaaPreset} factor (above 1.0), {@code TAAU} uses the configured {@link
     * dev.icehunter.fornax.config.TaauRatio}'s {@code perAxisScale()} (below 1.0), and {@code
     * TAA}/{@code OFF} both pin the scale to 1.0 -- TAA still gets an off-screen target (see {@link
     * #needsOffscreenTarget()}) but never a different render resolution. Must be public, not
     * package-private, since GameRendererMixin lives in a different package and calls this every
     * frame from HEAD of renderLevel.
     */
    public static void applyCurrentScale() {
        FornaxSettings settings = FornaxConfig.get();
        setScaleFactor(switch (settings.aaMethod) {
            case SSAA -> settings.ssaaPreset.linearScale();
            case TAAU, METALFX -> settings.taauRatio.perAxisScale();
            case TAA, OFF -> 1.0f;
        });
    }

    /**
     * Destroys and discards the cached scaled target, if one exists. Called whenever the active
     * method stops needing an off-screen target (i.e. {@code aaMethod} becomes {@code OFF}), so a
     * later re-activation always builds a fresh target instead of reusing one that sat idle -- a
     * stale cached target reused across an inactive period leaves its GPU resources invalid, even
     * though nothing here explicitly destroys them while inactive.
     */
    public static void deactivate() {
        if (scaledTarget != null) {
            // Same live-per-frame-resize crash class GBufferManager/ShadowMapManager/etc. already
            // guard against (see VulkanComputeBackend.waitForGpuIdleBeforeDestroy's own doc):
            // MetalFxUpscalePass reads this exact target as its low-res source every frame, so
            // destroying it mid-flight races that read.
            VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
            scaledTarget.destroyBuffers();
            scaledTarget = null;
            scaledWidth = 0;
            scaledHeight = 0;
        }
    }

    /**
     * Builds (or resizes, if the native resolution or scale changed) a scaled-size MainTarget and
     * returns it -- native size for TAA (scale 1.0), genuinely smaller for TAAU, genuinely larger
     * for SSAA. No-op/returns the existing instance if already correctly sized. Only call while
     * needsOffscreenTarget() is true -- callers (GameRendererMixin) are responsible for checking
     * that first.
     */
    public static RenderTarget ensureScaledTarget(int nativeWidth, int nativeHeight) {
        int targetWidth = Math.round(nativeWidth * scaleFactor);
        int targetHeight = Math.round(nativeHeight * scaleFactor);

        if (scaledTarget == null || scaledWidth != targetWidth || scaledHeight != targetHeight) {
            // Build the new target before destroying the old one: if MainTarget's constructor
            // throws (GPU OOM, invalid size), scaledTarget still points at a valid, non-destroyed
            // instance instead of a dangling one.
            RenderTarget next = new MainTarget(targetWidth, targetHeight);
            RenderTarget previous = scaledTarget;

            scaledTarget = next;
            scaledWidth = targetWidth;
            scaledHeight = targetHeight;

            if (previous != null) {
                // See deactivate()'s own comment -- the identical live-per-frame-resize hazard.
                VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
                previous.destroyBuffers();
            }

            FornaxMod.LOGGER.info("[SSAA] (Re)built scaled target at {}x{} (factor {})",
                    targetWidth, targetHeight, scaleFactor);
        }

        return scaledTarget;
    }
}
