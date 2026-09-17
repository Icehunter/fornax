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
import dev.icehunter.fornax.config.RtDebugScene;
import dev.icehunter.fornax.config.GBufferDebugView;
import dev.icehunter.fornax.pass.shadow.ShadowFrameState;
import dev.icehunter.fornax.pass.shadow.TerrainShadowResult;
import dev.icehunter.fornax.rt.CelestialFill;
import dev.icehunter.fornax.rt.RayTier;
import dev.icehunter.fornax.voxel.SectionHarvester;
import org.joml.Matrix4f;
import java.util.HashMap;
import dev.icehunter.fornax.metalfx.VulkanMetalInterop;
import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
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
    // 192: the int4 first-section member gives RtSunDepthConstants 16-byte alignment, so the
    // tier and fill-mode words at 176 and 180 sit in a tail padded out to 192.
    private static final long SUN_CONSTANTS_BYTES = 192;

    /** {@code RtDebugConstants} total size (see {@code rt_debug.metal}'s own layout comment). */
    private static final long DEBUG_CONSTANTS_BYTES = 96;

    /** {@code rt_trace}'s own thread-per-pixel dispatch tile. */
    private static final long TRACE_THREADS_PER_GROUP_X = 8;
    private static final long TRACE_THREADS_PER_GROUP_Y = 8;

    private static boolean failed;


    /** The voxel window reach the log last reported; see the mesh tier's own note. */
    private static float reportedVoxelRadius = Float.NaN;

    /**
     * The timeline value the last committed trace signals, and whether its image still owes the
     * pack a copy. The copy is taken at the START of the next frame rather than the end of this
     * one: waiting on a value Metal has not signalled yet blocks the render thread inside
     * vkQueueSubmit, which measured at 7.8 ms a frame, while waiting on one signalled a whole frame
     * ago costs nothing. The pack reads a one-frame-old celestial image in exchange, which is
     * invisible against a shadow map that already lags the camera through its own warp.
     */
    private static long pendingPublishValue;
    private static boolean publishPending;
    /** The image that pending publish reads. Held so a handover from one frame is not read from
     * this frame's image, which may be a different allocation after a resolution change. */
    private static VulkanMetalInterop.InteropImage pendingPublishImage;

    private static MetalRtShaders.Compiled compiled;

    /**
     * The cascade's celestial image, round-tripped through Metal. The engine's copy of it lives in
     * a Blaze3D texture that Metal cannot address, so the fill is: copy it in, let the voxel
     * traversal write only the texels no higher tier answered, copy it back. One image, two tiers,
     * one frame.
     */
    private static VulkanMetalInterop.InteropImage celestialIo;
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

    private static final Vector3f SUN_SCRATCH = new Vector3f();

    private MetalRtShadowPass() {
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
        runIfEnabled(gbuffer, wanted, null);
    }

    /**
     * @param celestial when non-null, this frame also fills {@code TerrainShadowResult} at the
     *                  {@code HARDWARE_VOXEL} tier, writing only texels no higher tier answered.
     *                  Its presence is also a reason to bring the backend up at all: a pack that
     *                  reads only the cascade's image subscribes to none of the legacy targets.
     */
    public static void runIfEnabled(GBuffer gbuffer, boolean wanted, CelestialFill celestial) {
        // A selected scene-debug mode is its own reason to bring the backend up. Without this the
        // diagnostic is unreachable on any pack that consumes the mesh-based rtTerrainShadowDepth
        // rather than the legacy rtSunDepth: the setting reads Normal, the view reads Voxel RT
        // scene, and the pass returns here before tracing anything, so the screen never changes and
        // nothing reports why.
        // A scene-debug mode is its own reason to bring the backend up, and so is a celestial
        // request: a pack that reads only the cascade's image subscribes to nothing else.
        boolean consumer = FornaxConfig.get().rtDebugMode != RtDebugMode.OFF || celestial != null;
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
            return;
        }
        try {
            run(gbuffer, celestial);
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
            // The cascade's own image needs no clear here: it carries per-texel validity, and a
            // session that will never trace again stops writing any, so every texel reads
            // as unanswered and the pack falls back to raster on its own.
            TerrainShadowResult.invalidate();
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
                VulkanMetalInterop.destroyImage(device, debugSceneOut);
                VulkanMetalInterop.destroyImage(device, atlasIn);
            }
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



    private static void run(GBuffer gbuffer, CelestialFill celestial) {
        debugSceneRenderedThisRun = false;
        // The scene debug traces in screen space, so it needs a G-buffer; the celestial fill
        // does not, and runs earlier in the frame than one exists.
        boolean debugTrace = gbuffer != null && FornaxConfig.get().rtDebugMode != RtDebugMode.OFF;
        // The cascade's own image has to exist before it can be filled; a pack that never declares
        // rtTerrainShadowDepth has no texture here, so this tier has nothing to do.
        boolean celestialFill = celestial != null && TerrainShadowResult.texture() != null;
        // The tier above hands over the image it traced, so this tier fills the same one rather
        // than copying a fresh one in and the result back out. Two Vulkan submits saved per frame,
        // which measured larger than the tracing itself.
        MeshMetalProvider.CascadeImage handover = celestialFill
                ? dev.icehunter.fornax.rt.RayRouter.provider(MeshMetalProvider.class)
                        .map(MeshMetalProvider::cascadeImage).orElse(null)
                : null;
        if (!debugTrace && !celestialFill) return;
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        if (device == null) {
            throw new IllegalStateException("no Vulkan device (GL backend?)");
        }
        ensureShaders();

        VoxelWindow.WindowState sourceWindow = VoxelWindow.currentState();
        VoxelWindow.WindowState window = MetalRtGeometry.exportedWindow(sourceWindow);
        int width = gbuffer != null ? gbuffer.getWidth() : 1;
        int height = gbuffer != null ? gbuffer.getHeight() : 1;
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
        ensureImages(device, debugTrace ? width : 1, debugTrace ? height : 1);
        if (celestialFill && handover == null) ensureCelestialImage(device, celestial.resolution());
        ensureAtlasImage(device);
        if (timeline == null) {
            timeline = VulkanMetalInterop.createSharedTimeline(device);
        }

        // Sub-phase timing, because the tier's total says only that it is expensive. Each row is
        // CPU time on the render thread: encode and submit, not GPU execution.
        long phaseStart = System.nanoTime();
        long commandQueue = VulkanMetalInterop.metalCommandQueue();
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
                    copyBlockAtlasIfChanged(cmd, stack);
                    // Only when no tier above handed one over: theirs is already Metal-side and
                    // already carries this frame's answers.
                    if (celestialFill && handover == null) copyCelestialIn(cmd, stack);
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
        phaseStart = record("RT voxel copy-in CPU", phaseStart);
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
            phaseStart = record("RT voxel structures CPU", phaseStart);
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
                if (celestialFill) {
                    encodeCelestialFill(cb, commandQueue, instanceStructure, readinessBuffer, window,
                            celestial, handover);
                }
                RtDebugMode debugMode = FornaxConfig.get().rtDebugMode;
                if (debugMode != RtDebugMode.OFF) {
                    // Second, independent dispatch in the same command buffer: rt_debug traces a
                    // primary ray per pixel rather than reading rt_trace's shadow-ray inputs, so it
                    // needs none of maskOut/depthIn/normalIn, only the instance structure and its
                    // own constants.
                    // Which structure the view shows is the owner's choice, and the two scenes are
                    // addressed in different frames: the voxel window's first section, or the mesh
                    // tier's coarse grid origin. The rays are built from whichever one is traced,
                    // so the scene and its origin always travel together.
                    MeshMetalProvider.DebugScene meshScene =
                            FornaxConfig.get().rtDebugScene == RtDebugScene.MESH
                                    ? dev.icehunter.fornax.rt.RayRouter.provider(MeshMetalProvider.class)
                                            .map(MeshMetalProvider::debugScene).orElse(null)
                                    : null;
                    try (Arena local = Arena.ofConfined()) {
                        MemorySegment debugConstants = local.allocate(DEBUG_CONSTANTS_BYTES);
                        if (meshScene != null) {
                            writeDebugConstants(debugConstants, width, height, debugMode,
                                    meshScene.originX(), meshScene.originY(), meshScene.originZ());
                        } else {
                            writeDebugConstants(debugConstants, width, height, window, debugMode);
                        }

                        if (meshScene != null && meshScene.readyEvent() != 0) {
                            // The mesh tier builds its structures in its own command buffer. A
                            // later command buffer on the same queue may begin before an earlier
                            // one finishes, so without this the trace reads a structure mid-build:
                            // the shapes land, because most of the BVH is already there, and the
                            // per-triangle data behind them is garbage. That reads as noise
                            // scattered over surfaces that should each be one flat colour.
                            Objc.msgSendVoidIdLong(cb, Objc.selector("encodeWaitForEvent:value:"),
                                    meshScene.readyEvent(), meshScene.readyValue());
                        }
                        long debugEncoder = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
                        if (debugEncoder == 0) {
                            throw new IllegalStateException("Metal compute encoder nil (rt debug)");
                        }
                        try {
                            Objc.msgSendVoid(debugEncoder, Objc.selector("setComputePipelineState:"), compiled.debug().pipeline());
                            Objc.msgSendVoidIdLong(debugEncoder,
                                    Objc.selector("setAccelerationStructure:atBufferIndex:"),
                                    meshScene != null ? meshScene.structure() : instanceStructure, 0L);
                            Objc.msgSendVoidIdLongLong(debugEncoder, Objc.selector("setBytes:length:atIndex:"),
                                    debugConstants.address(), DEBUG_CONSTANTS_BYTES, 1L);
                            Objc.msgSendVoidIdLong(debugEncoder, Objc.selector("setTexture:atIndex:"), debugSceneOut.mtlTexture, 0L);
                            if (meshScene != null) {
                                for (long resource : meshScene.resources()) {
                                    Objc.msgSendVoidIdLong(debugEncoder, Objc.selector("useResource:usage:"), resource, 1L);
                                }
                            } else {
                                MetalRtAcceleration.useResources(debugEncoder);
                            }
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
                // presenter that uninitialized content.
                FornaxMod.LOGGER.debug("[Fornax] Metal RT voxel tier: no instance structure yet, skipping trace this frame");
            }

            Objc.msgSendVoidIdLong(cb, Objc.selector("encodeSignalEvent:value:"), timeline.mtlSharedEvent, v + 1);
            Objc.msgSendVoid(cb, Objc.selector("commit"));
            phaseStart = record("RT voxel dispatch CPU", phaseStart);
        } finally {
            // Command buffers retain bound resources through completion; each frame has its own
            // immutable readiness allocation, never a CPU overwrite of an in-flight snapshot.
            if (readinessBuffer != 0) Objc.msgSendVoid(readinessBuffer, Objc.selector("release"));
            Objc.autoreleasePoolPop(pool);
        }

        VulkanMetalInterop.CmdRecorder copyBack = cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
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
        // Only the debug scene still copies back within the frame, and only when it was traced:
        // it is a diagnostic nobody profiles, and its output has no next frame to wait for.
        if (debugSceneRenderedThisRun) {
            encoder.waitSemaphore(timeline.vkSemaphore, v + 1, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
            VulkanMetalInterop.recordIntoStream(encoder, copyBack);
            encoder.signalSemaphore(timeline.vkSemaphore, v + 2, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
            long submitStart = record("RT voxel record CPU", phaseStart);
            encoder.submit();
            record("RT voxel submit CPU", submitStart);
            lastVulkanSignal = v + 2;
        } else {
            lastVulkanSignal = v;
        }
        record("RT voxel copy-back CPU", phaseStart);
        timelineValue = v + 3;
        if (celestialFill) {
            // Owed to the pack, taken at the start of the next frame. Publishing here would mean
            // waiting on a value Metal has not signalled yet, which blocks the render thread.
            pendingPublishImage = handover != null ? handover.image() : celestialIo;
            pendingPublishValue = v + 1;
            publishPending = true;
        }
        if (tracedThisFrame && celestialFill) {
            // The window is centred on the camera and spans diameter sections of 16 blocks, so its
            // half-extent is diameter * 8. One block comes off for the outer shell the kernel
            // refuses to certify (an absent owner outside it cannot prove an interior miss), which
            // is the same bound rtRayBox applies to every ray this tier traces.
            float voxelRadius = window.diameter() * 8.0f - 1.0f;
            if (voxelRadius > 0.0f) {
                ShadowFrameState.raiseRtDistance(voxelRadius);
            }
            dev.icehunter.fornax.pack.graph.GraphRunner.frameProfiler()
                    .recordValue("rt_tier2_window_blocks", window.diameter() * 16.0);
            float trusted = (float) Math.sqrt(ShadowFrameState.rtDistanceSquared());
            dev.icehunter.fornax.pack.graph.GraphRunner.frameProfiler()
                    .recordValue("rt_trusted_radius_blocks", trusted);
            // Same reasoning as the mesh tier's own line: reported when it changes, so a reader can
            // tell what this frame's coverage is rather than what some earlier frame's was.
            if (voxelRadius != reportedVoxelRadius) {
                FornaxMod.LOGGER.info(
                        "[Fornax] Voxel RT shadows filling: {} blocks window reach, {} blocks trusted overall",
                        voxelRadius, trusted);
                reportedVoxelRadius = voxelRadius;
            }
        }
    }

    /** Publishes the milliseconds since {@code from} under {@code label}; returns a fresh mark. */
    private static long record(String label, long from) {
        long now = System.nanoTime();
        dev.icehunter.fornax.pack.graph.GraphRunner.frameProfiler().record(label, (now - from) * 1e-6);
        return now;
    }

    /**
     * Delivers the image phase one traced. Waits on the value that trace signals, which by this
     * point in the frame is already signalled, so this records and submits rather than blocking.
     */
    static boolean publishPendingCelestial() {
        if (!publishPending) {
            return false;
        }
        VulkanDevice device = VulkanMetalInterop.vulkanDevice();
        GpuTexture texture = TerrainShadowResult.texture();
        if (device == null || texture == null || pendingPublishImage == null) {
            publishPending = false;
            return false;
        }
        long started = System.nanoTime();
        VulkanCommandEncoder encoder = device.createCommandEncoder();
        encoder.waitSemaphore(timeline.vkSemaphore, pendingPublishValue, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
        VulkanMetalInterop.recordIntoStream(encoder, cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                copyCelestialOut(cmd, stack, pendingPublishImage);
            }
        });
        long publishValue = pendingPublishValue + 1;
        encoder.signalSemaphore(timeline.vkSemaphore, publishValue, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT);
        encoder.submit();
        lastVulkanSignal = publishValue;
        TerrainShadowResult.published();
        publishPending = false;
        record("RT celestial publish CPU", started);
        return true;
    }

    private static void ensureCelestialImage(VulkanDevice device, int resolution) {
        if (resolution < 1) throw new IllegalStateException("the celestial target has no resolution");
        if (celestialIo != null && celestialIo.width == resolution) return;
        if (timeline != null && lastVulkanSignal > 0) VulkanMetalInterop.waitTimeline(device, timeline, lastVulkanSignal);
        VulkanMetalInterop.destroyImage(device, celestialIo);
        // TRANSFER_DST as well as SRC: unlike every other interop image here this one is filled
        // from the engine's own texture before Metal touches it, because its existing contents are
        // exactly what the fill has to respect.
        celestialIo = VulkanMetalInterop.createImage(device, resolution, resolution,
                VK13.VK_FORMAT_R32G32B32A32_SFLOAT,
                VK13.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK13.VK_IMAGE_USAGE_TRANSFER_DST_BIT
                        | VK13.VK_IMAGE_USAGE_STORAGE_BIT | VK13.VK_IMAGE_USAGE_SAMPLED_BIT,
                VK13.VK_IMAGE_ASPECT_COLOR_BIT);
    }

    private static void copyCelestialIn(VkCommandBuffer cmd, MemoryStack stack) {
        GpuTexture texture = TerrainShadowResult.texture();
        if (texture == null) throw new IllegalStateException("the celestial target disappeared during trace");
        long source = ((VulkanGpuTexture) texture).vkImage();
        VulkanMetalInterop.prepareGeneralTransferRead(cmd, stack, source, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        VulkanMetalInterop.prepareInteropTransferWrite(cmd, stack, celestialIo);
        VulkanMetalInterop.copyImage(cmd, stack, source, VK13.VK_IMAGE_LAYOUT_GENERAL,
                celestialIo.image, celestialIo.layout, VK13.VK_IMAGE_ASPECT_COLOR_BIT,
                celestialIo.width, celestialIo.height);
        VulkanMetalInterop.finishInteropTransferWrite(cmd, stack, celestialIo);
        VulkanMetalInterop.prepareInteropMetalWrite(cmd, stack, celestialIo);
    }

    private static void copyCelestialOut(VkCommandBuffer cmd, MemoryStack stack,
            VulkanMetalInterop.InteropImage source) {
        GpuTexture texture = TerrainShadowResult.texture();
        if (texture == null) throw new IllegalStateException("the celestial target disappeared during trace");
        if (source == null) {
            return;
        }
        long destination = ((VulkanGpuTexture) texture).vkImage();
        VulkanMetalInterop.prepareInteropTransferRead(cmd, stack, source);
        VulkanMetalInterop.prepareGeneralTransferWrite(cmd, stack, destination, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        VulkanMetalInterop.copyImage(cmd, stack, source.image, source.layout,
                destination, VK13.VK_IMAGE_LAYOUT_GENERAL, VK13.VK_IMAGE_ASPECT_COLOR_BIT,
                source.width, source.height);
        VulkanMetalInterop.finishInteropTransferRead(cmd, stack, source);
        VulkanMetalInterop.finishGeneralTransferWrite(cmd, stack, destination, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
    }

    /**
     * The voxel tier's celestial dispatch: the same kernel the legacy sun path uses, in fill mode
     * against the shared image, so it writes only where no higher tier answered.
     */
    private static void encodeCelestialFill(long cb, long queue, long instances, long readiness,
            VoxelWindow.WindowState window, CelestialFill request,
            MeshMetalProvider.CascadeImage handover) {
        // The tier above traces in its own command buffer, and Metal starts command buffers in
        // order without holding a later one until an earlier finishes. Reading its image without
        // this wait samples a half-written trace.
        if (handover != null && handover.readyEvent() != 0) {
            Objc.msgSendVoidIdLong(cb, Objc.selector("encodeWaitForEvent:value:"),
                    handover.readyEvent(), handover.readyValue());
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment constants = arena.allocate(SUN_CONSTANTS_BYTES);
            writeSunConstants(constants, request.resolution(), window,
                    RayTier.HARDWARE_VOXEL.ordinal(), true);
            long encoder = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
            if (encoder == 0) throw new IllegalStateException("Metal compute encoder nil (celestial fill)");
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
                VulkanMetalInterop.InteropImage target = handover != null ? handover.image() : celestialIo;
                Objc.msgSendVoidIdLong(encoder, Objc.selector("setTexture:atIndex:"), target.mtlTexture, 0L);
                Objc.msgSendVoidIdLong(encoder, Objc.selector("setTexture:atIndex:"), atlasIn != null ? atlasIn.mtlTexture : dummyAtlasTexture, 4L);
                MetalRtAcceleration.useResources(encoder);
                Objc.dispatchThreadgroups(encoder,
                        (target.width + TRACE_THREADS_PER_GROUP_X - 1) / TRACE_THREADS_PER_GROUP_X,
                        (target.height + TRACE_THREADS_PER_GROUP_Y - 1) / TRACE_THREADS_PER_GROUP_Y,
                        1, TRACE_THREADS_PER_GROUP_X, TRACE_THREADS_PER_GROUP_Y, 1);
            } finally {
                Objc.msgSendVoid(encoder, Objc.selector("endEncoding"));
            }
        }
    }




    /**
     * @param tier     the {@code RayTier} ordinal this dispatch writes into G on every texel it answers
     * @param fillMode true when another tier has already written into this image, so answered texels
     *                 must be read and left alone; false when this dispatch owns the whole image and
     *                 clears each texel before deciding
     */
    static void writeSunConstants(MemorySegment seg, int resolution, VoxelWindow.WindowState window,
            int tier, boolean fillMode) {
        Matrix4f projection = new Matrix4f(ShadowFrameState.current());
        int firstX = window.centerX() - window.radius();
        int firstY = window.centerY() - window.radius();
        int firstZ = window.centerZ() - window.radius();
        float[] inverse = new Matrix4f(projection).invert().get(new float[16]);
        float[] forward = projection.get(new float[16]);
        for (int i = 0; i < 16; ++i) {
            seg.set(ValueLayout.JAVA_FLOAT, i * 4L, inverse[i]);
            seg.set(ValueLayout.JAVA_FLOAT, 80 + i * 4L, forward[i]);
        }
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
        seg.set(ValueLayout.JAVA_INT, 176, tier);
        seg.set(ValueLayout.JAVA_INT, 180, fillMode ? 1 : 0);
        seg.set(ValueLayout.JAVA_INT, 184, 0);
        seg.set(ValueLayout.JAVA_INT, 188, 0);
    }

    private static void ensureShaders() {
        if (compiled != null) {
            return;
        }
        long metalDevice = Objc.msgSendId(VulkanMetalInterop.metalCommandQueue(), Objc.selector("device"));
        compiled = MetalRtShaders.compile(metalDevice);
    }

    private static void ensureImages(VulkanDevice device, int width, int height) {
        if (debugSceneOut != null && debugSceneOut.width == width && debugSceneOut.height == height) {
            return;
        }
        if (timeline != null && lastVulkanSignal > 0) {
            VulkanMetalInterop.waitTimeline(device, timeline, lastVulkanSignal);
        }
        VulkanMetalInterop.destroyImage(device, debugSceneOut);
        // RGBA16F rather than a single scalar: several debug modes (hit/miss, instance and
        // primitive id hashes, ray direction, face normal) write a genuine colour.
        int debugSceneUsage = VK13.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK13.VK_IMAGE_USAGE_STORAGE_BIT
                | VK13.VK_IMAGE_USAGE_SAMPLED_BIT;
        debugSceneOut = VulkanMetalInterop.createImage(device, width, height,
                VK13.VK_FORMAT_R16G16B16A16_SFLOAT, debugSceneUsage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
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
    /**
     * The block atlas as Metal sees it, or the opaque fallback before a pack has captured one. A
     * ray query alpha-tests against this; binding nothing would make every cutout see-through.
     */
    static long atlasTexture() {
        return atlasIn != null ? atlasIn.mtlTexture : dummyAtlasTexture;
    }

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


    /** Fills the 96-byte {@code RtDebugConstants} buffer {@code rt_debug.metal} documents at its
     * own declaration. The ray origin needs no separate camera-relative reconstruction the way
     * {@link #writeConstants} needs for a shadow ray from a G-buffer point: a primary ray starts at
     * the camera itself, so its grid-space position ({@code camAbs - firstSectionTimes16}) is
     * computed once here rather than carried as the two separate quantities {@code rt_trace}
     * needs. */
    private static void writeDebugConstants(MemorySegment seg, int width, int height,
            VoxelWindow.WindowState window, RtDebugMode mode) {
        int firstX = window.centerX() - window.radius();
        int firstY = window.centerY() - window.radius();
        int firstZ = window.centerZ() - window.radius();
        writeDebugConstants(seg, width, height, mode,
                firstX * 16.0f, firstY * 16.0f, firstZ * 16.0f);
    }

    /** @param originX the world origin the traced scene's own coordinates are relative to */
    private static void writeDebugConstants(MemorySegment seg, int width, int height, RtDebugMode mode,
            float originX, float originY, float originZ) {
        float[] invProjModelView = FrameCameraState.invProjModelView();
        for (int i = 0; i < 16; i++) {
            seg.set(ValueLayout.JAVA_FLOAT, (long) i * 4, invProjModelView[i]);
        }
        seg.set(ValueLayout.JAVA_FLOAT, 64, EmitterFrameState.camX() - originX);
        seg.set(ValueLayout.JAVA_FLOAT, 68, EmitterFrameState.camY() - originY);
        seg.set(ValueLayout.JAVA_FLOAT, 72, EmitterFrameState.camZ() - originZ);
        seg.set(ValueLayout.JAVA_FLOAT, 76, 0.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 80, MAX_DISTANCE);
        seg.set(ValueLayout.JAVA_INT, 84, mode.shaderMode());
        seg.set(ValueLayout.JAVA_INT, 88, width);
        seg.set(ValueLayout.JAVA_INT, 92, height);
    }
}
