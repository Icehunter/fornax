package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.debug.ComputeCapture;
import dev.icehunter.fornax.debug.FullscreenCapture;
import dev.icehunter.fornax.metalfx.VulkanMetalInterop;
import com.mojang.blaze3d.buffers.GpuBuffer;
import dev.icehunter.fornax.voxel.VoxelSourceWindow;
import dev.icehunter.fornax.voxel.VoxelWindow;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pipeline.FrameUniformValues;
import dev.icehunter.fornax.pack.RawShaderImports;
import dev.icehunter.fornax.pack.layout.PackOptionsBuffer;
import dev.icehunter.fornax.pack.layout.RuntimeShaderPack;
import dev.icehunter.fornax.pass.compute.ComputePipelineBuilder;
import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import dev.icehunter.fornax.pass.shadow.TerrainShadowResult;
import dev.icehunter.fornax.pass.shadow.ShadowComparisonSampler;
import dev.icehunter.fornax.pipeline.FramePacing;
import dev.icehunter.fornax.pipeline.VulkanPartialFlush;
import dev.icehunter.fornax.profile.ComputePassTimer;
import dev.icehunter.fornax.profile.FrameProfiler;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
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
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The compute-pass analog of {@link FullscreenPassRunner}: one built pipeline per declared
 * {@code compute} pass, dispatched with the group counts {@code PassSpec.dispatch()} declares.
 *
 * <p>Runs every frame once a real pack declares a compute pass (Task 2 onward), so it uses the same
 * frames-in-flight ring {@code VoxelDebugRaymarchPass} already proved correct: each ring slot owns its
 * own command pool + fence, submitted via a raw {@code vkQueueSubmit} (Blaze3D's own
 * {@code VulkanQueue.Submission.close()} hardcodes a null fence -- verified via {@code javap} -- so it
 * cannot be used to attach a completion fence). A slot's fence is waited on only when that SAME slot
 * is about to be reused ({@code FRAMES_IN_FLIGHT} frames later), never every frame, avoiding a
 * whole-queue {@code waitIdle()} on every dispatch.
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

    enum InputSamplerKind {
        SHADOW_COMPARISON(FilterMode.LINEAR, false, false),
        PACK_TEXTURE_REPEAT(FilterMode.LINEAR, true, false),
        PACK_TEXTURE_REPEAT_MIPPED(FilterMode.LINEAR, true, true),
        NEAREST_CLAMP(FilterMode.NEAREST, false, false);

        private final FilterMode filter;
        private final boolean repeat;
        private final boolean mipmapped;

        InputSamplerKind(FilterMode filter, boolean repeat, boolean mipmapped) {
            this.filter = filter;
            this.repeat = repeat;
            this.mipmapped = mipmapped;
        }

        FilterMode filter() { return filter; }

        boolean repeat() { return repeat; }

        boolean mipmapped() { return mipmapped; }
    }

    /** Reserved engine-recognized input name: a compute pass declaring this input in its {@code graph.toml}
     * binds the live {@code u_PackOptions} uniform block ({@link PackOptionsBuffer#currentBuffer()}) at that
     * binding slot, rather than a real {@code TargetRegistry} target -- the compute analog of the
     * unconditional {@code u_PackOptions} bind every FULLSCREEN pass gets. */
    static final String PACK_OPTIONS_INPUT = "packOptions";

    /** Pack-shipped textures are tileable sampled data, while graph targets and engine builtins
     * have real image edges. Keep this split pure so the descriptor path's otherwise silent sampler
     * contract can be pinned without constructing a Vulkan device. */
    static InputSamplerKind samplerKindFor(String ref, boolean packTexture, boolean volumeTexture) {
        // Comparison aliases need a compare-enabled sampler. Their raw aliases must keep plain
        // depth reads even when a pack declares a texture with the same name as the builtin.
        if (ref.equals(ShadowMapManager.TARGET) || ref.equals(ShadowMapManager.ENTITY_TARGET)) {
            return InputSamplerKind.SHADOW_COMPARISON;
        }
        // GraphInputResolver resolves engine-owned names before consulting PackTextureRegistry. A
        // malformed pack can currently declare a texture with the same name, so the sampler choice
        // must preserve that precedence instead of applying the pack-texture sampler to the builtin
        // view the resolver actually returned.
        if (GraphValidator.BUILTINS.contains(ref) || ShadowMapManager.isShadowMapRef(ref)) {
            return InputSamplerKind.NEAREST_CLAMP;
        }
        if (!packTexture) {
            return InputSamplerKind.NEAREST_CLAMP;
        }
        return volumeTexture
                ? InputSamplerKind.PACK_TEXTURE_REPEAT
                : InputSamplerKind.PACK_TEXTURE_REPEAT_MIPPED;
    }

    private final PassSpec spec;
    private final String resolvedSource;
    private final byte[] compiledSpirv;
    @Nullable private final ComputeReuseState reuseState;
    private final List<String> reuseInputTargets;
    private final List<String> reuseTargets;
    private final int[] reuseValues;
    private final Object[] reuseResources;
    private final long[] reuseInputRevisions;
    private final String dispatchCounterLabel;
    private final String reuseCounterLabel;
    private final VulkanComputeBackend backend;
    private final ComputePipelineBuilder.CompiledComputePipeline pipeline;
    private final List<String> bindingOrder;
    /** The {@code VkDescriptorType} of each binding, positionally aligned with {@link #bindingOrder}:
     * {@code STORAGE_BUFFER} for a registry buffer target, {@code COMBINED_IMAGE_SAMPLER} for a texture
     * target, {@code UNIFORM_BUFFER} for the reserved {@code packOptions} input. */
    private final List<Integer> descriptorTypes;
    /** Which sampler binding {@code i} wants, positionally aligned with {@link #bindingOrder}.
     * Only means anything where {@link #descriptorTypes} is {@code COMBINED_IMAGE_SAMPLER}.
     * Worked out once in {@link #build}, same as {@link #descriptorTypes}: which pack textures
     * exist does not change frame to frame, only on a pack rebuild. */
    private final List<InputSamplerKind> samplerKinds;
    /** Extra push-constant bytes (beyond the shared {@code PassParams.PUSH_CONSTANT_BASE_SIZE}) this
     * pass's pipeline layout was built with -- see {@link ExtraPushConstants}. Zero for every compute
     * pass that doesn't need more than the shared block; {@link #run} sizes its push-constant buffer to
     * {@code PassParams.PUSH_CONSTANT_BASE_SIZE + extraPushConstantBytes} and appends the
     * caller-supplied {@code extra} bytes after the base 32, matching what {@link #build} reserved in
     * the pipeline layout. */
    private final int extraPushConstantBytes;
    /** Graphics-owned inputs (G-buffer, shadow map, traced-shadow result) come from draws already
     * sent in an earlier Blaze3D command buffer. A MoltenVK 1.4.2 timeline signal covers only the
     * buffer it was recorded in. A compute-queue wait on it races those draws instead of following
     * them. Same-queue clash tracking in Metal does not have that gap. {@link #run} runs such a pass
     * in the graphics stream instead of the compute queue when this is true. */
    private final boolean graphicsStream;
    @Nullable
    private final CrossQueueImageReuseSequence imageReuseSequence;
    private long imageReuseTimelineSemaphore;
    @Nullable
    private final GraphicsInputDependency graphicsInputDependency;
    private long graphicsInputTimelineSemaphore;
    private CrossQueueImageReuseSequence.@Nullable Ticket pendingGraphicsRelease;
    @Nullable
    private final RawTimestampQueries timestampQueries;
    private final ComputePassTimer computeTimer;
    private final FrameProfiler profiler;
    private final String recycleTimingLabel;
    private final String lockTimingLabel;
    private final String submitTimingLabel;
    private final String dependencyTimingLabel;
    private final RingSlot[] ring = new RingSlot[FRAMES_IN_FLIGHT];
    private long descriptorPool;
    private final long[] descriptorSets = new long[FRAMES_IN_FLIGHT];
    private long frameIndex;

    private static final class RingSlot {
        @Nullable VulkanCommandPool commandPool;
        long fence;
        long graphicsSemaphore;
        boolean submitted;
        @Nullable ComputeCapture capture;
    }

    private ComputePassRunner(PassSpec spec, VulkanComputeBackend backend,
                               ComputePipelineBuilder.CompiledComputePipeline pipeline,
                               List<String> bindingOrder, List<Integer> descriptorTypes,
                               List<InputSamplerKind> samplerKinds,
                               int extraPushConstantBytes, boolean graphicsCompletionBeforeStorageWrite,
                               FrameProfiler profiler, String resolvedSource, byte[] compiledSpirv,
                               Set<String> graphicsWrittenTargets) {
        this.spec = spec;
        this.resolvedSource = resolvedSource;
        this.compiledSpirv = compiledSpirv;
        this.reuseState = spec.reuseWhenUnchanged() == null ? null : new ComputeReuseState();
        this.reuseInputTargets = reuseState == null ? List.of() : spec.inputs().stream()
                .filter(name -> !name.equals(PACK_OPTIONS_INPUT) && !name.equals(ParticlePassRunner.GLOBALS_INPUT))
                .toList();
        this.reuseTargets = new ArrayList<>(reuseInputTargets);
        if (reuseState != null) reuseTargets.addAll(spec.outputs());
        this.reuseValues = new int[reuseState == null ? 0
                : ComputeReuseState.valueCount(spec.reuseWhenUnchanged())];
        this.reuseResources = new Object[reuseTargets.size()];
        this.reuseInputRevisions = new long[reuseInputTargets.size()];
        this.dispatchCounterLabel = "compute dispatches " + spec.name();
        this.reuseCounterLabel = "compute reuses " + spec.name();
        this.profiler = profiler;
        this.recycleTimingLabel = "compute recycle CPU " + spec.name();
        this.lockTimingLabel = "compute lock CPU " + spec.name();
        this.submitTimingLabel = "compute submit CPU " + spec.name();
        this.dependencyTimingLabel = "compute dependency CPU " + spec.name();
        this.backend = backend;
        this.pipeline = pipeline;
        this.bindingOrder = bindingOrder;
        this.descriptorTypes = descriptorTypes;
        this.samplerKinds = samplerKinds;
        this.extraPushConstantBytes = extraPushConstantBytes;
        this.imageReuseSequence = graphicsCompletionBeforeStorageWrite
                ? new CrossQueueImageReuseSequence() : null;
        this.graphicsStream = GraphicsInputDependency.requiredBy(spec.inputs(), graphicsWrittenTargets);
        // A pass with graphics-owned inputs runs in the graphics stream instead (see run()), so
        // this dependency is never constructed. See docs/ARCHITECTURE.md.
        this.graphicsInputDependency = null;
        // runInGraphicsStream() never touches timestampQueries/computeTimer; see its own doc
        // comment ("no fence, no timestamp query"). A pool allocated here would never be read:
        // a raw VkQueryPool (one MTLCounterSampleBuffer under MoltenVK) held for the runner's
        // whole lifetime. Each pool is its own live counter-buffer allocation, and a device only
        // has so many; a pack with enough compute passes enabled at once can use them all up,
        // which MoltenVK reports as a later vkCreateQueryPool falling back to CPU-emulated
        // timestamps rather than a hard failure.
        this.timestampQueries = graphicsStream ? null : RawTimestampQueries.tryCreate(backend, spec.name());
        float timestampPeriodNs = backend.device().getDeviceInfo().timestampPeriod();
        int timestampValidBits = timestampQueries != null ? timestampQueries.validBits() : 0;
        this.computeTimer = new ComputePassTimer(profiler, spec.name(), timestampQueries,
                timestampPeriodNs, timestampValidBits);
        try {
            this.imageReuseTimelineSemaphore = graphicsCompletionBeforeStorageWrite
                    ? createTimelineSemaphore(backend) : 0;
            this.graphicsInputTimelineSemaphore = graphicsInputDependency != null
                    ? createTimelineSemaphore(backend) : 0;
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                ring[i] = new RingSlot();
                ring[i].commandPool = new VulkanCommandPool(backend.device(), backend.computeQueue());
                ring[i].fence = createFence(backend);
                ring[i].graphicsSemaphore = createSemaphore(backend);
            }
        } catch (RuntimeException e) {
            destroyRingResources();
            destroyImageReuseTimeline();
            destroyGraphicsInputTimeline(true);
            computeTimer.close();
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

    private static long createTimelineSemaphore(VulkanComputeBackend backend) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreTypeCreateInfo typeInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
                    .sType$Default()
                    .semaphoreType(VK13.VK_SEMAPHORE_TYPE_TIMELINE)
                    .initialValue(0);
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(typeInfo.address());
            LongBuffer out = stack.mallocLong(1);
            int result = VK13.vkCreateSemaphore(backend.device().vkDevice(), semaphoreInfo, null, out);
            if (result != VK13.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateSemaphore(timeline) failed with VkResult " + result);
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
                                          boolean graphicsCompletionBeforeStorageWrite,
                                          FrameProfiler profiler, Set<String> graphicsWrittenTargets) {
        String source = RuntimeShaderPack.getInstance().sourceOrNull(spec.shader());
        if (source == null) {
            throw new IllegalStateException("Fornax graph: compute pass '" + spec.name()
                    + "' names shader '" + spec.shader() + "' with no composed source");
        }
        source = RawShaderImports.expand(source, RuntimeShaderPack.getInstance().sourcesSnapshot(),
                spec.shader());
        List<String> bindingOrder = combinedBindingOrder(spec);
        List<Integer> descriptorTypes = new ArrayList<>(bindingOrder.size());
        List<InputSamplerKind> samplerKinds = new ArrayList<>(bindingOrder.size());
        PackTextureRegistry packTextures = GraphRunner.packTextureRegistry();
        for (String name : bindingOrder) {
            descriptorTypes.add(descriptorTypeFor(spec, name, registry));
            boolean packTexture = packTextures != null && packTextures.isDeclared(name);
            boolean volumeTexture = packTexture && packTextures.isVolume(name);
            samplerKinds.add(samplerKindFor(name, packTexture, volumeTexture));
        }

        ByteBuffer spirv = ComputeShaderCompiler.compileToSpirv(source, spec.shader());
        byte[] compiledSpirv = new byte[spirv.remaining()];
        spirv.duplicate().get(compiledSpirv); // Exact module bytes passed to pipeline creation below.
        try {
            var compiled = ComputePipelineBuilder.buildWithDescriptorLayout(backend.device(), spirv, descriptorTypes,
                    PassParams.PUSH_CONSTANT_BASE_SIZE + extraPushConstantBytes);
            ComputePassRunner runner = null;
            try {
                runner = new ComputePassRunner(spec, backend, compiled, bindingOrder, descriptorTypes,
                        samplerKinds, extraPushConstantBytes, graphicsCompletionBeforeStorageWrite, profiler, source,
                        compiledSpirv, graphicsWrittenTargets);
                runner.allocateDescriptorSets();
            } catch (RuntimeException e) {
                if (runner != null) {
                    runner.close();
                } else {
                    destroyPipeline(backend, compiled);
                }
                throw e;
            }
            if (runner.graphicsStream()) {
                FornaxMod.LOGGER.info("[Fornax] ComputePassRunner('{}'): reads graphics-written inputs and runs "
                        + "in the graphics stream", spec.name());
            } else {
                // Queue-topology fact, logged once per runner build instead of assumed.
                // VulkanComputeBackend already logs this once per session at tryCreate(). This line repeats
                // it to connect it with how this runner waits on graphics work, for this compute pass.
                // That keeps it visible next to the passes that depend on cross-queue handoff.
                FornaxMod.LOGGER.info("[Fornax] ComputePassRunner('{}'): compute queue {} the graphics queue family"
                                + ": semaphore handoff is available for same-frame graphics readers",
                        spec.name(), backend.sharesQueueFamilyWithGraphics() ? "SHARES" : "does NOT share");
            }
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
        if (ShadowMapManager.isShadowMapRef(name) || TerrainShadowResult.isRef(name)) {
            // Engine-owned sun shadow depth target (see ShadowMapManager), covering both its
            // pack-visible names (TARGET and RAW_TARGET -- same resource, different sampler chosen
            // downstream) -- deliberately NOT builtin.-prefixed (GraphValidator.checkInputRef treats
            // it as a peer of BUILTINS, not a member: see that method's own isShadowMapRef branch) and never
            // a TargetRegistry entry, so neither the BUILTINS check above nor the registry lookups
            // below ever match it -- without this branch a compute pass declaring "sunShadowMap"
            // would throw here instead of building. RtShadowResult's two names are validated on the
            // same kind of parallel branch (GraphValidator.checkInputRef's own RtShadowResult case)
            // and need the identical treatment here for the identical reason.
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
        // Pack-declared texture ([textures.*], e.g. a 2D PNG or a 3D raw volume) rather than a graph
        // target. resolveView (called from updateAndBindDescriptorSet) already resolves these
        // generically via PackTextureRegistry.isDeclared, the same way FullscreenPassRunner's sampler
        // special-case does; this branch only has to decide descriptor TYPE up front, same reasoning
        // as the shadow-map/builtin branches above. Sampled, not storage: a pack texture is read-only
        // input data here, same as everywhere else it is bound. Deliberately dimension-agnostic: a
        // VkWriteDescriptorSet built from a raw VkImageView handle (see updateAndBindDescriptorSet)
        // does not care whether that view is 2D or 3D, unlike com.mojang.blaze3d.vulkan.glsl.GlslCompiler's
        // graphics-pipeline reflection path, which rejects any non-2D/Cube sampler outright. That is
        // precisely why a pack-shipped sampler3D volume must be consumed from a compute pass, never a
        // fullscreen/geometry one.
        PackTextureRegistry textures = GraphRunner.packTextureRegistry();
        if (textures != null && textures.isDeclared(name)) {
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
     * without blocking the CPU. When {@code graphicsWaits} is non-null, it takes over this
     * submission's handoff until the next pass that depends on it, or until it closes. Pre-opaque
     * calls pass null.
     *
     * <p>A runner whose storage output can also be read on the graphics queue carries the reverse
     * edge independently: its raw submit waits on a per-runner timeline value at
     * {@code COMPUTE_SHADER}, and renderLevel RETURN records the next value only after every graphics
     * reader. Concurrent image sharing removes ownership transfers but cannot provide this execution
     * dependency, so it is not a substitute for the timeline. Known external shadow inputs add a
     * separate wait for this frame's graphics work, after flushing it out; that wait never replaces
     * the previous-frame storage-reuse wait.
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
    public long run(TargetRegistry registry, PassParams params, @Nullable PackOptionsBuffer options,
                    @Nullable GpuBufferSlice globals, @Nullable ExtraPushConstants extra,
                    @Nullable int[] dispatchOverride,
                    boolean synchronousWait, long graphicsWaitStageMask,
                    @Nullable ComputeGraphicsWaits graphicsWaits) {
        registry.requireStorageTextureInitializationComplete(spec.name());
        // Reject a late atlas publication before allocating/recording this raw compute submission.
        GraphRunner.requireComputeAtlasTexturesPrepared(spec.name(), bindingOrder, registry);
        if ((globals == null && bindingOrder.contains(ParticlePassRunner.GLOBALS_INPUT))
                || (reuseState != null && !spec.reuseWhenUnchanged().globals().isEmpty()
                && !FrameUniformValues.CURRENT.ready())) {
            if (reuseState != null) reuseState.invalidate();
            return -1L;
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
        if (graphicsStream) {
            return runInGraphicsStream(registry, params, options, globals, extra, dispatchOverride,
                    extraBytesThisCall);
        }
        int slotIndex = (int) (frameIndex % FRAMES_IN_FLIGHT);
        RingSlot slot = ring[slotIndex];
        frameIndex++;

        if (slot.submitted && slot.fence != 0) {
            long recycleStart = System.nanoTime();
            int waitResult = VK13.vkWaitForFences(backend.device().vkDevice(), slot.fence, true, FENCE_WAIT_TIMEOUT);
            profiler.record(recycleTimingLabel, (System.nanoTime() - recycleStart) / 1_000_000.0);
            if (!fenceWaitSucceeded(waitResult, "ring-slot recycle in '" + spec.name() + "'")) {
                // The fence never signalled, so this slot's prior buffer may still be in flight.
                // Resetting the pool now would be a use-after-free of a live command buffer; skip
                // this frame's dispatch entirely rather than recycle an undrained slot.
                return -1L;
            }
            if (slot.capture != null) {
                slot.capture.retireAfterFence();
                slot.capture = null;
            }
            computeTimer.drainCompleted(slotIndex);
            VK13.vkResetFences(backend.device().vkDevice(), slot.fence);
            slot.submitted = false;
        }
        VulkanCommandPool pool = slot.commandPool;
        if (pool != null) {
            pool.reset(); // this slot's prior buffer was drained by the checked fence wait above
        }

        long lockStart = System.nanoTime();
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            profiler.record(lockTimingLabel, (System.nanoTime() - lockStart) / 1_000_000.0);
            VkCommandBuffer cmd = pool != null ? pool.allocateBuffer() : null;
            if (cmd == null) {
                return -1L;
            }
            ComputeCapture capture = FullscreenCapture.isSelected(spec.name())
                    ? ComputeCapture.begin(spec, backend, resolvedSource, compiledSpirv) : null;
            boolean captureSubmitted = false;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
                VK13.vkBeginCommandBuffer(cmd, beginInfo);
                if (bindingOrder.contains(VoxelSourceWindow.TARGET)) VoxelWindow.refreshSourceWindow(registry);
                EngineBufferUploadQueue.recordForBindings(cmd, stack, registry, bindingOrder,
                        VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                VK13.vkCmdBindPipeline(cmd, VK13.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipeline());
                updateAndBindDescriptorSet(registry, cmd, descriptorSets[slotIndex], options, globals, capture);

                ByteBuffer push = stack.malloc(PassParams.PUSH_CONSTANT_BASE_SIZE + extraBytesThisCall);
                writePushConstants(push, params, extra);
                VK13.vkCmdPushConstants(cmd, pipeline.pipelineLayout(), VK13.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);

                int[] groups = resolveDispatchGroups(dispatchOverride, registry);
                int groupsX = groups[0];
                int groupsY = groups[1];
                int groupsZ = groups[2];
                boolean dispatchKernel = true;
                if (reuseState != null) {
                    if (!captureReuseInputs(registry, options, params, groupsX, groupsY, groupsZ)) {
                        reuseState.invalidate();
                        return -1L;
                    }
                    dispatchKernel = reuseState.needsDispatch(reuseValues, reuseResources, reuseInputRevisions);
                }
                if (capture != null) capture.beforeDispatch(cmd, push, groupsX, groupsY, groupsZ, dispatchKernel);
                // Reuse omits only the kernel and its timestamps. Descriptor retirement and the
                // binary/timeline handoffs still require the ordinary submission on every frame.
                if (dispatchKernel) {
                    if (timestampQueries != null) {
                        int firstQuery = slotIndex * 2;
                        VK13.vkCmdResetQueryPool(cmd, timestampQueries.pool(), firstQuery, 2);
                        // Match the image-reuse semaphore's wait stage. TOP_OF_PIPE can
                        // timestamp before graphics readers finish and charge that wait here.
                        // Stage timestamps are approximate intervals, not exclusive kernel time.
                        VK13.vkCmdWriteTimestamp(cmd, VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                timestampQueries.pool(), firstQuery);
                    }
                    VK13.vkCmdDispatch(cmd, groupsX, groupsY, groupsZ);
                    if (timestampQueries != null) {
                        VK13.vkCmdWriteTimestamp(cmd, VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                timestampQueries.pool(), slotIndex * 2 + 1);
                    }
                }
                if (capture != null) capture.afterDispatch(cmd);
                recordComputeWriteReleaseBarrier(cmd, stack,
                        VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_SHADER_READ_BIT);
                VK13.vkEndCommandBuffer(cmd);

                // Blaze3D's Submission.close() hardcodes a null fence -- bypass it, exactly like
                // VoxelDebugRaymarchPass.submitDispatch already does, so a real completion fence can
                // be attached.
                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(cmd));
                // Flush graphics work first, before the compute wait and before this dispatch's
                // outgoing graphics wait. Doing it in a different order locks up the GPU.
                long graphicsInputValue = publishGraphicsInputs();
                CrossQueueImageReuseSequence.Ticket reuseTicket = imageReuseSequence != null
                        ? imageReuseSequence.beginWrite() : null;
                List<GraphicsInputDependency.Wait> waits = GraphicsInputDependency.waits(
                        graphicsInputTimelineSemaphore, graphicsInputValue,
                        imageReuseTimelineSemaphore, reuseTicket != null ? reuseTicket.waitValue() : 0);
                if (!waits.isEmpty()) {
                    // These two waits are separate: this frame's graphics-write to compute-read,
                    // and last frame's graphics-read to this compute-write. CONCURRENT image sharing
                    // does not order either one.
                    LongBuffer waitSemaphores = stack.mallocLong(waits.size());
                    LongBuffer waitValues = stack.mallocLong(waits.size());
                    IntBuffer waitStages = stack.mallocInt(waits.size());
                    for (int i = 0; i < waits.size(); i++) {
                        waitSemaphores.put(i, waits.get(i).semaphore());
                        waitValues.put(i, waits.get(i).value());
                        waitStages.put(i, capture != null ? VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                                : VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                    }
                    VkTimelineSemaphoreSubmitInfo timelineInfo = VkTimelineSemaphoreSubmitInfo.calloc(stack)
                            .sType$Default().pWaitSemaphoreValues(waitValues);
                    if (graphicsWaitStageMask != 0) {
                        // The value list must still cover the existing binary signal semaphore.
                        // Vulkan ignores its value but still needs a zero entry in that spot.
                        timelineInfo.pSignalSemaphoreValues(stack.longs(0L));
                    }
                    submitInfo.pNext(timelineInfo.address())
                            .pWaitSemaphores(waitSemaphores).pWaitDstStageMask(waitStages);
                }
                if (graphicsWaitStageMask != 0) {
                    submitInfo.pSignalSemaphores(stack.longs(slot.graphicsSemaphore));
                }
                int result;
                try {
                    long submitStart = System.nanoTime();
                    result = VK13.vkQueueSubmit(backend.computeQueue().vkQueue(), submitInfo, slot.fence);
                    profiler.record(submitTimingLabel, (System.nanoTime() - submitStart) / 1_000_000.0);
                } catch (RuntimeException | Error e) {
                    if (reuseTicket != null) {
                        imageReuseSequence.cancel(reuseTicket);
                    }
                    throw e;
                }
                if (result != VK13.VK_SUCCESS) {
                    if (reuseTicket != null) {
                        imageReuseSequence.cancel(reuseTicket);
                    }
                    throw new IllegalStateException("vkQueueSubmit failed with VkResult " + result);
                }
                pendingGraphicsRelease = reuseTicket;
                if (dispatchKernel) computeTimer.markSubmitted(slotIndex);
                if (reuseState != null) {
                    reuseState.submitted(dispatchKernel, reuseValues, reuseResources, reuseInputRevisions);
                    if (dispatchKernel) registry.recordComputeWrite(spec.outputs());
                    profiler.incrementCounter(dispatchKernel ? dispatchCounterLabel : reuseCounterLabel);
                }
                slot.submitted = true;
                captureSubmitted = true;
                if (capture != null) {
                    slot.capture = capture;
                    if (capture.awaitFence(slot.fence)) slot.capture = null;
                }
                if (graphicsWaitStageMask != 0) {
                    // VulkanDevice returns its persistent encoder. Lighting producers request this
                    // handoff from GraphRunner.prepare(), before opaque terrain begins, so closing
                    // the command buffer recorded so far cannot split an active Apple tile render
                    // encoder. The semaphore supplies both execution ordering and device-memory
                    // visibility across the separate compute/graphics queues without a host stall.
                    if (graphicsWaits == null) {
                        VulkanCommandEncoder graphics = backend.device().createCommandEncoder();
                        graphics.waitSemaphore(slot.graphicsSemaphore, 0L, graphicsWaitStageMask);
                    } else {
                        graphicsWaits.submitted(spec, slot.graphicsSemaphore, graphicsWaitStageMask);
                    }
                }
                if (synchronousWait) {
                    // Legacy host wait for passes with a current-frame graphics -> compute input
                    // dependency that has not yet been converted to a bidirectional semaphore chain.
                    long waitStart = System.nanoTime();
                    int waitResult = VK13.vkWaitForFences(backend.device().vkDevice(), slot.fence, true, FENCE_WAIT_TIMEOUT);
                    long dependencyWaitNanos = System.nanoTime() - waitStart;
                    profiler.record(dependencyTimingLabel, dependencyWaitNanos / 1_000_000.0);
                    if (fenceWaitSucceeded(waitResult, "synchronous wait in '" + spec.name() + "'")) {
                        computeTimer.drainCompleted(slotIndex);
                        VK13.vkResetFences(backend.device().vkDevice(), slot.fence);
                        slot.submitted = false;
                    }
                    // On failure, leave slot.submitted = true: the dispatch this call just made has
                    // not been confirmed complete, so the caller's assumption that the compute result
                    // already landed does not hold. Next frame's ring-slot recycle wait retries it.
                    return dependencyWaitNanos;
                }
            } finally {
                if (capture != null && !captureSubmitted) capture.abortBeforeSubmit();
            }
        }
        return -1L;
    }

    boolean graphicsStream() {
        return graphicsStream;
    }

    /** PassParams travels as a push constant here. A compute pass has no reserved uniform-buffer slot
     * for it. The layout matches PassParams' own 32-byte std140 layout: vec2 texel size sits at
     * offset 0. Two scalars follow at offsets 8 and 12. vec3 sun direction sits at offset 16. Bytes
     * 28 through 31 are padding. A pass built with extraPushConstantBytes > 0 (see
     * ExtraPushConstants) gets those extra bytes appended right after. Their size matches the
     * pipeline layout's range built in build(). This uses PassParams.PUSH_CONSTANT_BASE_SIZE (32) on
     * purpose, not PassParams.BUFFER_SIZE (64). The sun/moon sprite rects make up the rest of
     * BUFFER_SIZE. They belong only to the uniform buffer (FULLSCREEN resolve), not to a compute
     * pass's push constants. A compute pass's push-constant byte layout must stay exactly where it
     * always was. Any ExtraPushConstants offset a pack's own compute shader hardcodes depends on
     * that layout staying fixed too. */
    private static void writePushConstants(ByteBuffer push, PassParams params, @Nullable ExtraPushConstants extra) {
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
    }

    /** A literal TOML dispatch, an engine-computed override, or output-resolution-derived groups, in
     * that priority order. dispatchOverride carries engine-computed group counts for passes whose
     * domain is a runtime-sized 3D volume. The voxel window scales with render distance, so TOML's
     * static literal cannot express it. localSize derivation only understands 2D texture outputs
     * too. See GraphRunner.computeDispatchOverride. A declared localSize derives groups from the
     * pass's first output's REAL resolved pixel size, every time it runs. TOML's literal x/y go
     * unused in this mode. This mirrors the exact ceil(width/LOCAL_SIZE) pattern
     * VoxelDebugRaymarchPass already proved correct for its own (non-TOML) dispatch. */
    private int[] resolveDispatchGroups(@Nullable int[] dispatchOverride, TargetRegistry registry) {
        List<Integer> d = spec.dispatch();
        int groupsX = d.get(0);
        int groupsY = d.get(1);
        int groupsZ = d.get(2);
        if (dispatchOverride != null) {
            groupsX = dispatchOverride[0];
            groupsY = dispatchOverride[1];
            groupsZ = dispatchOverride[2];
        } else {
            List<Integer> localSize = spec.localSize();
            if (localSize != null) {
                TargetInstance out = registry.get(spec.outputs().get(0));
                if (out == null) {
                    // registry.get() is the TEXTURE map: a buffer-only-output pass has no
                    // pixel size to derive groups from. This fails loudly here instead of
                    // running the dispatch against a target that is not set.
                    throw new IllegalStateException("Fornax graph: compute pass '" + spec.name()
                            + "' declares local_size but its first output '" + spec.outputs().get(0)
                            + "' is not a texture target: buffer-only passes need a literal"
                            + " dispatch or an engine dispatch override (GraphRunner.computeDispatchOverride)");
                }
                groupsX = (out.width() + localSize.get(0) - 1) / localSize.get(0);
                groupsY = (out.height() + localSize.get(1) - 1) / localSize.get(1);
            }
        }
        return new int[] { groupsX, groupsY, groupsZ };
    }

    /** Records the dispatch directly into Blaze3D's persistent graphics command encoder instead of
     * sending it to the compute queue. Recording order in that single encoder is what puts this
     * dispatch after the draws that produced its graphics-owned inputs. Metal catches that kind of
     * clash on its own within one queue. A cross-queue timeline wait does not give the same order on
     * this MoltenVK version. No fence, no timestamp query, no capture, no reuse ticket, no
     * graphics-wait semaphore signal. This dispatch needs none of the cross-queue code the
     * compute-queue path below still uses. */
    private long runInGraphicsStream(TargetRegistry registry, PassParams params,
                                      @Nullable PackOptionsBuffer options, @Nullable GpuBufferSlice globals,
                                      @Nullable ExtraPushConstants extra, @Nullable int[] dispatchOverride,
                                      int extraBytesThisCall) {
        int slotIndex = (int) (frameIndex % FRAMES_IN_FLIGHT);
        frameIndex++;
        int[] groups = resolveDispatchGroups(dispatchOverride, registry);
        VulkanMetalInterop.recordIntoStream(backend.device().createCommandEncoder(), cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (bindingOrder.contains(VoxelSourceWindow.TARGET)) VoxelWindow.refreshSourceWindow(registry);
                EngineBufferUploadQueue.recordForBindings(cmd, stack, registry, bindingOrder,
                        VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                VK13.vkCmdBindPipeline(cmd, VK13.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipeline());
                updateAndBindDescriptorSet(registry, cmd, descriptorSets[slotIndex], options, globals, null);
                ByteBuffer push = stack.malloc(PassParams.PUSH_CONSTANT_BASE_SIZE + extraBytesThisCall);
                writePushConstants(push, params, extra);
                VK13.vkCmdPushConstants(cmd, pipeline.pipelineLayout(), VK13.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                VK13.vkCmdDispatch(cmd, groups[0], groups[1], groups[2]);
                // Same-queue readers here are a fragment sampler (atmo_aerial -> resolve/resolve_hdr) and
                // a RAY_QUERY transfer copy (sun_shadow_seed -> sun_shadow_trace). Same-queue compute can
                // also read it.
                recordComputeWriteReleaseBarrier(cmd, stack,
                        VK13.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK13.VK_PIPELINE_STAGE_TRANSFER_BIT
                                | VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK13.VK_ACCESS_SHADER_READ_BIT | VK13.VK_ACCESS_TRANSFER_READ_BIT);
            }
        });
        return -1L;
    }

    /** Submit all current graphics work, including shadow raster writes and RT copy-back, and
     * signal a timeline value for it. A partial flush sends the work out without moving encoder
     * retirement forward or waiting on the host. The graph only calls compute between render
     * passes, so no short-lived encoder handle leaks out of this method. */
    private long publishGraphicsInputs() {
        if (graphicsInputDependency == null) return 0;
        VulkanCommandEncoder graphics = backend.device().createCommandEncoder();
        VulkanPartialFlush partialFlush = (VulkanPartialFlush) graphics;
        return graphicsInputDependency.publish(
                value -> graphics.signalSemaphore(graphicsInputTimelineSemaphore, value,
                        VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT),
                partialFlush::fornax$flushPending);
    }

    private boolean captureReuseInputs(TargetRegistry registry, @Nullable PackOptionsBuffer options,
                                       PassParams params, int groupsX, int groupsY, int groupsZ) {
        int index = 0;
        for (String key : spec.reuseWhenUnchanged().runtime()) {
            if (options == null) return false;
            reuseValues[index++] = Float.floatToRawIntBits(options.get(key, Float.NaN));
        }
        ComputeReuseState.writeFrameAndPush(spec.reuseWhenUnchanged(), FrameUniformValues.CURRENT,
                params, groupsX, groupsY, groupsZ, reuseValues);
        for (int i = 0; i < reuseTargets.size(); i++) {
            reuseResources[i] = registry.get(reuseTargets.get(i));
            if (reuseResources[i] == null) return false;
        }
        for (int i = 0; i < reuseInputTargets.size(); i++) {
            reuseInputRevisions[i] = registry.computeContentRevision(reuseInputTargets.get(i));
            if (reuseInputRevisions[i] == 0) return false;
        }
        return true;
    }

    boolean hasPendingGraphicsStorageReads() {
        return pendingGraphicsRelease != null;
    }

    /**
     * Records this runner's graphics-read completion value into Blaze3D's persistent graphics
     * encoder. The caller invokes this after every possible graph-target reader at renderLevel
     * RETURN. Recording only: this method neither submits nor host-waits the graphics queue.
     */
    void recordGraphicsStorageReadsComplete(VulkanCommandEncoder graphics) {
        CrossQueueImageReuseSequence.Ticket ticket = pendingGraphicsRelease;
        if (ticket == null || imageReuseSequence == null) {
            return;
        }
        graphics.signalSemaphore(imageReuseTimelineSemaphore, ticket.releaseValue(),
                VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
        imageReuseSequence.publishGraphicsCompletion(ticket);
        pendingGraphicsRelease = null;
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
     * {@link #runInGraphicsStream} has no cross-queue reader to signal for. Every reader is already
     * on the same queue. So it passes this same barrier a wider dstStageMask/dstAccessMask instead
     * of relying on that semaphore.
     */
    private static void recordComputeWriteReleaseBarrier(VkCommandBuffer cmd, MemoryStack stack,
                                                          int dstStageMask, int dstAccessMask) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK13.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(dstAccessMask);
        VK13.vkCmdPipelineBarrier(cmd, VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                dstStageMask,
                0, barrier, null, null);
    }

    /** Re-points this frame's descriptor set at the CURRENT resolved target handles (a target can be
     * reallocated between frames -- e.g. a resize -- so this must re-read handles every call, not
     * cache them at build time) and binds it before dispatch. Caller holds {@code SHARED_QUEUE_LOCK}. */
    private void updateAndBindDescriptorSet(TargetRegistry registry, VkCommandBuffer cmd, long descriptorSet,
                                            @Nullable PackOptionsBuffer options,
                                            @Nullable GpuBufferSlice globals, @Nullable ComputeCapture capture) {
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
                    VkDescriptorBufferInfo.Buffer bufferInfo = uniformBufferInfo(stack, name, options, globals);
                    write.descriptorType(VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(bufferInfo);
                    if (capture != null) {
                        GpuBuffer bound = name.equals(ParticlePassRunner.GLOBALS_INPUT)
                                ? globals.buffer() : options.currentBuffer();
                        capture.buffer(i, name, type, bufferInfo.get(0).buffer(),
                                bufferInfo.get(0).offset(), bufferInfo.get(0).range(), bound.size(),
                                (bound.usage() & GpuBuffer.USAGE_COPY_SRC) != 0);
                    }
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
                    if (capture != null) capture.buffer(i, name, type, bufferInfo.get(0).buffer(),
                            bufferInfo.get(0).offset(), bufferInfo.get(0).range(), buf.sizeBytes(), true);
                } else {
                    GpuTextureView view = GraphInputResolver.resolveView(name, registry, Map.of());
                    long imageView = ((VulkanGpuTextureView) view).vkImageView();
                    if (type == VK13.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE) {
                        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                                .sampler(0L).imageView(imageView)
                                .imageLayout(VK13.VK_IMAGE_LAYOUT_GENERAL);
                        write.descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imageInfo);
                        if (capture != null) capture.image(i, name, type, view, null, null);
                    } else {
                        InputSamplerKind samplerKind = samplerKinds.get(i);
                        VulkanGpuSampler sampler;
                        if (samplerKind == InputSamplerKind.SHADOW_COMPARISON) {
                            // A plain sampler makes sampler2DShadow reads invalid, and the shader
                            // still compiles, so there is no other way to catch this. There is no
                            // fallback if a comparison sampler is missing.
                            GpuSampler comparisonSampler = ShadowComparisonSampler.get();
                            if (!(comparisonSampler instanceof VulkanGpuSampler vulkanComparison)) {
                                throw new IllegalStateException("Fornax graph: compute pass '" + spec.name()
                                        + "' comparison input '" + name + "' requires a Vulkan shadow comparison sampler");
                            }
                            sampler = vulkanComparison;
                        } else {
                            sampler = (VulkanGpuSampler) (samplerKind.repeat()
                                    ? RenderSystem.getSamplerCache().getRepeat(
                                            samplerKind.filter(), samplerKind.mipmapped())
                                    : RenderSystem.getSamplerCache().getClampToEdge(samplerKind.filter()));
                        }
                        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                                .sampler(sampler.vkSampler()).imageView(imageView)
                                // Mojang keeps sampled and attachment textures in GENERAL on its
                                // Vulkan backend; use the real engine layout rather than claiming
                                // SHADER_READ_ONLY for a resource whose layout was never transitioned.
                                .imageLayout(VK13.VK_IMAGE_LAYOUT_GENERAL);
                        write.descriptorType(VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(imageInfo);
                        if (capture != null) {
                            PackTextureRegistry textures = GraphRunner.packTextureRegistry();
                            capture.image(i, name, type, view, sampler,
                                    textures != null && textures.getView(name) == view ? textures.volumeAsset(name) : null);
                        }
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
     * established for its own ring). A hazardous runner's image-reuse timeline is destroyed after
     * that drain. The current-input timeline also waits on graphics signals a failed submit left
     * outside every compute fence, or is kept alive if that submit's outcome is unknown.
     * Called from {@code GraphRunner.closeCurrent()}. */
    @Override
    public void close() {
        var device = backend.device().vkDevice();
        boolean computeCompleted = destroyRingResources();
        destroyGraphicsInputTimeline(computeCompleted);
        if (imageReuseTimelineSemaphore != 0) {
            VK13.vkDestroySemaphore(device, imageReuseTimelineSemaphore, null);
            imageReuseTimelineSemaphore = 0;
        }
        computeTimer.close();
        if (descriptorPool != 0) {
            VK13.vkDestroyDescriptorPool(device, descriptorPool, null);
            descriptorPool = 0;
        }
        destroyPipeline(backend, pipeline);
    }

    private void destroyGraphicsInputTimeline(boolean computeCompleted) {
        if (graphicsInputTimelineSemaphore == 0 || graphicsInputDependency == null) return;
        // A failed compute submit leaves a live graphics signal outside every ring fence. Do not
        // destroy it until it is proven done. If a partial flush's outcome is unknown, keep the
        // handle until the device itself is destroyed: waiting on a value that was never sent
        // could hang forever.
        boolean completed = graphicsInputDependency.awaitBeforeDestroy(computeCompleted, value -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc(stack).sType$Default()
                        .pSemaphores(stack.longs(graphicsInputTimelineSemaphore))
                        .pValues(stack.longs(value));
                int result = VK13.vkWaitSemaphores(backend.device().vkDevice(), waitInfo, FENCE_WAIT_TIMEOUT);
                if (result != VK13.VK_SUCCESS) {
                    FornaxMod.LOGGER.error("[Fornax] Graphics input timeline wait failed for '{}' at {}: {}",
                            spec.name(), value, result);
                }
                return result == VK13.VK_SUCCESS;
            }
        });
        if (!completed) {
            FornaxMod.LOGGER.error("[Fornax] Retaining graphics input semaphore {} for '{}' until device destruction: "
                            + "producer submission or producer/consumer completion is uncertain",
                    graphicsInputTimelineSemaphore, spec.name());
            return;
        }
        VK13.vkDestroySemaphore(backend.device().vkDevice(), graphicsInputTimelineSemaphore, null);
        graphicsInputTimelineSemaphore = 0;
    }

    private void destroyImageReuseTimeline() {
        if (imageReuseTimelineSemaphore != 0) {
            VK13.vkDestroySemaphore(backend.device().vkDevice(), imageReuseTimelineSemaphore, null);
            imageReuseTimelineSemaphore = 0;
        }
    }

    private boolean destroyRingResources() {
        boolean completed = true;
        var device = backend.device().vkDevice();
        for (int slotIndex = 0; slotIndex < ring.length; slotIndex++) {
            RingSlot slot = ring[slotIndex];
            if (slot == null) continue;
            if (slot.submitted && slot.fence != 0) {
                // Teardown has no retry option: log a failed wait and destroy anyway. Leaking the
                // fence/pool would be strictly worse than the small chance of destroying a handle
                // the GPU has already finished with.
                if (fenceWaitSucceeded(VK13.vkWaitForFences(device, slot.fence, true, FENCE_WAIT_TIMEOUT),
                        "ring teardown in '" + spec.name() + "'")) {
                    if (slot.capture != null) {
                        slot.capture.retireAfterFence();
                        slot.capture = null;
                    }
                    computeTimer.drainCompleted(slotIndex);
                } else {
                    completed = false;
                }
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
        return completed;
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

    /** Raw Vulkan query pool shared by this runner's fence-aligned timing slots. */
    private static final class RawTimestampQueries implements ComputePassTimer.QueryResults {
        private final org.lwjgl.vulkan.VkDevice device;
        private final int validBits;
        private long pool;

        private RawTimestampQueries(org.lwjgl.vulkan.VkDevice device, long pool, int validBits) {
            this.device = device;
            this.pool = pool;
            this.validBits = validBits;
        }

        @Nullable
        static RawTimestampQueries tryCreate(VulkanComputeBackend backend, String passName) {
            float periodNs = backend.device().getDeviceInfo().timestampPeriod();
            if (periodNs <= 0f) {
                FornaxMod.LOGGER.warn("[Fornax] Compute timestamps unsupported; '{}' will run untimed", passName);
                return null;
            }
            try {
                int validBits = resolveTimestampValidBits(backend);
                if (validBits == 0) {
                    FornaxMod.LOGGER.warn("[Fornax] Compute queue timestamps unsupported; '{}' will run untimed",
                            passName);
                    return null;
                }
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                            .sType$Default()
                            .queryType(VK13.VK_QUERY_TYPE_TIMESTAMP)
                            .queryCount(FRAMES_IN_FLIGHT * 2);
                    LongBuffer out = stack.mallocLong(1);
                    int result = VK13.vkCreateQueryPool(backend.device().vkDevice(), info, null, out);
                    if (result != VK13.VK_SUCCESS) {
                        FornaxMod.LOGGER.warn(
                                "[Fornax] vkCreateQueryPool returned VkResult {}; '{}' will run untimed",
                                result, passName);
                        return null;
                    }
                    return new RawTimestampQueries(backend.device().vkDevice(), out.get(0), validBits);
                }
            } catch (RuntimeException e) {
                FornaxMod.LOGGER.warn("[Fornax] Compute timestamp allocation failed; '{}' will run untimed: {}",
                        passName, e.toString());
                return null;
            }
        }

        long pool() {
            return pool;
        }

        int validBits() {
            return validBits;
        }

        /** Resolves timestamp support for the exact queue family this runner submits against. */
        private static int resolveTimestampValidBits(VulkanComputeBackend backend) {
            int familyIndex = backend.computeQueue().queueFamilyIndex();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer count = stack.mallocInt(1);
                VK13.vkGetPhysicalDeviceQueueFamilyProperties(
                        backend.device().vkDevice().getPhysicalDevice(), count, null);
                int familyCount = count.get(0);
                if (familyIndex < 0 || familyIndex >= familyCount) {
                    throw new IllegalStateException("compute queue family index " + familyIndex
                            + " outside physical-device family count " + familyCount);
                }
                VkQueueFamilyProperties.Buffer properties =
                        VkQueueFamilyProperties.calloc(familyCount, stack);
                VK13.vkGetPhysicalDeviceQueueFamilyProperties(
                        backend.device().vkDevice().getPhysicalDevice(), count, properties);
                return properties.get(familyIndex).timestampValidBits();
            }
        }

        @Override
        public OptionalLong tryRead(int queryIndex) {
            if (pool == 0) {
                return OptionalLong.empty();
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer value = stack.mallocLong(1);
                int result = VK13.vkGetQueryPoolResults(device, pool, queryIndex, 1, value,
                        Long.BYTES, VK13.VK_QUERY_RESULT_64_BIT);
                return result == VK13.VK_SUCCESS
                        ? OptionalLong.of(value.get(0))
                        : OptionalLong.empty();
            }
        }

        @Override
        public void close() {
            if (pool != 0) {
                VK13.vkDestroyQueryPool(device, pool, null);
                pool = 0;
            }
        }
    }
}
