package dev.icehunter.fornax.mixin.sodium;
import dev.icehunter.fornax.pipeline.FornaxChunkVertex;

import dev.icehunter.fornax.pipeline.PreviousFrameCameraTransform;
import dev.icehunter.fornax.util.SunDirection;
import net.caffeinemc.mods.sodium.client.gpu.device.context.VKDrawContext;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK13;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Widens the per-draw Vulkan push-constant block {@code VKDrawContext.updateData} writes from 20
 * to 60 bytes, adding the sun direction and previous-frame region offset terrain.vsh's
 * motion-vector and sun-lighting math need.
 *
 * <p>Replaces {@code net.caffeinemc.mods.sodium.client.gpu.device.context.VKDrawContext#
 * updateData(RenderRegion, CameraTransform)}. Verified against Sodium mc26.2-0.9.0 (bf93ed83); no Sodium source is reproduced here.
 *
 * <p><b>Push-constant layout contract:</b>
 * <ul>
 *   <li>{@code DrawContext.PUSH_CONSTANT_RANGE} ({@code public static final int} = 20) is inlined
 *   by javac at both of {@code VKDrawContext}'s use sites ({@code bipush 20} at the stack
 *   allocation and at the {@code vkCmdPushConstants} call) -- a mixin changing the field's declared
 *   value would not reach either site (the same hazard as {@code CompactChunkVertex.STRIDE}, see
 *   {@code FornaxChunkVertex}).</li>
 *   <li>The push-constant block's real member order/offsets, per {@code terrain.vsh}'s {@code
 *   FornaxPushConstants} declaration: {@code vec3 u_RegionOffset} at 0, {@code int u_CurrentTime}
 *   at 12, {@code uint u_RegionID} at 16, {@code vec3 u_SunDirection} at 32 (a 12-byte std430 gap
 *   for vec3 alignment), {@code vec3 u_PrevRegionOffset} at 48, ending at 60. This is not a pure
 *   tail append past the official 20 bytes -- the alignment gap has to be produced too.</li>
 *   <li>{@code MemoryStack.ncalloc} (zeroed) replaces {@code nmalloc} (uninitialized) so the
 *   alignment gaps read as zero rather than stack garbage -- a different LWJGL entry point, not
 *   just a different size argument to the same one.</li>
 * </ul>
 * These facts rule out a lighter seam (unlike the OpenGL side -- see {@code DrawContextGLMixin} --
 * where uniforms are addressed by name with no shared byte layout at all): the allocation function,
 * allocation size, and interior byte offsets all move together, so a full-method {@code
 * @Overwrite} is the least-fragile option.
 *
 * <p>{@code getCameraTranslation(int, int, float)} is not reimplemented here -- it is {@code
 * protected static} on {@code DrawContext}, {@code VKDrawContext}'s direct superclass, so it is
 * shadowed and called directly rather than restated.
 */
@Mixin(VKDrawContext.class)
public abstract class DrawContextVKMixin {
    @Shadow
    protected org.lwjgl.vulkan.VkCommandBuffer cmdBuf;

    @Shadow
    protected long layout;


    private static final int PUSH_CONSTANT_BLOCK_SIZE = dev.icehunter.fornax.pipeline.TerrainPushConstants.BLOCK_SIZE;
    private static final int SUN_DIRECTION_OFFSET = 32;
    private static final int PREV_REGION_OFFSET_OFFSET = 48;

    @Overwrite
    public void updateData(RenderRegion region, CameraTransform camera) {
        float regionX = DrawContextInvoker.fornax$getCameraTranslation(region.getOriginX(), camera.intX, camera.fracX);
        float regionY = DrawContextInvoker.fornax$getCameraTranslation(region.getOriginY(), camera.intY, camera.fracY);
        float regionZ = DrawContextInvoker.fornax$getCameraTranslation(region.getOriginZ(), camera.intZ, camera.fracZ);

        CameraTransform previousCamera = PreviousFrameCameraTransform.getCameraTransform();
        float prevRegionX = DrawContextInvoker.fornax$getCameraTranslation(region.getOriginX(), previousCamera.intX, previousCamera.fracX);
        float prevRegionY = DrawContextInvoker.fornax$getCameraTranslation(region.getOriginY(), previousCamera.intY, previousCamera.fracY);
        float prevRegionZ = DrawContextInvoker.fornax$getCameraTranslation(region.getOriginZ(), previousCamera.intZ, previousCamera.fracZ);

        Vector3f sunDirection = SunDirection.computeSunDirection();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long block = stack.ncalloc(1, 1, PUSH_CONSTANT_BLOCK_SIZE);

            MemoryUtil.memPutFloat(block, regionX);
            MemoryUtil.memPutFloat(block + 4, regionY);
            MemoryUtil.memPutFloat(block + 8, regionZ);
            MemoryUtil.memPutInt(block + 12, Math.toIntExact(System.currentTimeMillis() - region.getCreationTime()));
            MemoryUtil.memPutInt(block + 16, region.getId());
            // Bytes [20, 32) are the std430 alignment gap ahead of the next vec3 member; left
            // zeroed by ncalloc, never read by the shader.
            MemoryUtil.memPutFloat(block + SUN_DIRECTION_OFFSET, sunDirection.x());
            MemoryUtil.memPutFloat(block + SUN_DIRECTION_OFFSET + 4, sunDirection.y());
            MemoryUtil.memPutFloat(block + SUN_DIRECTION_OFFSET + 8, sunDirection.z());
            // Bytes [44, 48) are the matching alignment gap for u_PrevRegionOffset.
            MemoryUtil.memPutFloat(block + PREV_REGION_OFFSET_OFFSET, prevRegionX);
            MemoryUtil.memPutFloat(block + PREV_REGION_OFFSET_OFFSET + 4, prevRegionY);
            MemoryUtil.memPutFloat(block + PREV_REGION_OFFSET_OFFSET + 8, prevRegionZ);

            VK13.nvkCmdPushConstants(this.cmdBuf, this.layout, VK13.VK_SHADER_STAGE_ALL, 0, PUSH_CONSTANT_BLOCK_SIZE, block);
        }
    }
}
