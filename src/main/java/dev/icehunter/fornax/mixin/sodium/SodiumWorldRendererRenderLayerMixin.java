package dev.icehunter.fornax.mixin.sodium;
import dev.icehunter.fornax.pipeline.UniformBufferManagerExtension;
import dev.icehunter.fornax.pipeline.ChunkRenderContextHolder;

import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Populates {@link ChunkRenderContextHolder} at the start of every {@code renderLayer} call
 * (SOLID/CUTOUT/TRANSLUCENT, once per pass, every frame) and drives {@code
 * UniformBufferManager.updatePbrSettings()} (a Fornax-only addition -- see {@code
 * UniformBufferManagerMixin}).
 *
 * <p>{@code renderLayer(ChunkRenderMatrices, TerrainRenderPass, double, double, double,
 * FogParameters, GpuSampler)}'s signature is untouched here (unlike {@code
 * DefaultChunkRenderer.render}, no parameter is widened), and {@code uniformBufferManager} is a
 * private instance field with no public getter, requiring {@code @Shadow} to reach it from a
 * mixin mixed directly into this class.
 *
 * <p>{@code updatePbrSettings()} is safe to call once per pass (three times a frame): like the
 * official {@code update(...)} it mirrors, it is guarded internally by a
 * once-per-frame flag, so the second and third calls each frame are no-ops.
 */
@Mixin(SodiumWorldRenderer.class)
public class SodiumWorldRendererRenderLayerMixin {
    @Shadow
    private UniformBufferManager uniformBufferManager;

    // Sodium 0.9.1: NOT @At("HEAD") any more. getUniformBuffer() now returns this frame's
    // DynamicUniformStorage slice and THROWS ("Global terrain uniforms have not been updated")
    // until update() has run this frame -- renderLayer itself calls update() as its first real
    // statement, so anchoring AFTER that call is both the earliest safe point and the only
    // correct one (at HEAD the previous frame's slice would be captured even when it didn't
    // throw -- live crash 2026-07-08, first frame with SHADOWS off where no earlier explicit
    // update() had run).
    @Inject(
            method = "renderLayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;update(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/util/FogParameters;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void fornax$populateRenderContext(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z, FogParameters fogParameters, GpuSampler terrainSampler, CallbackInfo ci) {
        // UniformBufferManagerMixin adds updatePbrSettings()/getPbrSettingsBuffer() at runtime, but
        // they don't exist on the compile-time (official) UniformBufferManager -- cast through the
        // interface-injection surface, same pattern as this codebase's existing TextureAtlasAccessor.
        UniformBufferManagerExtension extension = (UniformBufferManagerExtension) this.uniformBufferManager;
        extension.updatePbrSettings();

        ChunkRenderContextHolder.set(
                this.uniformBufferManager.getUniformBuffer(),
                extension.getPbrSettingsBuffer()
        );
    }
}
