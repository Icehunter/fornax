package dev.icehunter.fornax.mixin.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import dev.icehunter.fornax.pipeline.PersistentPipelineCache;
import dev.icehunter.fornax.pipeline.TerrainPushConstants;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.nio.LongBuffer;

/**
 * Declares the widened push-constant range on the Vulkan pipeline layouts terrain draws use.
 *
 * <p>A Vulkan pipeline layout must declare every byte of push-constant data pushed against it:
 * {@code vkCmdPushConstants} writes outside the layout's declared ranges are silently dropped by
 * the driver (or flagged only under validation layers). {@code DrawContextVKMixin} pushes
 * {@link TerrainPushConstants#BLOCK_SIZE} bytes per region -- official Sodium's own 20-byte block
 * ({@code u_RegionOffset}/{@code u_CurrentTime}/{@code u_RegionID}) plus {@code u_SunDirection}
 * at 32 and {@code u_PrevRegionOffset} at 48 -- so the layout has to declare the full range or
 * everything above byte 20 never reaches the shader, zeroing the previous-frame region offset
 * that motion vectors and the sun direction the resolve pass depend on.
 *
 * <p>Applied to both the {@code sodium} and {@code fornax} pipeline namespaces: the chunk
 * pipeline's own registry label keeps official Sodium's {@code sodium} namespace (only its shader
 * sources are retargeted to {@code fornax:blocks/terrain} -- see
 * {@code ShaderChunkRendererShaderLocationMixin}), while Fornax-created pipelines register under
 * {@code fornax}. Declaring a range larger than a pipeline's shaders consume is legal; pushing
 * past the declared range is not, which is why the over-declaration goes here rather than
 * trimming per pipeline.
 */
@Mixin(VulkanRenderPipeline.class)
public class VulkanRenderPipelineMixin {
    @WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkPipelineLayoutCreateInfo;pSetLayouts(Ljava/nio/LongBuffer;)Lorg/lwjgl/vulkan/VkPipelineLayoutCreateInfo;"))
    private static VkPipelineLayoutCreateInfo fornax$declarePushConstantRange(VkPipelineLayoutCreateInfo instance, LongBuffer value, Operation<VkPipelineLayoutCreateInfo> original, @Local RenderPipeline pipeline, @Local MemoryStack stack) {
        String namespace = pipeline.getLocation().getNamespace();
        if (namespace.contains("sodium") || namespace.contains("fornax")) {
            instance.pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                    .offset(0)
                    .size(TerrainPushConstants.BLOCK_SIZE)
                    .stageFlags(VK13.VK_SHADER_STAGE_ALL));
        }
        return original.call(instance, value);
    }

    /**
     * Substitutes {@link PersistentPipelineCache#handle()} for the hardcoded {@code
     * VK_NULL_HANDLE} Blaze3D passes as {@code pipelineCache} to both {@code
     * vkCreateGraphicsPipelines} call sites inside {@code compile} (javap-confirmed: bytecode
     * offsets 1075 and 1152, identical descriptor, {@code index = 1} is the {@code long
     * pipelineCache} argument at both). No ordinal restriction, so this applies to both call sites
     * with one method.
     *
     * <p>This is the highest-value wiring point for the persistent pipeline cache: EVERY
     * fullscreen/mipchain/temporal/terrain/geometry pipeline in this mod compiles through here, and
     * none of them are reachable any other way -- {@code GpuDevice}/{@code RenderPipeline} expose no
     * pipeline-cache parameter of their own. It changes nothing about correctness: a real {@code
     * VkPipelineCache} handle behaves identically to {@code VK_NULL_HANDLE} except that the driver
     * may return an already-compiled result for identical SPIR-V + pipeline state, which is exactly
     * the point.
     */
    @ModifyArg(method = "compile", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"),
            index = 1)
    private static long fornax$usePersistentPipelineCache(long pipelineCache) {
        return PersistentPipelineCache.handle();
    }
}
