package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.mixin.vulkan.RenderPassBackendAccessor;
import dev.icehunter.fornax.mixin.vulkan.VulkanRenderPassCommandBufferAccessor;
import dev.icehunter.fornax.pack.ParticleSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.layout.PackOptionsBuffer;
import dev.icehunter.fornax.pack.layout.RuntimeShaderPack;
import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import dev.icehunter.fornax.pass.particle.ParticlePipelineBuilder;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import dev.icehunter.fornax.pipeline.FramePacing;
import dev.icehunter.fornax.pipeline.GBuffer;
import dev.icehunter.fornax.pipeline.GBufferManager;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * A pack-issued instanced draw: {@code vkCmdDraw(6, instances, 0, 0)} of a billboard quad whose
 * per-instance data the pack's own vertex shader reads out of a storage buffer the pack's own
 * compute pass wrote. This is the one capability the graph had no way to express -- {@code GEOMETRY}
 * passes only substitute a program into a draw vanilla was already making, and {@code FULLSCREEN}
 * passes draw a fixed screen triangle -- and it is what a GPU precipitation system is built on.
 *
 * <p><b>Modelled on {@link ComputePassRunner}, not {@link FullscreenPassRunner}.</b> The pipeline is
 * raw Vulkan ({@link ParticlePipelineBuilder}), with its own descriptor set layout, its own pool, and
 * {@code VK_DESCRIPTOR_TYPE_STORAGE_BUFFER} bindings -- for the reason ComputePassRunner went raw in
 * the first place, applied to a stage it had never been applied to here: Blaze3D's {@code
 * BindGroupLayout}/{@code UniformType} vocabulary has no storage buffer at all (its only
 * buffer-shaped entries are {@code UNIFORM_BUFFER} and {@code TEXEL_BUFFER} -- see
 * FullscreenPassRunner's own note on that limit), so an SSBO read in a VERTEX shader is not
 * expressible through it. Dropping to raw Vulkan means never having to find out whether it could be
 * made to.
 *
 * <p><b>The render pass itself is still Blaze3D's.</b> {@link #runFrame} opens an ordinary {@code
 * CommandEncoder.createRenderPass} on the output target + the G-buffer depth, then reaches the
 * underlying {@code VkCommandBuffer} through two accessor mixins (see
 * {@link RenderPassBackendAccessor}) and records the bind/push/draw itself. Blaze3D therefore still
 * owns the image layout transitions, the {@code vkCmdBeginRenderingKHR}/{@code End} pair, and the
 * initial viewport/scissor -- none of which this class could reimplement without also reimplementing
 * Blaze3D's private per-texture layout tracking. Only the pipeline had to be raw; the pass scope did
 * not.
 *
 * <p><b>Descriptor bindings</b> are the pass's declared {@code inputs}, in order, at bindings
 * {@code 0..N-1} (see {@link #bindingOrder}) -- outputs are attachments here, not descriptors, which
 * is the one structural difference from {@code ComputePassRunner.combinedBindingOrder}. Two reserved
 * input names bind engine resources rather than {@link TargetRegistry} entries:
 * {@link ComputePassRunner#PACK_OPTIONS_INPUT} (the live {@code u_PackOptions} block, exactly as on a
 * compute pass) and {@link #GLOBALS_INPUT} (Sodium's {@code u_Globals} block -- the projection and
 * model-view matrices, without which a billboard cannot be placed on screen at all).
 */
public final class ParticlePassRunner implements AutoCloseable {
    /** Reserved engine-recognized input name: a particles OR compute pass declaring this input binds
     * the live {@code u_Globals} uniform block (Sodium's own per-frame camera/fog buffer, carried as
     * a {@code GpuBufferSlice} whose OFFSET is load-bearing -- see {@code ChunkRenderContextHolder})
     * at that binding slot.
     *
     * <p>It lives in this class because a particles pass is what it was introduced for, but it is
     * shared with {@link ComputePassRunner} (which reciprocally owns {@code packOptions}, used by
     * both) -- a compute pass reads it for the per-frame WORLD state rather than the camera
     * matrices: the wind clock, frame counter, rain/thunder/wetness, weather anchor and true sun
     * direction that {@code PassParams}' name-keyed scalars cannot give a pack-authored pass. See
     * {@code ComputePassRunner.descriptorTypeFor}.
     *
     * <p>It has to be a reserved NAME rather than an implicit trailing binding because the pack's
     * vertex shader hardcodes {@code layout(std140, binding = N)} for it, and the only place that
     * {@code N} is visible to a pack author is the position they wrote the name at in {@code inputs}.
     *
     * <p>A shader may declare a PREFIX of the block rather than all 720 bytes -- e.g. just {@code mat4
     * u_ProjectionMatrix; mat4 u_ModelViewMatrix;} -- which is legal for the same reason the engine's
     * own {@code globals.glsl} documents: a uniform block only has to be as large as what it declares,
     * and binding a larger backing buffer is valid. That is deliberately the recommended shape here:
     * a particles shader that restates the whole layout would be a second copy of a byte-exact
     * contract that {@code GlobalsLayoutContractTest} only enforces on the engine's own two sides. */
    public static final String GLOBALS_INPUT = "globals";

    /**
     * How many descriptor sets are rotated through. There is no fence on this path -- the draw is
     * recorded into Blaze3D's own graphics command buffer and submitted by Blaze3D, so this class
     * never learns when it retired -- and a {@code vkUpdateDescriptorSets} against a set still
     * referenced by in-flight work is undefined behavior. Rotating a ring at least as deep as the
     * engine's maximum frame latency is the same fence-free guarantee {@code MappableRingBuffer}
     * already gives every fullscreen pass's {@code u_PassParams}; {@link FramePacing} exists so that
     * depth is stated once instead of guessed per subsystem.
     */
    private static final int RING_DEPTH = FramePacing.FRAMES_IN_FLIGHT;

    private final PassSpec spec;
    private final ParticleSpec particles;
    private final VulkanComputeBackend backend;
    private final ParticlePipelineBuilder.CompiledParticlePipeline pipeline;
    private final List<String> bindingOrder;
    /** The {@code VkDescriptorType} of each binding, positionally aligned with {@link #bindingOrder}. */
    private final List<Integer> descriptorTypes;
    private long descriptorPool;
    private final long[] descriptorSets = new long[RING_DEPTH];
    private long frameIndex;

    /** Latched on the first failure, exactly like {@code FullscreenPassRunner.invalid}: a pass that
     * cannot resolve its attachments or bind its pipeline once will fail the same way every frame,
     * and a per-frame exception out of {@code GraphRunner.finish()} kills the whole frame rather than
     * one pass. Cleared only by building a new runner. */
    private boolean invalid;

    private ParticlePassRunner(PassSpec spec, ParticleSpec particles, VulkanComputeBackend backend,
                               ParticlePipelineBuilder.CompiledParticlePipeline pipeline,
                               List<String> bindingOrder, List<Integer> descriptorTypes) {
        this.spec = spec;
        this.particles = particles;
        this.backend = backend;
        this.pipeline = pipeline;
        this.bindingOrder = bindingOrder;
        this.descriptorTypes = descriptorTypes;
    }

    /**
     * The positional binding order a particles pass's descriptor set is built from: its declared
     * inputs, in order. Binding N = the Nth input.
     *
     * <p>Unlike {@code ComputePassRunner.combinedBindingOrder}, outputs are NOT appended: a particles
     * pass writes through the graphics pipeline's color attachment, not a descriptor, so an output
     * has no binding to occupy. Appending them anyway would silently shift every pack-authored
     * {@code layout(binding = N)} by the number of outputs.
     *
     * <p>Pure function of the pass spec -- no GPU or registry access -- so it is directly
     * unit-testable, which is the point: this is the number a pack hardcodes in GLSL, and nothing
     * else in the system would notice if it moved.
     */
    public static List<String> bindingOrder(PassSpec spec) {
        return List.copyOf(spec.inputs());
    }

    public static ParticlePassRunner build(PassSpec spec, VulkanComputeBackend backend, TargetRegistry registry) {
        ParticleSpec particles = spec.particles();
        if (particles == null) {
            throw new IllegalStateException("Fornax graph: particles pass '" + spec.name()
                    + "' carries no ParticleSpec -- PackTomlLoader must attach one to every PARTICLES pass");
        }
        String fragmentPath = spec.shader();
        if (fragmentPath == null) {
            throw new IllegalStateException("Fornax graph: particles pass '" + spec.name()
                    + "' declares no fragment shader");
        }
        String vertexSource = requireSource(spec, particles.vertexShader(), "vertex");
        String fragmentSource = requireSource(spec, fragmentPath, "fragment");

        List<String> bindingOrder = bindingOrder(spec);
        List<Integer> descriptorTypes = new ArrayList<>(bindingOrder.size());
        for (String name : bindingOrder) {
            descriptorTypes.add(descriptorTypeFor(spec, name, registry));
        }

        // The color attachment's format has to be baked into the pipeline (VkPipelineRenderingCreateInfo)
        // and must equal the view the render pass is opened on. GraphValidator already refused
        // builtin.output and any non-texture output for a particles pass, so this is always a real
        // registry texture target; a null here means the registry has not allocated it yet, which
        // ensureRunnersBuilt treats as "retry next frame" rather than a pack error.
        TargetInstance output = registry.get(spec.outputs().get(0));
        if (output == null) {
            throw new IllegalStateException("Fornax graph: particles pass '" + spec.name()
                    + "' output target '" + spec.outputs().get(0) + "' is not allocated");
        }
        int colorFormat = VulkanConst.toVk(TargetRegistry.gpuFormat(output.format()));
        int depthFormat = VulkanConst.toVk(GBufferManager.DEPTH_FORMAT);

        ByteBuffer vertexSpirv = ComputeShaderCompiler.compileToSpirv(vertexSource, particles.vertexShader(),
                Shaderc.shaderc_glsl_vertex_shader);
        try {
            ByteBuffer fragmentSpirv = ComputeShaderCompiler.compileToSpirv(fragmentSource, fragmentPath,
                    Shaderc.shaderc_glsl_fragment_shader);
            try {
                var compiled = ParticlePipelineBuilder.build(backend.device(), vertexSpirv, fragmentSpirv,
                        descriptorTypes, colorFormat, depthFormat);
                ParticlePassRunner runner = new ParticlePassRunner(spec, particles, backend, compiled,
                        bindingOrder, descriptorTypes);
                try {
                    runner.allocateDescriptorSets();
                } catch (RuntimeException e) {
                    runner.close();
                    throw e;
                }
                return runner;
            } finally {
                MemoryUtil.memFree(fragmentSpirv);
            }
        } finally {
            MemoryUtil.memFree(vertexSpirv);
        }
    }

    private static String requireSource(PassSpec spec, String path, String stage) {
        String source = RuntimeShaderPack.getInstance().sourceOrNull(path);
        if (source == null) {
            throw new IllegalStateException("Fornax graph: particles pass '" + spec.name() + "' names "
                    + stage + " shader '" + path + "' with no composed source");
        }
        return source;
    }

    /**
     * Classifies one declared input to a {@code VkDescriptorType}. Same three-way split
     * {@code ComputePassRunner.build} makes, for the same reason (the pool has to be sized by type
     * before any frame runs), with {@link #GLOBALS_INPUT} added as a second reserved uniform-buffer
     * name alongside {@code packOptions}.
     */
    private static int descriptorTypeFor(PassSpec spec, String name, TargetRegistry registry) {
        if (name.equals(ComputePassRunner.PACK_OPTIONS_INPUT) || name.equals(GLOBALS_INPUT)) {
            return VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        }
        if (GraphValidator.BUILTINS.contains(name) || ShadowMapManager.isShadowMapRef(name)) {
            return VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        }
        if (registry.getBuffer(name) != null) {
            return VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        }
        if (registry.get(name) != null) {
            return VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        }
        PackTextureRegistry packTextures = GraphRunner.packTextureRegistry();
        if (packTextures != null && packTextures.isDeclared(name)) {
            return VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        }
        throw new IllegalStateException("Fornax graph: particles pass '" + spec.name()
                + "' references input '" + name + "' which is neither an allocated buffer nor texture target");
    }

    /** One descriptor set per ring slot from a dedicated pool, re-pointed at the live handles every
     * run -- the same allocate-once/update-per-use shape {@code ComputePassRunner} uses. */
    private void allocateDescriptorSets() {
        if (descriptorTypes.isEmpty()) {
            // A pool with poolSizeCount 0 is not portably valid, and an inputless pass has nothing to
            // bind anyway -- the pipeline layout still carries its (legal) zero-binding set layout,
            // and updateAndBindDescriptorSet short-circuits on the same condition.
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var device = backend.device();
            // A VkDescriptorPoolSize with descriptorCount 0 is invalid (VUID-VkDescriptorPoolSize-
            // descriptorCount-00302), so only the types actually present get a size entry.
            List<int[]> sizes = new ArrayList<>(3); // {type, count}
            for (int type : new int[]{VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER}) {
                int count = (int) descriptorTypes.stream().filter(t -> t == type).count();
                if (count > 0) {
                    sizes.add(new int[]{type, count * RING_DEPTH});
                }
            }
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(sizes.size(), stack);
            for (int i = 0; i < sizes.size(); i++) {
                poolSizes.get(i).type(sizes.get(i)[0]).descriptorCount(sizes.get(i)[1]);
            }
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(RING_DEPTH).pPoolSizes(poolSizes);
            LongBuffer poolOut = stack.mallocLong(1);
            checkVk(VK13.vkCreateDescriptorPool(device.vkDevice(), poolInfo, null, poolOut), "vkCreateDescriptorPool");
            descriptorPool = poolOut.get(0);

            LongBuffer layouts = stack.mallocLong(RING_DEPTH);
            for (int i = 0; i < RING_DEPTH; i++) {
                layouts.put(i, pipeline.descriptorSetLayout());
            }
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descriptorPool).pSetLayouts(layouts);
            LongBuffer setsOut = stack.mallocLong(RING_DEPTH);
            checkVk(VK13.vkAllocateDescriptorSets(device.vkDevice(), allocInfo, setsOut), "vkAllocateDescriptorSets");
            for (int i = 0; i < RING_DEPTH; i++) {
                descriptorSets[i] = setsOut.get(i);
            }
        }
    }

    /**
     * Draws this pass's {@code instances} billboards. A pass with a zero-input binding list, a
     * missing G-buffer, or an unresolvable output simply does not draw this frame.
     *
     * <p>{@code globals} is Sodium's live per-frame uniform slice and may be null before the first
     * terrain draw of a session; a pass that declared {@link #GLOBALS_INPUT} cannot bind anything
     * meaningful then, so it skips the frame rather than binding a stale or absent buffer.
     */
    public void run(TargetRegistry registry, Map<String, MipchainRunner> mipchainTargets,
                    @Nullable GpuBufferSlice globals, @Nullable PackOptionsBuffer options, PassParams params) {
        if (invalid) {
            return;
        }
        GBuffer gbuffer = GBufferManager.getInstance();
        if (gbuffer == null) {
            return; // no depth attachment to test against yet -- retry next frame, not a failure
        }
        if (globals == null && bindingOrder.contains(GLOBALS_INPUT)) {
            return;
        }
        try {
            runFrame(registry, mipchainTargets, gbuffer, globals, options, params);
        } catch (RuntimeException e) {
            // Same permanent-until-rebuild degradation FullscreenPassRunner.run documents: every
            // failure reachable here (an input target that vanished, a bind-group/shader mismatch)
            // reproduces identically next frame, so retrying only turns one broken pass into a
            // broken frame.
            invalid = true;
            FornaxMod.LOGGER.error("[Fornax] ParticlePassRunner: pass '{}' failed to draw -- skipping it for "
                    + "the rest of this runner's lifetime; the next successful rebuild retries it", spec.name(), e);
        }
    }

    private void runFrame(TargetRegistry registry, Map<String, MipchainRunner> mipchainTargets, GBuffer gbuffer,
                          @Nullable GpuBufferSlice globals, @Nullable PackOptionsBuffer options, PassParams params) {
        GpuTextureView outputView = GraphInputResolver.resolveView(spec.outputs().get(0), registry, mipchainTargets);
        int slotIndex = (int) (frameIndex % RING_DEPTH);
        long descriptorSet = descriptorSets[slotIndex];
        frameIndex++;

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        // Optional.empty()/OptionalDouble.empty() = LOAD, not CLEAR, on both attachments. Particles
        // composite over whatever the output target already holds and test against the opaque depth
        // the terrain draw left there; clearing either would erase the scene they are supposed to
        // sit in front of.
        try (RenderPass pass = encoder.createRenderPass(() -> "Fornax " + spec.name(),
                outputView, Optional.empty(), gbuffer.getDepthView(), OptionalDouble.empty())) {
            VkCommandBuffer cmd = commandBufferOf(pass);
            VK13.vkCmdBindPipeline(cmd, VK13.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline());
            updateAndBindDescriptorSet(registry, mipchainTargets, cmd, descriptorSet, globals, options);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                // Byte-identical to the block ComputePassRunner pushes, so a pack author reads one
                // PassParams layout across both raw-Vulkan pass types: vec2 texel size at 0, two
                // scalars at 8/12, vec3 sun direction at 16.
                ByteBuffer push = stack.malloc(PassParams.PUSH_CONSTANT_BASE_SIZE);
                push.putFloat(0, params.texelSizeX());
                push.putFloat(4, params.texelSizeY());
                push.putFloat(8, params.param2());
                push.putFloat(12, params.param3());
                push.putFloat(16, params.sunDirX());
                push.putFloat(20, params.sunDirY());
                push.putFloat(24, params.sunDirZ());
                VK13.vkCmdPushConstants(cmd, pipeline.pipelineLayout(),
                        VK13.VK_SHADER_STAGE_VERTEX_BIT | VK13.VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);
            }

            VK13.vkCmdDraw(cmd, ParticlePipelineBuilder.QUAD_VERTEX_COUNT, particles.instances(), 0, 0);
        }
    }

    /** Unwraps Blaze3D's {@code RenderPass} to the {@code VkCommandBuffer} it is recording into --
     * see {@link RenderPassBackendAccessor} for why this seam exists at all. Only ever reached with
     * the Vulkan backend active: a {@code ParticlePassRunner} is only built when {@code
     * VulkanComputeBackend.tryCreate()} succeeded, which is itself the backend-is-Vulkan check. */
    private static VkCommandBuffer commandBufferOf(RenderPass pass) {
        RenderPassBackend backend = ((RenderPassBackendAccessor) pass).fornax$backend();
        return ((VulkanRenderPassCommandBufferAccessor) backend).fornax$commandBuffer();
    }

    /** Re-points this frame's descriptor set at the CURRENT resolved handles (a target can be
     * reallocated between frames -- a resize, a pack rebuild -- so handles must be re-read every call)
     * and binds it. */
    private void updateAndBindDescriptorSet(TargetRegistry registry, Map<String, MipchainRunner> mipchainTargets,
                                            VkCommandBuffer cmd, long descriptorSet,
                                            @Nullable GpuBufferSlice globals, @Nullable PackOptionsBuffer options) {
        if (bindingOrder.isEmpty()) {
            return; // nothing to bind; the pipeline layout has an empty set 0
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(bindingOrder.size(), stack);
            for (int i = 0; i < bindingOrder.size(); i++) {
                String name = bindingOrder.get(i);
                int type = descriptorTypes.get(i);
                VkWriteDescriptorSet write = writes.get(i)
                        .sType$Default().dstSet(descriptorSet).dstBinding(i).descriptorCount(1);
                if (type == VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) {
                    write.descriptorType(VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                            .pBufferInfo(uniformBufferInfo(stack, name, globals, options));
                } else if (type == VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER) {
                    BufferInstance buf = registry.getBuffer(name);
                    if (buf == null) {
                        throw new IllegalStateException("Fornax graph: particles pass '" + spec.name()
                                + "' storage-buffer input '" + name + "' is not allocated");
                    }
                    VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                            .buffer(buf.vkBuffer()).offset(0).range(VK13.VK_WHOLE_SIZE);
                    write.descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(bufferInfo);
                } else {
                    GpuTextureView view = GraphInputResolver.resolveView(name, registry, mipchainTargets);
                    long imageView = ((VulkanGpuTextureView) view).vkImageView();
                    VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                            .sampler(samplerFor(name).vkSampler()).imageView(imageView)
                            .imageLayout(VK13.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    write.descriptorType(VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(imageInfo);
                }
            }
            VK13.vkUpdateDescriptorSets(backend.device().vkDevice(), writes, null);
            VK13.vkCmdBindDescriptorSets(cmd, VK13.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipelineLayout(),
                    0, new long[]{descriptorSet}, null);
        }
    }

    private VkDescriptorBufferInfo.Buffer uniformBufferInfo(MemoryStack stack, String name,
                                                            @Nullable GpuBufferSlice globals,
                                                            @Nullable PackOptionsBuffer options) {
        if (name.equals(GLOBALS_INPUT)) {
            if (globals == null) {
                throw new IllegalStateException("Fornax graph: particles pass '" + spec.name()
                        + "' binds reserved '" + GLOBALS_INPUT + "' but no u_Globals slice is live");
            }
            // offset + length, never the whole buffer at 0: Sodium's uniform buffer is a ring and
            // this frame's data lives mid-buffer (see ChunkRenderContextHolder's own note).
            return VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(((VulkanGpuBuffer) globals.buffer()).vkBuffer())
                    .offset(globals.offset()).range(globals.length());
        }
        if (options == null) {
            throw new IllegalStateException("Fornax graph: particles pass '" + spec.name()
                    + "' binds reserved '" + ComputePassRunner.PACK_OPTIONS_INPUT
                    + "' but no PackOptionsBuffer is active");
        }
        return VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(((VulkanGpuBuffer) options.currentBuffer()).vkBuffer())
                .offset(0).range(VK13.VK_WHOLE_SIZE);
    }

    /** LINEAR + REPEAT for the two tileable-image input kinds that already carry that contract
     * elsewhere in the graph (the engine noise texture and any pack-declared {@code [textures.*]}
     * asset -- see FullscreenPassRunner's identical special case), NEAREST + CLAMP_TO_EDGE for
     * everything else. A flake sprite is a pack texture, so it lands in the filtered branch without
     * needing a per-input filter syntax in graph.toml that nothing else would use. */
    private static VulkanGpuSampler samplerFor(String name) {
        PackTextureRegistry packTextures = GraphRunner.packTextureRegistry();
        boolean tileable = name.equals("builtin.noise") || (packTextures != null && packTextures.isDeclared(name));
        return (VulkanGpuSampler) (tileable
                ? RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR)
                : RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    }

    /** Destroys the descriptor pool (which implicitly frees its sets) and every pipeline handle.
     * Called from {@code GraphRunner.closeCurrent()}, which has already issued the device-wide
     * wait-idle every raw-Vulkan teardown in this codebase depends on. */
    @Override
    public void close() {
        if (descriptorPool != 0) {
            VK13.vkDestroyDescriptorPool(backend.device().vkDevice(), descriptorPool, null);
            descriptorPool = 0;
        }
        ParticlePipelineBuilder.destroy(backend.device(), pipeline.pipeline(), pipeline.pipelineLayout(),
                pipeline.descriptorSetLayout(), pipeline.vertexModule(), pipeline.fragmentModule());
    }

    private static void checkVk(int result, String call) {
        if (result != VK13.VK_SUCCESS) {
            throw new IllegalStateException(call + " failed with VkResult " + result);
        }
    }
}
