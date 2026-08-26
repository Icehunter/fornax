package dev.icehunter.fornax.mixin.sodium;
import dev.icehunter.fornax.pipeline.ChunkRenderContextHolder;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Binds {@code u_PbrSettings} right after the official {@code DefaultChunkRenderer.render()} binds
 * {@code u_SectionTimeInfo}, reading the buffer from {@link ChunkRenderContextHolder} rather than as
 * a method parameter.
 *
 * <p>{@code render(...)}'s signature is declared on the {@code ChunkRenderer} interface with an
 * exact 9-parameter shape, and {@code ShaderChunkRenderer} is its only implementor -- widening that
 * interface for one implementor to carry a 10th parameter is unnecessary risk when {@link
 * ChunkRenderContextHolder} (populated earlier this same frame/pass by {@code
 * SodiumWorldRendererRenderLayerMixin}) already carries the same value.
 *
 * <p>Sodium 0.9.1 binds {@code u_Globals} via the {@code setUniform(String,
 * GpuBufferSlice)} overload (the uniform manager sub-allocates from a ring buffer), so the plain
 * {@code setUniform(String, GpuBuffer)} overload this anchors on appears exactly ONCE in {@code
 * render(...)} -- the {@code u_SectionTimeInfo} bind (ordinal 0). Injecting immediately after it
 * lands right after {@code u_SectionTimeInfo} and before the texture binds.
 */
@Mixin(DefaultChunkRenderer.class)
public class DefaultChunkRendererRenderMixin {
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            )
    )
    private void fornax$bindPbrSettingsUniform(
            ChunkRenderMatrices matrices,
            ChunkRenderListIterable renderLists,
            TerrainRenderPass renderPass,
            CameraTransform camera,
            FogParameters fogParameters,
            boolean useBlockFaceCulling,
            GpuSampler terrainSampler,
            GpuBufferSlice uniformBuffer,
            GpuBuffer sectionTimeInfo,
            CallbackInfo ci,
            @Local RenderPass pass
    ) {
        pass.setUniform("u_PbrSettings", ChunkRenderContextHolder.getPbrSettingsBuffer());
    }
}
