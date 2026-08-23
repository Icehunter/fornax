package dev.icehunter.fornax.mixin.sodium;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Exposes {@code ShaderChunkRenderer}'s private STATIC pipeline cache: {@code private static final
 * Map<TerrainRenderPass, RenderPipeline> programs} (javap-verified against sodium-fabric-0.9.0).
 * The cache is process-wide -- {@code compileProgram} populates it lazily and {@code delete()} is a
 * no-op -- so renderer recreation alone never recompiles terrain pipelines. {@code
 * SodiumWorldRendererReloadMixin} clears it whenever the Fornax render-state latch advances, forcing
 * the next terrain draw to recompile every pass's pipeline under the just-latched state.
 */
@Mixin(ShaderChunkRenderer.class)
public interface ShaderChunkRendererAccessor {
    @Accessor(value = "programs", remap = false)
    static Map<TerrainRenderPass, RenderPipeline> fornax$getPrograms() {
        throw new AssertionError("mixin accessor not applied");
    }
}
