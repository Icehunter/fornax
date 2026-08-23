package dev.icehunter.fornax.mixin.sodium;

import dev.icehunter.fornax.atlas.BlockAtlasPages;
import dev.icehunter.fornax.pipeline.BlockClasses;
import dev.icehunter.fornax.pipeline.MaterialIdContext;
import dev.icehunter.fornax.pack.material.BlockMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sets the per-build-thread material ID from the blockstate about to be meshed, so
 * FornaxChunkVertex's encoder can pack it into a_Normal.yz. renderModel drives every terrain quad
 * for one block synchronously on the calling build thread, so HEAD-set / RETURN-clear cleanly scopes
 * the ID to that block's vertices.
 */
@Mixin(BlockRenderer.class)
public class BlockRendererMaterialIdMixin {
    @Inject(method = "renderModel(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;"
            + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"))
    private void fornax$setMaterialId(BlockStateModel model, BlockState state, BlockPos pos,
                                      BlockPos origin, CallbackInfo ci) {
        MaterialIdContext.set(BlockMaterials.idForState(state));
        // Biome precipitation TYPE -- none/rain/snow, not a boolean -- captured per BLOCK while its
        // position is in hand. Read from the client level on a chunk-build thread: a read-only biome
        // lookup against chunk data that is already resident (this block is being meshed, so its
        // section is loaded), which is the same kind of access Sodium's own biome tint does from
        // these threads.
        //
        // ClientLevel.getPrecipitationAt is vanilla's own query, and javap -c of ClientLevel against
        // the 26.2 deobf jar shows it is exactly the call GlobalUniformsWriteMixin spells out by hand
        // for the camera lane -- getBiome(pos).value().getPrecipitationAt(pos, getSeaLevel()) --
        // behind a chunkSource.hasChunk guard that returns NONE for an unloaded column. Calling it
        // instead of re-spelling the lookup keeps the per-block and camera lanes provably in
        // agreement (same biome data, same sea level, same altitude rain/snow split), and the guard
        // cannot fire here: this block is mid-mesh, so its chunk is loaded.
        //
        // Defaults to RAIN if the level is gone mid-reload, matching the pre-lane behaviour rather
        // than stamping a permanently dry patch into a mesh that then persists until the chunk is
        // rebuilt. NOT NONE: dryness is the visible change, not the neutral one.
        Minecraft client = Minecraft.getInstance();
        MaterialIdContext.setPrecipitation(client.level == null
                ? Biome.Precipitation.RAIN
                : client.level.getPrecipitationAt(pos));

        // How much light this block emits, straight off the BlockState already in hand. No lookup,
        // no allocation and no level access -- getLightEmission() reads a field cached on the
        // BlockState (SectionHarvester already calls it on the voxel path, javap-confirmed against
        // the 26.2 jar), which is what makes it affordable in the hottest loop in terrain meshing.
        //
        // Vanilla's own number for a vanilla question. See MaterialIdContext.setLightEmission for
        // why this is not the per-block-id material table Fornax refuses to host.
        MaterialIdContext.setLightEmission(state.getLightEmission());

        // Which vanilla CATEGORIES this block is in -- today just "is it a coal ore", per
        // Minecraft's own #minecraft:coal_ores tag. A map lookup keyed by Block, exactly like the
        // material id above and rebuilt on the same tag-load pass, so it costs one hash probe per
        // block rather than a tag-set scan: BlockState.is(TagKey) walks the holder's tag
        // collection, and asking that in the hottest loop in terrain meshing is the kind of cost
        // this encoder has already been burned by once (see FornaxChunkVertex's note on the
        // per-quad sprite lookup that dropped the frame rate to single digits).
        MaterialIdContext.setBlockClass(BlockClasses.flagsForBlock(state.getBlock()));

        // Which block-atlas PAGE this block's quads should sample from (M13's paged block atlas).
        // BlockAtlasPages.pageForState answers 0 for every block until a later phase populates its
        // cache (see that class's own doc), so this is currently a no-op write of the value every
        // block already implicitly had -- wiring the lookup path now costs nothing behaviorally.
        MaterialIdContext.setAtlasPage(BlockAtlasPages.pageForState(state));
    }

    @Inject(method = "renderModel(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;"
            + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/core/BlockPos;)V", at = @At("RETURN"))
    private void fornax$clearMaterialId(BlockStateModel model, BlockState state, BlockPos pos,
                                        BlockPos origin, CallbackInfo ci) {
        MaterialIdContext.clear();
    }
}
