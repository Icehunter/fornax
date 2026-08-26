package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.atlas.AtlasGenerationSchedule;
import dev.icehunter.fornax.atlas.LabPbrAtlasPair;
import dev.icehunter.fornax.atlas.LabPbrGeometryBindings;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Advances LabPBR sidecars immediately after vanilla advances the owning block albedo atlas, and
 * ticks {@link AtlasGenerationSchedule}'s retirement countdown for this location. Minecraft calls
 * this at most once per render-loop iteration (and may call it less often than displayed frames),
 * so successive polls are separated by the submits the Vulkan destroy ring needs.
 */
@Mixin(TextureAtlas.class)
public class TextureAtlasLabPbrAnimationMixin {
    @Shadow
    @Final
    private Identifier location;

    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
    private void fornax$tickLabPbrSidecars(CallbackInfo ci) {
        LabPbrAtlasPair pair = LabPbrAtlasPair.get(this.location);
        if (pair != null) {
            pair.tickAnimations();
        }
        if (LabPbrGeometryBindings.isMirroredAtlasOwner(this.location)) {
            AtlasGenerationSchedule.tick(this.location);
        }
    }
}
