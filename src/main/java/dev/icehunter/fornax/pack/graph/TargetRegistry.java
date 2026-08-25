package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.TargetSpec;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import org.jspecify.annotations.Nullable;
import org.joml.Vector4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns every {@link TargetInstance} a loaded pack's {@code GraphSpec.targets()} declares:
 * allocates one per enabled target ({@code enabled_if} false => not allocated, mirroring
 * {@link GraphValidator}'s own VRAM accounting), sized {@code round(renderSize * scale)}, clears
 * every texture at allocation (MoltenVK recycles garbage VRAM -- the same format-level guarantee
 * {@code SsrManager}'s ad-hoc zero-clear existed for, made structural here for every pack target),
 * and swaps history pairs at frame end.
 *
 * <p>{@link #ensureSize} is the sizing entry point, driven by {@link TargetPlan} (the pure-JVM
 * computation of what should exist); this class only reconciles that plan against whatever GPU
 * resources are currently allocated, allocating/freeing the difference -- the same
 * "no-op once already sized" shape every hardcoded manager (GBufferManager, SsaoManager, ...)
 * already follows.
 */
public final class TargetRegistry implements AutoCloseable {
    private final GraphSpec graph;
    private final Map<String, Integer> compileValues;
    private final Map<String, TargetInstance> targets = new LinkedHashMap<>();
    private final Map<String, BufferInstance> buffers = new LinkedHashMap<>();

    /**
     * Deferred-destruction ring for a buffer/texture a live resize just replaced. A wait-idle
     * before freeing (still applied, see {@link #advanceRetirementRing}) only proves the GPU has
     * finished EXECUTING prior submissions -- it says nothing about {@link ComputePassRunner}'s own
     * persistent {@code VkDescriptorSet}s (allocated once, re-pointed at the CURRENT handle via
     * {@code vkUpdateDescriptorSets} every {@code run()} call instead of being recreated -- see that
     * class's own doc). Until a descriptor set referencing the OLD handle is itself rewritten to
     * point at the NEW one, MoltenVK's residency bookkeeping for that set still structurally
     * references the old resource; destroying it before that rewrite has happened -- and been
     * committed via that pass's own next submit -- is a use-after-free from the DRIVER's bookkeeping
     * perspective even though the GPU has stopped executing against it. This is the mechanism behind
     * a live SIGSEGV in {@code AGXG17XFamilyResidencySet
     * -[_commitAddedAllocations:count:removedAllocations:count:]} -- 8 identical native crash
     * stacks, several occurring even after an earlier wait-idle-only fix, proving the idle wait
     * alone does not close this.
     *
     * <p>Each element is one "generation" -- everything retired between two consecutive {@link
     * #advanceRetirementRing} calls, which runs exactly once per frame (from {@link #ensureSize},
     * itself called once per frame from {@code GraphRunner.prepare()} while a pack is active). A
     * generation is actually freed {@link #RETIRE_GENERATIONS} + 1 generations after it was
     * populated -- comfortably more than the one full pass loop every {@code ComputePassRunner}
     * needs to naturally re-{@code vkUpdateDescriptorSets} away from the old handle, regardless of
     * whether the replacing {@code ensureBufferSize}/{@code reconcile} call happened at this frame's
     * START ({@code GraphRunner.prepare()}) or END ({@code VoxelDebugRaymarchPass.onFrame}, called
     * from {@code finish()} AFTER this frame's own pass loop already ran). {@code ensureBufferSize}
     * can retire an entry outside {@code ensureSize}'s own call (that onFrame path) -- {@link
     * #retire} lazily starts a generation bucket if none is open yet, so ordering between the two
     * entry points is never load-bearing.
     */
    private static final int RETIRE_GENERATIONS = 2;
    private final List<List<Runnable>> retiring = new ArrayList<>();

    /**
     * Names {@link #ensureBufferSize} has already reported "no compute backend available" for, so it
     * says so ONCE rather than once per frame.
     *
     * <p>Every pre-existing caller reached that branch at most a handful of times -- they are
     * one-shot or gated on {@code FX_COMPUTE}, which is 0 on the very backend that makes the branch
     * fire. A PACK-sized buffer target is not: {@link #ensureSize} re-asserts it every frame from
     * {@code GraphRunner.prepare()}, and the "already at this size" fast path cannot help while the
     * buffer has never been allocated at all -- so on a backend where {@code
     * VulkanComputeBackend.tryCreate()} always fails, this warning would otherwise repeat at frame
     * rate, forever, for the whole session. Cleared on a successful (re)build so a genuinely
     * TRANSIENT failure (a world-join window) still gets a fresh warning if it recurs.
     *
     * <p>A plain {@link HashSet} because both of its mutations sit inside
     * {@code VulkanComputeBackend.SHARED_QUEUE_LOCK} -- the same lock that already guards
     * {@link #buffers} against {@code BrickGridUpload.uploadSlot}'s worker threads.
     */
    private final Set<String> warnedNoBackend = new HashSet<>();

    private TargetRegistry(GraphSpec graph, Map<String, Integer> compileValues) {
        this.graph = graph;
        this.compileValues = compileValues;
    }

    public static TargetRegistry create(GraphSpec graph, Map<String, Integer> compileValues) {
        return new TargetRegistry(graph, compileValues);
    }

    /**
     * Queues {@code destroyAction} to actually run {@link #RETIRE_GENERATIONS} full frames from now,
     * instead of running it immediately -- see {@link #retiring}'s own doc for why. Safe to call
     * before this registry's first {@link #advanceRetirementRing} (lazily opens a generation bucket).
     */
    private void retire(Runnable destroyAction) {
        if (retiring.isEmpty()) {
            retiring.add(new ArrayList<>());
        }
        retiring.get(retiring.size() - 1).add(destroyAction);
    }

    /**
     * Opens a fresh generation bucket for this frame's retirements, then actually destroys whatever
     * has aged past {@link #RETIRE_GENERATIONS}. One combined {@code waitForGpuIdleBeforeDestroy()}
     * covers every destroy action a generation accumulated (typically zero -- a live resize is rare),
     * not one per action.
     */
    private void advanceRetirementRing() {
        retiring.add(new ArrayList<>());
        while (retiring.size() > RETIRE_GENERATIONS + 1) {
            List<Runnable> aged = retiring.remove(0);
            if (!aged.isEmpty()) {
                VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
                for (Runnable action : aged) {
                    action.run();
                }
            }
        }
    }

    @Nullable
    public TargetInstance get(String name) {
        return targets.get(name);
    }

    /**
     * How a pass sampling {@code name} should filter it -- see {@link TargetFilter}.
     *
     * <p>NEAREST for any name this registry does not own (an engine builtin, a pack texture asset,
     * an unknown reference), which is the pre-existing behaviour for all of those and saves the
     * caller a null check.
     */
    public TargetFilter filterFor(String name) {
        if (name.endsWith(".history")) {
            name = name.substring(0, name.length() - ".history".length());
        }
        TargetSpec spec = graph.targets().get(name);
        return spec == null ? TargetFilter.NEAREST : spec.filter();
    }

    /** True only for a graph-owned texture explicitly declared storage-capable. */
    public boolean isStorageTexture(String name) {
        if (name.endsWith(".history")) {
            name = name.substring(0, name.length() - ".history".length());
        }
        TargetSpec spec = graph.targets().get(name);
        return spec != null && spec.kind() == TargetKind.TEXTURE && spec.storage();
    }

    /** True for a declared graph-owned texture even before its GPU allocation exists. */
    public boolean isTextureTarget(String name) {
        if (name.endsWith(".history")) {
            name = name.substring(0, name.length() - ".history".length());
        }
        TargetSpec spec = graph.targets().get(name);
        return spec != null && spec.kind() == TargetKind.TEXTURE;
    }

    /**
     * Allocates/resizes every enabled target to {@code round(basisSize * its own scale)} --
     * {@code renderWidth}/{@code renderHeight} for a RENDER-basis target (the default; today this
     * is every pack-declared target), {@code outputWidth}/{@code outputHeight} for an OUTPUT-basis
     * one (e.g. the engine-injected {@code sceneHistory}).
     *
     * <p>Also allocates every PACK-SIZED buffer target -- see {@link #reconcilePackSizedBuffers},
     * which runs last so a buffer's bytes are live before {@code GraphRunner.ensureRunnersBuilt()}
     * (called immediately after this, from the same {@code prepare()}) classifies it into a
     * descriptor type.
     */
    public void ensureSize(int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
        // One frame boundary for the retirement ring (see its own doc) -- this is the one call site
        // guaranteed to run exactly once per frame while a pack is active.
        advanceRetirementRing();

        TargetPlan plan = TargetPlan.compute(graph, compileValues, renderWidth, renderHeight, outputWidth, outputHeight);

        Set<String> planned = new HashSet<>();
        for (TargetPlan.Entry e : plan.entries()) {
            if (e.mipLevels() > 1) {
                // Owned entirely by its MipchainRunner instead (a plain TargetInstance models one
                // mip level, and GraphRunner never looks this name up through this registry -- see
                // GraphInputResolver, which checks the mipchainTargets map first). Allocating a
                // second, single-level texture under the same name here would just waste VRAM.
                continue;
            }
            planned.add(e.name());
            reconcile(e.name(), e.format(), e.width(), e.height(), e.history(), e.storage());
        }

        // A target whose enabled_if just flipped false (or was dropped by a pack rebuild) is no
        // longer in the plan -- free it instead of leaving stale GPU memory around. Unlike
        // TargetRegistry.close(), this path can run OUTSIDE any GraphRunner.closeCurrent() teardown
        // -- ensureSize() is called every frame from GraphRunner.prepare(), on the SAME live
        // registry, with every ComputePassRunner still alive and its persistent descriptor sets
        // still possibly pointing at this target -- so this retires it (see #retiring's own doc)
        // rather than closing it immediately, exactly like reconcile()'s own replace path below.
        targets.keySet().removeIf(name -> {
            if (planned.contains(name)) {
                return false;
            }
            TargetInstance dropped = targets.get(name);
            retire(dropped::close);
            return true;
        });

        reconcilePackSizedBuffers(plan);
    }

    /**
     * Allocates/resizes every PACK-SIZED buffer target the plan carries, and frees any that dropped
     * out of it (an {@code enabled_if} that just flipped false) -- the buffer-kind half of the same
     * reconcile-against-the-plan contract {@link #ensureSize} applies to textures just above.
     *
     * <p>ENGINE-owned buffer targets are untouched by both halves of this. They never appear in
     * {@link TargetPlan#bufferEntries()} (no declared size), and the free loop below iterates the
     * graph's own PACK-sized buffer names rather than {@link #buffers}' key set -- so a live
     * {@code voxelOccupancy}/{@code analyticLightList} allocation, driven from
     * {@code BrickGridUpload}/{@code GraphRunner.prepare} on their own schedules and keyed on names
     * this class cannot predict (the voxel targets carry a cascade-tier suffix -- see
     * {@code BrickGridUpload.targetName}), can never be freed out from under its owner here. That
     * asymmetry with the texture path above is deliberate: textures have exactly one owner, buffers
     * have two.
     *
     * <p>{@link #ensureBufferSize} already no-ops when the requested size matches, so the steady
     * state costs one map lookup per pack buffer per frame -- no backend creation, no reallocation.
     */
    private void reconcilePackSizedBuffers(TargetPlan plan) {
        Set<String> planned = new HashSet<>();
        for (TargetPlan.BufferEntry b : plan.bufferEntries()) {
            planned.add(b.name());
            ensureBufferSize(b.name(), b.sizeBytes());
        }
        for (TargetSpec t : graph.targets().values()) {
            if (t.kind() != TargetKind.BUFFER || t.bufferSize() == null) {
                continue; // engine-owned: not this method's to allocate OR to free
            }
            if (!planned.contains(t.name()) && buffers.containsKey(t.name())) {
                releaseBuffer(t.name());
            }
        }
    }

    /**
     * Explicit size override for one already-allocated target, bypassing its graph-declared scale.
     * The SSR-sized-half-res-when-FAST parity hook: {@code SsrQuality} is a live runtime toggle a
     * static graph {@code scale} can't express, exactly like {@code FramePipeline.traceSsr()} sizes
     * {@code SsrManager} independently of the render target's own resolution today. No-ops if the
     * target hasn't been allocated by {@link #ensureSize} yet.
     */
    public void resize(String name, int width, int height) {
        TargetInstance existing = targets.get(name);
        if (existing == null) {
            return;
        }
        if (!isResize(existing.width(), existing.height(), width, height)) {
            return;
        }
        reconcile(name, existing.format(), width, height, existing.hasHistory(), isStorageTexture(name));
    }

    /**
     * Pure decision extracted from {@link #resize}: does {@code width x height} actually differ
     * from what's already allocated? A {@code true} here always reaches {@link #reconcile}, whose
     * rebuild path unconditionally re-clears the texture (see this class's own javadoc) -- so this
     * is the one seam that pins "a genuine size change is never silently treated as a no-op" without
     * needing a GPU device, which {@link #reconcile} itself requires to actually rebuild anything.
     */
    static boolean isResize(int existingWidth, int existingHeight, int width, int height) {
        return existingWidth != width || existingHeight != height;
    }

    /** Ping-pongs current <-> history for every history-backed target. Call once, at frame end. */
    public void swapHistory() {
        for (TargetInstance t : targets.values()) {
            if (t.hasHistory()) {
                t.swap(); // package-private; TargetRegistry is TargetInstance's only owner
            }
        }
    }

    @Nullable
    public BufferInstance getBuffer(String name) {
        return buffers.get(name);
    }

    /** Releases a buffer-kind target without attempting to create an invalid zero-byte VkBuffer.
     * The allocation is removed from lookup immediately and retired through the same delayed
     * destruction ring used by resize, allowing persistent descriptor sets to be rewritten first. */
    public void releaseBuffer(String name) {
        BufferInstance existing = buffers.get(name);
        if (existing == null) {
            return;
        }
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            try (VulkanComputeBackend backend = VulkanComputeBackend.tryCreate()) {
                if (backend == null) {
                    FornaxMod.LOGGER.warn("[Fornax] TargetRegistry cannot release buffer '{}': no compute backend available", name);
                    return;
                }
                buffers.remove(name);
                long vkBuffer = existing.vkBuffer();
                long vmaAllocation = existing.vmaAllocation();
                long vma = backend.device().vma();
                retire(() -> Vma.vmaDestroyBuffer(vma, vkBuffer, vmaAllocation));
            }
        }
        FornaxMod.LOGGER.info("[Fornax] Buffer '{}' released", name);
    }

    /**
     * Allocates or resizes the buffer-kind target {@code name} to exactly {@code sizeBytes},
     * bypassing {@link TargetPlan#compute}'s render/output-resolution sizing entirely -- no buffer
     * target is ever sized by a graph-declared {@code scale}. The caller is either an engine-owned
     * buffer's own manager (the voxel window manager, for the brick grid) or
     * {@link #reconcilePackSizedBuffers} passing a PACK's declared {@code stride_bytes x count}.
     * No-op if already at this size. Clears the buffer at (re)allocation, per the
     * MoltenVK garbage-VRAM law every other engine-managed resource in this class already follows.
     *
     * <p>{@code sizeBytes} is passed straight through to {@code vkCmdFillBuffer} (see
     * {@link #clearBuffer}), which requires its {@code size} argument to be a multiple of 4 (or
     * {@code VK_WHOLE_SIZE}) per the Vulkan spec -- callers must pass a 4-byte-aligned size.
     */
    public void ensureBufferSize(String name, long sizeBytes) {
        if (sizeBytes <= 0L) {
            throw new IllegalArgumentException("buffer size must be positive; use releaseBuffer() to free '" + name + "'");
        }
        if ((sizeBytes & 3L) != 0L) {
            throw new IllegalArgumentException("buffer size must be 4-byte aligned for vkCmdFillBuffer: " + sizeBytes);
        }
        BufferInstance existing = buffers.get(name);
        if (existing != null && existing.sizeBytes() == sizeBytes) {
            return;
        }

        // Held across the whole create+clear+destroy-old+map-mutate section: BrickGridUpload.uploadSlot
        // runs on Sodium's worker threads and both reads these same buffer handles and submits against
        // them, so freeing/reassigning a buffer here without this lock is a use-after-free against an
        // in-flight worker upload. See VulkanComputeBackend.SHARED_QUEUE_LOCK.
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
        try (VulkanComputeBackend backend = VulkanComputeBackend.tryCreate()) {
            if (backend == null) {
                if (warnedNoBackend.add(name)) {
                    FornaxMod.LOGGER.warn("[Fornax] TargetRegistry skipping (re)build of buffer '{}': no compute backend available", name);
                }
                return;
            }
            warnedNoBackend.remove(name);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                        .sType$Default()
                        .size(sizeBytes)
                        .usage(VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                | VK13.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | VK13.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                                // Texel-buffer reads from fullscreen (fragment) passes -- Blaze3D's
                                // fragment pipeline has no STORAGE_BUFFER uniform type (design limit),
                                // so buffer-kind targets are bound as UNIFORM_TEXEL_BUFFER there
                                // (FullscreenPassRunner). Additive: compute STORAGE_BUFFER binds are
                                // unaffected.
                                | VK13.VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT);

                // Cross-queue-FAMILY hazard fallback (queue-family-routing fix rejected as
                // structurally blocked -- see this method's own class-level buffer-kind-target
                // contract and VulkanComputeBackend.SHARED_QUEUE_LOCK's doc: BrickGridUpload
                // .uploadSlot submits vkQueueSubmit directly from Sodium's multi-threaded
                // chunk-build worker threads, and Blaze3D's own frame-end graphics-queue submit
                // has no mixin hook this mod can serialize against -- moving Fornax's compute
                // dispatches onto that same VkQueue would make concurrent vkQueueSubmit calls
                // from those workers and the render thread a real, unfixable-without-deep-
                // Blaze3D-mixins hazard, since neither call site would hold a shared lock).
                // Every buffer-kind target (voxelOccupancy/voxelPayload/voxelPalette/
                // voxelLightVolume/the index grid, voxelWaterRefl) is written on
                // VulkanComputeBackend's compute queue and several (voxelWaterRefl via
                // ssr_water_fill; voxelLightVolume via resolve_hdr_el) are read back the SAME
                // frame by a graphics-queue fullscreen pass as a UNIFORM_TEXEL_BUFFER. voxelOccupancy
                // is a related but distinct case (queue-topology fix): its own write
                // (BrickGridUpload's asynchronous, fence-completed streaming upload) is never
                // same-frame with the read, but a pack's own "celestial_shadow" fullscreen pass can
                // texelFetch it directly too -- the same cross-family write/graphics-queue-read
                // shape, just with a write that long-since completed rather than one still in this
                // frame's own timeline. calloc()
                // zero-initializes sharingMode to VK_SHARING_MODE_EXCLUSIVE (0), which per the
                // Vulkan spec requires an explicit release/acquire queue-family-ownership-
                // transfer barrier pair before a resource may be used on a different queue
                // family than the one that created/last used it -- this codebase issues no such
                // transfer, so every one of these buffers was genuinely undefined behavior on
                // this device's real topology (compute family 3, graphics family 0 -- see
                // VulkanComputeBackend.tryCreate's own log), independent of any memory-visibility
                // barrier. Declaring CONCURRENT sharing across both families removes that
                // ownership-transfer requirement (small MoltenVK-tolerated perf cost); it does
                // NOT by itself replace the still-needed availability/visibility barriers for the
                // write-then-read memory dependency itself -- see ComputePassRunner.run's own
                // release barrier for that half. A same-family device (sharesQueueFamilyWithGraphics()
                // true) leaves sharingMode at its EXCLUSIVE default: harmless and correct, since
                // there is only one family to share within.
                if (!backend.sharesQueueFamilyWithGraphics()) {
                    IntBuffer families = stack.ints(
                            backend.computeQueue().queueFamilyIndex(),
                            backend.device().graphicsQueue().queueFamilyIndex());
                    bufferInfo.sharingMode(VK13.VK_SHARING_MODE_CONCURRENT).pQueueFamilyIndices(families);
                }

                VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_AUTO);
                LongBuffer bufferOut = stack.mallocLong(1);
                PointerBuffer allocationOut = stack.mallocPointer(1);
                int result = Vma.vmaCreateBuffer(backend.device().vma(), bufferInfo, allocInfo,
                        bufferOut, allocationOut, null);
                if (result != VK13.VK_SUCCESS) {
                    FornaxMod.LOGGER.error("[Fornax] TargetRegistry: vmaCreateBuffer failed for '{}' with VkResult {}", name, result);
                    return;
                }
                long vkBuffer = bufferOut.get(0);
                long vmaAllocation = allocationOut.get(0);
                clearBuffer(backend, vkBuffer, sizeBytes);

                if (existing != null) {
                    // Retire the OLD buffer instead of destroying it here -- see #retiring's own doc.
                    // A plain wait-idle before the destroy (the original fix, commit 50d8eca) only
                    // proves the GPU finished EXECUTING against it; it says nothing about
                    // ComputePassRunner's own persistent VkDescriptorSets, allocated once and
                    // re-pointed at the CURRENT handle via vkUpdateDescriptorSets every run() call
                    // instead of being recreated -- a set that hasn't run (and committed) since this
                    // resize still structurally references the old handle in MoltenVK's own residency
                    // bookkeeping, and three of the eight live SIGSEGVs in
                    // AGXG17XFamilyResidencySet._commitAddedAllocations:...removedAllocations:...
                    // happened AFTER that wait-idle-only fix shipped -- proof it doesn't close this.
                    // Capture the OLD handles by value now: existing.reassign() below overwrites them
                    // in place on the SAME BufferInstance object every registry.getBuffer(name) caller
                    // already holds a reference to, so the retired closure must not read them back off
                    // `existing` later. `backend.device().vma()` is stable for VulkanComputeBackend's
                    // whole session (device() wraps RenderSystem's own cached GpuDevice, not anything
                    // this short-lived try-with-resources backend instance owns), so capturing the raw
                    // allocator handle is safe to use frames after this backend is closed below.
                    long oldVkBuffer = existing.vkBuffer();
                    long oldVmaAllocation = existing.vmaAllocation();
                    long vma = backend.device().vma();
                    retire(() -> Vma.vmaDestroyBuffer(vma, oldVkBuffer, oldVmaAllocation));
                    existing.reassign(vkBuffer, vmaAllocation, sizeBytes);
                } else {
                    buffers.put(name, new BufferInstance(name, vkBuffer, vmaAllocation, sizeBytes));
                }
            }
        }
        } // SHARED_QUEUE_LOCK

        FornaxMod.LOGGER.info("[Fornax] Buffer '{}' (re)built at {} bytes", name, sizeBytes);
    }

    /**
     * Zero-fills a freshly (re)allocated buffer -- MoltenVK does not zero-fill new VRAM, the same
     * law {@link #clear(GpuDevice, GpuTextureView)} already enforces for every texture target.
     */
    private static void clearBuffer(VulkanComputeBackend backend, long vkBuffer, long sizeBytes) {
        VkCommandBuffer cmd = backend.commandPool().allocateBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            VK13.vkBeginCommandBuffer(cmd, beginInfo);
            VK13.vkCmdFillBuffer(cmd, vkBuffer, 0, sizeBytes, 0);
            VK13.vkEndCommandBuffer(cmd);
        }
        try (var submission = backend.computeQueue().beginSubmit()) {
            submission.executeCommands(cmd);
        }
        backend.computeQueue().waitIdle();
        backend.commandPool().reset();
    }

    @Override
    public void close() {
        VulkanComputeBackend.waitForGpuIdleBeforeDestroy();

        // Drain anything still sitting in the retirement ring (see its own doc) instead of leaking
        // it. Safe to destroy immediately here, unlike a live mid-session resize: this is a full
        // registry teardown, and GraphRunner.closeCurrent() already closes every ComputePassRunner
        // (freeing their descriptor pools, and with them every persistent VkDescriptorSet that
        // could reference these handles) BEFORE calling this method -- the exact "detach descriptor
        // sets before destroying the resource" ordering the ring exists to enforce for a live resize
        // is already satisfied by that call ordering here.
        for (List<Runnable> generation : retiring) {
            for (Runnable action : generation) {
                action.run();
            }
        }
        retiring.clear();

        for (TargetInstance t : targets.values()) {
            t.close();
        }
        targets.clear();

        if (!buffers.isEmpty()) {
            // Same lock as ensureBufferSize/uploadSlot: a worker-thread upload may still hold a handle
            // to one of these buffers and be mid-submission against it -- destroying it out from under
            // that submit is a use-after-free. Holding SHARED_QUEUE_LOCK across the destroy loop AND the
            // map clear guarantees no worker upload observes a half-torn-down registry.
            synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
                try (VulkanComputeBackend backend = VulkanComputeBackend.tryCreate()) {
                    if (backend != null) {
                        // Defensive drain: every OTHER compute submitter waitIdles before releasing
                        // SHARED_QUEUE_LOCK, so historically the queue was always idle by the time close()
                        // ran. VoxelDebugRaymarchPass broke that invariant (it releases the lock with its
                        // dispatch still reading the occupancy buffer), so close() no longer trusts the
                        // caller -- it drains the queue itself before freeing any buffer. close() is rare
                        // (pack unload/reload), so this whole-queue wait costs nothing measurable, and it
                        // guards against any future submitter that likewise forgets to drain.
                        backend.computeQueue().waitIdle();
                        for (BufferInstance b : buffers.values()) {
                            Vma.vmaDestroyBuffer(backend.device().vma(), b.vkBuffer(), b.vmaAllocation());
                            b.close();
                        }
                    } else {
                        FornaxMod.LOGGER.warn("[Fornax] TargetRegistry.close(): no compute backend available, leaking {} buffer(s)", buffers.size());
                    }
                }
                buffers.clear();
            }
        }
    }

    private void reconcile(String name, TargetFormat format, int width, int height, boolean history,
                           boolean storage) {
        TargetInstance existing = targets.get(name);
        if (existing != null && existing.width() == width && existing.height() == height
                && existing.hasHistory() == history && existing.format() == format
                && ((existing.texture().usage() & FornaxTextureUsage.STORAGE) != 0) == storage) {
            return;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            FornaxMod.LOGGER.warn("[Fornax] TargetRegistry skipping (re)build of '{}': no GPU device available", name);
            return;
        }

        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
        if (storage) usage |= FornaxTextureUsage.STORAGE;
        GpuFormat gpuFormat = gpuFormat(format);

        // Hoisted so a failure anywhere in this sequence (the history pair failing after the
        // primary succeeded, or the primary's own view/clear failing after its texture succeeded)
        // doesn't orphan whatever already succeeded, with no reference left anywhere to close it.
        GpuTexture texture = null;
        GpuTextureView view = null;
        GpuTexture historyTexture = null;
        GpuTextureView historyView = null;
        try {
            texture = device.createTexture("Fornax Target " + name, usage, gpuFormat, width, height, 1, 1);
            view = device.createTextureView(texture);
            clear(device, view);

            if (history) {
                historyTexture = device.createTexture("Fornax Target " + name + " History", usage, gpuFormat, width, height, 1, 1);
                historyView = device.createTextureView(historyTexture);
                clear(device, historyView);
            }
        } catch (RuntimeException e) {
            if (historyView != null) historyView.close();
            if (historyTexture != null) historyTexture.close();
            if (view != null) view.close();
            if (texture != null) texture.close();
            throw e;
        }

        TargetInstance next = new TargetInstance(name, format, width, height, history, texture, view, historyTexture, historyView);
        TargetInstance old = targets.put(name, next);
        if (old != null) {
            // Live per-frame resize path (window resize / SSAA render-scale change): this can run on
            // an already-active registry, with every ComputePassRunner still alive. Retire rather
            // than close immediately -- see #retiring's own doc. A texture target is Blaze3D's own
            // GpuTexture/GpuTextureView (created via device.createTexture/createTextureView above),
            // and Blaze3D's own close() path already defers ITS OWN destruction safely through a
            // per-graphics-submission ring for consumers that bind through Blaze3D's own APIs
            // (FullscreenPassRunner's push-descriptor bindTexture/setUniform) -- but that ring has no
            // visibility into ComputePassRunner's hand-rolled raw-Vulkan VkDescriptorSets, which bind
            // a texture target's VkImageView as COMBINED_IMAGE_SAMPLER the exact same
            // allocate-once/re-vkUpdateDescriptorSets-every-run() way it binds buffer targets (see
            // updateAndBindDescriptorSet) -- so this path needs the SAME ring the buffer path needs,
            // for exactly the same reason.
            retire(old::close);
        }

        FornaxMod.LOGGER.info("[Fornax] Target '{}' (re)built at {}x{} ({}{})",
                name, width, height, gpuFormat, history ? ", history" : "");
    }

    /**
     * Clears a freshly allocated color texture to transparent zero via a clear-only render pass --
     * MoltenVK does not zero-fill new VRAM, and every pack target is cleared at allocation as a
     * format-level guarantee (the engine's own past green-splat bug class), not left to whichever
     * pass happens to write it first.
     */
    private static void clear(GpuDevice device, GpuTextureView view) {
        CommandEncoder encoder = device.createCommandEncoder();
        try (var pass = encoder.createRenderPass(() -> "Fornax Target Clear", view, Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 0.0f)))) {
            // Clear-only: the attachment's own clear value does the work, no draw call needed.
        }
    }

    static GpuFormat gpuFormat(TargetFormat f) {
        return switch (f) {
            case RGBA8 -> GpuFormat.RGBA8_UNORM;
            case RGBA16_SNORM -> GpuFormat.RGBA16_SNORM;
            case RGBA16F -> GpuFormat.RGBA16_FLOAT;
            case RG16F -> GpuFormat.RG16_FLOAT;
            case R8 -> GpuFormat.R8_UNORM;
            case R32F -> GpuFormat.R32_FLOAT;
        };
    }
}
