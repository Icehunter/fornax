package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.atlas.LabPbrDrawTextureRegistry;
import java.util.Map;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures Sampler0's exact authored resource owner at the point RenderType prepares its draw. */
@Mixin(RenderType.class)
public abstract class RenderTypeLabPbrOwnerMixin {
    @Shadow
    @Final
    private RenderSetup state;

    @Inject(method = "prepare", at = @At("RETURN"))
    private void fornax$rememberSampler0Owner(CallbackInfoReturnable<PreparedRenderType> cir) {
        Map<String, Object> textures = ((RenderSetupTexturesAccessor) (Object) this.state)
                .fornax$getTextures();
        Object sampler0 = textures.get("Sampler0");
        if (sampler0 != null) {
            capturePreparedOwner(cir.getReturnValue(),
                    ((RenderSetupTextureBindingAccessor) sampler0).location());
        }
    }

    private static void capturePreparedOwner(PreparedRenderType prepared, Identifier exactLocation) {
        LabPbrDrawTextureRegistry.remember(prepared, exactLocation);
    }
}
