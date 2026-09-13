package dev.icehunter.fornax.metalfx.rt;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.atlas.BlockAtlasView;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.RtDebugMode;
import dev.icehunter.fornax.config.GBufferDebugView;
import dev.icehunter.fornax.pass.shadow.ShadowFrameState;
import dev.icehunter.fornax.voxel.SectionHarvester;
import org.joml.Matrix4f;
import java.util.HashMap;
import dev.icehunter.fornax.metalfx.VulkanMetalInterop;
import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import dev.icehunter.fornax.pass.shadow.RtShadowResult;
import dev.icehunter.fornax.pipeline.FrameCameraState;
import dev.icehunter.fornax.pipeline.GBuffer;
import dev.icehunter.fornax.util.GpuFatalErrors;
import dev.icehunter.fornax.util.GpuFatalException;
import dev.icehunter.fornax.util.SunDirection;
import dev.icehunter.fornax.voxel.EmitterFrameState;
import dev.icehunter.fornax.voxel.VoxelWindow;
import net.minecraft.core.SectionPos;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Metal ray tracing sun-shadow pass: one sun ray per pixel traced against the loaded voxel
 * window, written into an R8 mask. Runs beside MoltenVK, sharing the depth copy-in/mask copy-back
 * cadence {@link dev.icehunter.fornax.metalfx.MetalFxUpscalePass} uses for its own interop
 * textures, but on its own {@link VulkanMetalInterop.SharedTimeline}. Several such timelines
 * coexist in this engine, one per Metal pass that shares a frame with Vulkan.
 *
 * <p>Everything fails closed: {@link #runIfEnabled} is a no-op unless {@code wanted} is true,
 * {@link MetalRtSupport#isAvailable()} says the hardware and setting allow it, and no earlier
 * frame this session has already failed. Any failure other than a device loss disables the pass
 * for the rest of the session with one ERROR log line, matching {@code MetalFxUpscalePass}'s own
 * discipline.
 */
public final class MetalRtShadowPass {
    /** Ray march distance in grid-space blocks. Authored: the tier-0 window's own diagonal at the
     * milestone's radius cap (see {@link MetalRtGeometry#MAX_DIAMETER}, 17 sections = 272 blocks
     * across, diagonal about 470 blocks) sets the floor; 512 clears it with headroom without
     * tracing indefinitely past the loaded window. */
    private static final float MAX_DISTANCE = 512.0f;

    /** {@code RtTraceConstants} total size (see {@code rt_trace.metal}'s own layout comment). */
    private static final long CONSTANTS_BYTES = 144;
    private static final long SUN_CONSTANTS_BYTES = 176;

    /** {@code RtDebugConstants} total size (see {@code rt_debug.metal}'s own layout comment). */
    private static final long DEBUG_CONSTANTS_BYTES = 96;

    /** {@code rt_trace}'s own thread-per-pixel dispatch tile. */
    private static final long TRACE_THREADS_PER_GROUP_X = 8;
    private static final long TRACE_THREADS_PER_GROUP_Y = 8;

    private static boolean failed;

    /** Set when {@link #clearRtShadowResultTargetsToInvalid} throws an ordinary (non-fatal)
     * failure instead of completing, so {@link RtShadowResult}'s targets are left holding whatever
     * they held before the attempt. Retried from the {@code !available} branch of {@link
     * #runIfEnabled} on every later frame until a clear succeeds, so a session-disabling
     * failure whose own cleanup clear also fails still reaches the zero sentinel eventually rather
     * than freezing rtSunValid/rtSunVisibility on the last real trace for the rest of the session. */
    private static boolean pendingResultClear;

    private static MetalRtShaders.Compiled compiled;

    private static VulkanMetalInterop.InteropImage depthIn;
    private static VulkanMetalInterop.InteropImage normalIn;
    private static VulkanMetalInterop.InteropImage maskOut;
    private static VulkanMetalInterop.InteropImage validOut;
    private static VulkanMetalInterop.InteropImage sunDepthOut;
    private static VulkanMetalInterop.InteropImage debugSceneOut;
    private static VulkanMetalInterop.InteropImage atlasIn;
    private static VulkanMetalInterop.SharedTimeline timeline;
    private static long timelineValue = 1;
    private static long lastVulkanSignal;

    /** {@link BlockAtlasView#generation()} the last time {@link #atlasIn} was copied into:
     * see {@link #copyBlockAtlasIfChanged}. Starts at -1, distinct from {@link BlockAtlasView}'s
     * own generation (which starts at 0), so a session where the atlas is captured but this pass
     * never sees the change (not possible in practice, since generation only increases, but kept
     * explicit rather than relying on that) still forces the first copy. */
    private static int lastAtlasGeneration = -1;

    /** A 4-byte Metal buffer bound at {@code rt_trace}'s {@code faceTexture}/buffer(3) argument
     * whenever {@link MetalRtGeometry#faceTexture()} is null (the active pack never enabled {@code
     * VoxelFaceTexture.TARGET}): the kernel never reads through it (see {@code cutoutFlags}'s own
     * doc in {@code rt_trace.metal}), but Metal still requires a real bound buffer for a declared
     * {@code device} argument. Created once, lazily, for the process. */
    private static long dummyFaceTextureBuffer;

    /** A 1x1 Metal texture bound at {@code rt_trace}'s {@code atlasIn}/texture(4) argument whenever
     * {@link #atlasIn} itself is null (no pack has captured the block atlas yet): same "never read,
     * but something must be bound" reasoning as {@link #dummyFaceTextureBuffer}. Created once,
     * lazily, for the process. */
    private static long dummyAtlasTexture;

    private static GpuTexture maskTexture;
    private static GpuTextureView maskTextureView;
    private static GpuTexture debugSceneTexture;
    private static GpuTextureView debugSceneTextureView;

    /** Whether {@link #maskTextureView} holds a mask from a frame this pass ran, as opposed to a
     * stale copy left over from before the pass was disabled or went inactive. Set true only at the
     * end of a successful {@link #run}; cleared the moment the pass stops running: on the {@code
     * !available} early return in {@link #runIfEnabled} (setting off, {@code wanted} false, or the
     * probe unavailable), on session-disabling failure, and in {@link #shutdown()}. That way {@link
     * #maskView()} can never hand a presenter a frozen frame from before any of those. */
    private static boolean maskValid;

    /** Whether {@link #debugSceneTextureView} holds a scene-debug frame the {@code rt_debug}
     * dispatch produced this run, as opposed to stale or all-zero content left over from before the
     * dispatch last ran. Set true only when {@link RtDebugMode#OFF} was NOT selected and an
     * instance structure existed to trace against; cleared on every path {@link #maskValid} is
     * cleared, plus (unlike {@code maskValid}) on any successful run where the dispatch itself did
     * not fire: a stale colored frame is a worse failure mode for a debug tool than an empty one. */
    private static boolean debugSceneValid;
    private static boolean debugSceneRenderedThisRun;
    private static boolean legacyRenderedThisRun;

    private static final Vector3f SUN_SCRATCH = new Vector3f();

    private MetalRtShadowPass() {
    }

    /** The R8 sun-visibility mask, copied back from Metal this frame: null before the first
     * successful run, and null again from the moment the pass stops running for any reason
     * (disabled, deactivated, or torn down) until it next runs successfully. Never a stale frame
     * from before that point. */
    public static GpuTextureView maskView() {
        return maskValid ? maskTextureView : null;
    }

    /** The colored RT scene-debug output, copied back from Metal this frame: null unless {@link
     * RtDebugMode} is something other than {@code OFF} and the {@code rt_debug} dispatch ran this
     * frame. Same never-stale contract as {@link #maskView()}. */
    public static GpuTextureView debugSceneView() {
        return debugSceneValid ? debugSceneTextureView : null;
    }

    /**
     * Runs the pass when {@code wanted} (the caller's own reason to want a mask this frame: a
     * debug view selected, or a system property, per the caller's own policy) and the hardware/
     * setting allow it; a no-op, and cheap, otherwise. Keeps {@link MetalRtGeometry#isActive()} in
     * step with the live decision every frame, seeding every populated slot dirty on the
     * OFF-to-ON transition so the first real frame has real geometry rather than starting from
     * nothing.
     */
    public static void runIfEnabled(GBuffer gbuffer, boolean wanted) {
        boolean consumer = dev.icehunter.fornax.pack.graph.GraphRunner.legacyRtShadowSubscriber();
        boolean available = wanted && !failed && MetalRtSupport.isAvailableFor(consumer);
        boolean wasActive = MetalRtGeometry.isActive();
        MetalRtGeometry.setActive(available);
        if (available && !wasActive) {
            MetalRtGeometry.markAllDirty(VoxelWindow.populatedSlotSections().keySet());
        }
        if (!available) {
            // Not running this frame: setting off, wanted false, or the probe unavailable.
            // Whatever maskTextureView still holds is from some earlier frame; a presenter must
            // never blit that as if it were current.
            maskValid = false;
            debugSceneValid = false;
            if (wasActive || pendingResultClear) {
                // rtSunValid/rtSunVisibility describe whether and how THIS frame's pixel was
                // traced (RtShadowResult's own contract). The pass just stopped running: setting
                // off, a session-disabling failure elsewhere, or a FORCE->AUTO flip landing on
                // non-apple9 hardware. So both must read 0, or a pack keeps compositing the last
                // real trace as a screen-space imprint that does not follow the camera.
                // pendingResultClear means an earlier attempt (here or in the session-disabling
                // catch below) failed without clearing anything: keep retrying every frame until
                // one succeeds, rather than leaving the targets stale for good.
                try {
                    clearRtShadowResultTargetsToInvalid();
                    pendingResultClear = false;
                } catch (GpuDeviceLossException | GpuFatalException e) {
                    throw e;
                } catch (Throwable retryFailure) {
                    pendingResultClear = true;
                    FornaxMod.LOGGER.error(
                            "[Fornax] Metal RT sun shadow: failed to clear result targets, will retry next frame",
                            retryFailure);
                }
            }
            return;
        }
        try {
            run(gbuffer);
            maskValid = legacyRenderedThisRun;
            debugSceneValid = debugSceneRenderedThisRun;
        } catch (GpuDeviceLossException | GpuFatalException e) {
            FornaxMod.LOGGER.error("[Fornax] Metal RT sun shadow: Vulkan device lost, unrecoverable", e);
            throw e;
        } catch (Throwable t) {
            failed = true;
            MetalRtGeometry.setActive(false);
            maskValid = false;
            debugSceneValid = false;
            FornaxMod.LOGGER.error("[Fornax] Metal RT sun shadow FAILED, disabled for this session", t);
            // Disabling for the session means this pass never runs again, so there is no reason
            // to keep its per-slot buffers, structures, interop images or exported Vulkan buffers
            // allocated for the rest of the process. Release everything here rather than leaking
            // it until the game closes. Done before the clear below so a GPU device that just
            // failed does not get to leak this pass's state on top of failing the clear too.
            releaseAllGpuState();
            // Same never-stale contract as the !available branch above: this session will never
            // trace again, so rtSunValid must read 0 from here on rather than freezing on
            // whatever the last successful frame traced. Its own try/catch: the failure above is
            // already logged and resources already released, so a second GPU command failing here
            // must not escape uncaught into the render path.
            try {
                clearRtShadowResultTargetsToInvalid();
            } catch (Throwable clearFailure) {
                GpuFatalErrors.rethrowIfFatal(clearFailure);
                pendingResultClear = true;
                FornaxMod.LOGGER.error(
                        "[Fornax] Metal RT sun shadow: failed to clear result targets after session-disabling failure, will retry next frame",
                        clearFailure);
            }
        }
    }

    /** Releases every Metal and Vulkan resource this pass owns: {@link MetalRtAcceleration}'s
     * per-slot buffers and structures, {@link MetalRtGeometry}'s exported Vulkan buffers, the
     * depth/mask interop images, the Fornax-owned mask texture, and the compiled kernels. Called
     * on session-disabling failure and from {@link #shutdown()}. Best-effort: quiesces the GPU
     * first via {@link VulkanComputeBackend#waitForGpuIdleBeforeDestroy()} (the same guard
     * {@code OpaqueDepth}/{@code GBufferManager}/{@code ShadowMapManager} use before their own
     * texture teardown), then never throws. A teardown failure is logged, not propagated,
     * because the caller (a failure handler, or process shutdown) must not itself fail here. */
    private static void releaseAllGpuState() {
        maskValid = false;
        debugSceneValid = false;
        try {
            VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
            MetalRtAcceleration.destroy();
            VulkanDevice device = VulkanMetalInterop.vulkanDevice();
            if (device != null) {
                MetalRtGeometry.destroy(device);
                VulkanMetalInterop.destroyImage(device, depthIn);
                VulkanMetalInterop.destroyImage(device, normalIn);
                VulkanMetalInterop.destroyImage(device, maskOut);
                VulkanMetalInterop.destroyImage(device, validOut);
                VulkanMetalInterop.destroyImage(device, sunDepthOut);
                VulkanMetalInterop.destroyImage(device, debugSceneOut);
                VulkanMetalInterop.destroyImage(device, atlasIn);
            }
            depthIn = null;
            normalIn = null;
            maskOut = null;
            validOut = null;
            sunDepthOut = null;
            debugSceneOut = null;
            atlasIn = null;
            lastAtlasGeneration = -1;
            if (dummyFaceTextureBuffer != 0) {
                Objc.msgSendVoid(dummyFaceTextureBuffer, Objc.selector("release"));
                dummyFaceTextureBuffer = 0;
            }
            if (dummyAtlasTexture != 0) {
                Objc.msgSendVoid(dummyAtlasTexture, Objc.selector("release"));
                dummyAtlasTexture = 0;
            }
            if (maskTextureView != null) {
                maskTextureView.close();
                maskTextureView = null;
            }
            if (maskTexture != null) {
                maskTexture.close();
                maskTexture = null;
            }
            if (debugSceneTextureView != null) {
                debugSceneTextureView.close();
                debugSceneTextureView = null;
            }
            if (debugSceneTexture != null) {
                debugSceneTexture.close();
                debugSceneTexture = null;
            }
            if (compiled != null) {
                compiled.release();
                compiled = null;
            }
            timeline = null;
            timelineValue = 1;
            lastVulkanSignal = 0;
        } catch (Throwable t) {
            FornaxMod.LOGGER.debug("[Fornax] Metal RT sun shadow: teardown hit an error, continuing", t);
        }
    }

    /** Process-shutdown teardown, mirroring the other best-effort {@code CLIENT_STOPPING} cleanup
     * calls in {@code FornaxMod} (this pass has no pack-switch or renderer-close teardown site of
     * its own to hook, unlike a documented target lifecycle: Metal ray tracing state lives for the
     * process, same as {@code MetalFxUpscalePass}'s, so process exit is the one point every path
     * reaches). Safe to call whether or not the pass ever ran. */
    public static void shutdown() {
        releaseAllGpuState();
    }

    /** Explicitly clears {@link RtShadowResult}'s two pack-visible targets to the same zero
     * sentinel {@link RtShadowResult#ensureSize} clears them to at allocation, for a frame where
     * {@code rt_trace} never dispatched at all (no instance structure yet). Without this, the
     * copy-back in {@link #run} would either skip these two targets, leaving them at whatever
     * uninitialized VRAM {@link VulkanMetalInterop#createImage} handed {@link #maskOut}/{@link
     * #validOut} on session start, or, on a later frame with no instances, leave them holding a
     * stale real value copied on some earlier frame that DID trace. Either way is exactly the
     * silent-stale-VRAM failure this codebase's zero-fill law exists to catch: {@code rtSunValid}
     * describes whether THIS frame's pixel was traced, so a frame with nothing to trace must read
     * 0 for every pixel, not leftover content from a different frame. No-op before {@link
     * RtShadowResult#ensureSize} has ever run (both getters null). */
    private static void clearRtShadowResultTargetsToInvalid() {
        GpuTexture sunDepthTexture = RtShadowResult.getDepthTexture();
        if (sunDepthTexture != null) RenderSystem.getDevice().createCommandEncoder()
                .clearColorTexture(sunDepthTexture, new Vector4f(0.0f, 0.0f, 0.0f, 0.0f));
        clearLegacyRtTargetsToInvalid();
    }

    private static void clearLegacyRtTargetsToInvalid() {
        GpuTexture rtVisibilityTexture = RtShadowResult.getVisibilityTexture();
        if (rtVisibilityTexture != null) {
            RenderSystem.getDevice().createCommandEncoder()
                    .clearColorTexture(rtVisibilityTexture, new Vector4f(0.0f, 0.0f, 0.0f, 0.0f));
        }
        GpuTexture rtValidTexture = RtShadowResult.getValidTexture();
        if (rtValidTexture != null) {
            RenderSystem.getDevice().createCommandEncoder()
                    .clearColorTexture(rtValidTexture, new Vector4f(0.0f, 0.0f, 0.0f, 0.0f));
        }
    }

    private static void run(GBuffer gbuffer) {
        debugSceneRenderedThisRun = false;
        legacyRenderedThisRun = false;
        boolean legacyTrace = RtShadowResult.legacyRequested()
                || FornaxConfig.get().debugView == GBufferDebugView.METAL_RT_SUN_MASK;
        // A debug-only legacy trace can stop while the sun-space consumer remains active.
        // Clear that transition too, so even the old getters never retain a valid frozen mask.
        if (!legacyTrace && maskValid) clearLegacyRtTargetsToInvalid();
        boolean sunTrace = RtShadowResult.sunDepthRequested();
        boolean debugTrace = FornaxConfig.get().rtDebugMode != RtDebugMode.OFF;
        if (!legacyTrace && !sunTrace && !debugTrace) return;
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        if (device == null) {
            throw new IllegalStateException("no Vulkan device (GL backend?)");
        }
        ensureShaders();

        VoxelWindow.WindowState sourceWindow = VoxelWindow.currentState();
        VoxelWindow.WindowState window = MetalRtGeometry.exportedWindow(sourceWindow);
        int width = gbuffer.getWidth();
        int height = gbuffer.getHeight();
        // Read once here, ahead of the lock-scoped fetch below: ensureBuffers only needs the
        // registry to decide whether VoxelFaceTexture.TARGET is enabled (stable pack-load metadata,
        // not a buffer handle), so this does not need SHARED_QUEUE_LOCK the way the actual buffer
        // instance reads later in this method do.
        TargetRegistry registryForGeometry = VoxelWindow.attachedRegistry();

        if (MetalRtGeometry.needsReallocation(registryForGeometry, sourceWindow.diameter())
                && timeline != null && lastVulkanSignal > 0) {
            VulkanMetalInterop.waitTimeline(device, timeline, lastVulkanSignal);
        }
        MetalRtGeometry.ensureBuffers(device, registryForGeometry, sourceWindow.diameter());
        ensureImages(device, legacyTrace || debugTrace ? width : 1, legacyTrace || debugTrace ? height : 1);
        if (sunTrace) ensureSunImage(device, RtShadowResult.sunResolution());
        ensureAtlasImage(device);
        if (timeline == null) {
            timeline = VulkanMetalInterop.createSharedTimeline(device);
        }

        long commandQueue = VulkanMetalInterop.metalCommandQueue();
        VulkanGpuTexture depthTex = (VulkanGpuTexture) gbuffer.getDepthView().texture();
        VulkanGpuTexture normalTex = (VulkanGpuTexture) gbuffer.getNormalView().texture();
        VulkanCommandEncoder encoder = device.createCommandEncoder();
        long v = timelineValue;

        Map<Integer, SectionPos> populatedSlots;
        List<Integer> dirty;
        Map<Integer, SectionHarvester.Result> capturedSections = new HashMap<>();
        // recordSlotCopies can drop a slot (out of the exported window, or the registry has no
        // brick-grid buffer yet) without throwing. A dropped slot's exported data is stale or
        // absent, so it must not be expanded this frame, and its dirty mark is gone the moment
        // drainDirty() ran above, so it must be re-marked or it would never retry.
        @SuppressWarnings("unchecked")
        List<Integer>[] copiedHolder = new List[] {List.<Integer>of()};
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            populatedSlots = MetalRtGeometry.prepareSnapshot(VoxelWindow.populatedSlotSections());
            dirty = MetalRtGeometry.selectDirtyBatch();
            TargetRegistry registry = VoxelWindow.attachedRegistry();
            VulkanMetalInterop.CmdRecorder copyIn = cmd -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    if (legacyTrace) {
                    VulkanMetalInterop.prepareGeneralTransferRead(
                            cmd, stack, depthTex.vkImage(), VK13.VK_IMAGE_ASPECT_DEPTH_BIT);
                    VulkanMetalInterop.prepareInteropTransferWrite(cmd, stack, depthIn);
                    VulkanMetalInterop.copyImage(cmd, stack,
                            depthTex.vkImage(), VK13.VK_IMAGE_LAYOUT_GENERAL,
                            depthIn.image, depthIn.layout,
                            VK13.VK_IMAGE_ASPECT_DEPTH_BIT, width, height);
                    VulkanMetalInterop.finishInteropTransferWrite(cmd, stack, depthIn);
                    VulkanMetalInterop.prepareGeneralTransferRead(
                            cmd, stack, normalTex.vkImage(), VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                    VulkanMetalInterop.prepareInteropTransferWrite(cmd, stack, normalIn);
                    VulkanMetalInterop.copyImage(cmd, stack,
                            normalTex.vkImage(), VK13.VK_IMAGE_LAYOUT_GENERAL,
                            normalIn.image, normalIn.layout,
                            VK13.VK_IMAGE_ASPECT_COLOR_BIT, width, height);
                    VulkanMetalInterop.finishInteropTransferWrite(cmd, stack, normalIn);
                    }
                    copyBlockAtlasIfChanged(cmd, stack);
                    if (sunTrace) VulkanMetalInterop.prepareInteropMetalWrite(cmd, stack, sunDepthOut);
                    VulkanMetalInterop.prepareInteropMetalWrite(cmd, stack, maskOut);
                    VulkanMetalInterop.prepareInteropMetalWrite(cmd, stack, validOut);
                    VulkanMetalInterop.prepareInteropMetalWrite(cmd, stack, debugSceneOut);
                    if (!dirty.isEmpty() && registry != null) {
                        copiedHolder[0] = MetalRtGeometry.recordSlotCopies(cmd, registry, dirty);
                        for (int slot : copiedHolder[0]) {
                            SectionHarvester.Result data = VoxelWindow.committedSectionData(slot, populatedSlots.get(slot));
                            if (data != null) capturedSections.put(slot, data);
                        }
                    }
                }
            };
            VulkanMetalInterop.recordIntoStream(encoder, copyIn);
            encoder.signalSemaphore(timeline.vkSemaphore, v, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
            encoder.submit();
        }
        List<Integer> copiedSlots = copiedHolder[0];
        if (copiedSlots.size() != dirty.size()) {
            Set<Integer> copiedSet = new HashSet<>(copiedSlots);
            for (int slot : dirty) {
                if (!copiedSet.contains(slot)) {
                    MetalRtGeometry.markDirty(slot);
                }
            }
        }

        long pool = Objc.autoreleasePoolPush();
        boolean tracedThisFrame;
        long readinessBuffer = 0;
        try {
            MetalRtAcceleration.rebuildDirty(commandQueue, compiled, copiedSlots, populatedSlots,
                    timeline.mtlSharedEvent, v, capturedSections);
            MetalRtAcceleration.rebuildInstances(commandQueue, populatedSlots);
            MetalRtGeometry.markPublished(copiedSlots, populatedSlots);
            int[] readiness;
            synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
                readiness = MetalRtGeometry.sectionReadiness(populatedSlots);
                // Publication can be current yet contain unsupported model forms. Such an owner
                // cannot certify exact terrain replacement, even on a ray missing its proxy boxes.
                for (var owner : populatedSlots.entrySet()) {
                    int slot = MetalRtGeometry.exportedSlot(owner.getKey());
                    if (slot >= 0 && slot < readiness.length
                            && !MetalRtAcceleration.representationReady(owner.getKey(), owner.getValue())) readiness[slot] = 0;
                }
            }
            long metalDevice = Objc.msgSendId(commandQueue, Objc.selector("device"));
            readinessBuffer = MetalRtAcceleration.createBuffer(metalDevice, Math.max(4L, readiness.length * 4L));
            MemorySegment readinessMemory = MemorySegment.ofAddress(Objc.msgSendId(readinessBuffer, Objc.selector("contents")))
                    .reinterpret(Math.max(4L, readiness.length * 4L));
            for (int i = 0; i < readiness.length; ++i) readinessMemory.setAtIndex(ValueLayout.JAVA_INT, i, readiness[i]);

            long cb = Objc.msgSendId(commandQueue, Objc.selector("commandBuffer"));
            if (cb == 0) {
                throw new IllegalStateException("Metal command buffer nil (rt trace)");
            }
            Objc.msgSendVoidIdLong(cb, Objc.selector("encodeWaitForEvent:value:"), timeline.mtlSharedEvent, v);

            long instanceStructure = MetalRtAcceleration.instanceStructure();
            tracedThisFrame = instanceStructure != 0;
            if (tracedThisFrame) {
                if (legacyTrace) {
                // Constants prepared before the encoder exists: a throw from writeConstants (pure
                // Java, no GPU call) then has no open encoder to leak.
                try (Arena local = Arena.ofConfined()) {
                    MemorySegment constants = local.allocate(CONSTANTS_BYTES);
                    writeConstants(constants, width, height, window);

                    long computeEncoder = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
                    if (computeEncoder == 0) {
                        throw new IllegalStateException("Metal compute encoder nil (rt trace)");
                    }
                    // Every encoder-mutating call from here on must run inside this try: Metal
                    // aborts the process on -[_MTLCommandEncoder dealloc] if an open encoder is
                    // ever released without endEncoding, which is exactly what happens if a throw
                    // here escaped past a still-open encoder into the autorelease pool pop below.
                    try {
                        ensureCutoutFallbacks(metalDevice);
                        Objc.msgSendVoidIdLongLong(computeEncoder, Objc.selector("setBuffer:offset:atIndex:"), readinessBuffer, 0L, 10L);
                        long faceTextureBuffer = MetalRtGeometry.faceTexture() != null
                                ? MetalRtGeometry.faceTexture().mtlBuffer() : dummyFaceTextureBuffer;
                        long atlasTexture = atlasIn != null ? atlasIn.mtlTexture : dummyAtlasTexture;
                        // Two independent bits (rt_trace.metal's CUTOUT_ATLAS_BIT/
                        // CUTOUT_FACE_TEXTURE_BIT): a CROSS entry alpha-tests off the atlas alone,
                        // so a pack that captures the atlas but never enables
                        // VoxelFaceTexture.TARGET must not lose plant alpha testing for a reason
                        // that only matters to the FULL/PARTIAL path.
                        int cutoutFlags = (atlasIn != null && BlockAtlasView.texture() != null ? 0x1 : 0)
                                | (MetalRtGeometry.faceTexture() != null ? 0x2 : 0);

                        Objc.msgSendVoid(computeEncoder, Objc.selector("setComputePipelineState:"), compiled.trace().pipeline());
                        Objc.msgSendVoidIdLong(computeEncoder,
                                Objc.selector("setAccelerationStructure:atBufferIndex:"), instanceStructure, 1L);
                        Objc.msgSendVoidIdLongLong(computeEncoder, Objc.selector("setBuffer:offset:atIndex:"),
                                MetalRtGeometry.palette().mtlBuffer(), 0L, 2L);
                        Objc.msgSendVoidIdLongLong(computeEncoder, Objc.selector("setBuffer:offset:atIndex:"),
                                faceTextureBuffer, 0L, 3L);
                        Objc.msgSendVoidIdLongLong(computeEncoder, Objc.selector("setBuffer:offset:atIndex:"),
                                MetalRtAcceleration.instanceSlotMap(), 0L, 4L);
                        try (Arena flagArena = Arena.ofConfined()) {
                            MemorySegment flag = flagArena.allocate(ValueLayout.JAVA_INT);
                            flag.set(ValueLayout.JAVA_INT, 0, cutoutFlags);
                            Objc.msgSendVoidIdLongLong(computeEncoder,
                                    Objc.selector("setBytes:length:atIndex:"), flag.address(), 4L, 5L);
                        }
                        Objc.msgSendVoidIdLong(computeEncoder, Objc.selector("setTexture:atIndex:"), maskOut.mtlTexture, 0L);
                        Objc.msgSendVoidIdLong(computeEncoder, Objc.selector("setTexture:atIndex:"), depthIn.mtlTexture, 1L);
                        Objc.msgSendVoidIdLong(computeEncoder, Objc.selector("setTexture:atIndex:"), normalIn.mtlTexture, 2L);
                        Objc.msgSendVoidIdLong(computeEncoder, Objc.selector("setTexture:atIndex:"), validOut.mtlTexture, 3L);
                        Objc.msgSendVoidIdLong(computeEncoder, Objc.selector("setTexture:atIndex:"), atlasTexture, 4L);
                        Objc.msgSendVoidIdLongLong(computeEncoder,
                                Objc.selector("setBytes:length:atIndex:"), constants.address(), CONSTANTS_BYTES, 0L);
                        MetalRtAcceleration.useResources(computeEncoder);
                        long groupsX = (width + TRACE_THREADS_PER_GROUP_X - 1) / TRACE_THREADS_PER_GROUP_X;
                        long groupsY = (height + TRACE_THREADS_PER_GROUP_Y - 1) / TRACE_THREADS_PER_GROUP_Y;
                        Objc.dispatchThreadgroups(computeEncoder, groupsX, groupsY, 1,
                                TRACE_THREADS_PER_GROUP_X, TRACE_THREADS_PER_GROUP_Y, 1);
                    } finally {
                        Objc.msgSendVoid(computeEncoder, Objc.selector("endEncoding"));
                    }
                }

                legacyRenderedThisRun = true;
                }
                if (sunTrace) encodeSunDepth(cb, commandQueue, instanceStructure, readinessBuffer, window);
                RtDebugMode debugMode = FornaxConfig.get().rtDebugMode;
                if (debugMode != RtDebugMode.OFF) {
                    // Second, independent dispatch in the same command buffer: rt_debug traces a
                    // primary ray per pixel rather than reading rt_trace's shadow-ray inputs, so it
                    // needs none of maskOut/depthIn/normalIn, only the instance structure and its
                    // own constants.
                    try (Arena local = Arena.ofConfined()) {
                        MemorySegment debugConstants = local.allocate(DEBUG_CONSTANTS_BYTES);
                        writeDebugConstants(debugConstants, width, height, window, debugMode);

                        long debugEncoder = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
                        if (debugEncoder == 0) {
                            throw new IllegalStateException("Metal compute encoder nil (rt debug)");
                        }
                        try {
                            Objc.msgSendVoid(debugEncoder, Objc.selector("setComputePipelineState:"), compiled.debug().pipeline());
                            Objc.msgSendVoidIdLong(debugEncoder,
                                    Objc.selector("setAccelerationStructure:atBufferIndex:"), instanceStructure, 0L);
                            Objc.msgSendVoidIdLongLong(debugEncoder, Objc.selector("setBytes:length:atIndex:"),
                                    debugConstants.address(), DEBUG_CONSTANTS_BYTES, 1L);
                            Objc.msgSendVoidIdLong(debugEncoder, Objc.selector("setTexture:atIndex:"), debugSceneOut.mtlTexture, 0L);
                            MetalRtAcceleration.useResources(debugEncoder);
                            long groupsX = (width + TRACE_THREADS_PER_GROUP_X - 1) / TRACE_THREADS_PER_GROUP_X;
                            long groupsY = (height + TRACE_THREADS_PER_GROUP_Y - 1) / TRACE_THREADS_PER_GROUP_Y;
                            Objc.dispatchThreadgroups(debugEncoder, groupsX, groupsY, 1,
                                    TRACE_THREADS_PER_GROUP_X, TRACE_THREADS_PER_GROUP_Y, 1);
                        } finally {
                            Objc.msgSendVoid(debugEncoder, Objc.selector("endEncoding"));
                        }
                        debugSceneRenderedThisRun = true;
                    }
                }
            } else {
                // No instances yet (nothing built this session, or an empty window): nothing to
                // trace against. Neither rt_trace nor rt_debug runs; maskTexture keeps last frame's
                // copy-back content, or, before any frame has run, whatever bytes the interop
                // VkImage allocation happened to contain (createImage does not clear it). The
                // scene-debug output is safe from the same gap: debugSceneValid only ever turns
                // true on a frame the rt_debug dispatch ran, so debugSceneView() never hands a
                // presenter that uninitialized content. RtShadowResult's own pack-visible targets
                // get the same never-stale treatment explicitly, below, since the copy-back that
                // would otherwise write them only runs when tracedThisFrame is true.
                FornaxMod.LOGGER.debug("[Fornax] Metal RT sun shadow: no instance structure yet, skipping trace this frame");
                clearRtShadowResultTargetsToInvalid();
            }

            Objc.msgSendVoidIdLong(cb, Objc.selector("encodeSignalEvent:value:"), timeline.mtlSharedEvent, v + 1);
            Objc.msgSendVoid(cb, Objc.selector("commit"));
        } finally {
            // Command buffers retain bound resources through completion; each frame has its own
            // immutable readiness allocation, never a CPU overwrite of an in-flight snapshot.
            if (readinessBuffer != 0) Objc.msgSendVoid(readinessBuffer, Objc.selector("release"));
            Objc.autoreleasePoolPop(pool);
        }

        VulkanMetalInterop.CmdRecorder copyBack = cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (tracedThisFrame && legacyTrace) {
                long maskImage = ((VulkanGpuTexture) maskTexture).vkImage();
                VulkanMetalInterop.prepareInteropTransferRead(cmd, stack, maskOut);
                VulkanMetalInterop.prepareGeneralTransferWrite(cmd, stack, maskImage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                VulkanMetalInterop.copyImage(cmd, stack,
                        maskOut.image, maskOut.layout,
                        maskImage, VK13.VK_IMAGE_LAYOUT_GENERAL,
                        VK13.VK_IMAGE_ASPECT_COLOR_BIT, width, height);
                VulkanMetalInterop.finishInteropTransferRead(cmd, stack, maskOut);
                VulkanMetalInterop.finishGeneralTransferWrite(cmd, stack, maskImage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);

                // Same maskOut content, additionally copied into RtShadowResult's own pack-visible
                // rtSunVisibility target (getTexture() is null only before RtShadowResult.ensureSize
                // has ever run, which SodiumWorldRendererOrchestrationMixin calls unconditionally
                // every frame the graph is active: see that class's own doc), but ONLY on a frame
                // the trace dispatched: maskOut/validOut hold no real content for THIS frame
                // otherwise (see clearRtShadowResultTargetsToInvalid, which handles that case
                // instead, called from the tracedThisFrame == false branch above).
                if (tracedThisFrame) {
                    GpuTexture rtVisibilityTexture = RtShadowResult.getVisibilityTexture();
                    if (rtVisibilityTexture != null) {
                        long rtVisibilityImage = ((VulkanGpuTexture) rtVisibilityTexture).vkImage();
                        VulkanMetalInterop.prepareInteropTransferRead(cmd, stack, maskOut);
                        VulkanMetalInterop.prepareGeneralTransferWrite(cmd, stack, rtVisibilityImage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                        VulkanMetalInterop.copyImage(cmd, stack,
                                maskOut.image, maskOut.layout,
                                rtVisibilityImage, VK13.VK_IMAGE_LAYOUT_GENERAL,
                                VK13.VK_IMAGE_ASPECT_COLOR_BIT, width, height);
                        VulkanMetalInterop.finishInteropTransferRead(cmd, stack, maskOut);
                        VulkanMetalInterop.finishGeneralTransferWrite(cmd, stack, rtVisibilityImage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                    }

                    GpuTexture rtValidTexture = RtShadowResult.getValidTexture();
                    if (rtValidTexture != null) {
                        long rtValidImage = ((VulkanGpuTexture) rtValidTexture).vkImage();
                        VulkanMetalInterop.prepareInteropTransferRead(cmd, stack, validOut);
                        VulkanMetalInterop.prepareGeneralTransferWrite(cmd, stack, rtValidImage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                        VulkanMetalInterop.copyImage(cmd, stack,
                                validOut.image, validOut.layout,
                                rtValidImage, VK13.VK_IMAGE_LAYOUT_GENERAL,
                                VK13.VK_IMAGE_ASPECT_COLOR_BIT, width, height);
                        VulkanMetalInterop.finishInteropTransferRead(cmd, stack, validOut);
                        VulkanMetalInterop.finishGeneralTransferWrite(cmd, stack, rtValidImage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                    }
                }

                }
                if (tracedThisFrame && sunTrace) {
                    copySunDepth(cmd, stack);
                }
                if (debugSceneRenderedThisRun) {
                long debugSceneImage = ((VulkanGpuTexture) debugSceneTexture).vkImage();
                VulkanMetalInterop.prepareInteropTransferRead(cmd, stack, debugSceneOut);
                VulkanMetalInterop.prepareGeneralTransferWrite(cmd, stack, debugSceneImage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                VulkanMetalInterop.copyImage(cmd, stack,
                        debugSceneOut.image, debugSceneOut.layout,
                        debugSceneImage, VK13.VK_IMAGE_LAYOUT_GENERAL,
                        VK13.VK_IMAGE_ASPECT_COLOR_BIT, width, height);
                VulkanMetalInterop.finishInteropTransferRead(cmd, stack, debugSceneOut);
                VulkanMetalInterop.finishGeneralTransferWrite(cmd, stack, debugSceneImage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
                }
            }
        };
        encoder.waitSemaphore(timeline.vkSemaphore, v + 1, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
        VulkanMetalInterop.recordIntoStream(encoder, copyBack);
        encoder.signalSemaphore(timeline.vkSemaphore, v + 2, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
        encoder.submit();
        lastVulkanSignal = v + 2;
        timelineValue = v + 3;
    }

    private static void ensureSunImage(VulkanDevice device, int resolution) {
        if (resolution < 1) throw new IllegalStateException("rtSunDepth has no allocated shadow resolution");
        if (sunDepthOut != null && sunDepthOut.width == resolution) return;
        if (timeline != null && lastVulkanSignal > 0) VulkanMetalInterop.waitTimeline(device, timeline, lastVulkanSignal);
        VulkanMetalInterop.destroyImage(device, sunDepthOut);
        sunDepthOut = VulkanMetalInterop.createImage(device, resolution, resolution,
                VK13.VK_FORMAT_R32G32B32A32_SFLOAT,
                VK13.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK13.VK_IMAGE_USAGE_STORAGE_BIT | VK13.VK_IMAGE_USAGE_SAMPLED_BIT,
                VK13.VK_IMAGE_ASPECT_COLOR_BIT);
    }

    private static void copySunDepth(VkCommandBuffer cmd, MemoryStack stack) {
        GpuTexture texture = RtShadowResult.getDepthTexture();
        if (texture == null) throw new IllegalStateException("rtSunDepth target disappeared during trace");
        long destination = ((VulkanGpuTexture) texture).vkImage();
        VulkanMetalInterop.prepareInteropTransferRead(cmd, stack, sunDepthOut);
        VulkanMetalInterop.prepareGeneralTransferWrite(cmd, stack, destination, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        VulkanMetalInterop.copyImage(cmd, stack, sunDepthOut.image, sunDepthOut.layout,
                destination, VK13.VK_IMAGE_LAYOUT_GENERAL, VK13.VK_IMAGE_ASPECT_COLOR_BIT,
                sunDepthOut.width, sunDepthOut.height);
        VulkanMetalInterop.finishInteropTransferRead(cmd, stack, sunDepthOut);
        VulkanMetalInterop.finishGeneralTransferWrite(cmd, stack, destination, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
    }

    private static void encodeSunDepth(long cb, long queue, long instances, long readiness,
            VoxelWindow.WindowState window) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment constants = arena.allocate(SUN_CONSTANTS_BYTES);
            writeSunConstants(constants, RtShadowResult.sunResolution(), window);
            long encoder = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
            if (encoder == 0) throw new IllegalStateException("Metal compute encoder nil (sun depth)");
            try {
                ensureCutoutFallbacks(Objc.msgSendId(queue, Objc.selector("device")));
                Objc.msgSendVoid(encoder, Objc.selector("setComputePipelineState:"), compiled.sunDepth().pipeline());
                Objc.msgSendVoidIdLong(encoder, Objc.selector("setAccelerationStructure:atBufferIndex:"), instances, 1L);
                Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), MetalRtGeometry.palette().mtlBuffer(), 0L, 2L);
                Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"),
                        MetalRtGeometry.faceTexture() != null ? MetalRtGeometry.faceTexture().mtlBuffer() : dummyFaceTextureBuffer, 0L, 3L);
                Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), MetalRtAcceleration.instanceSlotMap(), 0L, 4L);
                MemorySegment flags = arena.allocate(ValueLayout.JAVA_INT);
                flags.set(ValueLayout.JAVA_INT, 0, (atlasIn != null && BlockAtlasView.texture() != null ? 1 : 0) | (MetalRtGeometry.faceTexture() != null ? 2 : 0));
                Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBytes:length:atIndex:"), flags.address(), 4L, 5L);
                Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), readiness, 0L, 10L);
                Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBytes:length:atIndex:"), constants.address(), SUN_CONSTANTS_BYTES, 0L);
                Objc.msgSendVoidIdLong(encoder, Objc.selector("setTexture:atIndex:"), sunDepthOut.mtlTexture, 0L);
                Objc.msgSendVoidIdLong(encoder, Objc.selector("setTexture:atIndex:"), atlasIn != null ? atlasIn.mtlTexture : dummyAtlasTexture, 4L);
                MetalRtAcceleration.useResources(encoder);
                Objc.dispatchThreadgroups(encoder,
                        (sunDepthOut.width + TRACE_THREADS_PER_GROUP_X - 1) / TRACE_THREADS_PER_GROUP_X,
                        (sunDepthOut.height + TRACE_THREADS_PER_GROUP_Y - 1) / TRACE_THREADS_PER_GROUP_Y,
                        1, TRACE_THREADS_PER_GROUP_X, TRACE_THREADS_PER_GROUP_Y, 1);
            } finally {
                Objc.msgSendVoid(encoder, Objc.selector("endEncoding"));
            }
        }
    }

    static void writeSunConstants(MemorySegment seg, int resolution, VoxelWindow.WindowState window) {
        Matrix4f projection = new Matrix4f(ShadowFrameState.current());
        float[] inverse = new Matrix4f(projection).invert().get(new float[16]);
        float[] forward = projection.get(new float[16]);
        for (int i = 0; i < 16; ++i) {
            seg.set(ValueLayout.JAVA_FLOAT, i * 4L, inverse[i]);
            seg.set(ValueLayout.JAVA_FLOAT, 80 + i * 4L, forward[i]);
        }
        int firstX = window.centerX() - window.radius();
        int firstY = window.centerY() - window.radius();
        int firstZ = window.centerZ() - window.radius();
        seg.set(ValueLayout.JAVA_FLOAT, 64, EmitterFrameState.camX() - firstX * 16.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 68, EmitterFrameState.camY() - firstY * 16.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 72, EmitterFrameState.camZ() - firstZ * 16.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 76, 0.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 144, ShadowFrameState.currentBias());
        seg.set(ValueLayout.JAVA_INT, 148, window.diameter());
        seg.set(ValueLayout.JAVA_INT, 152, resolution);
        seg.set(ValueLayout.JAVA_INT, 156, resolution);
        seg.set(ValueLayout.JAVA_INT, 160, firstX);
        seg.set(ValueLayout.JAVA_INT, 164, firstY);
        seg.set(ValueLayout.JAVA_INT, 168, firstZ);
        seg.set(ValueLayout.JAVA_INT, 172, 0);
    }

    private static void ensureShaders() {
        if (compiled != null) {
            return;
        }
        long metalDevice = Objc.msgSendId(VulkanMetalInterop.metalCommandQueue(), Objc.selector("device"));
        compiled = MetalRtShaders.compile(metalDevice);
    }

    private static void ensureImages(VulkanDevice device, int width, int height) {
        if (depthIn != null && depthIn.width == width && depthIn.height == height) {
            return;
        }
        if (timeline != null && lastVulkanSignal > 0) {
            VulkanMetalInterop.waitTimeline(device, timeline, lastVulkanSignal);
        }
        VulkanMetalInterop.destroyImage(device, depthIn);
        VulkanMetalInterop.destroyImage(device, normalIn);
        VulkanMetalInterop.destroyImage(device, maskOut);
        VulkanMetalInterop.destroyImage(device, validOut);
        VulkanMetalInterop.destroyImage(device, debugSceneOut);
        int depthUsage = VK13.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK13.VK_IMAGE_USAGE_SAMPLED_BIT;
        depthIn = VulkanMetalInterop.createImage(
                device, width, height, VK13.VK_FORMAT_D32_SFLOAT, depthUsage, VK13.VK_IMAGE_ASPECT_DEPTH_BIT);
        // Matches GBufferManager's own gNormal format exactly (world-space normal, signed, in
        // .rgb: see terrain.fsh's gNormalOut write; .a carries the flat face index rt_trace.metal
        // decodes for its ray-origin bias): RGBA16_SNORM, so the same bytes MoltenVK exports read
        // back as the same already-decoded [-1, 1] floats on the Metal side, no reinterpretation
        // needed, the same reasoning depthIn's own comment states for the depth format.
        int normalUsage = VK13.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK13.VK_IMAGE_USAGE_SAMPLED_BIT;
        normalIn = VulkanMetalInterop.createImage(device, width, height,
                VK13.VK_FORMAT_R16G16B16A16_SNORM, normalUsage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        int maskUsage = VK13.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK13.VK_IMAGE_USAGE_STORAGE_BIT
                | VK13.VK_IMAGE_USAGE_SAMPLED_BIT;
        maskOut = VulkanMetalInterop.createImage(
                device, width, height, VK13.VK_FORMAT_R8_UNORM, maskUsage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        // Same shape as maskOut: R8_UNORM, written by rt_trace, copied back to RtShadowResult's
        // own rtSunValid target.
        validOut = VulkanMetalInterop.createImage(
                device, width, height, VK13.VK_FORMAT_R8_UNORM, maskUsage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        // RGBA16F rather than maskOut's R8: several modes (hit/miss, instance/primitive id hash,
        // ray direction) write a genuine colour, not a single scalar.
        int debugSceneUsage = VK13.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK13.VK_IMAGE_USAGE_STORAGE_BIT
                | VK13.VK_IMAGE_USAGE_SAMPLED_BIT;
        debugSceneOut = VulkanMetalInterop.createImage(device, width, height,
                VK13.VK_FORMAT_R16G16B16A16_SFLOAT, debugSceneUsage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        ensureMaskTexture(width, height);
        ensureDebugSceneTexture(width, height);
    }

    /** Recreates {@link #atlasIn} to match the live block atlas's current size and format, if
     * either has changed since the interop image was last allocated. A no-op before any pack has
     * captured the atlas ({@link BlockAtlasView#texture()} null): {@link #atlasIn} then stays
     * whatever it was, since {@link #copyBlockAtlasIfChanged} also checks for null and copies
     * nothing until a real atlas exists. Reads the atlas's actual {@code GpuFormat} rather than
     * assuming one, the same way {@code VulkanMetalInterop}'s own passthrough self-test reads a
     * live color target's format before mapping it to a {@code VkFormat}. Created through {@link
     * VulkanMetalInterop#createImage}, the same exclusive-sharing-mode helper {@link #depthIn} and
     * {@link #normalIn} already use: this image is written only by the single Vulkan queue this
     * pass's own encoder submits to (never a separate compute queue) and read only by Metal through
     * the exported {@code MTLTexture}, exactly like those two, so no additional queue-family sharing
     * or barrier is needed beyond what {@link #copyBlockAtlasIfChanged} already does. */
    private static void ensureAtlasImage(VulkanDevice device) {
        GpuTexture atlasTexture = BlockAtlasView.texture();
        if (atlasTexture == null) {
            return;
        }
        VulkanGpuTexture atlasTex = (VulkanGpuTexture) atlasTexture;
        int width = atlasTex.getWidth(0);
        int height = atlasTex.getHeight(0);
        int vkFormat = VulkanMetalInterop.mapFormat(atlasTex.getFormat().toString());
        if (atlasIn != null && atlasIn.width == width && atlasIn.height == height && atlasIn.vkFormat == vkFormat) {
            return;
        }
        if (timeline != null && lastVulkanSignal > 0) {
            VulkanMetalInterop.waitTimeline(device, timeline, lastVulkanSignal);
        }
        VulkanMetalInterop.destroyImage(device, atlasIn);
        int atlasUsage = VK13.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK13.VK_IMAGE_USAGE_SAMPLED_BIT;
        atlasIn = VulkanMetalInterop.createImage(
                device, width, height, vkFormat, atlasUsage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        // createImage does not clear the fresh allocation, so force the next
        // copyBlockAtlasIfChanged to run even if BlockAtlasView's generation has not moved since
        // the last copy. This recreate can happen for reasons other than a new capture (a
        // format or size change surfaced through some other path), and without this the new image
        // would sit in VK_IMAGE_LAYOUT_UNDEFINED holding whatever VRAM it was given.
        lastAtlasGeneration = -1;
    }

    /** Copy current atlas content every RT frame: allocation generation does not change when
     * animated sprites update texels in place. The Vulkan signal orders uploads before Metal. */
    private static void copyBlockAtlasIfChanged(VkCommandBuffer cmd, MemoryStack stack) {
        GpuTexture atlasTexture = BlockAtlasView.texture();
        int generation = BlockAtlasView.generation();
        if (atlasTexture == null || atlasIn == null) {
            return;
        }
        long atlasImage = ((VulkanGpuTexture) atlasTexture).vkImage();
        VulkanMetalInterop.prepareGeneralTransferRead(cmd, stack, atlasImage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        VulkanMetalInterop.prepareInteropTransferWrite(cmd, stack, atlasIn);
        VulkanMetalInterop.copyImage(cmd, stack,
                atlasImage, VK13.VK_IMAGE_LAYOUT_GENERAL,
                atlasIn.image, atlasIn.layout,
                VK13.VK_IMAGE_ASPECT_COLOR_BIT, atlasIn.width, atlasIn.height);
        VulkanMetalInterop.finishInteropTransferWrite(cmd, stack, atlasIn);
        lastAtlasGeneration = generation;
    }

    /** Creates {@link #dummyFaceTextureBuffer}/{@link #dummyAtlasTexture} the first time either is
     * needed: see their own field javadoc for why {@code rt_trace} always needs something bound at
     * those argument indices even when no real cutout texture data exists yet. */
    private static void ensureCutoutFallbacks(long metalDevice) {
        if (dummyFaceTextureBuffer == 0) {
            dummyFaceTextureBuffer = MetalRtAcceleration.createBuffer(metalDevice, 4);
        }
        if (dummyAtlasTexture == 0) {
            // Same confirmed-real Metal enum values MetalRtSmokeTest's own header comment
            // documents (MTLTextureType2D, MTLPixelFormatR8Unorm, MTLTextureUsageShaderRead,
            // MTLStorageModeShared). Never sampled for real data, only bound so the kernel's
            // declared texture argument is never left unset.
            long alloc = Objc.msgSendId(Objc.getClass("MTLTextureDescriptor"), Objc.selector("alloc"));
            long desc = Objc.msgSendId(alloc, Objc.selector("init"));
            Objc.msgSendVoidLong(desc, Objc.selector("setTextureType:"), 2L);
            Objc.msgSendVoidLong(desc, Objc.selector("setPixelFormat:"), 10L);
            Objc.msgSendVoidLong(desc, Objc.selector("setWidth:"), 1L);
            Objc.msgSendVoidLong(desc, Objc.selector("setHeight:"), 1L);
            Objc.msgSendVoidLong(desc, Objc.selector("setUsage:"), 1L);
            Objc.msgSendVoidLong(desc, Objc.selector("setStorageMode:"), 0L);
            dummyAtlasTexture = Objc.msgSendId(metalDevice, Objc.selector("newTextureWithDescriptor:"), desc);
            Objc.msgSendVoid(desc, Objc.selector("release"));
            if (dummyAtlasTexture == 0) {
                throw new IllegalStateException("newTextureWithDescriptor: returned nil (dummy atlas texture)");
            }
        }
    }

    private static void ensureMaskTexture(int width, int height) {
        if (maskTextureView != null) {
            maskTextureView.close();
            maskTextureView = null;
        }
        if (maskTexture != null) {
            maskTexture.close();
            maskTexture = null;
        }
        maskTexture = RenderSystem.getDevice().createTexture("Fornax Metal RT Sun Mask",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.R8_UNORM, width, height, 1, 1);
        maskTextureView = RenderSystem.getDevice().createTextureView(maskTexture);
    }

    private static void ensureDebugSceneTexture(int width, int height) {
        if (debugSceneTextureView != null) {
            debugSceneTextureView.close();
            debugSceneTextureView = null;
        }
        if (debugSceneTexture != null) {
            debugSceneTexture.close();
            debugSceneTexture = null;
        }
        debugSceneTexture = RenderSystem.getDevice().createTexture("Fornax Metal RT Scene Debug",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
        debugSceneTextureView = RenderSystem.getDevice().createTextureView(debugSceneTexture);
    }

    /** Fills the 144-byte {@code RtTraceConstants} buffer {@code rt_trace.metal} documents at its
     * own declaration: see that file's byte-offset table, which this method's field order and
     * comments mirror exactly. */
    private static void writeConstants(MemorySegment seg, int width, int height, VoxelWindow.WindowState window) {
        float[] invProjModelView = FrameCameraState.invProjModelView();
        for (int i = 0; i < 16; i++) {
            seg.set(ValueLayout.JAVA_FLOAT, (long) i * 4, invProjModelView[i]);
        }
        seg.set(ValueLayout.JAVA_FLOAT, 64, EmitterFrameState.camX());
        seg.set(ValueLayout.JAVA_FLOAT, 68, EmitterFrameState.camY());
        seg.set(ValueLayout.JAVA_FLOAT, 72, EmitterFrameState.camZ());
        seg.set(ValueLayout.JAVA_FLOAT, 76, 0.0f);
        int firstX = window.centerX() - window.radius();
        int firstY = window.centerY() - window.radius();
        int firstZ = window.centerZ() - window.radius();
        seg.set(ValueLayout.JAVA_FLOAT, 80, firstX * 16.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 84, firstY * 16.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 88, firstZ * 16.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 92, 0.0f);
        Vector3f sun = SunDirection.computeSunDirection(SUN_SCRATCH);
        seg.set(ValueLayout.JAVA_FLOAT, 96, sun.x());
        seg.set(ValueLayout.JAVA_FLOAT, 100, sun.y());
        seg.set(ValueLayout.JAVA_FLOAT, 104, sun.z());
        seg.set(ValueLayout.JAVA_FLOAT, 108, 0.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 112, MAX_DISTANCE);
        // Actual centered RT domain, not the larger source-harvest window.
        seg.set(ValueLayout.JAVA_INT, 116, window.diameter());
        seg.set(ValueLayout.JAVA_INT, 120, width);
        seg.set(ValueLayout.JAVA_INT, 124, height);
        // Allocation alone cannot certify a miss; every domain section must have current geometry.
        // ABI mode 2 selects the coherent per-section readiness buffer (0 is invalid, 1 is the
        // legacy synthetic fixture mode retained for existing screen diagnostic tests).
        seg.set(ValueLayout.JAVA_INT, 128, 2);
        seg.set(ValueLayout.JAVA_INT, 132, firstX);
        seg.set(ValueLayout.JAVA_INT, 136, firstY);
        seg.set(ValueLayout.JAVA_INT, 140, firstZ);
    }

    /** Fills the 96-byte {@code RtDebugConstants} buffer {@code rt_debug.metal} documents at its
     * own declaration. The ray origin needs no separate camera-relative reconstruction the way
     * {@link #writeConstants} needs for a shadow ray from a G-buffer point: a primary ray starts at
     * the camera itself, so its grid-space position ({@code camAbs - firstSectionTimes16}) is
     * computed once here rather than carried as the two separate quantities {@code rt_trace}
     * needs. */
    private static void writeDebugConstants(MemorySegment seg, int width, int height,
            VoxelWindow.WindowState window, RtDebugMode mode) {
        float[] invProjModelView = FrameCameraState.invProjModelView();
        for (int i = 0; i < 16; i++) {
            seg.set(ValueLayout.JAVA_FLOAT, (long) i * 4, invProjModelView[i]);
        }
        int firstX = window.centerX() - window.radius();
        int firstY = window.centerY() - window.radius();
        int firstZ = window.centerZ() - window.radius();
        seg.set(ValueLayout.JAVA_FLOAT, 64, EmitterFrameState.camX() - firstX * 16.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 68, EmitterFrameState.camY() - firstY * 16.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 72, EmitterFrameState.camZ() - firstZ * 16.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 76, 0.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 80, MAX_DISTANCE);
        seg.set(ValueLayout.JAVA_INT, 84, mode.shaderMode());
        seg.set(ValueLayout.JAVA_INT, 88, width);
        seg.set(ValueLayout.JAVA_INT, 92, height);
    }
}
