package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.atlas.LabPbrSidecarStitchFilter;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Keeps labPBR {@code _n}/{@code _s} sidecars out of the vanilla BLOCK atlas.
 *
 * <p>See {@link LabPbrSidecarStitchFilter} for why they are in it at all, why nothing reads them
 * from it, and the measured atlas sizes this recovers. This class is only the hook.
 *
 * <p><b>Why {@code stitch} and not somewhere earlier.</b> It is the first and only point where both
 * halves of the decision are available: {@code location} says which atlas is being built (this must
 * not touch the items or GUI atlases, whose sidecars Fornax does not consume), and the argument list
 * is the complete, resolved sprite set, which is what the "the base sprite is present" safety rule
 * needs to look at. Everything upstream sees one sprite at a time and does not know where it is
 * going.
 *
 * <p>The cost of hooking this late is that the dropped PNGs have already been decoded. That is not
 * new cost -- it is what happens today -- and the decoded {@link SpriteContents} are
 * {@linkplain SpriteContents#close() closed} here rather than dropped on the floor, so their native
 * images are released immediately instead of at the next GC. On the 64x + 512x-maps pack that is
 * roughly 1.5 GB of {@code NativeImage} returned during the reload rather than held to the end of
 * it.
 */
// Priority 900 (below default): this filter's argument replacement must land before
// SpriteLoaderPagedStitchMixin (priority 1100) reads the list at the same HEAD injection point --
// the pager must plan against the sidecar-FILTERED sprite population, and without explicit
// priorities the relative order of two mixins' HEAD injections is unspecified.
@Mixin(value = SpriteLoader.class, priority = 900)
public class SpriteLoaderSidecarStitchMixin {
    @Shadow
    @Final
    private Identifier location;

    @ModifyVariable(
            method = "stitch(Ljava/util/List;ILjava/util/concurrent/Executor;)"
                    + "Lnet/minecraft/client/renderer/texture/SpriteLoader$Preparations;",
            at = @At("HEAD"), argsOnly = true, index = 1)
    private List<SpriteContents> fornax$dropLabPbrSidecars(List<SpriteContents> contents) {
        if (!TextureAtlas.LOCATION_BLOCKS.equals(this.location)) {
            return contents;
        }

        Set<Identifier> drop = LabPbrSidecarStitchFilter.sidecarsToDrop(
                contents.stream().map(SpriteContents::name).toList());
        if (drop.isEmpty()) {
            // No pack loaded, or a pack with no labPBR maps: hand back the very same list, so the
            // common case is not merely equivalent but identical.
            return contents;
        }

        List<SpriteContents> kept = new ArrayList<>(contents.size() - drop.size());
        long droppedTexels = 0L;
        for (SpriteContents sprite : contents) {
            if (drop.contains(sprite.name())) {
                droppedTexels += (long) sprite.width() * sprite.height();
                sprite.close();
            } else {
                kept.add(sprite);
            }
        }

        // Loud and quantified, once per reload. This changes what vanilla stitches, so if a texture
        // ever does go missing this line is the first thing to read -- and the texel figure is the
        // one that decides whether a pack fits under the device's dimension cap at all.
        FornaxMod.LOGGER.info("[LabPBR] Block atlas stitch: kept {} sprites, left out {} labPBR"
                        + " sidecars ({} Mtexel, {} MB at RGBA8). Sidecars are read from their source"
                        + " files into Fornax's own normal/material atlases and are never sampled"
                        + " from the block atlas.",
                kept.size(), drop.size(),
                String.format("%.1f", droppedTexels / 1.0e6),
                Math.round(droppedTexels * 4.0 / 1.0e6));
        return kept;
    }
}
