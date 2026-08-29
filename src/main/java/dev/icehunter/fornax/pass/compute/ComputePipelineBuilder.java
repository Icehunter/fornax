package dev.icehunter.fornax.pass.compute;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.icehunter.fornax.pack.graph.ExtraPushConstants;
import dev.icehunter.fornax.pack.graph.PassParams;
import dev.icehunter.fornax.pipeline.PersistentPipelineCache;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds one compute pipeline (shader module, descriptor set layout, pipeline layout, pipeline
 * object) from SPIR-V bytecode -- the compute-side analog of {@code FullscreenPassRunner.build}'s
 * {@code RenderPipeline.builder()} chain, at the raw-Vulkan layer since Blaze3D has no compute
 * pipeline abstraction to build against.
 */
public final class ComputePipelineBuilder {
    private ComputePipelineBuilder() {
    }

    @FunctionalInterface
    interface PipelineCreateCall {
        int create(long pipelineCache);
    }

    static int createWithCacheFallback(long pipelineCache, PipelineCreateCall create) {
        int result = create.create(pipelineCache);
        if (result != VK13.VK_SUCCESS && pipelineCache != VK13.VK_NULL_HANDLE) {
            return create.create(VK13.VK_NULL_HANDLE);
        }
        return result;
    }

    public record CompiledComputePipeline(long pipeline, long pipelineLayout, long descriptorSetLayout,
                                           long shaderModule) {
    }

    /** {@code storageBufferCount} bindings, all {@code VK_DESCRIPTOR_TYPE_STORAGE_BUFFER} at
     * sequential bindings 0..N-1 -- every compute pass this backend runs binds only storage
     * buffers in this milestone (no images yet; see this plan's Global Constraints on why). */
    public static CompiledComputePipeline build(VulkanDevice device, ByteBuffer spirv, int storageBufferCount) {
        List<Integer> allBuffers = new ArrayList<>(storageBufferCount);
        for (int i = 0; i < storageBufferCount; i++) {
            allBuffers.add(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
        }
        return buildWithDescriptorLayout(device, spirv, allBuffers, PassParams.PUSH_CONSTANT_BASE_SIZE);
    }

    /**
     * Builds a pipeline whose descriptor set layout has one binding per {@code descriptorTypes} entry,
     * at sequential bindings {@code 0..N-1}: binding {@code i} carries the {@code VkDescriptorType}
     * {@code descriptorTypes.get(i)}. A compute pass reads a mix of registry buffers
     * ({@code STORAGE_BUFFER}), texture targets ({@code COMBINED_IMAGE_SAMPLER}) and the reserved
     * {@code u_PackOptions} block ({@code UNIFORM_BUFFER}), driven by its declared inputs/outputs (see
     * {@code ComputePassRunner.combinedBindingOrder}).
     *
     * <p>The pipeline layout also reserves a {@code pushConstantBytes}-byte compute-stage push constant
     * range -- {@link #build}'s original form deliberately omitted push constants, but every compute
     * pass this runner drives receives at least {@code PassParams} (texel size + sun direction) as a
     * push constant, exactly like {@code VoxelDebugRaymarchPass} reserves its own camera-block range.
     * {@code pushConstantBytes} is always {@code PassParams.PUSH_CONSTANT_BASE_SIZE} plus however many
     * extra bytes ({@link ExtraPushConstants}) the specific pass being built needs -- callers with no
     * extra data pass {@code PassParams.PUSH_CONSTANT_BASE_SIZE} unchanged. Deliberately NOT {@code
     * PassParams.BUFFER_SIZE} -- see that constant's sibling doc comment for why the compute
     * push-constant contract is pinned independently of the (larger) uniform-buffer path.
     */
    public static CompiledComputePipeline buildWithDescriptorLayout(VulkanDevice device, ByteBuffer spirv,
                                                                    List<Integer> descriptorTypes,
                                                                    int pushConstantBytes) {
        // Handles hoisted out of the try so the catch can free whatever was created before the
        // failure, the same shape ParticlePipelineBuilder.build uses: GraphRunner.ensureRunnersBuilt
        // retries a failed compute-pass build every frame, so a mid-build throw that leaked a handle
        // would leak it again every retry for as long as the pack stays broken.
        long shaderModule = VK13.VK_NULL_HANDLE;
        long descriptorSetLayout = VK13.VK_NULL_HANDLE;
        long pipelineLayout = VK13.VK_NULL_HANDLE;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(spirv);
            LongBuffer moduleOut = stack.mallocLong(1);
            checkVk(VK13.vkCreateShaderModule(device.vkDevice(), moduleInfo, null, moduleOut), "vkCreateShaderModule");
            shaderModule = moduleOut.get(0);

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(descriptorTypes.size(), stack);
            for (int i = 0; i < descriptorTypes.size(); i++) {
                bindings.get(i)
                        .binding(i)
                        .descriptorType(descriptorTypes.get(i))
                        .descriptorCount(1)
                        .stageFlags(VK13.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(bindings);
            LongBuffer setLayoutOut = stack.mallocLong(1);
            checkVk(VK13.vkCreateDescriptorSetLayout(device.vkDevice(), layoutInfo, null, setLayoutOut),
                    "vkCreateDescriptorSetLayout");
            descriptorSetLayout = setLayoutOut.get(0);

            // PassParams (32-byte std140: vec2 texel + 2 scalars + vec3 sun dir) rides as a push constant
            // because a compute pass has no reserved uniform-buffer slot for it the way FULLSCREEN passes do.
            // pushConstantBytes is PassParams.PUSH_CONSTANT_BASE_SIZE plus whatever extra bytes
            // (ExtraPushConstants) this specific pass needs appended after it -- see this method's own javadoc.
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK13.VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(pushConstantBytes);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushRange);
            LongBuffer pipelineLayoutOut = stack.mallocLong(1);
            checkVk(VK13.vkCreatePipelineLayout(device.vkDevice(), pipelineLayoutInfo, null, pipelineLayoutOut),
                    "vkCreatePipelineLayout");
            pipelineLayout = pipelineLayoutOut.get(0);

            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(VK13.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo pipelineInfo = VkComputePipelineCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(stageInfo)
                    .layout(pipelineLayout);
            LongBuffer pipelineOut = stack.mallocLong(1);
            int pipelineResult = createWithCacheFallback(PersistentPipelineCache.handle(), cache ->
                    VK13.vkCreateComputePipelines(device.vkDevice(), cache,
                            VkComputePipelineCreateInfo.create(pipelineInfo.address(), 1), null, pipelineOut));
            checkVk(pipelineResult, "vkCreateComputePipelines");

            return new CompiledComputePipeline(pipelineOut.get(0), pipelineLayout, descriptorSetLayout, shaderModule);
        } catch (RuntimeException e) {
            destroy(device, VK13.VK_NULL_HANDLE, pipelineLayout, descriptorSetLayout, shaderModule);
            throw e;
        }
    }

    /** Destroys every handle in a {@link CompiledComputePipeline} (or the partial set a failed
     * {@link #buildWithDescriptorLayout} produced). Null handles are skipped, so this is safe at
     * any point mid-build. */
    public static void destroy(VulkanDevice device, long pipeline, long pipelineLayout, long descriptorSetLayout,
                               long shaderModule) {
        var vkDevice = device.vkDevice();
        if (pipeline != VK13.VK_NULL_HANDLE) {
            VK13.vkDestroyPipeline(vkDevice, pipeline, null);
        }
        if (pipelineLayout != VK13.VK_NULL_HANDLE) {
            VK13.vkDestroyPipelineLayout(vkDevice, pipelineLayout, null);
        }
        if (descriptorSetLayout != VK13.VK_NULL_HANDLE) {
            VK13.vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayout, null);
        }
        if (shaderModule != VK13.VK_NULL_HANDLE) {
            VK13.vkDestroyShaderModule(vkDevice, shaderModule, null);
        }
    }

    private static void checkVk(int result, String call) {
        if (result != VK13.VK_SUCCESS) {
            throw new IllegalStateException(call + " failed with VkResult " + result);
        }
    }
}
