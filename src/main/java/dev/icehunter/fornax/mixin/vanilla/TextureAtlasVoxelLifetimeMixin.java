package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.voxel.VoxelHarvestLifecycle;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops CPU voxel readers before the block atlas closes its SpriteContents images.
 * Both upload and close call clearTextureData; a GPU-only release hook cannot protect these
 * native pixels. With no active pack there are no leases to drain and no storage to retire. */
@Mixin(TextureAtlas.class)
public class TextureAtlasVoxelLifetimeMixin {
    @Shadow @Final private Identifier location;
    @Inject(method = "clearTextureData()V", at = @At("HEAD"))
    private void fornax$retireVoxelModelReads(CallbackInfo ci) {
        if (TextureAtlas.LOCATION_BLOCKS.equals(this.location)) {
            VoxelHarvestLifecycle.onBlockAtlasRetired();
        }
    }
}
