package dev.icehunter.fornax.metalfx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.metalfx.VulkanMetalInterop.InteropImage;
import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.pack.graph.TargetInstance;
import dev.icehunter.fornax.pass.reconstruct.ReconstructPass;
import dev.icehunter.fornax.pipeline.SceneHistory;
import org.joml.Vector2f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;

/**
 * MetalFX temporal upscale at the reconstruct seam (spike M2). Replaces {@code ReconstructPass}
 * for a frame: copies jittered low-res color plus render-res depth/motion/reactive-mask inputs into
 * exported interop textures, runs {@code MTLFXTemporalScaler} (which owns its temporal history
 * internally), writes the unsharpened native result into sceneHistory, and presents it through the
 * engine's existing sharpen pass. Returns false
 * (caller falls back to the normal TAAU reconstruct, same frame) unless armed via
 * {@code -Dfornax.metalfx.scaler=true} -- and permanently for the session on any failure.
 *
 * <p>CONVENTION PINNING (the plan's flagged silent-quality risk -- signs/scales between fornax's
 * conventions and MetalFX's are pinned empirically, with system-property knobs so an A/B needs a
 * relaunch, not a rebuild):
 * <ul>
 *   <li>JITTER: fornax's {@code CameraJitter.currentOffsetNdc()} is the offset ADDED to the
 *       projection, GL-style NDC (y up). MetalFX wants input-texture PIXELS. Default conversion
 *       {@code px = ndc.x * w/2}, {@code py = -ndc.y * h/2} (the NDC-up to texture-down flip);
 *       {@code -Dfornax.metalfx.jitterFlipX/Y=true} flips either axis.</li>
 *   <li>MOTION: gMotion stores {@code currentUV - previousUV} (consumers reproject via
 *       {@code prevUV = uv - motion}). MetalFX (like DLSS/FSR) reprojects via
 *       {@code prevPos = pos + motion * scale}, so the default scale is NEGATIVE input size
 *       ({@code -w, -h}); {@code -Dfornax.metalfx.mvFlip=true} flips both.</li>
 *   <li>DEPTH: reversed-Z (engine-wide) -- {@code depthReversed} is always set.</li>
 * </ul>
 *
 * <p>Sync/layout/failure discipline: shared-event GPU ordering by default, with a host-serialized
 * diagnostic fallback. Vulkan copies use transfer-optimal interop layouts and stage-accurate
 * barriers. Scene history receives the unsharpened result before presentation sharpening, so the
 * feedback-loop law matches {@code ReconstructPass}.
 */
public final class MetalFxUpscalePass {
    private static final boolean SCALER_REQUESTED = Boolean.getBoolean("fornax.metalfx.scaler");
    // Package-private (not private): FrameGenPass reuses these unchanged so its own jitterPixels
    // conversion for MTLFXFrameInterpolator stays bit-identical to this pass's own MTLFXTemporalScaler
    // conversion for the same frame -- one A/B knob pair, not two independently-driftable ones.
    static final boolean JITTER_FLIP_X = Boolean.getBoolean("fornax.metalfx.jitterFlipX");
    static final boolean JITTER_FLIP_Y = Boolean.getBoolean("fornax.metalfx.jitterFlipY");
    private static final boolean MV_FLIP = Boolean.getBoolean("fornax.metalfx.mvFlip");
    // Debug escape back to the M2 host-serialized sync (three CPU<->GPU stalls/frame). Default is
    // the M3 zero-stall path: one exported MTLSharedEvent orders Vulkan copy-in -> Metal scaler ->
    // Vulkan copy-back entirely GPU-side (see VulkanMetalInterop.createSharedTimeline).
    private static final boolean HARD_SYNC = Boolean.getBoolean("fornax.metalfx.hardSync");

    private static boolean failed;
    private static boolean loggedOnce;

    private static InteropImage colorIn;
    private static InteropImage depthIn;
    private static InteropImage motionIn;
    private static InteropImage reactiveIn;
    private static InteropImage output;
    // The render-res off-screen target's OWN depth attachment for the frame just run -- i.e. the
    // exact `lowRes.getDepthTextureView()` this pass fed MetalFxReactiveMaskPass as u_SceneDepth.
    // Stashed (mirroring depthIn/motionIn's own "reused later this same frame" role) so
    // FrameGenPresenter's unified fill pass can rebuild the SAME scene-depth-vs-G-buffer-depth
    // predicate the reactive mask uses, without a second render-res depth copy: `lowRes` is a
    // persistent pooled target (never destroyed mid-frame), so this view stays valid through the
    // rest of this same GameRenderer.render() call, which is the only lifetime the later
    // GuiRendererCaptureMixin capture needs.
    private static GpuTextureView lastSceneDepthView;
    private static MetalFxScaler scaler;
    private static boolean pendingReset;
    private static VulkanMetalInterop.SharedTimeline timeline;
    private static long timelineValue = 1;   // next Vulkan-side signal value (semaphore starts at 0)
    private static long lastVulkanSignal;    // highest value signaled after Vulkan copy-back
    private static final Vector2f JITTER_PIXELS = new Vector2f();
    private static final Vector2f MOTION_SCALE = new Vector2f();

    private MetalFxUpscalePass() {}

    /**
     * Runs the MetalFX upscale when armed and healthy; returns whether it handled the frame
     * (false -> caller runs the normal reconstruct).
     */
    public static boolean runIfEnabled(RenderTarget lowRes, RenderTarget nativeDest,
            GpuTextureView motionView, GpuTextureView depthView, TargetInstance sceneHistory,
            Vector2f jitterNdc) {
        boolean armed = SCALER_REQUESTED
                || dev.icehunter.fornax.config.FornaxConfig.get().aaMethod
                        == dev.icehunter.fornax.config.AaMethod.METALFX;
        if (!armed || failed) {
            return false;
        }
        if (!MetalFxSupport.isAvailable()) {
            failed = true;
            FornaxMod.LOGGER.warn("[Fornax] MetalFX scaler requested but probe says unavailable");
            return false;
        }
        try {
            run(lowRes, nativeDest, motionView, depthView, sceneHistory, jitterNdc);
            return true;
        } catch (Throwable t) {
            failed = true;
            FornaxMod.LOGGER.error("[Fornax] MetalFX scaler FAILED -- falling back to TAAU for this session", t);
            return false;
        }
    }

    private static void run(RenderTarget lowRes, RenderTarget nativeDest,
            GpuTextureView motionView, GpuTextureView depthView, TargetInstance sceneHistory,
            Vector2f jitterNdc) {
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        if (device == null) {
            throw new IllegalStateException("no Vulkan device (GL backend?)");
        }
        VulkanGpuTexture colorTex = (VulkanGpuTexture) lowRes.getColorTextureView().texture();
        VulkanGpuTexture nativeTex = (VulkanGpuTexture) nativeDest.getColorTextureView().texture();
        VulkanGpuTexture motionTex = (VulkanGpuTexture) motionView.texture();
        VulkanGpuTexture depthTex = (VulkanGpuTexture) depthView.texture();
        int inW = colorTex.getWidth(0);
        int inH = colorTex.getHeight(0);
        int outW = nativeTex.getWidth(0);
        int outH = nativeTex.getHeight(0);
        // The render-res G-buffer must match the low-res color's extent for vkCmdCopyImage and for
        // MetalFX's own validation; a mismatch (mid-resize frame) falls back cleanly this frame.
        if (motionTex.getWidth(0) != inW || motionTex.getHeight(0) != inH
                || depthTex.getWidth(0) != inW || depthTex.getHeight(0) != inH) {
            throw new IllegalStateException("G-buffer extent mismatch: color " + inW + "x" + inH
                    + " motion " + motionTex.getWidth(0) + "x" + motionTex.getHeight(0)
                    + " depth " + depthTex.getWidth(0) + "x" + depthTex.getHeight(0));
        }
        ensureResources(device, inW, inH, outW, outH);
        GpuTextureView sceneDepthView = lowRes.getDepthTextureView();
        lastSceneDepthView = sceneDepthView;
        GpuTextureView reactiveMaskView = MetalFxReactiveMaskPass.render(sceneDepthView, depthView, inW, inH);
        VulkanGpuTexture reactiveTex = (VulkanGpuTexture) reactiveMaskView.texture();
        VulkanGpuTexture historyTex =
                (VulkanGpuTexture) SceneHistory.writeSlot(sceneHistory);

        VulkanCommandEncoder encoder = device.createCommandEncoder();
        long v = timelineValue;

        // ---- Vulkan: copy color/depth/motion into the interop set (ordered after frame draws) ----
        VulkanMetalInterop.CmdRecorder copyIn = cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanMetalInterop.prepareGeneralTransferRead(
                        cmd, stack, colorTex.vkImage(), VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                VulkanMetalInterop.prepareGeneralTransferRead(
                        cmd, stack, motionTex.vkImage(), VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                VulkanMetalInterop.prepareGeneralTransferRead(
                        cmd, stack, depthTex.vkImage(), VK13.VK_IMAGE_ASPECT_DEPTH_BIT);
                VulkanMetalInterop.prepareGeneralTransferRead(
                        cmd, stack, reactiveTex.vkImage(), VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                VulkanMetalInterop.prepareInteropTransferWrite(cmd, stack, colorIn);
                VulkanMetalInterop.prepareInteropTransferWrite(cmd, stack, motionIn);
                VulkanMetalInterop.prepareInteropTransferWrite(cmd, stack, depthIn);
                VulkanMetalInterop.prepareInteropTransferWrite(cmd, stack, reactiveIn);
                VulkanMetalInterop.prepareInteropMetalWrite(cmd, stack, output);
                VulkanMetalInterop.copyImage(cmd, stack,
                        colorTex.vkImage(), VK13.VK_IMAGE_LAYOUT_GENERAL,
                        colorIn.image, colorIn.layout,
                        VK13.VK_IMAGE_ASPECT_COLOR_BIT, inW, inH);
                VulkanMetalInterop.copyImage(cmd, stack,
                        motionTex.vkImage(), VK13.VK_IMAGE_LAYOUT_GENERAL,
                        motionIn.image, motionIn.layout,
                        VK13.VK_IMAGE_ASPECT_COLOR_BIT, inW, inH);
                VulkanMetalInterop.copyImage(cmd, stack,
                        depthTex.vkImage(), VK13.VK_IMAGE_LAYOUT_GENERAL,
                        depthIn.image, depthIn.layout,
                        VK13.VK_IMAGE_ASPECT_DEPTH_BIT, inW, inH);
                VulkanMetalInterop.copyImage(cmd, stack,
                        reactiveTex.vkImage(), VK13.VK_IMAGE_LAYOUT_GENERAL,
                        reactiveIn.image, reactiveIn.layout,
                        VK13.VK_IMAGE_ASPECT_COLOR_BIT, inW, inH);
                VulkanMetalInterop.finishInteropTransferWrite(cmd, stack, colorIn);
                VulkanMetalInterop.finishInteropTransferWrite(cmd, stack, motionIn);
                VulkanMetalInterop.finishInteropTransferWrite(cmd, stack, depthIn);
                VulkanMetalInterop.finishInteropTransferWrite(cmd, stack, reactiveIn);
            }
        };
        if (HARD_SYNC) {
            VulkanMetalInterop.recordAndFlush(encoder, copyIn);
        } else {
            // Zero-stall: append the copy-in to the encoder's stream, GPU-signal the shared
            // timeline at v, and flush the submission WITHOUT any host wait.
            VulkanMetalInterop.recordIntoStream(encoder, copyIn);
            encoder.signalSemaphore(timeline.vkSemaphore, v, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
            encoder.submit();
        }

        // ---- Metal: run the temporal scaler (event-ordered against the Vulkan queue) ----
        MetalFxConventions.jitterPixels(
                jitterNdc, inW, inH, JITTER_FLIP_X, JITTER_FLIP_Y, JITTER_PIXELS);
        MetalFxConventions.motionScale(inW, inH, MV_FLIP, MOTION_SCALE);
        float jitterX = JITTER_PIXELS.x;
        float jitterY = JITTER_PIXELS.y;
        float mvScaleX = MOTION_SCALE.x;
        float mvScaleY = MOTION_SCALE.y;
        long pool = Objc.autoreleasePoolPush();
        try {
            long cb = Objc.msgSendId(VulkanMetalInterop.metalCommandQueue(),
                    Objc.selector("commandBuffer"));
            if (cb == 0) {
                throw new IllegalStateException("Metal command buffer nil");
            }
            if (!HARD_SYNC) {
                Objc.msgSendVoidIdLong(cb, Objc.selector("encodeWaitForEvent:value:"),
                        timeline.mtlSharedEvent, v);
            }
            scaler.encode(cb, colorIn.mtlTexture, depthIn.mtlTexture, motionIn.mtlTexture,
                    reactiveIn.mtlTexture,
                    output.mtlTexture, jitterX, jitterY, mvScaleX, mvScaleY, pendingReset, true);
            if (!HARD_SYNC) {
                Objc.msgSendVoidIdLong(cb, Objc.selector("encodeSignalEvent:value:"),
                        timeline.mtlSharedEvent, v + 1);
            }
            Objc.msgSendVoid(cb, Objc.selector("commit"));
            if (HARD_SYNC) {
                Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));
            }
            pendingReset = false;
        } finally {
            Objc.autoreleasePoolPop(pool);
        }

        // ---- Vulkan: upscaled output -> native target color ----
        VulkanMetalInterop.CmdRecorder copyBack = cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanMetalInterop.prepareInteropTransferRead(cmd, stack, output);
                VulkanMetalInterop.prepareGeneralTransferWrite(
                        cmd, stack, historyTex.vkImage(), VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                VulkanMetalInterop.copyImage(cmd, stack,
                        output.image, output.layout,
                        historyTex.vkImage(), VK13.VK_IMAGE_LAYOUT_GENERAL,
                        VK13.VK_IMAGE_ASPECT_COLOR_BIT, outW, outH);
                VulkanMetalInterop.finishInteropTransferRead(cmd, stack, output);
                VulkanMetalInterop.finishGeneralTransferWrite(
                        cmd, stack, historyTex.vkImage(), VK13.VK_IMAGE_ASPECT_COLOR_BIT);
            }
        };
        if (HARD_SYNC) {
            VulkanMetalInterop.recordAndFlush(encoder, copyBack);
        } else {
            // GPU-wait the scaler's completion signal in the NEXT submission, then append the
            // copy-back to the stream -- everything after it this frame (sceneHistory copy, HUD,
            // present) is already ordered behind it by submission order. No host stall.
            encoder.waitSemaphore(timeline.vkSemaphore, v + 1, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
            VulkanMetalInterop.recordIntoStream(encoder, copyBack);
            encoder.signalSemaphore(timeline.vkSemaphore, v + 2,
                    VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
            encoder.submit();
            lastVulkanSignal = v + 2;
            timelineValue = v + 3;
        }
        ReconstructPass.presentSharpened(
                SceneHistory.writeSlotView(sceneHistory), nativeDest,
                inW, inH, FornaxConfig.get().reconstructSharpen);

        if (!loggedOnce) {
            loggedOnce = true;
            FornaxMod.LOGGER.info(
                    "[Fornax] MetalFX temporal scaler live: {}x{} -> {}x{} (sync={}, jitter px {},{} mvScale {},{})",
                    inW, inH, outW, outH, HARD_SYNC ? "hard-wait" : "shared-event",
                    jitterX, jitterY, mvScaleX, mvScaleY);
        }
    }

    private static void ensureResources(VulkanDevice device, int inW, int inH, int outW, int outH) {
        boolean sizesMatch = colorIn != null && colorIn.width == inW && colorIn.height == inH
                && reactiveIn != null && reactiveIn.width == inW && reactiveIn.height == inH
                && output != null && output.width == outW && output.height == outH;
        if (sizesMatch && scaler != null) {
            return;
        }
        if (timeline == null && !HARD_SYNC) {
            timeline = VulkanMetalInterop.createSharedTimeline(device);
        }
        if (scaler != null) {
            // Resize teardown: v+2 is signaled only after Metal completed and Vulkan finished the
            // copy-back, so one bounded timeline wait quiesces the whole outgoing resource set.
            if (!HARD_SYNC && timeline != null && lastVulkanSignal > 0) {
                VulkanMetalInterop.waitTimeline(device, timeline, lastVulkanSignal);
            }
            scaler.release();
            scaler = null;
        }
        // FrameGenPass borrows depthIn/motionIn every frame it runs (see depthInterop()/
        // motionInterop() below) and reads them on its OWN independently-timed Metal command
        // buffer -- this pass's own timeline wait above has no visibility into that. Without this
        // call, a resize landing here while frame generation was engaged the previous frame can
        // free memory FrameGenPass's still-executing interpolator encode is still reading (a
        // genuine cross-class GPU use-after-free, live-caught: see the mc-vulkan-realism crash
        // investigation follow-up). No-op if frame generation has never run this session.
        FrameGenPass.waitForOwnGpuWork();
        VulkanMetalInterop.destroyImage(device, colorIn);
        VulkanMetalInterop.destroyImage(device, depthIn);
        VulkanMetalInterop.destroyImage(device, motionIn);
        VulkanMetalInterop.destroyImage(device, reactiveIn);
        VulkanMetalInterop.destroyImage(device, output);

        int inputUsage = VK13.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK13.VK_IMAGE_USAGE_SAMPLED_BIT;
        colorIn = VulkanMetalInterop.createImage(device, inW, inH,
                VK13.VK_FORMAT_R8G8B8A8_UNORM, inputUsage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        motionIn = VulkanMetalInterop.createImage(device, inW, inH,
                VK13.VK_FORMAT_R16G16_SFLOAT, inputUsage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        depthIn = VulkanMetalInterop.createImage(device, inW, inH,
                VK13.VK_FORMAT_D32_SFLOAT,
                inputUsage | VK13.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                VK13.VK_IMAGE_ASPECT_DEPTH_BIT);
        reactiveIn = VulkanMetalInterop.createImage(device, inW, inH,
                VK13.VK_FORMAT_R8_UNORM, inputUsage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        // Output usage covers every MTLTextureUsage MetalFX may validate on its output
        // (RenderTarget via COLOR_ATTACHMENT, ShaderRead via SAMPLED, ShaderWrite via STORAGE).
        output = VulkanMetalInterop.createImage(device, outW, outH,
                VK13.VK_FORMAT_R8G8B8A8_UNORM,
                VK13.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK13.VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK13.VK_IMAGE_USAGE_STORAGE_BIT | VK13.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                VK13.VK_IMAGE_ASPECT_COLOR_BIT);

        scaler = MetalFxScaler.create(MetalFxSupport.metalDevice(), inW, inH, outW, outH,
                MetalFxScaler.PIXEL_FORMAT_RGBA8_UNORM, MetalFxScaler.PIXEL_FORMAT_DEPTH32_FLOAT,
                MetalFxScaler.PIXEL_FORMAT_RG16_FLOAT, MetalFxScaler.PIXEL_FORMAT_RGBA8_UNORM);
        if (scaler == null) {
            throw new IllegalStateException("MTLFXTemporalScaler creation refused ("
                    + inW + "x" + inH + " -> " + outW + "x" + outH + ")");
        }
        pendingReset = true;
    }

    /** Render-res interop images populated by the most recent run(); for FrameGenPass reuse. */
    static VulkanMetalInterop.InteropImage depthInterop() { return depthIn; }
    static VulkanMetalInterop.InteropImage motionInterop() { return motionIn; }

    /**
     * The render-res off-screen target's OWN depth view from this same frame's {@link #run} (the
     * SAME {@code u_SceneDepth} input {@code MetalFxReactiveMaskPass} used) -- {@code null} until the
     * first successful upscale. Public (unlike {@link #depthInterop}/{@link #motionInterop}, which
     * only {@code FrameGenPass} in this same package reuses): {@link
     * dev.icehunter.fornax.pass.FrameGenPresenter}'s unified fill pass needs it too, reached from a
     * different mixin ({@code GuiRendererCaptureMixin}) later in the same frame, so it must cross a
     * package boundary the same way {@code FrameGenPass.generatedFrameReady()} already does.
     */
    public static GpuTextureView sceneDepthView() { return lastSceneDepthView; }
}
