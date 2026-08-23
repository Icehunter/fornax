package dev.icehunter.fornax.mixin.vanilla;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Constructor access for {@link TextureAtlasSprite}, whose only constructor is protected. The paged
 * stitch takeover ({@code BlockAtlasPagedStitch}) builds page-0 sprites itself -- placement comes
 * from its own {@code Stitcher} rather than vanilla's private {@code getStitchedSprites} -- and
 * those sprites must be EXACTLY the vanilla type with vanilla's own UV derivation, not a subclass:
 * only spilled sprites get {@code BlockAtlasGhostSprite}'s overridden geometry.
 */
@Mixin(TextureAtlasSprite.class)
public interface TextureAtlasSpriteInvoker {
    @Invoker("<init>")
    static TextureAtlasSprite fornax$create(Identifier atlasLocation, SpriteContents contents,
                                            int atlasWidth, int atlasHeight, int x, int y, int padding) {
        throw new AssertionError("mixin invoker not applied");
    }
}
