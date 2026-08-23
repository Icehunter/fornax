package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import org.jspecify.annotations.Nullable;

/**
 * Engine-owned, sampleable D32 copy of the opaque G-buffer depth, captured once per frame at the
 * finish-opaque boundary (see {@code GraphRunner.finish}). Not routed through {@code
 * TargetRegistry}: {@code TargetFormat} defines only colour formats (no D32), and {@code
 * TargetRegistry.reconcile}'s clear path is a colour render pass ({@code Vector4f} clear) that
 * cannot clear a depth attachment -- so this self-owns its {@link GpuTexture}, mirroring {@link
 * GBufferManager}'s ownership idiom, and clears via {@code clearDepthTexture} to the reversed-Z far
 * value instead. Translucent draws bind the live G-buffer depth attachment for depth-testing, so
 * sampling that attachment directly mid-draw is a Vulkan hazard; this copy is the safe, portable
 * route -- the exact D32-to-D32 {@code copyTextureToTexture} primitive {@code GraphRunner.finish}'s
 * fallback depth copy-back already uses, proven-portable on MoltenVK.
 *
 * <p>{@link #ensureSize(int, int)} builds the replacement texture/view fully before swapping them
 * into the live fields, closing the previous pair only after the swap succeeds -- the same
 * exception-safe build-then-close ordering {@link GBufferManager#ensureSize} uses (a destroy-then-
 * build ordering was found and fixed as a real bug in this codebase's SSAA render-scale work: if
 * texture creation ever throws, e.g. GPU OOM on a large resize, a destroy-first field is left
 * pointing at an already-closed resource).
 */
public final class OpaqueDepth {
    public static final String NAME = "builtin.depth_opaque";
    /** Reversed-Z far. Cleared to this at allocation so a pre-capture sample reads far, not garbage. */
    public static final float FAR_CLEAR = 0.0f;

    @Nullable
    private GpuTexture texture;
    @Nullable
    private GpuTextureView view;
    private int width;
    private int height;

    /**
     * Idempotent: a texture already at (w, h) is left alone; a size mismatch (or no texture yet)
     * builds a fresh one at the new size and clears it to {@link #FAR_CLEAR} before it becomes
     * visible to any consumer -- MoltenVK garbage-VRAM law, freshly allocated VRAM is never
     * zero-filled.
     */
    public void ensureSize(int w, int h) {
        if (texture != null && width == w && height == h) {
            return;
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            FornaxMod.LOGGER.warn("[OpaqueDepth] Skipping (re)build: no GPU device available");
            return;
        }

        int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST
                | GpuTexture.USAGE_RENDER_ATTACHMENT;
        GpuTexture nextTexture = device.createTexture("Fornax Opaque Depth", usage,
                GpuFormat.D32_FLOAT, w, h, 1, 1);
        GpuTextureView nextView = device.createTextureView(nextTexture);
        // MoltenVK garbage-VRAM law: clear at allocation, before the swap below publishes this
        // texture -- a graph reading builtin.depth_opaque before the first real capture() must see
        // FAR_CLEAR, never driver garbage. Depth clears via clearDepthTexture, never the colour
        // clear-only render pass TargetRegistry uses (TargetFormat has no depth format).
        device.createCommandEncoder().clearDepthTexture(nextTexture, FAR_CLEAR);

        GpuTexture oldTexture = texture;
        GpuTextureView oldView = view;
        texture = nextTexture;
        view = nextView;
        width = w;
        height = h;

        if (oldView != null || oldTexture != null) {
            // Live per-frame resize path (window resize / SSAA render-scale change): this runs on an
            // already-active engine object every frame from GraphRunner.prepare(), with prior frames'
            // GPU work potentially still in flight -- the identical hazard TargetRegistry.ensureSize's
            // removeIf branch and reconcile() guard against (see VulkanComputeBackend
            // .waitForGpuIdleBeforeDestroy's own doc: two live MoltenVK crashes, hs_err_pid16681.log/
            // hs_err_pid32359.log, both faulted in vkQueueSubmit2KHR from a texture destroyed out from
            // under a still-executing submission). GraphRunner.closeCurrent()'s own top-of-method
            // wait-idle does NOT cover this call site -- that one only guards the free() teardown path,
            // never this per-frame rebuild path -- so it needs its own guard here, same as
            // TargetRegistry's.
            VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
        }
        if (oldView != null) {
            oldView.close();
        }
        if (oldTexture != null) {
            oldTexture.close();
        }
    }

    /**
     * Finish-opaque capture: a straight D32-to-D32 copy from the live G-buffer depth into this
     * self-owned texture -- the same {@code copyTextureToTexture} primitive {@code
     * GraphRunner.finish}'s fallback depth copy-back already uses. A no-op before the first {@link
     * #ensureSize(int, int)} (no texture to copy into).
     */
    public void capture(GpuTexture gbufferDepth, int w, int h) {
        if (texture == null) {
            return;
        }
        int cw = Math.min(w, width);
        int ch = Math.min(h, height);
        RenderSystem.getDevice().createCommandEncoder()
                .copyTextureToTexture(gbufferDepth, texture, 0, 0, 0, 0, 0, cw, ch);
    }

    @Nullable
    public GpuTextureView getView() {
        return view;
    }

    @Nullable
    public GpuTexture getTexture() {
        return texture;
    }

    /** Releases the GPU resources, if any. Safe to call repeatedly, including before any allocation. */
    public void free() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        width = 0;
        height = 0;
    }
}
