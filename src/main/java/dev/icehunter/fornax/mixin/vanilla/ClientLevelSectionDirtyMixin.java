package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.voxel.VoxelMeshHarvestTelemetry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stamps the moment a section is marked for rebuild, which is the first thing that happens when a
 * block changes.
 *
 * <p>Fornax's own timing starts when Sodium's meshing task calls into it, so everything before
 * that is invisible from inside the engine: whether a block edit reaches the voxel grid slowly
 * because Fornax is slow, or because the rebuild it rides on was scheduled late. This closes that
 * gap, and it is the whole reason the mixin exists.
 *
 * <p>Every way in is stamped, and the earliest one wins. A block the client places or breaks goes
 * through {@code setBlock}; {@code setBlocksDirty} is the renderer being told about it, and
 * {@code setSectionDirtyWithNeighbors} is light and chunk loading, which a block edit never uses.
 *
 * <p>The stamp is cleared when the harvest it belongs to asks for one, so it is the first change
 * since that section was last read, not since the world loaded.
 */
@Mixin(ClientLevel.class)
public class ClientLevelSectionDirtyMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void fornax$stampBlockSet(BlockPos pos, BlockState state, int flags, int limit,
                                      CallbackInfoReturnable<Boolean> cir) {
        VoxelMeshHarvestTelemetry.LIVE.dirtied(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    @Inject(method = "setBlocksDirty(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("HEAD"))
    private void fornax$stampBlockDirty(BlockPos pos, BlockState before, BlockState after,
                                        CallbackInfo ci) {
        VoxelMeshHarvestTelemetry.LIVE.dirtied(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    @Inject(method = "setSectionDirtyWithNeighbors(III)V", at = @At("HEAD"))
    private void fornax$stampSectionDirty(int sectionX, int sectionY, int sectionZ, CallbackInfo ci) {
        VoxelMeshHarvestTelemetry.LIVE.dirtied(sectionX, sectionY, sectionZ);
    }
}
