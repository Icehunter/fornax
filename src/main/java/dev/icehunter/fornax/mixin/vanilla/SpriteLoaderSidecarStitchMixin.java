package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.atlas.LabPbrSidecarStitchFilter;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
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
 * Keeps labPBR {@code _n}/{@code _s} sidecars out of EVERY vanilla atlas.
 *
 * <p>See {@link LabPbrSidecarStitchFilter} for why they are in one at all, why nothing reads them
 * from it, and the measured atlas sizes this recovers. This class is only the hook.
 *
 * <p>This was block-only at first, on the reasoning that Fornax reads block sidecars and not the
 * others. That is backwards: a sidecar nothing reads is ballast wherever it sits. On a 256x pack
 * shipping entity maps, the atlases outside the block one carried 500 of them, 513 Mtexel, close
 * to 2 GB at RGBA8, and the entity atlas they inflated stitched at 16384x8192 while the machine
 * went to swap.
 *
 * <p>What keeps this safe is the filter's own rule rather than the atlas identity, and it is the
 * stronger of the two: a sprite goes only when the same name without the suffix is in the same
 * stitch set. No vanilla texture ends in {@code _n} or {@code _s}, so vanilla alone is untouched in
 * every atlas.
 *
 * <p><b>Why {@code stitch} and not somewhere earlier.</b> It is the first point where the argument
 * list is the complete, resolved sprite set, which is what the "the base sprite is present" rule
 * needs to look at. Everything upstream sees one sprite at a time and does not know what it will be
 * stitched beside.
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
        FornaxMod.LOGGER.info("[LabPBR] {} stitch: kept {} sprites, left out {} labPBR"
                        + " sidecars ({} Mtexel, {} MB at RGBA8). Sidecars are read from their source"
                        + " files into Fornax's own normal/material atlases and are never sampled"
                        + " from an atlas.",
                this.location, kept.size(), drop.size(),
                String.format("%.1f", droppedTexels / 1.0e6),
                Math.round(droppedTexels * 4.0 / 1.0e6));
        return kept;
    }
}
