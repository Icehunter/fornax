package dev.icehunter.fornax.mixin.vanilla;

import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exact resource owner retained by RenderSetup.TextureBinding. */
@Mixin(targets = "net.minecraft.client.renderer.rendertype.RenderSetup$TextureBinding")
public interface RenderSetupTextureBindingAccessor {
    @Accessor("location")
    Identifier location();
}
