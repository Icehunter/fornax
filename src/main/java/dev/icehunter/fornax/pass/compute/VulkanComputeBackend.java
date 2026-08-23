package dev.icehunter.fornax.pass.compute;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandPool;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.mixin.vulkan.GpuDeviceBackendAccessor;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the compute-dispatch seam into Blaze3D's own already-running Vulkan device: reached via
 * {@link GpuDeviceBackendAccessor} (one mixin-exposed field), everything past that is public API
 * on {@link VulkanDevice}/{@link VulkanQueue}/{@link VulkanCommandPool}. No separate Vulkan
 * instance/device is created -- this rides Mojang's own, the same one every graphics pass in this
 * mod already draws through.
 */
public final class VulkanComputeBackend implements AutoCloseable {
    /**
     * The single, process-wide synchronization point for EVERY use of the shared native compute
     * queue and EVERY brick-grid buffer lifecycle mutation. This is genuinely one contended
     * resource: {@link #tryCreate()} hands back a fresh {@link VulkanCommandPool} per call but the
     * SAME underlying {@link VulkanQueue}/{@code VkQueue} handle every time (confirmed via {@code
     * javap} on {@code VulkanDevice}: {@code computeQueue()} returns a cached field, not a new queue),
     * and {@code vkQueueSubmit}/{@code vkQueueWaitIdle} require external synchronization per the Vulkan
     * spec (neither {@code VulkanQueue} nor its {@code Submission} is internally synchronized -- no
     * {@code ACC_SYNCHRONIZED}).
     *
     * <p>Held by ALL of these, so no two ever touch the queue -- or a buffer another is mid-submission
     * against -- concurrently:
     * <ul>
     *   <li>{@code TargetRegistry.ensureBufferSize} (render thread): {@code vmaCreateBuffer} + clear
     *       submit + destroy of the OLD buffer + the {@code buffers}-map mutation;</li>
     *   <li>{@code TargetRegistry.close} (render thread, pack unload): {@code vmaDestroyBuffer} of every
     *       brick-grid buffer + the {@code buffers}-map clear;</li>
     *   <li>{@code BrickGridUpload.uploadSlot} (Sodium's multi-threaded chunk-build workers): reads the
     *       occupancy/payload buffer handles AND records+submits a {@code vkCmdUpdateBuffer} write against
     *       them, both inside one lock hold -- so a concurrent {@code close}/{@code ensureBufferSize} can
     *       neither free nor reassign a buffer between the handle read and the submit (the use-after-free
     *       the Task 11 review flagged). Unlike the buffer-lifecycle paths below, it does NOT drain the
     *       queue: it waits on a per-call {@code VkFence} scoped to just its own submission, so a burst of
     *       worker uploads no longer blocks the render thread out of this lock behind whole-queue idles;</li>
     *   <li>{@code VoxelDebugRaymarchPass}'s raymarch dispatch (render thread): reads the same occupancy
     *       handle, updates its descriptor set, and submits, all under one hold -- but NOT the completion
     *       wait, which it does via a per-frame {@code VkFence} outside this lock (it runs every frame and
     *       must not stall the queue), so unlike the paths below it does not hold this lock across a wait;</li>
     *   <li>{@code ComputePassRunner.run} (render thread): a pack compute pass's own queue submit.</li>
     * </ul>
     *
     * A SINGLE lock (rather than each call site's own) is required because the hazard is cross-cutting:
     * a worker-thread upload can collide with a render-thread buffer free OR a render-thread submit, and
     * only one shared monitor serializes all three. Because {@code TargetRegistry.getBuffer}'s {@code
     * buffers} map is a plain {@code LinkedHashMap}, every reader that dereferences a handle it returns
     * (uploadSlot, the raymarch) also holds this lock, and every writer (ensureBufferSize/close) mutates
     * the map under it -- so the map access is serialized too, not just the native calls. Reentrant
     * (plain {@code synchronized}); no site nests a different lock inside it, so there is no lock-ordering
     * deadlock. It IS held across {@code waitIdle} by the buffer-lifecycle paths ({@code
     * ensureBufferSize}/{@code close}), which globally serializes those compute submissions -- an accepted
     * cost for their rare (not per-frame) use. The upload path ({@code BrickGridUpload.uploadSlot}) also
     * holds the lock across its completion wait, but that wait is a per-call {@code VkFence} scoped to its
     * OWN single submission, not a whole-queue {@code waitIdle} -- so while it still serializes uploads
     * against each other and against the lifecycle paths (that is the point of the lock), it no longer
     * drains unrelated in-flight work off the shared queue. The per-frame raymarch is the one path that
     * deliberately does NOT wait under this lock at all, fencing its completion outside it instead so it
     * never stalls the queue every frame.
     */
    public static final Object SHARED_QUEUE_LOCK = new Object();

    /** Logged once, process-wide, the first time {@link #tryCreate()} ever runs -- see the log site
     * itself for why a plain per-call log was spam. {@code AtomicBoolean} (not a plain boolean, let
     * alone {@code loggedTexelBufferBindOnce}'s volatile-boolean pattern) because {@link #tryCreate}
     * is called from Sodium's multi-threaded chunk-build workers too (see {@code
     * BrickGridUpload#uploadSlot}), not just the render thread -- a compare-and-set is the only way
     * to guarantee exactly one thread ever logs, not merely "probably one." */
    private static final AtomicBoolean loggedQueueTopologyOnce = new AtomicBoolean(false);

    private final VulkanDevice device;
    private final VulkanQueue computeQueue;
    private final VulkanCommandPool commandPool;
    private final boolean sharesQueueFamilyWithGraphics;

    private VulkanComputeBackend(VulkanDevice device, VulkanQueue computeQueue, VulkanCommandPool commandPool,
                                  boolean sharesQueueFamilyWithGraphics) {
        this.device = device;
        this.computeQueue = computeQueue;
        this.commandPool = commandPool;
        this.sharesQueueFamilyWithGraphics = sharesQueueFamilyWithGraphics;
    }

    /**
     * Returns null on the GL backend (no Vulkan device to reach) or before any GPU device exists
     * yet -- mirrors {@code TargetRegistry.reconcile}'s own {@code RenderSystem.tryGetDevice() ==
     * null} guard, the established "retry next frame, never throw" convention for device-not-ready.
     */
    @Nullable
    public static VulkanComputeBackend tryCreate() {
        GpuDevice gpuDevice = RenderSystem.tryGetDevice();
        if (gpuDevice == null) {
            return null;
        }
        GpuDeviceBackend backend = ((GpuDeviceBackendAccessor) (Object) gpuDevice).fornax$backend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            return null; // GL backend: no compute path exists, entire RT stack degrades (see FX_COMPUTE)
        }

        VulkanQueue computeQueue = vulkanDevice.computeQueue();
        VulkanQueue graphicsQueue = vulkanDevice.graphicsQueue();
        boolean sameFamily = computeQueue.queueFamilyIndex() == graphicsQueue.queueFamilyIndex();
        if (loggedQueueTopologyOnce.compareAndSet(false, true)) {
            // This fact never changes mid-session (queue family assignment is fixed at device
            // creation), but tryCreate() itself is called far more than once per session -- every
            // BrickGridUpload.uploadSlot/clearLightSlot call (per-section, from Sodium's worker
            // threads) and every TargetRegistry buffer (re)allocation makes its own throwaway
            // VulkanComputeBackend, so an unconditional log here spammed once per resync/upload
            // instead of once ever. Observed live on Apple M-series (Metal-backed MoltenVK): the
            // compute queue family is GENUINELY separate from the graphics queue family (family 3 vs
            // family 0) -- real hardware topology, not a driver quirk. Compute-only chains rely on
            // same-queue submission order; outputs consumed by graphics use ComputePassRunner's
            // explicit semaphore path. TargetRegistry allocates shared buffers concurrently across
            // both families, so no ownership transfer is required.
            FornaxMod.LOGGER.info("[Fornax] Compute backend: compute queue family {} ({} graphics queue family {})",
                    computeQueue.queueFamilyIndex(), sameFamily ? "same as" : "DIFFERENT from", graphicsQueue.queueFamilyIndex());
        }

        VulkanCommandPool pool = new VulkanCommandPool(vulkanDevice, computeQueue);
        return new VulkanComputeBackend(vulkanDevice, computeQueue, pool, sameFamily);
    }

    public VulkanDevice device() {
        return device;
    }

    public VulkanQueue computeQueue() {
        return computeQueue;
    }

    public VulkanCommandPool commandPool() {
        return commandPool;
    }

    /**
     * Whether the compute queue Mojang's device exposes is the SAME hardware queue family as the
     * graphics queue. If true, a compute dispatch this backend records can be synchronized against
     * graphics-queue work with a plain {@code vkCmdPipelineBarrier} in a shared timeline; if false,
     * cross-queue semaphores (via {@code VulkanQueue.Submission.waitSemaphore}/{@code
     * signalSemaphore}) are required instead. Logged at {@link #tryCreate} so this is known from
     * the first deploy, before any later milestone's code needs to branch on it.
     */
    public boolean sharesQueueFamilyWithGraphics() {
        return sharesQueueFamilyWithGraphics;
    }

    @Override
    public void close() {
        commandPool.destroy();
    }

    /**
     * Drains both the graphics and compute queues before any GPU texture is destroyed. {@code
     * GpuTexture.close()}/{@code GpuTextureView.close()} defer their actual {@code
     * vkDestroyImage(View)} call through Blaze3D's own per-submission destruction ring (verified via
     * {@code javap} against the real MC jar: {@code VulkanGpuTexture}/{@code
     * VulkanGpuTextureView.close()} both route into {@code VulkanCommandEncoder.queueForDestroy}),
     * but that ring only rotates -- and only ever actually destroys a queued entry -- once per
     * {@code VulkanCommandEncoder.submit()}, i.e. once per Blaze3D-owned GRAPHICS-queue submission.
     * This mod also submits GPU work directly against the COMPUTE queue via raw {@code
     * vkQueueSubmit} ({@code ComputePassRunner.run}, {@code TargetRegistry.clearBuffer}, {@code
     * VoxelDebugRaymarchPass}'s dispatch), entirely outside that ring's bookkeeping -- a compute
     * pass still mid-dispatch against one of these targets (bound as a {@code
     * COMBINED_IMAGE_SAMPLER} input, see {@code ComputePassRunner.updateAndBindDescriptorSet}) is
     * therefore invisible to Blaze3D's own destroy gate. Two crashes caught live (hs_err_pid16681.log,
     * 2026-07-15 18:32:40, SIGSEGV; hs_err_pid32359.log, 20:30:11, SIGBUS, 24 bytes apart -- same call
     * site) both fault inside {@code libMoltenVK.dylib}'s {@code vkQueueSubmit2KHR}, reached via
     * {@code VulkanQueue$Submission.close() -> VulkanCommandEncoder.submit()} -- consistent with a
     * texture destroyed out from under a still-executing submission on either queue (see commit
     * a32f118, "Wait for GPU idle before destroying render-graph textures", the original fix for
     * {@code GraphRunner.closeCurrent()}/{@code TargetRegistry}'s texture-teardown paths).
     *
     * <p>Promoted here (out of {@code TargetRegistry}, its original home) so every {@code ensureSize}-
     * shaped rebuild that destroys an old texture/view on a live per-frame path -- not just
     * {@code TargetRegistry}'s own -- can share one guard: {@code OpaqueDepth}, {@code
     * GBufferManager}, {@code ShadowMapManager}, {@code WaterSurfaceManager}, and {@code
     * MipchainRunner} all rebuild their GPU-backed texture(s) from a call reached every frame
     * (directly or via a mixin HEAD inject) from {@code GraphRunner.prepare()}'s own call chain, on
     * the SAME live objects {@code GraphRunner.closeCurrent()}'s own top-of-method wait-idle does NOT
     * cover (that one only guards teardown, not the live per-frame resize path) -- exactly the same
     * "can fire mid-session outside any closeCurrent() call" hazard {@code TargetRegistry.ensureSize}'s
     * own removeIf branch already documented for itself.
     *
     * <p>Called only right before an actual destroy (never unconditionally from an {@code ensureSize}
     * per-frame steady state, where the size already matches and the method returns before reaching
     * this), so this costs nothing once a target is already stable at its current size.
     */
    public static void waitForGpuIdleBeforeDestroy() {
        synchronized (SHARED_QUEUE_LOCK) {
            try (VulkanComputeBackend backend = tryCreate()) {
                if (backend != null) {
                    backend.device().graphicsQueue().waitIdle();
                    backend.computeQueue().waitIdle();
                }
            }
        }
    }
}
