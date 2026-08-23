package dev.icehunter.fornax.pass.particle;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.icehunter.fornax.pack.graph.PassParams;
import dev.icehunter.fornax.pipeline.PersistentPipelineCache;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

/**
 * Builds one raw-Vulkan GRAPHICS pipeline for a {@code particles} pass -- the graphics-stage sibling
 * of {@link dev.icehunter.fornax.pass.compute.ComputePipelineBuilder}, and for the same reason it
 * exists at all: Blaze3D's {@code RenderPipeline} abstraction has no way to express a storage-buffer
 * read, and a pack-issued instanced draw needs exactly that in the VERTEX stage (each instance reads
 * its own flake out of a buffer a compute pass wrote). Going raw here means never having to answer
 * whether {@code BindGroupLayout} could be made to carry an SSBO; it simply isn't involved.
 *
 * <p><b>Dynamic rendering, not a VkRenderPass object.</b> The pipeline is created with a {@link
 * VkPipelineRenderingCreateInfoKHR} chained onto {@code pNext} rather than a {@code renderPass}
 * handle, because that is what it has to be compatible with: Blaze3D's own {@code
 * VulkanCommandEncoder.createRenderPass} begins rendering with {@code vkCmdBeginRenderingKHR} and
 * {@code VkRenderingInfo}/{@code VkRenderingAttachmentInfo} (verified by disassembling
 * {@code VulkanCommandEncoder} from the deobf 26.2 jar -- those three symbols appear in its constant
 * pool and no {@code VkRenderPassBeginInfo} does). The KHR-suffixed struct, not the 1.3 core one:
 * Blaze3D's {@code VulkanInstance} requests {@code VK_API_VERSION_1_2} (same disassembly), so
 * dynamic rendering is present as the extension, and 1.3 core structures are not guaranteed to be
 * recognized. The two are byte- and value-identical, so this is purely about not claiming an API
 * version the instance never asked for.
 *
 * <p><b>Fixed state, deliberately not pack-configurable.</b> Premultiplied-alpha blending
 * ({@code ONE} / {@code ONE_MINUS_SRC_ALPHA}), depth test ON, depth write OFF, no culling. This is
 * the only correct state for a cloud of unsorted, world-up-locked billboards: they must occlude
 * against the opaque scene (test on) but never against each other in draw order, which is arbitrary
 * (write off), and premultiplied alpha is order-independent for the emission-like accumulation a
 * flake sheet actually is, where straight alpha is not. The compare op is {@code GREATER_OR_EQUAL}
 * because this engine's main camera is reversed-Z -- 1.0 is the NEAR plane (see
 * {@code DeferredGeometryPipelines}, which documents the same fact from the opposite direction: its
 * shadow pipeline has to opt back OUT to forward-Z {@code LESS_THAN_OR_EQUAL} for a zero-to-one
 * ortho light projection).
 *
 * <p><b>No vertex input state.</b> {@code pVertexInputState} declares zero bindings and zero
 * attributes: the quad's six corners come from {@code gl_VertexIndex} and everything per-flake comes
 * from the storage buffer indexed by {@code gl_InstanceIndex}. There is no vertex buffer to bind and
 * none is ever bound.
 */
public final class ParticlePipelineBuilder {
    /** Two triangles, no index buffer -- the fixed billboard the vertex stage builds from {@code
     * gl_VertexIndex}. Pinned here (rather than at the draw call) so the pipeline's topology and the
     * draw's vertex count can never drift apart. */
    public static final int QUAD_VERTEX_COUNT = 6;

    private ParticlePipelineBuilder() {
    }

    public record CompiledParticlePipeline(long pipeline, long pipelineLayout, long descriptorSetLayout,
                                            long vertexModule, long fragmentModule) {
    }

    /**
     * @param descriptorTypes one {@code VkDescriptorType} per binding, at sequential bindings
     *                        {@code 0..N-1} -- exactly the shape
     *                        {@code ComputePipelineBuilder.buildWithDescriptorLayout} uses, driven by
     *                        the pass's declared inputs. Every binding is made visible to BOTH the
     *                        vertex and fragment stages: {@code graph.toml} says which resources a
     *                        pass reads, never which stage reads them, and a descriptor a stage never
     *                        touches costs nothing, so narrowing this would mean inventing a syntax
     *                        to express a fact with no consequence.
     * @param colorFormat     the {@code VkFormat} of the single color attachment this pipeline renders
     *                        into -- must equal the format of the view the render pass is opened on.
     * @param depthFormat     the {@code VkFormat} of the depth attachment (the G-buffer depth).
     */
    public static CompiledParticlePipeline build(VulkanDevice device, ByteBuffer vertexSpirv, ByteBuffer fragmentSpirv,
                                                  List<Integer> descriptorTypes, int colorFormat, int depthFormat) {
        // Handles hoisted out of the try so the catch can free whatever was created before the
        // failure. This matters more here than on the compute path: GraphRunner.ensureRunnersBuilt
        // retries a failed runner build EVERY frame, so a mid-build throw that leaked (say) two
        // shader modules would leak them again 60 times a second for as long as the pack stays
        // broken, rather than once.
        long vertexModule = VK13.VK_NULL_HANDLE;
        long fragmentModule = VK13.VK_NULL_HANDLE;
        long descriptorSetLayoutHandle = VK13.VK_NULL_HANDLE;
        long pipelineLayoutHandle = VK13.VK_NULL_HANDLE;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vertexModule = createShaderModule(device, stack, vertexSpirv);
            fragmentModule = createShaderModule(device, stack, fragmentSpirv);

            int stageFlags = VK13.VK_SHADER_STAGE_VERTEX_BIT | VK13.VK_SHADER_STAGE_FRAGMENT_BIT;
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(descriptorTypes.size(), stack);
            for (int i = 0; i < descriptorTypes.size(); i++) {
                bindings.get(i)
                        .binding(i)
                        .descriptorType(descriptorTypes.get(i))
                        .descriptorCount(1)
                        .stageFlags(stageFlags);
            }
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(bindings);
            LongBuffer setLayoutOut = stack.mallocLong(1);
            checkVk(VK13.vkCreateDescriptorSetLayout(device.vkDevice(), layoutInfo, null, setLayoutOut),
                    "vkCreateDescriptorSetLayout");
            long descriptorSetLayout = setLayoutOut.get(0);
            descriptorSetLayoutHandle = descriptorSetLayout;

            // Same 32-byte PassParams push-constant block a compute pass gets, for the same reason:
            // there is no reserved uniform-buffer slot for it on this path either. Visible to both
            // stages so a billboard vertex shader can read the sun direction (flake lighting is
            // decided per-quad, not per-fragment) and the fragment stage can read texel size.
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(stageFlags)
                    .offset(0)
                    .size(PassParams.PUSH_CONSTANT_BASE_SIZE);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushRange);
            LongBuffer pipelineLayoutOut = stack.mallocLong(1);
            checkVk(VK13.vkCreatePipelineLayout(device.vkDevice(), pipelineLayoutInfo, null, pipelineLayoutOut),
                    "vkCreatePipelineLayout");
            long pipelineLayout = pipelineLayoutOut.get(0);
            pipelineLayoutHandle = pipelineLayout;

            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK13.VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertexModule).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK13.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragmentModule).pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType$Default(); // zero bindings, zero attributes -- see this class's doc

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .topology(VK13.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                    .primitiveRestartEnable(false);

            // Counts only: both are DYNAMIC (below), and the real values are already recorded into the
            // command buffer by Blaze3D's VulkanRenderPass constructor, which issues vkCmdSetViewport
            // (x=0, y=0, w=outputWidth, h=outputHeight, minDepth=0, maxDepth=1) and vkCmdSetScissor
            // before returning -- verified in the deobf 26.2 jar's disassembly of that constructor, not
            // assumed. Declaring them dynamic here is what makes this pipeline inherit that state
            // instead of baking its own.
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .viewportCount(1)
                    .scissorCount(1);

            VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(VK13.VK_POLYGON_MODE_FILL)
                    // A camera-facing billboard has no meaningful winding: the quad basis is built from
                    // the view direction, so the same triangle flips orientation as the camera passes
                    // it. Culling either face would blink half the storm out.
                    .cullMode(VK13.VK_CULL_MODE_NONE)
                    .frontFace(VK13.VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(false)
                    .lineWidth(1.0f);

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .rasterizationSamples(VK13.VK_SAMPLE_COUNT_1_BIT)
                    .sampleShadingEnable(false);

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthTestEnable(true)
                    .depthWriteEnable(false)
                    .depthCompareOp(VK13.VK_COMPARE_OP_GREATER_OR_EQUAL) // reversed-Z: 1.0 is NEAR
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(false);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack)
                            .blendEnable(true)
                            .srcColorBlendFactor(VK13.VK_BLEND_FACTOR_ONE)
                            .dstColorBlendFactor(VK13.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                            .colorBlendOp(VK13.VK_BLEND_OP_ADD)
                            .srcAlphaBlendFactor(VK13.VK_BLEND_FACTOR_ONE)
                            .dstAlphaBlendFactor(VK13.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                            .alphaBlendOp(VK13.VK_BLEND_OP_ADD)
                            .colorWriteMask(VK13.VK_COLOR_COMPONENT_R_BIT | VK13.VK_COLOR_COMPONENT_G_BIT
                                    | VK13.VK_COLOR_COMPONENT_B_BIT | VK13.VK_COLOR_COMPONENT_A_BIT);
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .logicOpEnable(false)
                    .pAttachments(blendAttachment);

            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pDynamicStates(stack.ints(VK13.VK_DYNAMIC_STATE_VIEWPORT, VK13.VK_DYNAMIC_STATE_SCISSOR));

            VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .colorAttachmentCount(1)
                    .pColorAttachmentFormats(stack.ints(colorFormat))
                    .depthAttachmentFormat(depthFormat);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .pNext(rendering.address())
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewport)
                    .pRasterizationState(rasterization)
                    .pMultisampleState(multisample)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlend)
                    .pDynamicState(dynamicState)
                    .layout(pipelineLayout)
                    .renderPass(VK13.VK_NULL_HANDLE) // dynamic rendering -- see this class's doc
                    .subpass(0);

            LongBuffer pipelineOut = stack.mallocLong(1);
            checkVk(VK13.vkCreateGraphicsPipelines(device.vkDevice(), PersistentPipelineCache.handle(), pipelineInfo,
                    null, pipelineOut), "vkCreateGraphicsPipelines");

            return new CompiledParticlePipeline(pipelineOut.get(0), pipelineLayout, descriptorSetLayout,
                    vertexModule, fragmentModule);
        } catch (RuntimeException e) {
            destroy(device, VK13.VK_NULL_HANDLE, pipelineLayoutHandle, descriptorSetLayoutHandle,
                    vertexModule, fragmentModule);
            throw e;
        }
    }

    /** Destroys every handle in a {@link CompiledParticlePipeline} (or the partial set a failed
     * {@link #build} produced). Null handles are skipped, so this is safe at any point mid-build. */
    public static void destroy(VulkanDevice device, long pipeline, long pipelineLayout, long descriptorSetLayout,
                               long vertexModule, long fragmentModule) {
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
        if (vertexModule != VK13.VK_NULL_HANDLE) {
            VK13.vkDestroyShaderModule(vkDevice, vertexModule, null);
        }
        if (fragmentModule != VK13.VK_NULL_HANDLE) {
            VK13.vkDestroyShaderModule(vkDevice, fragmentModule, null);
        }
    }

    private static long createShaderModule(VulkanDevice device, MemoryStack stack, ByteBuffer spirv) {
        VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType$Default()
                .pCode(spirv);
        LongBuffer out = stack.mallocLong(1);
        checkVk(VK13.vkCreateShaderModule(device.vkDevice(), moduleInfo, null, out), "vkCreateShaderModule");
        return out.get(0);
    }

    private static void checkVk(int result, String call) {
        if (result != VK13.VK_SUCCESS) {
            throw new IllegalStateException(call + " failed with VkResult " + result);
        }
    }
}
