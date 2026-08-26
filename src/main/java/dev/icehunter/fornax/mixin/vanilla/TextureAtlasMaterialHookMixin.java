package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.atlas.AtlasGenerationSchedule;
import dev.icehunter.fornax.atlas.LabPbrAtlasPair;
import dev.icehunter.fornax.atlas.LabPbrGeometryBindings;
import dev.icehunter.fornax.atlas.LabPbrSidecarRegistry;
import dev.icehunter.fornax.atlas.MaterialMapAtlasReloadListener;
import dev.icehunter.fornax.atlas.NormalMapAtlasReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Builds and atomically publishes both LabPBR atlas lanes whenever a vanilla atlas is uploaded.
 * One callback owns both builds so injection ordering can never expose lanes from different reloads.
 *
 * <p>Skips its own build when {@link AtlasGenerationSchedule#hasPending} is true for this location:
 * {@code TextureAtlasReleaseGenerationMixin}'s own HEAD hook already released the previous
 * generation and scheduled a deferred rebuild THIS SAME {@code upload} call, once intervening
 * render-loop submits have let that release actually reclaim VRAM (see that mixin's and {@link
 * AtlasGenerationSchedule}'s own docs for why). Building here too would defeat the whole point --
 * the new generation would be allocated in the same call as the release, with zero frames between
 * them. An unchanged non-block reload skips scheduling entirely. An unchanged block reload still
 * has pending overflow/grid work, while retaining its published sidecar pair, so it also returns
 * here without rebuilding the expensive lanes.
 */
@Mixin(TextureAtlas.class)
public class TextureAtlasMaterialHookMixin {
    @Shadow
    @Final
    private Identifier location;

    @Inject(method = "upload", at = @At("RETURN"))
    private void fornax$buildMaterialMapAtlas(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
        if (!LabPbrGeometryBindings.isMirroredAtlasOwner(this.location)) {
            return;
        }
        if (this.location.equals(TextureAtlas.LOCATION_BLOCKS)) {
            LabPbrSidecarRegistry.refreshActive(resourceManager);
        }
        if (AtlasGenerationSchedule.hasPending(this.location)) {
            return;
        }
        LabPbrAtlasPair.rebuild(this.location,
                () -> NormalMapAtlasReloadListener.build(
                        this.location, preparations, resourceManager),
                () -> MaterialMapAtlasReloadListener.build(
                        this.location, preparations, resourceManager));
    }
}
