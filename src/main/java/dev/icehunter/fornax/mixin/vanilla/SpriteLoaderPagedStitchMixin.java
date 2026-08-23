package dev.icehunter.fornax.mixin.vanilla;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.atlas.BlockAtlasGhostLayout;
import dev.icehunter.fornax.atlas.BlockAtlasPagedLayout;
import dev.icehunter.fornax.atlas.BlockAtlasPagedStitch;
import dev.icehunter.fornax.atlas.BlockAtlasPaging;
import dev.icehunter.fornax.atlas.LabPbrSidecarSurvey;
import dev.icehunter.fornax.config.FornaxConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * The paged block atlas's stitch decision point (M13 phase 3, gated behind
 * {@code FornaxSettings.pagedBlockAtlasEnabled}): plans every BLOCK-atlas stitch through
 * {@link BlockAtlasPaging}, and when -- and only when -- the pack cannot fit one page, TAKES OVER
 * the stitch entirely, returning {@link BlockAtlasPagedStitch}-built {@code Preparations} in
 * vanilla's place: page 0 placed by vanilla's own {@code Stitcher} at three-quarter height, the
 * bottom-quarter ghost strip describing every spilled sprite at quarter scale (see
 * {@link BlockAtlasGhostLayout}), and the spilled sprites' full-resolution placements recorded in
 * {@link BlockAtlasPagedLayout} for the overflow-layer phases.
 *
 * <p><b>The bit-identical guarantee for fitting packs.</b> A pack that fits one page NEVER takes
 * the takeover path: the fit check below runs vanilla's own stitch math (same {@code Stitcher},
 * same lowered mip level, same anisotropy padding -- {@link BlockAtlasPagedStitch#lowerMipLevel} is
 * the decompile-exact replica) and, on success, falls through untouched so vanilla performs its own
 * stitch exactly as it always has. With the flag off, this method costs one boolean read and
 * touches nothing.
 *
 * <p><b>Failure honesty.</b> If even {@code 1 + }{@link BlockAtlasGhostLayout#MAX_OVERFLOW_PAGES}
 * pages cannot hold the pack, or the takeover itself fails, this logs and falls through to
 * vanilla's own stitch -- which produces exactly today's failure (a {@code StitcherException}
 * crash-report path) rather than a novel one this mixin invented.
 *
 * <p>Priority 1100 (above default): {@code SpriteLoaderSidecarStitchMixin} (900) replaces the
 * {@code contents} argument at this same HEAD injection point, and the pager must see the
 * sidecar-FILTERED population -- see the pin comment there. Targets the descriptor pinned by
 * {@code dev.icehunter.fornax.pipeline.SpriteLoaderStitchHookTargetTest}.
 */
@Mixin(value = SpriteLoader.class, priority = 1100)
public class SpriteLoaderPagedStitchMixin {
    @Shadow
    @Final
    private Identifier location;

    @Inject(
            method = "stitch(Ljava/util/List;ILjava/util/concurrent/Executor;)"
                    + "Lnet/minecraft/client/renderer/texture/SpriteLoader$Preparations;",
            at = @At("HEAD"),
            cancellable = true)
    private void fornax$pagedStitch(List<SpriteContents> contents, int mipLevel, Executor executor,
                                    CallbackInfoReturnable<SpriteLoader.Preparations> cir) {
        if (!TextureAtlas.LOCATION_BLOCKS.equals(this.location)) {
            return;
        }
        if (!FornaxConfig.get().pagedBlockAtlasEnabled) {
            BlockAtlasPagedLayout.clear();
            return;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            FornaxMod.LOGGER.warn("[Fornax] Paged block atlas: no GPU device available, vanilla stitch proceeds");
            BlockAtlasPagedLayout.clear();
            return;
        }

        try {
            int maxTextureSize = LabPbrSidecarSurvey.maxTextureDimension(device);
            int anisotropicLevel = fornax$anisotropicLevel();
            int loweredMip = BlockAtlasPagedStitch.lowerMipLevel(this.location, contents, mipLevel);

            try {
                BlockAtlasPaging.plan(contents, maxTextureSize, loweredMip, anisotropicLevel, 1);
                // Fits one page: vanilla's own stitch below produces the identical result, so the
                // paged machinery stays fully dormant for this generation.
                FornaxMod.LOGGER.info(
                        "[Fornax] Paged block atlas: {} sprite(s) fit one page, vanilla stitch proceeds",
                        contents.size());
                BlockAtlasPagedLayout.clear();
                return;
            } catch (BlockAtlasPaging.PagingException overflowsOnePage) {
                // The pack genuinely needs paging -- the takeover below is the only path that can
                // load it at all (vanilla's own stitch would throw StitcherException).
            }

            BlockAtlasPagedStitch.Takeover takeover = BlockAtlasPagedStitch.takeover(
                    this.location, contents, maxTextureSize, loweredMip, anisotropicLevel, executor);

            BlockAtlasPagedLayout.install(takeover.layout());
            long animatedGhosts = takeover.layout().ghosts().stream()
                    .filter(ghost -> !ghost.hasOverflowCopy()).count();
            FornaxMod.LOGGER.info(
                    "[Fornax] Paged block atlas TAKEOVER: {} overflow page(s) on a {}x{} canvas,"
                            + " {} sprite(s) total, {} spilled to the ghost strip"
                            + " ({} animated in the animated cell), mip level {}",
                    takeover.layout().overflowPageCount(), maxTextureSize, maxTextureSize,
                    contents.size(), takeover.layout().ghosts().size(), animatedGhosts, loweredMip);
            cir.setReturnValue(takeover.preparations());
        } catch (BlockAtlasPaging.PagingException e) {
            FornaxMod.LOGGER.warn(
                    "[Fornax] Paged block atlas: pack exceeds even {} pages ({}), vanilla stitch proceeds"
                            + " and will fail exactly as it does unpaged",
                    1 + BlockAtlasGhostLayout.MAX_OVERFLOW_PAGES, e.getMessage());
            BlockAtlasPagedLayout.clear();
        } catch (RuntimeException e) {
            // Broad on purpose: a takeover bug must degrade to vanilla's own behavior, never invent
            // a new failure mode for the reload.
            FornaxMod.LOGGER.warn("[Fornax] Paged block atlas: takeover failed unexpectedly,"
                    + " vanilla stitch proceeds", e);
            BlockAtlasPagedLayout.clear();
        }
    }

    /**
     * Vanilla's own {@code anisotropicLevel} derivation, reproduced exactly (javap-verified against
     * {@code SpriteLoader.stitch} in the 26.2 client jar): 0 unless the player's texture filtering is
     * ANISOTROPIC, in which case it's their configured max-anisotropy BIT (not vanilla's already-
     * clamped {@code Options.maxAnisotropyValue()}, which folds in the device's own anisotropy cap --
     * {@code stitch} uses the raw bit). This value becomes {@link Stitcher}'s {@code padding}
     * parameter (confirmed via decompile: the constructor's 4th argument is stored as a field named
     * {@code padding}, not merely carried inertly), so an inaccurate value here would size the paged
     * canvases differently than vanilla's own stitch would have -- matching it exactly is what makes
     * the fit check and the takeover vanilla-equivalent rather than approximate.
     */
    private static int fornax$anisotropicLevel() {
        Options options = Minecraft.getInstance().options;
        return options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC
                ? options.maxAnisotropyBit().get()
                : 0;
    }

}
