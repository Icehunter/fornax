package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.pipeline.FornaxRenderState;
import dev.icehunter.fornax.voxel.VoxelWindow;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks a section whose light changed, meshed and on screen or not. Reads no world and does no
 * GPU work. Does nothing with no pack active. */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheVoxelLightMixin {
    @Inject(method = "onLightUpdate", at = @At("RETURN"))
    private void fornax$queueVoxelLightRefresh(LightLayer layer, SectionPos section, CallbackInfo ci) {
        if (!FornaxRenderState.isActive()) return;
        VoxelWindow.onSectionLightChanged(section);
    }
}
