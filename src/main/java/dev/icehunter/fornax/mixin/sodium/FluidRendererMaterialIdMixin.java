package dev.icehunter.fornax.mixin.sodium;

import dev.icehunter.fornax.atlas.BlockAtlasPages;
import dev.icehunter.fornax.pipeline.BlockClasses;
import dev.icehunter.fornax.pipeline.MaterialIdContext;
import dev.icehunter.fornax.pack.material.BlockMaterials;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fluid-side counterpart to {@link BlockRendererMaterialIdMixin}: Sodium meshes a block's fluid
 * geometry (water/lava surfaces) through a wholly separate call from {@code BlockRenderer.renderModel}
 * -- {@code ChunkBuilderMeshingTask.execute} calls {@code blockRenderer.renderModel(...)} and then,
 * independently, {@code cache.getFluidRenderer().render(...)} for the same block position, both
 * synchronously in the same per-block loop on the same build thread (verified via decompile of
 * sodium-mc26.2-0.9.1-fabric.jar's {@code ChunkBuilderMeshingTask}). The platform {@code
 * FluidRendererImpl.render} (Fabric) forwards straight through to this class's {@code render} with
 * the same {@code BlockState} the block path already saw. For a PURE fluid block that state is the
 * fluid's own block ({@code Blocks.WATER} via its single post-1.13 block id, exactly what
 * {@code BlockMaterials} keys on) -- but for a WATERLOGGED block it is the HOST state
 * ({@code lantern[waterlogged=true]}), whose block is not in any fluid category. Every lane below
 * therefore keys on {@code fluidState.createLegacyBlock()} -- the fluid's own BlockState -- so a
 * waterlogged block's water quads classify as water, not as lantern (and do not inherit the host
 * block's light emission; a waterlogged lantern is level 15, its water is not). Without this seam,
 * every fluid quad kept material id 0 (uncategorized) because renderModel is never called for pure
 * fluid blocks (RenderShape != MODEL), so matIsWater() was never true and the water shader stack
 * never activated. HEAD-set / RETURN-clear scopes the ID to this one block's fluid quads, same as
 * the block mixin's threading doc.
 */
@Mixin(DefaultFluidRenderer.class)
public class FluidRendererMaterialIdMixin {
    @Inject(method = "render(Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;"
            + "Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/material/FluidState;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/buffers/ChunkModelBuilder;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;"
            + "Lnet/caffeinemc/mods/sodium/client/model/color/ColorProvider;"
            + "Lnet/minecraft/client/renderer/block/FluidModel;)V", at = @At("HEAD"))
    private void fornax$setMaterialId(LevelSlice level, BlockState blockState, FluidState fluidState,
                                      BlockPos blockPos, BlockPos offset, TranslucentGeometryCollector collector,
                                      ChunkModelBuilder meshBuilder, Material material, ColorProvider colorProvider,
                                      FluidModel sprites, CallbackInfo ci) {
        // The fluid's own BlockState, NOT the (possibly waterlogged host) blockState parameter --
        // see the class doc. Guarded for an empty FluidState only out of caution; this renderer is
        // not called without one.
        BlockState fluidKey = fluidState.isEmpty() ? blockState : fluidState.createLegacyBlock();
        MaterialIdContext.set(BlockMaterials.idForState(fluidKey));
        // Fluids are meshed HERE, not by BlockRenderer, so the precipitation lane has to be set on
        // this path too -- water is a fluid, and setting it only on the block path left every water
        // quad carrying whatever flag the previous BLOCK happened to leave behind. That reads as
        // splash rings on an ocean under a clear sky, and it is invisible until you look at water
        // specifically, because the block path is right.
        //
        // Read from the client level, the same source the block path uses -- see that mixin for the
        // javap note on what ClientLevel.getPrecipitationAt resolves to. LevelSlice would be the
        // better source -- it is the thread-safe snapshot chunk building is meant to read -- but it
        // exposes no biome accessor, so both paths share one lookup and one failure mode instead of
        // diverging.
        //
        // This carries the TYPE (none/rain/snow), same as the block path. Water in a snowy biome must
        // report SNOW and not merely "precipitates", or the water pre-pass writes a positive alpha
        // sign and water_composite rings a frozen lake with rain splashes.
        Minecraft client = Minecraft.getInstance();
        MaterialIdContext.setPrecipitation(client.level == null
                ? Biome.Precipitation.RAIN
                : client.level.getPrecipitationAt(blockPos));

        // Light emission on the fluid path too, and this one is not a formality: LAVA is level 15,
        // the brightest emitter in the game, and it is meshed HERE and never by BlockRenderer. A
        // block-path-only lane would leave every lava surface carrying whatever the previous BLOCK
        // left behind -- the exact failure the precipitation lane already had to fix once on this
        // same seam. Keyed on fluidKey so getLightEmission() answers for the FLUID (Blocks.LAVA /
        // Blocks.WATER) and never for whatever contains it -- a waterlogged lantern's water quads
        // must not carry the lantern's level 15.
        MaterialIdContext.setLightEmission(fluidKey.getLightEmission());

        // Set EXPLICITLY on this path too, and not left to the block path's clear(). Every lane on
        // this seam that was set on one path and inherited on the other has been a shipped bug once
        // already -- water carrying the previous block's precipitation flag is the recorded case --
        // and the failure here would be a lava or water surface inheriting a COAL flag from
        // whatever block was meshed before it. No fluid is in the coal tag, so this resolves to
        // NONE today; the point is that it resolves from the fluid's own BlockState rather than
        // from history -- or from a waterlogged host block's flags.
        MaterialIdContext.setBlockClass(BlockClasses.flagsForBlock(fluidKey.getBlock()));

        // Page lookup keyed on fluidKey too, and not the (possibly waterlogged) host blockState --
        // same rationale as every other lane above. BlockAtlasPages.pageForState(BlockState) is
        // called directly with the already-resolved fluidKey rather than through the
        // pageForFluidState(FluidState) overload, avoiding a second createLegacyBlock() resolution
        // in this hot path -- see BlockAtlasPages' own doc on that overload.
        MaterialIdContext.setAtlasPage(BlockAtlasPages.pageForState(fluidKey));
    }

    @Inject(method = "render(Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;"
            + "Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/material/FluidState;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/buffers/ChunkModelBuilder;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;"
            + "Lnet/caffeinemc/mods/sodium/client/model/color/ColorProvider;"
            + "Lnet/minecraft/client/renderer/block/FluidModel;)V", at = @At("RETURN"))
    private void fornax$clearMaterialId(LevelSlice level, BlockState blockState, FluidState fluidState,
                                        BlockPos blockPos, BlockPos offset, TranslucentGeometryCollector collector,
                                        ChunkModelBuilder meshBuilder, Material material, ColorProvider colorProvider,
                                        FluidModel sprites, CallbackInfo ci) {
        MaterialIdContext.clear();
    }
}
