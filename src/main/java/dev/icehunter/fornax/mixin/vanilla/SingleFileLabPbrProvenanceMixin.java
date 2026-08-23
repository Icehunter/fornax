package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.atlas.LabPbrAtlasProvenance;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.sources.SingleFile;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Records SingleFile's exact resource owner even when the atlas gives it an unrelated sprite id. */
@Mixin(SingleFile.class)
public class SingleFileLabPbrProvenanceMixin {
    @Shadow
    @Final
    private Identifier resourceId;

    @Redirect(
            method = "run",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/atlas/SpriteSource$Output;"
                            + "add(Lnet/minecraft/resources/Identifier;"
                            + "Lnet/minecraft/server/packs/resources/Resource;)V"))
    private void fornax$rememberExactSource(SpriteSource.Output output, Identifier spriteId,
                                             Resource resource) {
        LabPbrAtlasProvenance.rememberSource(resource,
                SpriteSource.TEXTURE_ID_CONVERTER.idToFile(this.resourceId));
        LabPbrAtlasProvenance.addExactSource(output, spriteId, resource);
    }
}
