package dev.icehunter.fornax.mixin.vanilla;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Exposes an atlas's stitched sprite list.
 *
 * <p>Needed because a sprite's true rectangle is knowable only from the atlas. Inferring it from the
 * geometry -- taking the min/max UV of each quad -- looks equivalent and is not: block models
 * routinely map a face onto part of a texture rather than all of it, so those quads measure a
 * sub-rectangle and every calculation scaled by it comes out wrong.
 */
@Mixin(TextureAtlas.class)
public interface TextureAtlasSpritesAccessor {
    @Accessor("sprites")
    List<TextureAtlasSprite> fornax$sprites();
}
