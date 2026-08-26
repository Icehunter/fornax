package dev.icehunter.fornax.pass;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.metalfx.FrameGenPass;
import dev.icehunter.fornax.pass.ssaa.SsaaManager;
import dev.icehunter.fornax.pipeline.FrameGenPacer;
import dev.icehunter.fornax.util.GpuFatalErrors;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * The double-present seam: whenever this frame produced a MetalFX-generated (interpolated) image
 * ({@link FrameGenPass#generatedFrameReady()}), presents it through the SAME {@code GpuSurface}
 * vanilla's own frame is about to present through, then hands vanilla a freshly acquired image for
 * its own real frame -- so the swapchain sees generated frame, then real frame, in that order every
 * armed frame.
 *
 * <p><b>Two-phase, split across the frame ({@code prepareGeneratedFrame} then {@code
 * presentGeneratedIfReady}):</b> staging content is assembled from {@code
 * GuiRendererCaptureMixin}'s HUD-capture wrap, not from this class's own present-time injection
 * site, because the sky fill (see {@link FrameGenSkyFillPass}) needs a SCENE-ONLY (HUD-free) native
 * color to fill sky/far-depth pixels with. At present-seam time, {@code GuiRendererCaptureMixin} has
 * ALREADY composited the captured HUD onto {@code GameRenderer.mainRenderTarget()} for the real
 * frame -- sampling that HUD-contaminated target would copy HUD pixels over sky-depth into {@code
 * staging}, which {@link UiLayerCapture#compositeOnto} then blends a SECOND time on top when
 * stamping the generated frame's own HUD, double-alpha-ing every translucent HUD edge over sky on
 * every generated frame. {@code GuiRendererCaptureMixin}'s HUD-capture wrap is the one place in the
 * frame where a scene-only main target is naturally available (its own {@code realTarget} local,
 * captured before its own HUD composite runs), which is where {@link #prepareGeneratedFrame} runs.
 * See that method's own header for why recording {@link FrameGenPass#copyGeneratedInto} at this
 * earlier point is still GPU-correct despite the interpolator being encoded even earlier, in {@code
 * renderLevel}.
 *
 * <p><b>Site:</b> {@code PresentSeamMixin} calls {@link #presentGeneratedIfReady} from inside
 * {@code Minecraft.renderFrame(boolean)}, immediately BEFORE vanilla's own {@code
 * windowSurface.blitFromTexture(...)} call in the method's late {@code "present"} profiler section
 * -- NOT before vanilla's early {@code acquireNextTexture()} (see below for why the early site is
 * wrong). At this late
 * site, {@code windowSurface} is already acquired (vanilla's own {@code acquireNextTexture()} ran
 * earlier this same {@code renderFrame} call, before any rendering), and -- critically -- this
 * frame's own {@link FrameGenPass#run} has ALSO already happened by now (it runs from
 * {@code GameRenderer.render() -> renderLevel()}, which is itself called from deep inside
 * {@code renderFrame} between the early acquire and this late present section), so
 * {@code generatedFrameReady()} here reflects the interpolation between THIS frame's native color
 * and the PREVIOUS frame's ({@code G(N-1,N)}), not last frame's leftover result.
 *
 * <p><b>Why the early site was wrong:</b> injecting before vanilla's acquire (offset ~210 in the
 * deobf jar's {@code renderFrame} bytecode) runs before this same invocation's own
 * {@code GameRenderer.render()} (offset ~520) has produced this frame's generated image --
 * {@code generatedFrameReady()} there could only ever reflect the PREVIOUS invocation's result,
 * {@code G(N-2,N-1)}. Presenting that stale image immediately before real frame N gives presented
 * scene-times {@code ..., N-1.5, N, N-0.5, N+1, N+0.5, ...} -- each generated frame lands AFTER the
 * real frame whose motion it predates, an oscillating backward step every other present (motion
 * judder), not smoothing. The late site fixes this: {@code G(N-1,N)} (time {@code N-0.5}) presents
 * BEFORE real frame N (time {@code N}), giving a monotonic {@code ..., N-1, N-0.5, N, N+0.5, N+1,
 * ...}.
 *
 * <p><b>Why the late site is safe against {@code GpuSurface}'s single-acquire-slot invariant:</b>
 * {@code GpuSurface} (javap'd directly) tracks state with two private booleans, {@code
 * hasImageAcquired} and {@code hasBlittedTexture} -- exactly one outstanding acquire, exactly one
 * blit per acquire cycle, no per-call handle. This injection sits INSIDE vanilla's own
 * {@code isAcquired()}-guarded branch (vanilla's own check, earlier in the "present" section,
 * already passed by the time execution reaches this call site), so on entry: acquired, not yet
 * blitted. This method blits G into THAT already-acquired image (never touching vanilla's own
 * acquire), presents it (clearing the acquired flag), then reacquires a FRESH image -- leaving the
 * surface in exactly the state vanilla's own immediately-following (untouched) {@code
 * blitFromTexture}/{@code present} calls expect: acquired, not yet blitted, ready for the real
 * frame. Never two outstanding acquires, never a second blit on one acquire cycle.
 *
 * <p>Gated on {@link GpuSurface.Configuration#presentMode()} being {@code FIFO}/{@code
 * FIFO_RELAXED} (vsync-paced): frame generation only makes sense self-paced against the compositor's
 * own cadence -- under {@code IMMEDIATE}/{@code MAILBOX} (vsync off) doubling the present count just
 * doubles submitted-but-uncapped frames with no visible smoothing benefit, so this no-ops with one
 * logged line (logged once total, not once per frame) rather than presenting there too. This check
 * is applied TWICE: once in {@link #prepareGeneratedFrame} (so a vsync-off session skips the whole
 * staging pipeline -- interop copy plus two fullscreen passes -- instead of building it every frame
 * only to discard it here) and again in {@link #presentGeneratedIfReady} itself as a cheap second
 * guard against the surface configuration changing between the two calls.
 *
 * <p>The staging target is a lazily-built/resized native-resolution RGBA8 {@link MainTarget}, sized
 * off {@code SsaaManager.nativeWidth/Height} -- the SAME native resolution as the {@code generated}
 * ring image {@link FrameGenPass#copyGeneratedInto} copies in (an unscaled {@code vkCmdCopyImage})
 * and the {@code uiTarget} {@link UiLayerCapture#compositeOnto} composites on. It must NOT be sized
 * off the surface's own {@code Configuration}: on a Retina surface that is the swapchain image size
 * (2x native), which would leave the unscaled generated copy filling only a native-sized sub-rect
 * and stretch the HUD composite 2x into an oversized, corner-displaced HUD.
 * {@code surface.blitFromTexture} below scales this native staging up to the swapchain exactly as
 * vanilla's own real-frame blit scales the native {@code mainRenderTarget} up, so the two present
 * paths are size-symmetric.
 *
 * <p>Vanilla's own present contract, confirmed by disassembling {@code Minecraft.renderFrame}
 * itself (not assumed from the {@code GpuSurface} API alone), is {@code blitFromTexture} (offset
 * ~622) -> {@code CommandEncoder.submit()} (offset ~689) -> {@code present()} (offset ~706): both
 * {@code GpuSurface.blitFromTexture} and {@code GpuSurface.present} only STAGE work on the encoder
 * (bytecode-verified -- neither one submits), so this seam's own generated-frame cycle calls
 * {@code encoder.submit()} between its own blit and present too, mirroring vanilla exactly --
 * omitting it leaves the generated present's semaphore signal never actually submitted to the
 * queue, stalling {@code vkQueuePresentKHR}'s wait indefinitely and crashing with a
 * {@code VK_TIMEOUT}/device-lost error.
 *
 * <p><b>Exception safety, phase by phase</b> (each of {@code GpuSurface}'s three mutating calls only
 * flips its internal flag(s) AFTER its backend call succeeds -- bytecode-verified, not assumed):
 * <ul>
 *   <li><b>{@link #prepareGeneratedFrame} failure</b> (earlier in the frame, nowhere near the
 *   surface): reported via {@link FrameGenPass#markFailed} and leaves {@link #stagingPrepared}
 *   false; {@link #presentGeneratedIfReady} then sees unprepared staging and no-ops for the whole
 *   frame, exactly as if generation were disarmed -- vanilla's real frame is entirely unaffected.
 *   <li><b>{@code blitFromTexture(G)}/{@code encoder.submit()} failure</b> (grouped in one
 *   try/catch): a {@code blitFromTexture} throw itself leaves {@code hasBlittedTexture} false (the
 *   flag only flips after the backend call returns) -- surface still acquired-not-blitted, vanilla's
 *   own blit/present run normally. A {@code submit()} throw AFTER a successful {@code
 *   blitFromTexture} is a DIFFERENT case, even though it shares this catch: {@code
 *   blitFromTexture} already flipped {@code hasBlittedTexture} true (unconditionally, before
 *   {@code submit()} is even reached -- {@code CommandEncoder} is a separate object from {@code
 *   GpuSurface} and {@code submit()} cannot un-set it), leaving the identical
 *   {@code hasImageAcquired=true}/{@code hasBlittedTexture=true} state the {@code present()} failure
 *   case below produces -- NOT the safe "vanilla proceeds normally" outcome the blit-only failure
 *   gets. Grouping them in one catch is purely organizational (same corrective action either way:
 *   {@code markFailed} and return); the two sub-cases are NOT equivalent in downstream risk, and this
 *   is documented here precisely so a future reader does not assume otherwise.
 *   <li><b>{@code present()} failure</b> (after a successful blit): {@code hasImageAcquired} and
 *   {@code hasBlittedTexture} are BOTH stuck true, with no {@code GpuSurface} API to reset either
 *   short of a present/acquire that just failed. Deliberately NOT recovered: vanilla's own untouched
 *   {@code blitFromTexture} call, which runs unconditionally immediately after this method returns
 *   (no per-call {@code isAcquired()} guard at that call site -- the guard vanilla checked was
 *   earlier, before this whole "present" section), will itself throw {@code "Already blitted to this
 *   frame!"}, uncaught -- {@code Minecraft.renderFrame}'s own exception table has no handler over
 *   this region at all, so vanilla's OWN blit/present calls already carry this exact same
 *   unguarded-backend-failure risk with zero framegen involved. Swallowing this and attempting a
 *   synthetic recovery would trade one loud, log-preceded crash for a silent, permanent render-loop
 *   freeze on every following frame ({@code renderFrame}'s own HEAD {@code isAcquired()} guard would
 *   early-return forever, since nothing else in {@code Minecraft} ever calls {@code present()} to
 *   clear it) -- the strictly worse outcome by this engine's own standing principle (a wedged render
 *   loop beats nothing; a wedged GPU can take the whole OS down on macOS -- see
 *   {@code VoxelDebugRaymarchPass}'s own history).
 *   <li><b>{@code acquireNextTexture()} (reacquire) failure</b> (after a successful blit+present of
 *   G): {@code hasImageAcquired} stays false. Also NOT recovered, for the identical reason: vanilla's
 *   untouched {@code blitFromTexture} call runs unconditionally next and throws {@code "Cannot
 *   present to an unacquired surface"}, uncaught, in the same unguarded region. Unlike the
 *   {@code present()} case above there is no LATENT freeze risk here specifically (the surface ends
 *   up fully unacquired, not stuck acquired) -- but recovering it safely would still require
 *   cancelling the rest of {@code renderFrame} mid-method, after its {@code "present"} profiler
 *   section has already been pushed with no matching pop reachable from a cancel at this site, so
 *   this is left uncancelled too rather than trading a proven-safe crash for an unverified profiler
 *   stack corruption.
 * </ul>
 * Every one of these throws is reported through {@link FrameGenPass#markFailed} first (fail-closed
 * for the rest of the session, like every other MetalFX path, so a crash-and-relaunch never re-hits
 * the same fault) and none of them can double-acquire or touch vanilla's own tracked index --
 * {@code GpuSurface.acquireNextTexture()} itself throws {@code IllegalStateException} if ever called
 * while already acquired, so even a logic bug here cannot silently corrupt that invariant.
 */
public final class FrameGenPresenter {
    @Nullable
    private static RenderTarget stagingTarget;
    private static int stagingWidth;
    private static int stagingHeight;
    private static boolean loggedNonFifoSkip;
    private static boolean loggedMissingResourcesSkip;

    // True only between a successful prepareGeneratedFrame() and the present seam consuming it --
    // cleared on every exit path of both methods (including every skip/failure branch) plus
    // deactivate(), so a stale true from a previous frame (or a previous armed session) can never
    // survive into a frame that never re-prepared staging.
    private static boolean stagingPrepared;

    // Cadence instrumentation: the actual console line in maybeLogCadence prints only behind
    // -Dfornax.framegen.log=true, but the underlying counting/windowing also runs whenever
    // FrameGenPass.armed() is true (see recordSkip/recordPresented) -- ProfilerOverlay's own
    // "FrameGen: ..." row (overlayLine(), profile package) reads the SAME per-second rates this
    // window computes, and needs them live even for players who never set the dev log property.
    // For the overwhelmingly common disarmed case
    // (no MetalFX / framegen off) this is still a single static boolean check per present-seam call
    // with no allocation, no extra branching, and no windowing work at all -- the cost only applies
    // to sessions that already pay far more for MetalFX itself.
    private static final boolean LOG_ENABLED = Boolean.getBoolean("fornax.framegen.log");
    private static final long LOG_INTERVAL_NANOS = 5_000_000_000L; // 5 seconds

    // Latest per-second rates from the last completed cadence window (see maybeLogCadence) --
    // refreshed at most once per LOG_INTERVAL_NANOS, same cadence as the optional console line.
    // Read by overlayLine() for ProfilerOverlay; this is the "small shared holder" so the overlay
    // and the dev cadence log can never disagree, and neither one runs a second independent
    // measurement.
    private static double lastRenderedFps;
    private static double lastGeneratedFps;

    // HUD-path isolation (-Dfornax.framegen.uiDebug=true): keeps GuiRendererCaptureMixin's own
    // capture-into-uiTarget + composite-onto-the-real-frame roundtrip live (it is gated only on
    // FrameGenPass.generatedFrameReady(), untouched by this flag), but makes the double-present seam
    // a no-op so NO generated frame is ever presented. The user then sees only real frames whose HUD
    // went through the capture/composite roundtrip -- which must be pixel-identical to vanilla,
    // isolating the HUD path from the (separately verified) generated-present path.
    private static final boolean UI_DEBUG = Boolean.getBoolean("fornax.framegen.uiDebug");

    private static long logIntervalStartNanos;
    private static long presentedCount;
    private static long notFifoSkips;
    private static long notReadySkips;
    private static long failedSkips;

    // Adaptive-pacing instrumentation: engaged/disengaged frame tallies are OWNED and incremented by
    // FrameGenPacer itself (see that class's header for why it is the sole writer of the engagement
    // decision -- FrameGenPass.runIfEnabled is the only caller of FrameGenPacer#update, once per
    // armed frame); this class only READS the running totals here to print and reset them on the
    // same 5s cadence as every other counter above. loggedPacingThresholdsOnce follows the identical
    // one-time pattern as loggedNonFifoSkip/FrameGenPass's own loggedConventionsOnce -- the threshold
    // VALUES don't change frame to frame, so they are logged once total, not on every cadence line.
    private static boolean loggedPacingThresholdsOnce;

    private enum SkipReason { NOT_FIFO, NOT_READY, FAILED }

    private FrameGenPresenter() {
    }

    /**
     * Assembles this frame's generated-image staging content -- called from {@code
     * GuiRendererCaptureMixin}'s HUD-capture wrap, AFTER vanilla's HUD draw has been captured into
     * {@link UiLayerCapture}'s off-screen target but BEFORE that mixin composites the same capture
     * onto {@code GameRenderer.mainRenderTarget()} for the real frame. This is the earliest (and,
     * short of adding a new injection, ONLY) point in the frame where a SCENE-ONLY native color is
     * naturally available: {@code sceneOnlyMain} is the caller's own {@code realTarget} local --
     * exactly {@code mainRenderTarget} as {@code renderLevel} left it, before any HUD pixel has
     * touched it -- which {@link FrameGenSkyFillPass} needs as its real-color fill source (see this
     * class's own header for the double-alpha bug this fixes, from an earlier revision that sampled
     * the present-time, already-HUD-composited {@code mainRenderTarget} instead). {@code gbufferDepthView}
     * is the caller's own {@code GBufferManager.getInstance().getDepthView()} -- the same render-res,
     * reversed-Z depth this frame's {@code MetalFxFrameInterpolator} consumed. {@code sceneDepthView}
     * (the render-res off-screen target's OWN depth, from {@code MetalFxUpscalePass.sceneDepthView()})
     * and {@code motionView} ({@code GBufferManager.getMotionView()}) are the two extra inputs the
     * unified fill's responsive-pixel and edge-disocclusion classes need -- see {@code
     * framegen_sky_fill.fsh}'s own header for what each class does with them.
     *
     * <p>Recording {@link FrameGenPass#copyGeneratedInto} here, earlier than the present seam used
     * to, is still GPU-correct even though {@code MTLFXFrameInterpolator.encodeToCommandBuffer:} was
     * itself recorded even earlier, back in {@code renderLevel}: {@code copyGeneratedInto}'s Vulkan
     * copy waits on a timeline SEMAPHORE VALUE ({@code FrameGenPass}'s own {@code SharedTimeline}),
     * which the GPU satisfies whenever it actually reaches that point in its own command stream --
     * there is no host-side ordering requirement here, only the GPU-side one the semaphore wait
     * already enforces regardless of how early the waiting command buffer was itself submitted.
     *
     * <p>Sets {@link #stagingPrepared} true only on full success (copy + sky-fill + UI composite all
     * completed without throwing); any failure anywhere in this method is reported via {@link
     * FrameGenPass#markFailed} and leaves {@link #stagingPrepared} false, so {@link
     * #presentGeneratedIfReady} sees unprepared staging later this same frame and no-ops entirely --
     * vanilla presents its one real frame exactly as if generation were off, never risking a
     * stale/undefined staging blit.
     *
     * <p>Gated on the SAME FIFO/FIFO_RELAXED check {@link #presentGeneratedIfReady} itself applies
     * (hoisted here from that method): without it, a vsync-off session with generation armed ran the
     * whole staging pipeline (the interop copy plus two fullscreen passes) every single frame only to
     * have {@link #presentGeneratedIfReady}'s own not-FIFO skip throw the result away unused -- wasted
     * GPU work the old single-phase seam never did (it skipped the FIFO check before doing any of this
     * work). {@code windowSurface()} is fetched directly ({@code Minecraft.getInstance()}, a public
     * getter) rather than threaded through as a parameter, since this method is called from {@code
     * GuiRendererCaptureMixin} (a {@code GameRenderer} mixin with no surface reference of its own).
     */
    public static void prepareGeneratedFrame(RenderTarget sceneOnlyMain, GpuTextureView gbufferDepthView,
            GpuTextureView sceneDepthView, GpuTextureView motionView) {
        stagingPrepared = false;
        // Frame generation must not prepare anything unless it is actually ARMED -- MetalFX selected
        // as the AA method, frame interpolation available, no prior failure. FrameGenPass.armed()
        // is exactly that test, and this method did not ask it: it checked vsync and then went on to
        // size a native-resolution staging target and open command encoders for a frame that could
        // never be presented.
        //
        // It never did run unguarded, but only by accident. The caller (GuiRendererCaptureMixin)
        // skips this call when MetalFxUpscalePass.sceneDepthView() is null, which under any
        // non-MetalFX AA method it is -- verified live: a TAA session logs zero "(Re)built frame-gen
        // present staging target" lines across an entire run. So the real gate was a null check in
        // another class, protecting this one as a side effect of what it was written to test.
        // Anything that makes that view non-null while MetalFX is not driving -- a fallback path,
        // another upscaler, MetalFX arming and then failing mid-session -- and this whole path runs
        // every frame for output that is discarded.
        //
        // The condition belongs next to the work it governs, not inferred from a caller's unrelated
        // null check.
        if (!FrameGenPass.armed()) {
            return;
        }
        try {
            Optional<GpuSurface.Configuration> config =
                    Minecraft.getInstance().windowSurface().currentConfiguration();
            if (config.isEmpty() || !isVsyncFifo(config.get().presentMode())) {
                logOnceNotFifo(config.isEmpty());
                recordSkip(SkipReason.NOT_FIFO);
                return;
            }
            // Native resolution -- NOT the surface/swapchain config size. See this class's own
            // header (staging-target paragraph) for why: everything written into staging is
            // native-res, and sizing it to the (possibly 2x, Retina) swapchain image would stretch
            // the composited HUD 2x into a corner.
            RenderTarget staging = ensureStagingTarget(SsaaManager.nativeWidth(), SsaaManager.nativeHeight());
            if (!FrameGenPass.copyGeneratedInto(staging)) {
                return;
            }
            // Sky-fill and the UI composite below each open their OWN CommandEncoder/RenderPass
            // (RenderSystem.getDevice().createCommandEncoder(), same as UiLayerCapture/
            // MetalFxReactiveMaskPass), separate from copyGeneratedInto's own encoder just above --
            // but all three share the render thread's single Vulkan queue (RenderSystem's own
            // VulkanDevice/VulkanQueue), so MoltenVK's per-queue hazard tracking orders these
            // same-queue submissions against each other by submission order alone: staging's copy-in
            // is guaranteed visible to the sky fill's read and the UI composite's blend with no
            // explicit cross-submission semaphore needed here, unlike the Vulkan<->Metal interop
            // boundary elsewhere in this pipeline (which DOES need one -- see SharedTimeline). The
            // present seam's later blit of this same `staging` target relies on the identical
            // same-queue guarantee.
            FrameGenSkyFillPass.compositeOnto(staging, gbufferDepthView, sceneDepthView, motionView,
                    sceneOnlyMain.getColorTextureView());
            if (UiLayerCapture.activeThisFrame()) {
                UiLayerCapture.compositeOnto(staging);
            }
            stagingPrepared = true;
        } catch (Throwable t) {
            // Fatal rethrows: copyGeneratedInto and the sky-fill/UI composites below it all record
            // and submit real GPU work on the same shared queue -- see GpuFatalErrors' own doc for
            // why a dead device must not be swallowed into the ordinary soft-fail path here.
            GpuFatalErrors.rethrowIfFatal(t);
            FrameGenPass.markFailed("present seam (prepare G)", t);
        }
    }

    public static void presentGeneratedIfReady(GpuSurface surface, CommandEncoder encoder) {
        if (UI_DEBUG) {
            // HUD-path isolation: present exactly one (real) frame per renderFrame, as vanilla does.
            // GuiRendererCaptureMixin still ran its capture+composite this frame (gated on
            // generatedFrameReady(), not this flag), so the on-screen result exercises the full HUD
            // roundtrip minus any generated present -- it must look pixel-identical to vanilla.
            // stagingPrepared is cleared here too, same as every other exit path of this method (see
            // that field's own doc comment): prepareGeneratedFrame() already built staging content
            // this frame that UI_DEBUG is choosing never to consume, so the flag must not survive
            // into the next frame as a stale true.
            stagingPrepared = false;
            return;
        }
        if (!FrameGenPass.generatedFrameReady() || !stagingPrepared) {
            recordSkip(SkipReason.NOT_READY);
            stagingPrepared = false;
            return;
        }

        // Cheap second guard: prepareGeneratedFrame already checked FIFO before doing any staging
        // work, but the surface configuration could theoretically change between that earlier
        // (HUD-capture-time) check and this present-time one, so this stays rather than trusting the
        // earlier result blindly.
        Optional<GpuSurface.Configuration> config = surface.currentConfiguration();
        if (config.isEmpty() || !isVsyncFifo(config.get().presentMode())) {
            logOnceNotFifo(config.isEmpty());
            recordSkip(SkipReason.NOT_FIFO);
            stagingPrepared = false;
            return;
        }

        // Prepared earlier this frame by prepareGeneratedFrame() -- guaranteed non-null here since
        // stagingPrepared is only ever set true right after ensureStagingTarget() populated it.
        RenderTarget staging = stagingTarget;

        // This injection runs INSIDE vanilla's own isAcquired()-guarded branch, immediately before
        // vanilla's own (untouched) blitFromTexture call -- windowSurface is guaranteed
        // acquired-but-not-yet-blitted here. Blit G into THAT already-acquired image, SUBMIT the
        // recorded command buffer (blitFromTexture/present only STAGE work on the encoder --
        // bytecode-verified neither one submits; vanilla's own sequence is
        // blitFromTexture -> CommandEncoder.submit() -> present(), and skipping the submit here
        // left the generated present's semaphore signal never actually submitted to the queue,
        // so vkQueuePresentKHR's wait stalled forever -- a live-reproduced VK_TIMEOUT/device-lost
        // crash this fixes), then present it, then reacquire a fresh image so vanilla's own
        // blit/present that immediately follow draw and present the real frame into the NEW
        // image: generated presents, then real, in order.
        try {
            surface.blitFromTexture(encoder, staging.getColorTextureView());
            encoder.submit();
        } catch (Throwable t) {
            // Fatal (GpuDeviceLossException, or a VulkanMetalInterop GpuFatalException) rethrows
            // here rather than falling into the same soft-degrade path as an ordinary failure --
            // see GpuFatalErrors' own doc for why: swallowing it hands vanilla's own blit/submit/
            // present, which runs immediately after this injection point, the same dead device.
            GpuFatalErrors.rethrowIfFatal(t);
            FrameGenPass.markFailed("present seam (blit G)", t);
            recordSkip(SkipReason.FAILED);
            stagingPrepared = false;
            return;
        }

        try {
            surface.present();
        } catch (Throwable t) {
            GpuFatalErrors.rethrowIfFatal(t);
            FrameGenPass.markFailed("present seam (present G)", t);
            recordSkip(SkipReason.FAILED);
            stagingPrepared = false;
            return;
        }
        recordPresented();

        try {
            surface.acquireNextTexture();
        } catch (Throwable t) {
            // Rethrown when fatal: a failed reacquire here means vanilla's own blitFromTexture/
            // present for the real frame, which runs immediately after this injection point with
            // no image acquired, must not run either -- propagating aborts the rest of this
            // renderFrame call instead of letting vanilla draw into nothing (the exact seam
            // documented above this method as a live-reproduced device-lost crash).
            GpuFatalErrors.rethrowIfFatal(t);
            FrameGenPass.markFailed("present seam (reacquire)", t);
            recordSkip(SkipReason.FAILED);
        }
        stagingPrepared = false;
        // Vanilla's own blitFromTexture/present for the REAL frame follow immediately after this
        // injection point runs, completely untouched -- see PresentSeamMixin.
    }

    /**
     * Named log-once skip (never per frame) for {@code GuiRendererCaptureMixin}'s own {@code
     * GBufferManager.getInstance()}/{@code MetalFxUpscalePass.sceneDepthView()} null guard, called
     * from that mixin's guard's else-branch instead of letting a violation surface only as a
     * catch-all NPE further down the call chain. That guard's own doc comment already argues the
     * null case should be unreachable in practice ({@code generatedFrameReady()} true implies a
     * successful {@code MetalFxUpscalePass} ran this frame, which requires a live {@code GBuffer}),
     * but "should never happen" is exactly the case worth a named, findable log line instead of
     * silent degradation (that frame's staging is simply never prepared -- the present seam then
     * sees unprepared staging and no-ops, so vanilla's real frame is unaffected either way).
     */
    public static void logOnceMissingGBufferResources() {
        if (loggedMissingResourcesSkip) {
            return;
        }
        loggedMissingResourcesSkip = true;
        FornaxMod.LOGGER.warn(
                "[Fornax] Frame generation staging skipped: GBuffer/scene depth unavailable despite a generated frame being ready this frame");
    }

    /** Releases the staging target; safe to call anytime (config-off transitions, shutdown). */
    public static void deactivate() {
        if (stagingTarget != null) {
            stagingTarget.destroyBuffers();
            stagingTarget = null;
            stagingWidth = 0;
            stagingHeight = 0;
        }
        stagingPrepared = false;
    }

    /**
     * Releases every frame-generation resource across all three owning classes; safe to call
     * anytime. The one shared implementation both {@code FornaxSettingsScreen}'s
     * {@code FRAMEGEN_DEACTIVATE} action and {@code GraphRunner.closeCurrent()} call, since frame
     * generation presents every frame regardless of pack state and must deactivate on pack teardown
     * too -- leaving it active through a pack teardown crashes MoltenVK on the next present.
     */
    public static void deactivateAll() {
        FrameGenPass.deactivate();
        UiLayerCapture.deactivate();
        deactivate();
    }

    private static boolean isVsyncFifo(GpuSurface.PresentMode mode) {
        return mode == GpuSurface.PresentMode.FIFO || mode == GpuSurface.PresentMode.FIFO_RELAXED;
    }

    /**
     * Logged once total (never per frame, like every skip reason here). {@code configEmpty}
     * distinguishes the two distinct not-FIFO causes this method is called for: an EMPTY {@code
     * currentConfiguration()} (surface mid-reconfigure/not-yet-configured -- a transient, likely
     * self-resolving state) versus a genuinely present but non-FIFO/FIFO_RELAXED mode (a standing
     * vsync-off session, which will not self-resolve). Conflating the two into one message would
     * make a transient reconfigure window look identical to "vsync is permanently off," misdirecting
     * troubleshooting if the two ever alternated across a session.
     */
    private static void logOnceNotFifo(boolean configEmpty) {
        if (loggedNonFifoSkip) {
            return;
        }
        loggedNonFifoSkip = true;
        if (configEmpty) {
            FornaxMod.LOGGER.info(
                    "[Fornax] Frame generation present skipped: surface configuration unavailable (mid-reconfigure?)");
        } else {
            FornaxMod.LOGGER.info(
                    "[Fornax] Frame generation present skipped: surface present mode is not FIFO/FIFO_RELAXED (vsync required)");
        }
    }

    /**
     * Bumps a skip counter and checks the 5s cadence window. No-op when neither the dev log flag
     * nor an armed session needs the counting -- see {@link #LOG_ENABLED}'s own comment for why
     * an armed session is also checked here, not {@link #LOG_ENABLED} alone.
     */
    private static void recordSkip(SkipReason reason) {
        if (!LOG_ENABLED && !FrameGenPass.armed()) {
            return;
        }
        switch (reason) {
            case NOT_FIFO -> notFifoSkips++;
            case NOT_READY -> notReadySkips++;
            case FAILED -> failedSkips++;
        }
        maybeLogCadence();
    }

    /** Bumps the presented-generated-frame counter and checks the 5s cadence window. */
    private static void recordPresented() {
        if (!LOG_ENABLED && !FrameGenPass.armed()) {
            return;
        }
        presentedCount++;
        maybeLogCadence();
    }

    /**
     * Refreshes {@link #lastRenderedFps}/{@link #lastGeneratedFps} at most once every {@link
     * #LOG_INTERVAL_NANOS} (~5s): rendered fps (from {@link FrameGenPass#clock()}'s EMA-smoothed
     * interval) and generated-frames-presented per second over the elapsed window. This computation
     * always runs (whenever a caller above lets a skip/present event through at all -- see {@link
     * #LOG_ENABLED}'s comment), since {@link #overlayLine()} needs live rates regardless of the dev
     * log flag; only the console line itself, and {@link #logOncePacingThresholds()}, stay gated on
     * {@link #LOG_ENABLED}. Resets all counters (this class's own plus the pacer's) every window
     * regardless of whether the line printed, so the next window's rates aren't inflated by a
     * carried-over count.
     */
    private static void maybeLogCadence() {
        long now = System.nanoTime();
        if (logIntervalStartNanos == 0L) {
            logIntervalStartNanos = now;
            return;
        }
        long elapsed = now - logIntervalStartNanos;
        if (elapsed < LOG_INTERVAL_NANOS) {
            return;
        }

        double seconds = elapsed / 1.0e9;
        long emaIntervalNanos = FrameGenPass.clock().emaIntervalNanos();
        lastRenderedFps = emaIntervalNanos > 0 ? 1.0e9 / emaIntervalNanos : 0.0;
        lastGeneratedFps = presentedCount / seconds;

        if (LOG_ENABLED) {
            logOncePacingThresholds();
            FornaxMod.LOGGER.info(
                    "[Fornax] framegen cadence: rendered={}fps generated={}fps paced={} "
                            + "skips(not-fifo={}, not-ready={}, failed={}) frames(engaged={}, disengaged={})",
                    String.format("%.1f", lastRenderedFps), String.format("%.1f", lastGeneratedFps),
                    FrameGenPacer.engaged() ? "engaged" : "disengaged",
                    notFifoSkips, notReadySkips, failedSkips,
                    FrameGenPacer.engagedFrameCount(), FrameGenPacer.disengagedFrameCount());
        }

        presentedCount = 0;
        notFifoSkips = 0;
        notReadySkips = 0;
        failedSkips = 0;
        FrameGenPacer.resetFrameCounts();
        logIntervalStartNanos = now;
    }

    /**
     * Formatted "FrameGen: ..." row for {@code ProfilerOverlay} (profile package), or {@code null}
     * when frame generation is not armed this session ({@link FrameGenPass#armed()} false -- config
     * off, unsupported hardware, or a prior failure) -- the overlay draws nothing in that case, the
     * same "absent when the subsystem is off" convention every other overlay row follows. Sourced
     * from the SAME {@link #lastRenderedFps}/{@link #lastGeneratedFps} fields {@link
     * #maybeLogCadence} already computes for the optional dev cadence log, plus {@link
     * FrameGenPacer#engaged()} for the current pacing state, rather than a second independent
     * measurement -- see {@link #LOG_ENABLED}'s own comment for why that window now runs whenever
     * armed, not only behind the dev log flag. Values reflect the last completed ~5s window (see
     * {@link #LOG_INTERVAL_NANOS}), not an instantaneous per-frame reading, exactly like the console
     * cadence line this shares its math with.
     */
    @Nullable
    public static String overlayLine() {
        if (!FrameGenPass.armed()) {
            return null;
        }
        double rendered = lastRenderedFps;
        double generated = lastGeneratedFps;
        return String.format(Locale.ROOT, "FrameGen: %.1f pres/s (%.1f real + %.1f gen), %s",
                rendered + generated, rendered, generated,
                FrameGenPacer.engaged() ? "engaged" : "paced-out");
    }

    /**
     * Logs the pacer's engage/disengage thresholds and the currently detected display refresh rate
     * exactly once total (never per cadence line -- these values don't change frame to frame, unlike
     * the paced=... state printed above). Deferred to the first cadence line rather than class-init
     * time since {@link FrameGenPacer#displayRefreshHz()} needs a live {@code Minecraft.getInstance
     * ().getWindow()} on the render thread, which class initialization cannot guarantee.
     */
    private static void logOncePacingThresholds() {
        if (loggedPacingThresholdsOnce) {
            return;
        }
        loggedPacingThresholdsOnce = true;
        FornaxMod.LOGGER.info(
                "[Fornax] framegen adaptive pacing: engage below {}x displayHz, disengage above {}x "
                        + "displayHz (displayHz={})",
                FrameGenPacer.ENGAGE_FRACTION, FrameGenPacer.DISENGAGE_FRACTION,
                FrameGenPacer.displayRefreshHz());
    }

    private static RenderTarget ensureStagingTarget(int requestedWidth, int requestedHeight) {
        if (stagingTarget != null && stagingWidth == requestedWidth && stagingHeight == requestedHeight) {
            return stagingTarget;
        }

        // Build the new target before destroying the old one -- mirrors UiLayerCapture.ensureSize /
        // SsaaManager.ensureScaledTarget's own rebuild-then-destroy order, so a constructor failure
        // (GPU OOM, invalid size) leaves stagingTarget pointing at a valid instance instead of a
        // dangling one.
        RenderTarget next = new MainTarget(requestedWidth, requestedHeight);
        RenderTarget previous = stagingTarget;

        stagingTarget = next;
        stagingWidth = requestedWidth;
        stagingHeight = requestedHeight;

        if (previous != null) {
            previous.destroyBuffers();
        }

        FornaxMod.LOGGER.info(
                "[Fornax] (Re)built frame-gen present staging target at {}x{}", requestedWidth, requestedHeight);
        return stagingTarget;
    }
}
