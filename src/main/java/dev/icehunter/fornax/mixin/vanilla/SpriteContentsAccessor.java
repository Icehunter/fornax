package dev.icehunter.fornax.mixin.vanilla;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code SpriteContents}'s private, already-retained {@code originalImage} -- the real
 * decoded atlas sprite pixel data, kept in CPU memory by vanilla for the whole atlas generation's
 * lifetime (confirmed: not closed until the NEXT atlas reload). No GPU readback needed; this is the
 * seam that lets voxel harvesting read real per-block texel color entirely on the CPU.
 */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("originalImage")
    NativeImage fornax$originalImage();

    /**
     * The per-mip pixel data vanilla itself uploads from ({@code uploadFirstFrame} reads
     * {@code byMipLevel[level]} -- decompile-verified), populated by {@code increaseMipLevel}
     * before any atlas upload runs. The paged block atlas's overflow compositor copies these
     * SAME images to the overflow layers, so layer content is vanilla's own mip chain, never a
     * re-downsample. NOT cached by callers: the field is non-final and reassigned by
     * {@code increaseMipLevel}.
     */
    @Accessor("byMipLevel")
    NativeImage[] fornax$byMipLevel();
}
