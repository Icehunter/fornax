package dev.icehunter.fornax.metalfx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.AaMethod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.metalfx.VulkanMetalInterop.InteropImage;
import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.pipeline.FrameClock;
import dev.icehunter.fornax.pipeline.FrameGenPacer;
import dev.icehunter.fornax.util.GpuFatalErrors;
import org.joml.Vector2f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;

/**
 * MetalFX frame interpolation ({@code MTLFXFrameInterpolator}), one seam past
 * {@link MetalFxUpscalePass}. Reachable ONLY behind a successful MetalFX upscale for the frame (the
 * mixin seam calls this right after {@code MetalFxUpscalePass.runIfEnabled} returns {@code true}):
 * upscale already wrote the frame's native color and its unsharpened result into {@code
 * SceneHistory}'s write slot, and this pass NEVER touches {@code SceneHistory} itself -- it keeps
 * its own three-image native-resolution color ring ({@code prevColor}/{@code curColor}/{@code
 * generated}) and its own {@link VulkanMetalInterop.SharedTimeline}, entirely independent of the
 * upscale pass's timeline and of the sceneHistory write-phase law.
 *
 * <p>Per frame: the native color the upscale pass just produced is copied (Vulkan) into {@code
 * curColor}, signaling this pass's timeline at {@code v}. Once history exists (two frames seen) and
 * {@link FrameClock#ready()}, Metal GPU-waits {@code v}, runs {@code MTLFXFrameInterpolator} against
 * {@code prevColor}/{@code curColor} plus the upscale pass's already-populated render-resolution
 * depth/motion interop images ({@link MetalFxUpscalePass#depthInterop()}/{@link
 * MetalFxUpscalePass#motionInterop()}), writes {@code generated}, and signals {@code v+1} -- no host
 * wait anywhere in the steady state. {@code prevColor}/{@code curColor} then pointer-swap (no copy)
 * for the next frame's ring position. {@code MTLFXFrameInterpolator}'s configured input dims are the
 * RENDER-resolution depth/motion textures' own size (from {@code depthInterop().width/height}), not
 * the native output size the color ring uses -- mirroring {@code MTLFXTemporalScaler}'s own
 * input/output split; feeding it native dims for a render-res input produced the scale mismatch a
 * live A/B caught (motion-proportional lattice smearing on camera movement, clean when standing
 * still).
 *
 * <p>{@link #copyGeneratedInto} is the ONLY way a generated frame leaves this package: it records a
 * Vulkan copy {@code generated -> dest} that waits the pass's {@code v+1} and signals {@code v+2},
 * mirroring the upscale pass's own copy-back. {@code -Dfornax.framegen.debugView=true} calls it at
 * the end of every armed {@link #runIfEnabled} so the screen shows nothing but generated frames --
 * the whole Metal path verified without any present-path integration.
 *
 * <p><b>Adaptive engagement</b> -- {@code runIfEnabled} does not unconditionally interpolate every
 * armed frame. {@link dev.icehunter.fornax.pipeline.FrameGenPacer} tracks render fps against the
 * display's own refresh rate with hysteresis and only engages the (costly) interpolator encode when
 * the machine genuinely needs the assist; while disengaged this pass is a no-op single-present, same
 * as if generation were unarmed. See {@code runIfEnabled}'s own header for the full mechanics, and
 * {@link dev.icehunter.fornax.pipeline.FrameGenPacer}'s for why: under FIFO/vsync, unconditional
 * double-present caps the REAL frame rate at {@code displayHz / 2} even on machines that could
 * already render well above that on their own.
 */
public final class FrameGenPass {
    private static final boolean ARM_REQUESTED = Boolean.getBoolean("fornax.framegen");
    private static final boolean DEBUG_VIEW = Boolean.getBoolean("fornax.framegen.debugView");
    private static final boolean MV_FLIP = Boolean.getBoolean("fornax.metalfx.mvFlip");
    // Interpolator-feed-only A/B knobs (mirrors MetalFxUpscalePass's own jitterFlipX/Y / mvFlip
    // mechanism, see that class's header): applied AFTER the shared MetalFxConventions call above
    // reuses the scaler's own MV_FLIP/JITTER_FLIP_X/Y, so these never touch what MetalFxUpscalePass
    // feeds MTLFXTemporalScaler -- only what this pass feeds MTLFXFrameInterpolator.
    //
    // FRAMEGEN_MV_FLIP semantics were INVERTED 2026-07-24: a live A/B on real hardware
    // (-Dfornax.framegen.mvFlip=true, this knob's ORIGINAL sense) visibly reduced generated-frame
    // dither, proving MTLFXFrameInterpolator's motion convention is the OPPOSITE sign of
    // MTLFXTemporalScaler's (same live-A/B pinning method fornax.metalfx.mvFlip used historically to
    // pin the scaler's own convention). The flipped (positive-dims) motion scale is now the DEFAULT
    // feed for the interpolator; this knob, still named fornax.framegen.mvFlip and kept for future
    // A/B, now flips BACK to the scaler-style negative-dims convention when set true -- see its use
    // site in run() for the actual sign flip, and MetalFxUpscalePass/MetalFxConventions#motionScale
    // for the untouched scaler-side convention this pass no longer defaults to matching.
    private static final boolean FRAMEGEN_MV_FLIP = Boolean.getBoolean("fornax.framegen.mvFlip");
    private static final boolean FRAMEGEN_JITTER_FLIP = Boolean.getBoolean("fornax.framegen.jitterFlip");

    private static final FrameClock CLOCK = new FrameClock();
    private static final Vector2f MOTION_SCALE = new Vector2f();
    private static final Vector2f JITTER_PIXELS = new Vector2f();

    private static boolean failed;
    private static boolean loggedConventionsOnce;

    private static InteropImage prevColor;
    private static InteropImage curColor;
    private static InteropImage generated;
    private static MetalFxFrameInterpolator interpolator;

    private static VulkanMetalInterop.SharedTimeline timeline;
    private static long timelineValue = 1;   // next fresh value on this pass's OWN timeline
    private static long lastVulkanSignal;    // highest value known signaled (resize/deactivate wait)
    private static long generatedSignalValue; // value Metal signals when `generated` is ready

    // The interpolator's configured input (render-res depth/motion) dims -- tracked separately from
    // the color ring's dims (native/output res) since ensureResources must recreate the interpolator
    // when EITHER pair changes, not just the color images' own size.
    private static int interpolatorInputW;
    private static int interpolatorInputH;

    private static boolean pendingReset;
    private static boolean hasHistory;
    private static boolean generatedReady;

    private FrameGenPass() {}

    /**
     * Runs frame interpolation for this frame when armed and healthy. No-ops silently (including on
     * a disarmed/unavailable state) so the caller never needs its own gate. {@code nativeDest} is
     * the same native-resolution target the upscale pass just wrote (its unsharpened result already
     * landed in {@code SceneHistory}; this call never touches that slot).
     *
     * <p>{@code nearPlane}/{@code farPlane}/{@code fovDegrees} are the mixin seam's own {@code
     * GameRenderer.mainCamera} values for this frame (see {@code GameRendererMixin#fornax$reconstruct}),
     * fed straight through to {@link MetalFxFrameInterpolator#encode} to linearize our reversed-Z
     * depth -- see that method's own javadoc for the unit decisions (degrees FOV, block-unit
     * near/far). Aspect ratio is not threaded through the public API: it is derived locally in
     * {@link #run} from the same {@code nativeDest} texture's own dimensions, avoiding a second
     * source of truth for a value this pass already computes.
     *
     * <p>{@code jitterNdc} is the mixin seam's own {@code CameraJitter.currentOffsetNdc()} for this
     * frame -- the SAME value {@code MetalFxUpscalePass.run} already read this same frame to jitter
     * {@code MTLFXTemporalScaler}'s input. This pass's interpolator receives that same jittered
     * render-res depth/color (reused from the upscale pass, see this class's own header), so it needs
     * the identical jitter-pixels conversion or its reprojection reads sub-pixel-misaligned samples --
     * the live symptom that motivated threading this through (uniform diagonal dither/stipple on
     * generated frames).
     *
     * <p><b>Adaptive pacing (disengage below the display's own refresh rate)</b>: {@link
     * FrameGenPacer#update} runs here, ONCE per armed frame -- the single decision point both this
     * method's own arming below and {@code FrameGenPresenter}'s later cadence log trace back to (see
     * {@link FrameGenPacer}'s own header for why this is the only writer). {@code CLOCK.markFrame} is
     * called UNCONDITIONALLY first, before the pacer even runs, so render-fps measurement never stops
     * while disengaged -- it is exactly the signal {@link FrameGenPacer} needs to decide when to
     * re-engage. When {@link FrameGenPacer#engaged()} is false, this method returns WITHOUT calling
     * {@link #run}: no Vulkan copy into {@code curColor}, no {@code MTLFXFrameInterpolator} encode --
     * the simplest of the two correct options considered (the alternative, keeping the color-ring
     * copy alive every frame to avoid any warm-up gap on re-engage, would still cost a Vulkan copy
     * every disengaged frame for a ring the interpolator never reads while disengaged; skipping
     * everything is strictly cheaper and the warm-up cost it trades in is exactly one frame -- see
     * below). {@code generatedReady} is forced false on every disengaged frame, so {@code
     * generatedFrameReady()} reads FALSE exactly as if generation were unarmed -- {@code
     * GuiRendererCaptureMixin} and {@code FrameGenPresenter} both gate on that one flag, so a
     * disengaged frame takes the identical code path a framegen-off frame takes in both of them,
     * with no separate "disengaged" branch needed in either.
     *
     * <p>{@code hasHistory} is force-cleared (with {@code pendingReset} set, mirroring what {@link
     * #ensureResources} does on a genuine resize) the FIRST frame a disengage is observed -- i.e.
     * only on the true->false transition, guarded by {@code if (hasHistory)} so later disengaged
     * frames are a no-op repeat of the same skip. Without this, {@code prevColor}/{@code curColor}
     * would sit frozen at whatever they held when disengagement began (never copied into again while
     * disengaged, since {@link #run} does not execute), and the FIRST frame after re-engaging would
     * interpolate against that stale pair -- ghosting proportional to however long the disengaged
     * window lasted, not a clean one-frame gap. Clearing {@code hasHistory} instead makes the first
     * re-engaged frame behave exactly like the second frame of a fresh arm: it runs {@link #run},
     * refreshes {@code curColor}, but {@code generatedReady} stays false that one frame (history
     * incomplete); the SECOND re-engaged frame then has real two-frame history and resumes generating
     * normally. One frame of no-generation on re-engage, never more, regardless of how long the
     * disengaged window was.
     */
    public static void runIfEnabled(RenderTarget nativeDest, float nearPlane, float farPlane,
            float fovDegrees, Vector2f jitterNdc) {
        if (!armed()) {
            // Arming can flip false mid-session (config change, a prior failure); without this, a
            // `generatedReady` left true by the last armed frame would stay stale-true forever since
            // it is otherwise only ever written inside run() below -- the present seam would keep
            // trying to present a frame that was never produced this session.
            generatedReady = false;
            return;
        }
        try {
            CLOCK.markFrame(System.nanoTime());
            FrameGenPacer.update(CLOCK.emaIntervalNanos());
            if (!FrameGenPacer.engaged()) {
                if (hasHistory) {
                    // True->false transition only (see this method's own header): force a one-frame
                    // warm-up on re-engage instead of interpolating against a color ring that went
                    // stale for the whole disengaged window.
                    hasHistory = false;
                    pendingReset = true;
                }
                generatedReady = false;
                return;
            }
            run(nativeDest, nearPlane, farPlane, fovDegrees, jitterNdc);
            if (DEBUG_VIEW && generatedReady) {
                copyGeneratedInto(nativeDest);
            }
        } catch (Throwable t) {
            // Fatal rethrows: run() records and submits real GPU work (the Vulkan copy-in plus the
            // Metal interpolator encode), so a GpuDeviceLossException or one of VulkanMetalInterop's
            // own GpuFatalExceptions here means the device is already gone. Swallowing it into the
            // same soft failed=true fallback as an ordinary bug hands the very next unrelated
            // submit() that same dead device.
            GpuFatalErrors.rethrowIfFatal(t);
            markFailed("runIfEnabled", t);
        }
    }

    /**
     * Whether frame generation is armed for this session (config/JVM-property toggle on, no prior
     * failure, METALFX selected, and the hardware probe passed). Public so {@link
     * dev.icehunter.fornax.pass.FrameGenPresenter}'s overlay accessor can gate its "FrameGen: ..."
     * profiler-overlay row on the same single source of truth {@link #runIfEnabled} itself gates
     * on, rather than re-deriving the same condition from config/support state a second time.
     */
    public static boolean armed() {
        // Config is the primary arming switch (the settings-screen toggle); the JVM property stays
        // live as an OR so a dev override still works without touching fornax.json.
        return (FornaxConfig.get().frameGeneration || ARM_REQUESTED) && !failed
                && FornaxConfig.get().aaMethod == AaMethod.METALFX
                && MetalFxSupport.isFrameInterpolationAvailable();
    }

    /** True when this frame produced a generated image ready to present. */
    public static boolean generatedFrameReady() {
        return generatedReady;
    }

    /**
     * Records the {@code generated -> dest} Vulkan copy (waiting/advancing this pass's OWN
     * timeline), or no-ops and returns false when nothing is ready.
     */
    public static boolean copyGeneratedInto(RenderTarget dest) {
        if (!generatedReady) {
            return false;
        }
        try {
            VulkanDevice device = VulkanMetalInterop.vulkanDevice();
            if (device == null) {
                throw new IllegalStateException("no Vulkan device (GL backend?)");
            }
            VulkanGpuTexture destTex = (VulkanGpuTexture) dest.getColorTextureView().texture();
            VulkanCommandEncoder encoder = device.createCommandEncoder();
            long waitV = generatedSignalValue;
            long signalV = timelineValue;
            timelineValue = signalV + 1;

            VulkanMetalInterop.CmdRecorder copyOut = cmd -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VulkanMetalInterop.prepareInteropTransferRead(cmd, stack, generated);
                    VulkanMetalInterop.prepareGeneralTransferWrite(
                            cmd, stack, destTex.vkImage(), VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                    VulkanMetalInterop.copyImage(cmd, stack,
                            generated.image, generated.layout,
                            destTex.vkImage(), VK13.VK_IMAGE_LAYOUT_GENERAL,
                            VK13.VK_IMAGE_ASPECT_COLOR_BIT, generated.width, generated.height);
                    VulkanMetalInterop.finishInteropTransferRead(cmd, stack, generated);
                    VulkanMetalInterop.finishGeneralTransferWrite(
                            cmd, stack, destTex.vkImage(), VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                }
            };
            encoder.waitSemaphore(timeline.vkSemaphore, waitV, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
            VulkanMetalInterop.recordIntoStream(encoder, copyOut);
            encoder.signalSemaphore(timeline.vkSemaphore, signalV, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
            encoder.submit();
            lastVulkanSignal = signalV;
            return true;
        } catch (Throwable t) {
            // Fatal rethrows -- same reasoning as runIfEnabled's own catch: this records and
            // submits real GPU work too.
            GpuFatalErrors.rethrowIfFatal(t);
            markFailed("copyGeneratedInto", t);
            return false;
        }
    }

    /** External failure report (e.g. the present seam), fail-closed for the rest of the session. */
    public static void markFailed(String where, Throwable t) {
        failed = true;
        FornaxMod.LOGGER.error(
                "[Fornax] MetalFX frame generation FAILED at {} -- disabled for this session", where, t);
    }

    /**
     * Blocks until this pass's own last-signaled GPU work (Vulkan copy plus the Metal interpolator
     * encode, both on this pass's OWN independent {@link VulkanMetalInterop.SharedTimeline}) has
     * completed -- but does NOT release or null out any resource. Package-visible so {@link
     * MetalFxUpscalePass} can call it before tearing down the {@code depthIn}/{@code motionIn}
     * interop images this pass borrows every frame (see {@link MetalFxUpscalePass#depthInterop()}/
     * {@link MetalFxUpscalePass#motionInterop()}): MetalFxUpscalePass's own timeline wait has no
     * visibility into this pass's independently-timed Metal command buffer, so without this call a
     * resize landing on MetalFxUpscalePass while this pass was engaged the previous frame can free
     * memory this pass's still-executing interpolator encode is still reading -- a genuine
     * cross-class GPU use-after-free, live-caught (see the mc-vulkan-realism crash investigation,
     * docs/reference/vulkan-renderer-architecture-audit.md follow-up). No-op if this pass has never
     * run or has no outstanding signal.
     */
    static void waitForOwnGpuWork() {
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        if (device != null && timeline != null && lastVulkanSignal > 0) {
            try {
                VulkanMetalInterop.waitTimeline(device, timeline, lastVulkanSignal);
            } catch (Throwable t) {
                // Fatal rethrows: this method's own doc explains the wait exists specifically to
                // stop deactivate() from freeing the interpolator/images while GPU work still
                // reads them. Downgrading a failed wait to a warning and proceeding to free them
                // anyway is exactly the use-after-free this method exists to prevent.
                GpuFatalErrors.rethrowIfFatal(t);
                FornaxMod.LOGGER.warn("[Fornax] frame generation cross-teardown wait failed", t);
            }
        }
    }

    /** Releases every resource; safe to call anytime (config-off transitions, shutdown). */
    public static void deactivate() {
        waitForOwnGpuWork();
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        if (interpolator != null) {
            interpolator.release();
            interpolator = null;
        }
        if (device != null) {
            VulkanMetalInterop.destroyImage(device, prevColor);
            VulkanMetalInterop.destroyImage(device, curColor);
            VulkanMetalInterop.destroyImage(device, generated);
        }
        prevColor = null;
        curColor = null;
        generated = null;
        hasHistory = false;
        generatedReady = false;
        pendingReset = false;
        // timelineValue/lastVulkanSignal stay monotonic across deactivate/re-arm: the exported
        // SharedTimeline semaphore is never destroyed here (see the class header), and a re-arm
        // that reset the counter back to a stale-low value would be an illegal signal on the same
        // semaphore -- MoltenVK requires timeline values to be non-decreasing.
        CLOCK.reset();
        // Pacer state is session-scoped like everything else above: a later re-arm should start
        // disengaged and re-measure from scratch, not carry over whatever engagement this session
        // ended in.
        FrameGenPacer.reset();
    }

    /** This pass's own {@link FrameClock} (instrumentation). */
    public static FrameClock clock() {
        return CLOCK;
    }

    private static void run(RenderTarget nativeDest, float nearPlane, float farPlane, float fovDegrees,
            Vector2f jitterNdc) {
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        if (device == null) {
            throw new IllegalStateException("no Vulkan device (GL backend?)");
        }
        // Render-res depth/motion, reused from the upscale pass this same frame -- the interpolator's
        // input dims describe THESE textures (Apple's convention, mirroring MTLFXTemporalScaler), not
        // the native output. Reachable only after a successful MetalFxUpscalePass.runIfEnabled this
        // frame (the mixin seam's coupling invariant), so these should never be null in practice; the
        // null check is defense against creating the interpolator with stale/absent input dims rather
        // than a state this pass expects to hit.
        InteropImage depthInterop = MetalFxUpscalePass.depthInterop();
        InteropImage motionInterop = MetalFxUpscalePass.motionInterop();
        if (depthInterop == null || motionInterop == null) {
            return;
        }
        VulkanGpuTexture nativeTex = (VulkanGpuTexture) nativeDest.getColorTextureView().texture();
        int outW = nativeTex.getWidth(0);
        int outH = nativeTex.getHeight(0);
        int inW = depthInterop.width;
        int inH = depthInterop.height;
        ensureResources(device, inW, inH, outW, outH);

        VulkanCommandEncoder encoder = device.createCommandEncoder();
        long v = timelineValue;

        // ---- Vulkan: copy this frame's native color into the ring's `curColor` slot ----
        VulkanMetalInterop.CmdRecorder copyIn = cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanMetalInterop.prepareGeneralTransferRead(
                        cmd, stack, nativeTex.vkImage(), VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                VulkanMetalInterop.prepareInteropTransferWrite(cmd, stack, curColor);
                // First-use (and post-resize) transition for the output image, mirroring
                // MetalFxUpscalePass's own prepareInteropMetalWrite(output) -- without this,
                // `generated` stays UNDEFINED until its first copy-out and the driver is free to
                // discard whatever Metal just wrote instead of preserving it for that copy.
                VulkanMetalInterop.prepareInteropMetalWrite(cmd, stack, generated);
                VulkanMetalInterop.copyImage(cmd, stack,
                        nativeTex.vkImage(), VK13.VK_IMAGE_LAYOUT_GENERAL,
                        curColor.image, curColor.layout,
                        VK13.VK_IMAGE_ASPECT_COLOR_BIT, outW, outH);
                VulkanMetalInterop.finishInteropTransferWrite(cmd, stack, curColor);
            }
        };
        VulkanMetalInterop.recordIntoStream(encoder, copyIn);
        encoder.signalSemaphore(timeline.vkSemaphore, v, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
        encoder.submit();
        lastVulkanSignal = v;
        timelineValue = v + 1;

        // ---- Metal: interpolate between prevColor/curColor once history + a valid deltaTime exist ----
        generatedReady = false;
        if (hasHistory && CLOCK.ready()) {
            // Motion scale stays render-res -- the motion texture's own texel space, independent of
            // the interpolator's separately-tracked input/output dims above.
            MetalFxConventions.motionScale(motionInterop.width, motionInterop.height, MV_FLIP, MOTION_SCALE);
            float mvScaleX = MOTION_SCALE.x;
            float mvScaleY = MOTION_SCALE.y;
            // Interpolator motion convention A/B-pinned OPPOSITE the scaler's on live hardware
            // 2026-07-24 (same pinning method as fornax.metalfx.mvFlip historically): default now
            // flips to the positive-dims sign (negating the scaler-style baseline computed above);
            // FRAMEGEN_MV_FLIP=true flips BACK to that scaler-style negative-dims convention. See the
            // field's own declaration comment for the full A/B history.
            if (!FRAMEGEN_MV_FLIP) {
                mvScaleX = -mvScaleX;
                mvScaleY = -mvScaleY;
            }
            // Jitter pixels: SAME conversion, SAME flip knobs, and the SAME dims basis
            // (depthInterop.width/height, i.e. inW/inH -- the render-res depth/motion textures this
            // frame reused from MetalFxUpscalePass, which itself computed inW/inH from that same
            // colorTex extent) that MetalFxUpscalePass.run used to jitter MTLFXTemporalScaler this
            // same frame. depthInterop is the interpolator's own depth input (see this class's
            // header), so this MUST match the upscale pass's own jitterPixels call bit-for-bit or the
            // interpolator misaligns against the jittered samples it actually receives.
            MetalFxConventions.jitterPixels(jitterNdc, inW, inH,
                    MetalFxUpscalePass.JITTER_FLIP_X, MetalFxUpscalePass.JITTER_FLIP_Y, JITTER_PIXELS);
            float jitterX = JITTER_PIXELS.x;
            float jitterY = JITTER_PIXELS.y;
            if (FRAMEGEN_JITTER_FLIP) {
                jitterX = -jitterX;
                jitterY = -jitterY;
            }

            if (!loggedConventionsOnce) {
                loggedConventionsOnce = true;
                // Sign coefficients (not per-frame pixel values, which move with jitterNdc every
                // frame): x's baseline coefficient is +1, y's is -1 (the NDC-up to texture-down flip,
                // see MetalFxConventions#jitterPixels), each then toggled by the scaler's own
                // JITTER_FLIP_X/Y and this pass's own FRAMEGEN_JITTER_FLIP in turn -- so the printed
                // sign is exactly what this frame's jitterX/jitterY above were actually multiplied by.
                float jitterSignX = (MetalFxUpscalePass.JITTER_FLIP_X ? -1f : 1f)
                        * (FRAMEGEN_JITTER_FLIP ? -1f : 1f);
                float jitterSignY = (MetalFxUpscalePass.JITTER_FLIP_Y ? 1f : -1f)
                        * (FRAMEGEN_JITTER_FLIP ? -1f : 1f);
                FornaxMod.LOGGER.info(
                        "[Fornax] framegen conventions: mvScale=({},{}) jitter=({},{}) "
                                + "[flips: mv={} jitter={}]",
                        mvScaleX, mvScaleY, jitterSignX >= 0 ? "+" : "-", jitterSignY >= 0 ? "+" : "-",
                        FRAMEGEN_MV_FLIP, FRAMEGEN_JITTER_FLIP);
            }

            long genValue = v + 1;
            long pool = Objc.autoreleasePoolPush();
            try {
                long cb = Objc.msgSendId(
                        VulkanMetalInterop.metalCommandQueue(), Objc.selector("commandBuffer"));
                if (cb == 0) {
                    throw new IllegalStateException("Metal command buffer nil");
                }
                Objc.msgSendVoidIdLong(cb, Objc.selector("encodeWaitForEvent:value:"),
                        timeline.mtlSharedEvent, v);
                // Aspect ratio is the color ring's own (native output) dims -- not the render-res
                // depth/motion textures' -- since it describes the CAMERA's projection, matching how
                // nearPlane/farPlane/fovDegrees describe that same projection rather than either
                // texture's own basis.
                float aspectRatio = (float) outW / (float) outH;
                interpolator.encode(cb, prevColor.mtlTexture, curColor.mtlTexture,
                        depthInterop.mtlTexture, motionInterop.mtlTexture, generated.mtlTexture,
                        jitterX, jitterY, CLOCK.deltaTimeSeconds(), mvScaleX, mvScaleY, pendingReset,
                        true, nearPlane, farPlane, fovDegrees, aspectRatio);
                Objc.msgSendVoidIdLong(cb, Objc.selector("encodeSignalEvent:value:"),
                        timeline.mtlSharedEvent, genValue);
                Objc.msgSendVoid(cb, Objc.selector("commit"));
                pendingReset = false;
            } finally {
                Objc.autoreleasePoolPop(pool);
            }
            generatedSignalValue = genValue;
            generatedReady = true;
            lastVulkanSignal = genValue;
            timelineValue = genValue + 1;
        }

        // ---- Pointer swap: this frame's color becomes `prev` for the next frame's ring position ----
        InteropImage swap = prevColor;
        prevColor = curColor;
        curColor = swap;
        hasHistory = true;
    }

    /**
     * {@code inW}/{@code inH} are the render-res depth/motion textures' size (the interpolator's
     * configured INPUT dims); {@code outW}/{@code outH} are the native color ring's size (its
     * configured OUTPUT dims). Recreates the whole resource set -- color ring images AND the
     * interpolator -- when EITHER pair changes, mirroring how MetalFxUpscalePass rebuilds its whole
     * set together rather than tracking each image's size independently.
     */
    private static void ensureResources(VulkanDevice device, int inW, int inH, int outW, int outH) {
        boolean sizesMatch = curColor != null && curColor.width == outW && curColor.height == outH
                && interpolator != null && interpolatorInputW == inW && interpolatorInputH == inH;
        if (sizesMatch) {
            return;
        }
        if (timeline == null) {
            timeline = VulkanMetalInterop.createSharedTimeline(device);
        }
        if (interpolator != null) {
            // Resize teardown: one bounded timeline wait quiesces the whole outgoing resource set,
            // same discipline as MetalFxUpscalePass -- never vkDeviceWaitIdle.
            if (lastVulkanSignal > 0) {
                VulkanMetalInterop.waitTimeline(device, timeline, lastVulkanSignal);
            }
            interpolator.release();
            interpolator = null;
        }
        VulkanMetalInterop.destroyImage(device, prevColor);
        VulkanMetalInterop.destroyImage(device, curColor);
        VulkanMetalInterop.destroyImage(device, generated);

        int colorUsage = VK13.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK13.VK_IMAGE_USAGE_SAMPLED_BIT;
        prevColor = VulkanMetalInterop.createImage(device, outW, outH,
                VK13.VK_FORMAT_R8G8B8A8_UNORM, colorUsage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        curColor = VulkanMetalInterop.createImage(device, outW, outH,
                VK13.VK_FORMAT_R8G8B8A8_UNORM, colorUsage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        // `generated` covers every MTLTextureUsage MetalFX may validate plus TRANSFER_SRC for its
        // own copy-back, mirroring MetalFxUpscalePass's `output` image.
        generated = VulkanMetalInterop.createImage(device, outW, outH,
                VK13.VK_FORMAT_R8G8B8A8_UNORM,
                VK13.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK13.VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK13.VK_IMAGE_USAGE_STORAGE_BIT | VK13.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                VK13.VK_IMAGE_ASPECT_COLOR_BIT);

        interpolator = MetalFxFrameInterpolator.create(MetalFxSupport.metalDevice(),
                inW, inH, outW, outH,
                MetalFxScaler.PIXEL_FORMAT_RGBA8_UNORM, MetalFxScaler.PIXEL_FORMAT_DEPTH32_FLOAT,
                MetalFxScaler.PIXEL_FORMAT_RG16_FLOAT, MetalFxScaler.PIXEL_FORMAT_RGBA8_UNORM);
        if (interpolator == null) {
            throw new IllegalStateException("MTLFXFrameInterpolator creation refused ("
                    + inW + "x" + inH + " -> " + outW + "x" + outH + ")");
        }
        interpolatorInputW = inW;
        interpolatorInputH = inH;
        pendingReset = true;
        hasHistory = false;
        generatedReady = false;
        CLOCK.reset();
    }
}
