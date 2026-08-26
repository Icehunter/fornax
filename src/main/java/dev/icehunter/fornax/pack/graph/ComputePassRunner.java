package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.RawShaderImports;
import dev.icehunter.fornax.pack.layout.PackOptionsBuffer;
import dev.icehunter.fornax.pack.layout.RuntimeShaderPack;
import dev.icehunter.fornax.pass.compute.ComputePipelineBuilder;
import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import dev.icehunter.fornax.pipeline.FramePacing;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandPool;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The compute-pass analog of {@link FullscreenPassRunner}: one built pipeline per declared
 * {@code compute} pass, dispatched with the group counts {@code PassSpec.dispatch()} declares.
 *
 * <p>Runs every frame once a real pack declares a compute pass (Task 2 onward), so it uses the same
 * frames-in-flight ring {@code VoxelDebugRaymarchPass} already proved correct: each ring slot owns its
 * own command pool + fence, submitted via a raw {@code vkQueueSubmit} (Blaze3D's own
 * {@code VulkanQueue.Submission.close()} hardcodes a null fence -- verified via {@code javap} -- so it
 * cannot be used to attach a completion fence). A slot's fence is waited on only when that SAME slot
 * is about to be reused ({@code FRAMES_IN_FLIGHT} frames later), never every frame -- this is the
 * exact fix for the anti-pattern the original version of this class carried (a whole-queue
 * {@code waitIdle()} every single call, harmless only because no real pack ever exercised this path
 * until now).
 *
 * <p>Each pass's descriptor set is built from its declared {@code inputs} + {@code outputs}
 * (see {@link #combinedBindingOrder}): buffer-kind targets bind as {@code STORAGE_BUFFER}, texture-kind
 * targets as {@code COMBINED_IMAGE_SAMPLER}, and two reserved input NAMES bind engine uniform blocks
 * instead of any {@link TargetRegistry} entry -- {@link #PACK_OPTIONS_INPUT} (the pack's own tunables)
 * and {@link ParticlePassRunner#GLOBALS_INPUT} (Sodium's {@code u_Globals}: the per-frame clock,
 * weather and camera state a simulation pass cannot advance without). See
 * {@link #descriptorTypeFor}. The buffer-handle read + descriptor update + submit all
 * happen inside {@link VulkanComputeBackend#SHARED_QUEUE_LOCK} as one atomic critical section, so a
 * concurrent {@code TargetRegistry.close()}/{@code ensureBufferSize()} can never free or reassign a
 * buffer this pass is mid-dispatch on -- the same hazard {@code BrickGridUpload.uploadSlot} and
 * {@code VoxelDebugRaymarchPass} already guard against.
 */
public final class ComputePassRunner implements AutoCloseable {
    private static final int FRAMES_IN_FLIGHT = FramePacing.FRAMES_IN_FLIGHT;
    private static final long FENCE_WAIT_TIMEOUT = 0xFFFF_FFFF_FFFF_FFFFL; // UINT64_MAX

    /** Reserved engine-recognized input name: a compute pass declaring this input in its {@code graph.toml}
     * binds the live {@code u_PackOptions} uniform block ({@link PackOptionsBuffer#currentBuffer()}) at that
     * binding slot, rather than a real {@code TargetRegistry} target -- the compute analog of the
     * unconditional {@code u_PackOptions} bind every FULLSCREEN pass gets. */
    static final String PACK_OPTIONS_INPUT = "packOptions";

    private final PassSpec spec;
    private final VulkanComputeBackend backend;
    private final ComputePipelineBuilder.CompiledComputePipeline pipeline;
    private final List<String> bindingOrder;
    /** The {@code VkDescriptorType} of each binding, positionally aligned with {@link #bindingOrder}:
     * {@code STORAGE_BUFFER} for a registry buffer target, {@code COMBINED_IMAGE_SAMPLER} for a texture
     * target, {@code UNIFORM_BUFFER} for the reserved {@code packOptions} input. */
    private final List<Integer> descriptorTypes;
    /** Extra push-constant bytes (beyond the shared {@code PassParams.PUSH_CONSTANT_BASE_SIZE}) this
     * pass's pipeline layout was built with -- see {@link ExtraPushConstants}. Zero for every compute
     * pass that doesn't need more than the shared block; {@link #run} sizes its push-constant buffer to
     * {@code PassParams.PUSH_CONSTANT_BASE_SIZE + extraPushConstantBytes} and appends the
     * caller-supplied {@code extra} bytes after the base 32, matching what {@link #build} reserved in
     * the pipeline layout. */
    private final int extraPushConstantBytes;
    private final boolean graphicsDrainBeforeStorageWrite;
    private final RingSlot[] ring = new RingSlot[FRAMES_IN_FLIGHT];
    private long descriptorPool;
    private final long[] descriptorSets = new long[FRAMES_IN_FLIGHT];
    private long frameIndex;

    private static final class RingSlot {
        @Nullable VulkanCommandPool commandPool;
        long fence;
        long graphicsSemaphore;
        boolean submitted;
    }

    private ComputePassRunner(PassSpec spec, VulkanComputeBackend backend,
                               ComputePipelineBuilder.CompiledComputePipeline pipeline,
                               List<String> bindingOrder, List<Integer> descriptorTypes,
                               int extraPushConstantBytes, boolean graphicsDrainBeforeStorageWrite) {
        this.spec = spec;
        this.backend = backend;
        this.pipeline = pipeline;
        this.bindingOrder = bindingOrder;
        this.descriptorTypes = descriptorTypes;
        this.extraPushConstantBytes = extraPushConstantBytes;
        this.graphicsDrainBeforeStorageWrite = graphicsDrainBeforeStorageWrite;
        try {
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                ring[i] = new RingSlot();
                ring[i].commandPool = new VulkanCommandPool(backend.device(), backend.computeQueue());
                ring[i].fence = createFence(backend);
                ring[i].graphicsSemaphore = createSemaphore(backend);
            }
        } catch (RuntimeException e) {
            destroyRingResources();
            throw e;
        }
    }

    private static long createFence(VulkanComputeBackend backend) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default(); // unsignaled
            LongBuffer out = stack.mallocLong(1);
            int result = VK13.vkCreateFence(backend.device().vkDevice(), fenceInfo, null, out);
            if (result != VK13.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateFence failed with VkResult " + result);
            }
            return out.get(0);
        }
    }

    private static long createSemaphore(VulkanComputeBackend backend) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer out = stack.mallocLong(1);
            int result = VK13.vkCreateSemaphore(backend.device().vkDevice(), semaphoreInfo, null, out);
            if (result != VK13.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateSemaphore failed with VkResult " + result);
            }
            return out.get(0);
        }
    }

    /** The positional binding order a compute pass's descriptor set is built from: every declared
     * input, in order, then every declared output, in order. Binding N = the Nth entry -- pure
     * function of the pass spec, no GPU/registry access, so this is directly unit-testable. */
    static List<String> combinedBindingOrder(PassSpec spec) {
        List<String> combined = new ArrayList<>(spec.inputs().size() + spec.outputs().size());
        combined.addAll(spec.inputs());
        combined.addAll(spec.outputs());
        return List.copyOf(combined);
    }

    public static ComputePassRunner build(PassSpec spec, VulkanComputeBackend backend, TargetRegistry registry,
                                          int extraPushConstantBytes,
                                          boolean graphicsDrainBeforeStorageWrite) {
        String source = RuntimeShaderPack.getInstance().sourceOrNull(spec.shader());
        if (source == null) {
            throw new IllegalStateException("Fornax graph: compute pass '" + spec.name()
                    + "' names shader '" + spec.shader() + "' with no composed source");
        }
        source = RawShaderImports.expand(source, RuntimeShaderPack.getInstance().sourcesSnapshot(),
                spec.shader());
        List<String> bindingOrder = combinedBindingOrder(spec);
        List<Integer> descriptorTypes = new ArrayList<>(bindingOrder.size());
        for (String name : bindingOrder) {
            descriptorTypes.add(descriptorTypeFor(spec, name, registry));
        }

        ByteBuffer spirv = ComputeShaderCompiler.compileToSpirv(source, spec.shader());
        try {
            var compiled = ComputePipelineBuilder.buildWithDescriptorLayout(backend.device(), spirv, descriptorTypes,
                    PassParams.PUSH_CONSTANT_BASE_SIZE + extraPushConstantBytes);
            ComputePassRunner runner = null;
            try {
                runner = new ComputePassRunner(spec, backend, compiled, bindingOrder, descriptorTypes,
                        extraPushConstantBytes, graphicsDrainBeforeStorageWrite);
                runner.allocateDescriptorSets();
            } catch (RuntimeException e) {
                if (runner != null) {
                    runner.close();
                } else {
                    destroyPipeline(backend, compiled);
                }
                throw e;
            }
            // Queue-topology fact, once per runner build (assert-don't-assume): VulkanComputeBackend
            // already logs this once per session at tryCreate(), but this line ties it explicitly to
            // the sync model used by this runner, per compute pass, so it is visible next to the
            // passes that depend on cross-queue handoff.
            FornaxMod.LOGGER.info("[Fornax] ComputePassRunner('{}'): compute queue {} the graphics queue family"
                            + " -- semaphore handoff is available for same-frame graphics consumers",
                    spec.name(), backend.sharesQueueFamilyWithGraphics() ? "SHARES" : "does NOT share");
            return runner;
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    /**
     * Classifies one entry of {@link #combinedBindingOrder} to a {@code VkDescriptorType}. The pool
     * has to be sized by type before any frame runs, so this must be decidable without touching the
     * GPU -- which is also what makes it directly unit-testable, the reason it is a named
     * package-private method rather than an inline chain inside {@link #build} (the shape
     * {@code ParticlePassRunner.descriptorTypeFor} already uses).
     */
    static int descriptorTypeFor(PassSpec spec, String name, TargetRegistry registry) {
        if (name.equals(PACK_OPTIONS_INPUT)) {
            // Reserved engine input: the live u_PackOptions block, resolved to
            // PackOptionsBuffer.currentBuffer() at bind time, not a TargetRegistry entry.
            return VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        }
        if (name.equals(ParticlePassRunner.GLOBALS_INPUT)) {
            // Reserved engine input: Sodium's live u_Globals block. This is a compute pass's ONLY
            // route to per-frame world state -- the wind clock, the frame counter, rain/thunder/
            // wetness, the weather anchor, the true sun direction (see fornax:globals.glsl's own
            // field-by-field doc). Without it a pack compute pass cannot advance a simulation at all:
            // its PassParams push constant carries texel size plus two scalars whose values
            // GraphRunner.computeParams fills in BY PASS NAME, so a pack-authored pass name that the
            // engine does not recognize receives zeros in every one of them, every frame.
            //
            // Deliberately the SAME reserved name and the SAME uniform-buffer binding a PARTICLES
            // pass already gets (GLOBALS_INPUT lives in ParticlePassRunner, which introduced it),
            // rather than a second mechanism: a pack author then reads one rule -- "put 'globals' in
            // inputs, declare the block at that positional binding" -- across both raw-Vulkan pass
            // types, exactly as they already do for packOptions, which lives here and is used by
            // both. The cross-referencing is symmetric and intentional.
            //
            // Binding a Blaze3D-owned, host-written ring buffer into a descriptor set submitted on
            // the COMPUTE queue is not new here: packOptions above is a MappableRingBuffer bound the
            // same way, on the same queue, in shipped packs. Both therefore carry the same caveat --
            // the ring's rotation is paced against Blaze3D's own graphics submissions, not against
            // this runner's fence -- and neither is made worse by the other.
            return VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        }
        if (GraphValidator.BUILTINS.contains(name)) {
            // Every recognized builtin.* name is a texture (the engine's G-buffer channels / main
            // render target) -- never a storage buffer. Reuses GraphValidator's own allowed-name set
            // instead of a second, driftable list; the real handle is resolved per-frame in
            // updateAndBindDescriptorSet via GraphInputResolver.resolveView, not here (this only
            // determines descriptor TYPE, no GPU access needed yet).
            return VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        }
        if (ShadowMapManager.isShadowMapRef(name)) {
            // Engine-owned sun shadow depth target (see ShadowMapManager), covering both its
            // pack-visible names (TARGET and RAW_TARGET -- same resource, different sampler chosen
            // downstream) -- deliberately NOT builtin.-prefixed (GraphValidator.checkInputRef treats
            // it as a peer of BUILTINS, not a member: see that method's own isShadowMapRef branch) and never
            // a TargetRegistry entry, so neither the BUILTINS check above nor the registry lookups
            // below ever match it -- this was the exact gap that made a compute pass declaring
            // "sunShadowMap" throw here instead of building (the wsrw-computebind fix).
            // FullscreenPassRunner never needed an equivalent special case: it classifies every
            // non-buffer input as a plain sampler unconditionally and defers entirely to
            // GraphInputResolver.resolveView at run time, which already resolves
            // ShadowMapManager.TARGET generically (same nullable fallback-to-64px path as every other
            // engine-owned target) -- this compute path only needs its own name match because it must
            // know descriptor TYPE up front, to size allocateDescriptorSets' pool. The real handle is
            // resolved the same way, in updateAndBindDescriptorSet, via that same resolveView call.
            return VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        }
        if (registry.getBuffer(name) != null) {
            return VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        }
        if (registry.isStorageTexture(name)) {
            return VK13.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        }
        if (registry.get(name) != null || registry.isTextureTarget(name)) {
            return VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        }
        throw new IllegalStateException("Fornax graph: compute pass '" + spec.name()
                + "' references target '" + name + "' which is neither an allocated buffer nor texture target");
    }

    /** Allocates one descriptor set per ring slot from a dedicated pool, matching
     * {@code VoxelDebugRaymarchPass.ensurePipeline}'s "allocate once, re-{@code vkUpdateDescriptorSets}
     * per use" pattern -- the sets are re-pointed at the current target handles every frame in
     * {@link #updateAndBindDescriptorSet}, never reallocated. */
    private void allocateDescriptorSets() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var device = backend.device();
            long storageBindings = countType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            long storageImageBindings = countType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            long textureBindings = countType(VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            long uniformBindings = countType(VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);

            // A VkDescriptorPoolSize with descriptorCount 0 is invalid (VUID-VkDescriptorPoolSize-
            // descriptorCount-00302), so only include the types actually present -- a pass that uses
            // just one of storage-buffer/texture/uniform-buffer must not name a zero-count size.
            List<int[]> sizes = new ArrayList<>(4); // {type, count}
            if (storageBindings > 0) {
                sizes.add(new int[]{VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, (int) storageBindings * FRAMES_IN_FLIGHT});
            }
            if (textureBindings > 0) {
                sizes.add(new int[]{VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, (int) textureBindings * FRAMES_IN_FLIGHT});
            }
            if (storageImageBindings > 0) {
                sizes.add(new int[]{VK13.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                        (int) storageImageBindings * FRAMES_IN_FLIGHT});
            }
            if (uniformBindings > 0) {
                sizes.add(new int[]{VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, (int) uniformBindings * FRAMES_IN_FLIGHT});
            }
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(sizes.size(), stack);
            for (int i = 0; i < sizes.size(); i++) {
                poolSizes.get(i).type(sizes.get(i)[0]).descriptorCount(sizes.get(i)[1]);
            }
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(FRAMES_IN_FLIGHT).pPoolSizes(poolSizes);
            LongBuffer poolOut = stack.mallocLong(1);
            checkVk(VK13.vkCreateDescriptorPool(device.vkDevice(), poolInfo, null, poolOut), "vkCreateDescriptorPool");
            descriptorPool = poolOut.get(0);

            LongBuffer layouts = stack.mallocLong(FRAMES_IN_FLIGHT);
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                layouts.put(i, pipeline.descriptorSetLayout());
            }
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descriptorPool).pSetLayouts(layouts);
            LongBuffer setsOut = stack.mallocLong(FRAMES_IN_FLIGHT);
            checkVk(VK13.vkAllocateDescriptorSets(device.vkDevice(), allocInfo, setsOut), "vkAllocateDescriptorSets");
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                descriptorSets[i] = setsOut.get(i);
            }
        }
    }

    /**
     * Records and submits this pass's dispatch into the current ring slot, fenced (not
     * whole-queue-waited); recycles that SAME slot's prior dispatch (from {@code FRAMES_IN_FLIGHT}
     * frames ago) first, which by steady state has long since completed, making that wait near-instant
     * rather than a queue-wide barrier. The registry-handle read + descriptor update + submit all run
     * inside {@link VulkanComputeBackend#SHARED_QUEUE_LOCK}, so no concurrent registry buffer lifecycle
     * op can free a target mid-dispatch.
     *
     * <p>{@code dispatchOverride}, when non-null, replaces both the TOML literal dispatch AND the
     * {@code localSize}-derived group counts for passes whose domain is an engine-computed runtime
     * volume (see {@code GraphRunner.computeDispatchOverride}) -- every other pass passes {@code null}
     * here and this method's behavior is unchanged. {@code synchronousWait}, when true, CPU-waits this
     * dispatch's completion for a legacy bidirectional dependency. {@code graphicsWaitStageMask},
     * when non-zero, signals a binary semaphore and inserts its wait into Blaze3D's pending graphics
     * submission AT THOSE STAGES, providing a device-side compute-write to graphics-read dependency
     * without blocking the CPU.
     *
     * <p>The wait stage is a caller decision rather than a constant because the consuming stage
     * differs by consumer: a fullscreen pass reading a lighting buffer needs {@code FRAGMENT_SHADER},
     * while a particles pass reading a flake buffer needs {@code VERTEX_SHADER} -- earlier in the
     * pipeline, so a fragment-only wait would let vertex invocations read the buffer while this
     * dispatch was still writing it. See {@code GraphRunner.computeGraphicsWaitStages}. Zero means
     * "no cross-queue consumer this frame", which is the common case.
     *
     * <p>{@code globals} is Sodium's live per-frame uniform slice, bound only by a pass that declared
     * the reserved {@link ParticlePassRunner#GLOBALS_INPUT} input. It may legitimately be null before
     * the first terrain draw of a session (and at {@code prepare()} time, where the pre-opaque
     * lighting passes run); a pass that needs it skips the frame rather than dispatching against
     * absent per-frame state, which is the same choice {@code ParticlePassRunner.run} makes. Skipping
     * is safe for a simulation pass in a way that binding stale data is not: one missed tick of a
     * field that accumulates is invisible, one tick against another frame's clock is not.
     */
    public void run(TargetRegistry registry, PassParams params, @Nullable PackOptionsBuffer options,
                    @Nullable GpuBufferSlice globals, @Nullable ExtraPushConstants extra,
                    @Nullable int[] dispatchOverride,
                    boolean synchronousWait, long graphicsWaitStageMask) {
        if (globals == null && bindingOrder.contains(ParticlePassRunner.GLOBALS_INPUT)) {
            return;
        }
        if (graphicsDrainBeforeStorageWrite) {
            // Storage images are shared CONCURRENTLY when queue families differ, but sharing mode
            // removes only ownership transfers, not the prior graphics-read -> next compute-write
            // execution dependency. Only writers whose physical image is also used by an enabled
            // graphics pass take this conservative drain; compute-only intermediates and read-only
            // storage bindings do not serialize the graphics queue. The compute -> later graphics
            // direction remains device-side via the semaphore stage mask computed by GraphRunner.
            backend.device().graphicsQueue().waitIdle();
        }
        int extraBytesThisCall = extra != null ? extra.byteSize() : 0;
        if (extraBytesThisCall != extraPushConstantBytes) {
            // The pipeline layout's push-constant range was sized to PassParams.PUSH_CONSTANT_BASE_SIZE +
            // extraPushConstantBytes at build() time -- a caller passing an `extra` whose byteSize()
            // disagrees (or omitting one the pass was built expecting) would silently over/under-run
            // that fixed range, so fail loudly instead.
            throw new IllegalStateException("Fornax graph: compute pass '" + spec.name()
                    + "' built with extraPushConstantBytes=" + extraPushConstantBytes
                    + " but run() received extra push constants of " + extraBytesThisCall + " bytes");
        }
        int slotIndex = (int) (frameIndex % FRAMES_IN_FLIGHT);
        RingSlot slot = ring[slotIndex];
        frameIndex++;

        if (slot.submitted && slot.fence != 0) {
            int waitResult = VK13.vkWaitForFences(backend.device().vkDevice(), slot.fence, true, FENCE_WAIT_TIMEOUT);
            if (!fenceWaitSucceeded(waitResult, "ring-slot recycle in '" + spec.name() + "'")) {
                // The fence never signalled, so this slot's prior buffer may still be in flight.
                // Resetting the pool now would be a use-after-free of a live command buffer; skip
                // this frame's dispatch entirely rather than recycle an undrained slot.
                return;
            }
            VK13.vkResetFences(backend.device().vkDevice(), slot.fence);
            slot.submitted = false;
        }
        VulkanCommandPool pool = slot.commandPool;
        if (pool != null) {
            pool.reset(); // this slot's prior buffer was drained by the checked fence wait above
        }

        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            VkCommandBuffer cmd = pool != null ? pool.allocateBuffer() : null;
            if (cmd == null) {
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
                VK13.vkBeginCommandBuffer(cmd, beginInfo);
                EngineBufferUploadQueue.recordForBindings(cmd, stack, registry, bindingOrder);
                VK13.vkCmdBindPipeline(cmd, VK13.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipeline());
                updateAndBindDescriptorSet(registry, cmd, descriptorSets[slotIndex], options, globals);

                // PassParams delivered as a push constant (a compute pass has no reserved uniform-buffer
                // slot for it), matching PassParams' own 32-byte std140 layout: vec2 texel size at 0, two
                // scalars at 8/12, vec3 sun direction at 16 (bytes 28-31 padding). A pass built with
                // extraPushConstantBytes > 0 (see ExtraPushConstants) gets those extra bytes appended
                // right after, sized to match the pipeline layout's range built in build(). Deliberately
                // PassParams.PUSH_CONSTANT_BASE_SIZE (32), not PassParams.BUFFER_SIZE (64) -- the new
                // sun/moon sprite rects are a uniform-buffer-only (FULLSCREEN resolve) concern; a compute
                // pass's push-constant byte layout, and therefore any ExtraPushConstants offset a pack's
                // own compute shader hardcodes, must stay exactly where it always was.
                ByteBuffer push = stack.malloc(PassParams.PUSH_CONSTANT_BASE_SIZE + extraBytesThisCall);
                push.putFloat(0, params.texelSizeX());
                push.putFloat(4, params.texelSizeY());
                push.putFloat(8, params.param2());
                push.putFloat(12, params.param3());
                push.putFloat(16, params.sunDirX());
                push.putFloat(20, params.sunDirY());
                push.putFloat(24, params.sunDirZ());
                if (extra != null) {
                    extra.writeInto(push, PassParams.PUSH_CONSTANT_BASE_SIZE);
                }
                VK13.vkCmdPushConstants(cmd, pipeline.pipelineLayout(), VK13.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);

                List<Integer> d = spec.dispatch();
                int groupsX = d.get(0);
                int groupsY = d.get(1);
                int groupsZ = d.get(2);
                if (dispatchOverride != null) {
                    // Engine-computed group counts for passes whose domain is a runtime-sized 3D
                    // volume (the voxel window scales with render distance -- TOML's static literal
                    // cannot express it, and localSize derivation only understands 2D texture
                    // outputs). See GraphRunner.computeDispatchOverride.
                    groupsX = dispatchOverride[0];
                    groupsY = dispatchOverride[1];
                    groupsZ = dispatchOverride[2];
                } else {
                    List<Integer> localSize = spec.localSize();
                    if (localSize != null) {
                        // Derived from the pass's first output's REAL resolved pixel size every dispatch
                        // (not TOML's literal x/y, which are unused placeholders in this mode) --
                        // required for any compute pass whose output scales with render resolution,
                        // mirroring the exact ceil(width/LOCAL_SIZE) pattern VoxelDebugRaymarchPass
                        // already proved correct for its own (non-TOML) dispatch.
                        TargetInstance out = registry.get(spec.outputs().get(0));
                        if (out == null) {
                            // The trap the emitter research documented: registry.get() is the TEXTURE
                            // map -- a buffer-only-output pass has no pixel size to derive groups from
                            // and used to NPE right here. Fail loudly with the fix named.
                            throw new IllegalStateException("Fornax graph: compute pass '" + spec.name()
                                    + "' declares local_size but its first output '" + spec.outputs().get(0)
                                    + "' is not a texture target -- buffer-only passes need a literal"
                                    + " dispatch or an engine dispatch override (GraphRunner.computeDispatchOverride)");
                        }
                        groupsX = (out.width() + localSize.get(0) - 1) / localSize.get(0);
                        groupsY = (out.height() + localSize.get(1) - 1) / localSize.get(1);
                    }
                }
                VK13.vkCmdDispatch(cmd, groupsX, groupsY, groupsZ);
                recordComputeWriteReleaseBarrier(cmd, stack);
                VK13.vkEndCommandBuffer(cmd);

                // Blaze3D's Submission.close() hardcodes a null fence -- bypass it, exactly like
                // VoxelDebugRaymarchPass.submitDispatch already does, so a real completion fence can
                // be attached.
                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd));
                if (graphicsWaitStageMask != 0) {
                    submitInfo.pSignalSemaphores(stack.longs(slot.graphicsSemaphore));
                }
                int result = VK13.vkQueueSubmit(backend.computeQueue().vkQueue(), submitInfo, slot.fence);
                if (result != VK13.VK_SUCCESS) {
                    throw new IllegalStateException("vkQueueSubmit failed with VkResult " + result);
                }
                slot.submitted = true;
                if (graphicsWaitStageMask != 0) {
                    // VulkanDevice returns its persistent encoder. Lighting producers request this
                    // handoff from GraphRunner.prepare(), before opaque terrain begins, so closing
                    // the command buffer recorded so far cannot split an active Apple tile render
                    // encoder. The semaphore supplies both execution ordering and device-memory
                    // visibility across the separate compute/graphics queues without a host stall.
                    VulkanCommandEncoder graphics = backend.device().createCommandEncoder();
                    graphics.waitSemaphore(slot.graphicsSemaphore, 0L, graphicsWaitStageMask);
                }
                if (synchronousWait) {
                    // Legacy host wait for passes with a current-frame graphics -> compute input
                    // dependency that has not yet been converted to a bidirectional semaphore chain.
                    int waitResult = VK13.vkWaitForFences(backend.device().vkDevice(), slot.fence, true, FENCE_WAIT_TIMEOUT);
                    if (fenceWaitSucceeded(waitResult, "synchronous wait in '" + spec.name() + "'")) {
                        VK13.vkResetFences(backend.device().vkDevice(), slot.fence);
                        slot.submitted = false;
                    }
                    // On failure, leave slot.submitted = true: the dispatch this call just made has
                    // not been confirmed complete, so the caller's assumption that the compute result
                    // already landed does not hold. Next frame's ring-slot recycle wait retries it.
                }
            }
        }
    }

    /**
     * Release-side memory barrier for this pass's own dispatch, recorded as the LAST command
     * before {@code vkEndCommandBuffer} (a pipeline barrier's second synchronization scope covers
     * every later-submitted command on the same queue, not just later commands in the same
     * command buffer, so this is a valid release even with nothing recorded after it -- same
     * reasoning {@code BrickGridUpload.recordUploadToComputeReadBarrier} already documents for
     * its own transfer-write -> compute-read handoff).
     *
     * <p>Covers another compute pass reading this pass's STORAGE_BUFFER output on the SAME queue
     * later this frame
     * (e.g. {@code light_propagate} reading {@code light_inject}'s {@code voxelLightVolume}
     * write) -- for this reader, {@code srcAccessMask -> dstAccessMask} on the compute queue is
     * the complete dependency. A same-frame FULLSCREEN graphics reader gets its cross-queue
     * dependency when {@link #run} is called with
     * {@code synchronizeWithGraphics}: the compute submit signals a semaphore and the persistent
     * VulkanCommandEncoder inserts its wait before subsequently recorded fullscreen work. Buffer
     * targets use CONCURRENT sharing across the two families, so no ownership transfer is required.
     */
    private static void recordComputeWriteReleaseBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK13.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK13.VK_ACCESS_SHADER_READ_BIT);
        VK13.vkCmdPipelineBarrier(cmd, VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, barrier, null, null);
    }

    /** Re-points this frame's descriptor set at the CURRENT resolved target handles (a target can be
     * reallocated between frames -- e.g. a resize -- so this must re-read handles every call, not
     * cache them at build time) and binds it before dispatch. Caller holds {@code SHARED_QUEUE_LOCK}. */
    private void updateAndBindDescriptorSet(TargetRegistry registry, VkCommandBuffer cmd, long descriptorSet,
                                            @Nullable PackOptionsBuffer options,
                                            @Nullable GpuBufferSlice globals) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(bindingOrder.size(), stack);
            for (int i = 0; i < bindingOrder.size(); i++) {
                String name = bindingOrder.get(i);
                int type = descriptorTypes.get(i);
                VkWriteDescriptorSet write = writes.get(i)
                        .sType$Default().dstSet(descriptorSet).dstBinding(i).descriptorCount(1);
                if (type == VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) {
                    // One of the two reserved engine inputs -> a live engine uniform buffer, not a
                    // registry target. descriptorTypeFor only assigns UNIFORM_BUFFER to those two
                    // names, so the name test below is exhaustive.
                    write.descriptorType(VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                            .pBufferInfo(uniformBufferInfo(stack, name, options, globals));
                } else if (type == VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER) {
                    BufferInstance buf = registry.getBuffer(name);
                    if (buf == null) {
                        // descriptorTypeFor decided STORAGE_BUFFER at build time from a then-non-null
                        // buffer; a release (e.g. reconcilePackSizedBuffers dropping a pack-sized
                        // buffer that fell out of the plan) can land between that decision and this
                        // re-read. Name the pass and the target instead of an unnamed NPE.
                        throw new IllegalStateException("Fornax graph: compute pass '" + spec.name()
                                + "' storage-buffer input '" + name + "' is not allocated");
                    }
                    VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                            .buffer(buf.vkBuffer()).offset(0).range(VK13.VK_WHOLE_SIZE);
                    write.descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(bufferInfo);
                } else {
                    GpuTextureView view = GraphInputResolver.resolveView(name, registry, Map.of());
                    long imageView = ((VulkanGpuTextureView) view).vkImageView();
                    if (type == VK13.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE) {
                        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                                .sampler(0L).imageView(imageView)
                                .imageLayout(VK13.VK_IMAGE_LAYOUT_GENERAL);
                        write.descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imageInfo);
                    } else {
                        VulkanGpuSampler sampler = (VulkanGpuSampler)
                                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                                .sampler(sampler.vkSampler()).imageView(imageView)
                                // Mojang keeps sampled and attachment textures in GENERAL on its
                                // Vulkan backend; use the real engine layout rather than claiming
                                // SHADER_READ_ONLY for a resource whose layout was never transitioned.
                                .imageLayout(VK13.VK_IMAGE_LAYOUT_GENERAL);
                        write.descriptorType(VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(imageInfo);
                    }
                }
            }
            VK13.vkUpdateDescriptorSets(backend.device().vkDevice(), writes, null);
            VK13.vkCmdBindDescriptorSets(cmd, VK13.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipelineLayout(),
                    0, new long[]{descriptorSet}, null);
        }
    }

    /**
     * The {@code VkDescriptorBufferInfo} for whichever of the two reserved uniform-buffer inputs
     * {@code name} is. Mirrors {@code ParticlePassRunner.uniformBufferInfo} field for field,
     * including the one asymmetry that matters: {@code globals} binds
     * {@code offset + length}, NEVER the whole buffer at 0, because Sodium's uniform buffer is a
     * ring and this frame's data lives mid-buffer (see {@code ChunkRenderContextHolder}'s own note).
     * Binding it at offset 0 would read some other frame's camera and weather without any error.
     */
    private VkDescriptorBufferInfo.Buffer uniformBufferInfo(MemoryStack stack, String name,
                                                            @Nullable PackOptionsBuffer options,
                                                            @Nullable GpuBufferSlice globals) {
        if (name.equals(ParticlePassRunner.GLOBALS_INPUT)) {
            if (globals == null) {
                // Unreachable via run()'s own null guard, which skips the whole dispatch first; kept
                // as the loud second half of that contract rather than a silent NPE if the guard is
                // ever moved.
                throw new IllegalStateException("Fornax graph: compute pass '" + spec.name()
                        + "' binds reserved '" + ParticlePassRunner.GLOBALS_INPUT
                        + "' but no u_Globals slice is live");
            }
            return VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(((VulkanGpuBuffer) globals.buffer()).vkBuffer())
                    .offset(globals.offset()).range(globals.length());
        }
        if (options == null) {
            throw new IllegalStateException("Fornax graph: compute pass '" + spec.name()
                    + "' binds reserved '" + PACK_OPTIONS_INPUT + "' but no PackOptionsBuffer is active");
        }
        return VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(((VulkanGpuBuffer) options.currentBuffer()).vkBuffer())
                .offset(0).range(VK13.VK_WHOLE_SIZE);
    }

    /** Destroys this runner's compiled pipeline's four Vulkan handles, its descriptor pool (which
     * implicitly frees the sets allocated from it), plus every ring slot's command pool and fence
     * (draining any still-in-flight dispatch first, so a pack (re)build never destroys a buffer the
     * GPU is still reading -- same invariant {@code VoxelDebugRaymarchPass.disable()} already
     * established for its own ring). Called from {@code GraphRunner.closeCurrent()}. */
    @Override
    public void close() {
        var device = backend.device().vkDevice();
        destroyRingResources();
        if (descriptorPool != 0) {
            VK13.vkDestroyDescriptorPool(device, descriptorPool, null);
            descriptorPool = 0;
        }
        destroyPipeline(backend, pipeline);
    }

    private void destroyRingResources() {
        var device = backend.device().vkDevice();
        for (RingSlot slot : ring) {
            if (slot == null) continue;
            if (slot.submitted && slot.fence != 0) {
                // Teardown has no retry option: log a failed wait and destroy anyway. Leaking the
                // fence/pool would be strictly worse than the small chance of destroying a handle
                // the GPU has already finished with.
                fenceWaitSucceeded(VK13.vkWaitForFences(device, slot.fence, true, FENCE_WAIT_TIMEOUT),
                        "ring teardown in '" + spec.name() + "'");
            }
            if (slot.fence != 0) {
                VK13.vkDestroyFence(device, slot.fence, null);
            }
            if (slot.graphicsSemaphore != 0) {
                VK13.vkDestroySemaphore(device, slot.graphicsSemaphore, null);
            }
            if (slot.commandPool != null) {
                slot.commandPool.destroy();
                slot.commandPool = null;
            }
        }
    }

    private static void destroyPipeline(VulkanComputeBackend backend,
                                        ComputePipelineBuilder.CompiledComputePipeline compiled) {
        var device = backend.device().vkDevice();
        VK13.vkDestroyPipeline(device, compiled.pipeline(), null);
        VK13.vkDestroyPipelineLayout(device, compiled.pipelineLayout(), null);
        VK13.vkDestroyDescriptorSetLayout(device, compiled.descriptorSetLayout(), null);
        VK13.vkDestroyShaderModule(device, compiled.shaderModule(), null);
    }

    private long countType(int descriptorType) {
        return descriptorTypes.stream().filter(t -> t == descriptorType).count();
    }

    /** True if the wait actually drained the slot. False means the caller must not recycle a
     * command pool or destroy a handle the GPU may still be using -- the fence never signalled. */
    static boolean fenceWaitSucceeded(int result, String context) {
        if (result != VK13.VK_SUCCESS) {
            FornaxMod.LOGGER.error("[Fornax] ComputePassRunner: vkWaitForFences returned VkResult {} for {}",
                    result, context);
            return false;
        }
        return true;
    }

    private static void checkVk(int result, String call) {
        if (result != VK13.VK_SUCCESS) {
            throw new IllegalStateException(call + " failed with VkResult " + result);
        }
    }
}
