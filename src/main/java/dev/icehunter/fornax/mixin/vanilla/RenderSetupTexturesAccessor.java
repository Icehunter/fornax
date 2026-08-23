package dev.icehunter.fornax.mixin.vanilla;

import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Narrow access to the authored texture bindings retained by a render setup. */
@Mixin(RenderSetup.class)
public interface RenderSetupTexturesAccessor {
    @Accessor("textures")
    Map<String, Object> fornax$getTextures();
}
