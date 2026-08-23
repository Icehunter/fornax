package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.atlas.LabPbrAtlasPair;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Advances LabPBR sidecars immediately after vanilla advances the owning block albedo atlas. */
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
    }
}
