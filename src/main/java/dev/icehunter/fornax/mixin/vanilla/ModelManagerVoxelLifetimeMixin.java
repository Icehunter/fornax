package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.voxel.VoxelHarvestLifecycle;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reopens CPU voxel reads only after the newly baked model sets are published successfully.
 * Atlas upload RETURN is too early: old model objects can still point at freed sprite images.
 * A failed application or shutdown has no successful callback and leaves reads paused.
 * With no active pack the reset leaves empty voxel bookkeeping and changes no rendering. */
@Mixin(ModelManager.class)
public class ModelManagerVoxelLifetimeMixin {
    @Inject(method = "apply(Lnet/minecraft/client/resources/model/ModelManager$ReloadState;)V",
            at = @At("RETURN"))
    private void fornax$publishVoxelModels(CallbackInfo ci) {
        VoxelHarvestLifecycle.onModelsPublished();
    }
}
