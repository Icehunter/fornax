package dev.icehunter.fornax.mixin.vanilla;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.atlas.BlockAtlasGhostLayout;
import dev.icehunter.fornax.atlas.BlockAtlasPagedLayout;
import dev.icehunter.fornax.atlas.BlockAtlasPagedStitch;
import dev.icehunter.fornax.atlas.BlockAtlasPaging;
import dev.icehunter.fornax.atlas.LabPbrSidecarSurvey;
import dev.icehunter.fornax.util.GpuMemoryEstimator;
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
 * The paged block atlas's stitch decision point: plans every BLOCK-atlas stitch through
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
 * stitch exactly as it always has. Every pack small enough to fit today is therefore unaffected,
 * which is what lets this run unconditionally.
 *
 * <p>There is no setting for this. Paging only engages where the alternative is the reload
 * aborting and the game turning the player's resource packs off, so there is nothing for a toggle
 * to pick between. While one existed, default-off, it turned a loadable pack into "Maybe try a
 * lower resolution resourcepack?" with nothing on screen pointing at the switch.
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
                // Back to the cheap grid: a previous pack may have escalated it, and without this
                // the biggest pack of the session would keep its 512 MB grid allocated for every
                // pack loaded after it. NOT applied here -- this hook runs on the stitch's
                // background executor, not the render thread, and SpriteBoundsTexture.useGridSize
                // closes a live GPU texture. BlockAtlasOverflow.rebuild(null), called from the
                // render-thread RETURN hook once BlockAtlasPagedLayout.current() reads null below,
                // is what actually resets the grid.
                BlockAtlasPagedLayout.clear();
                return;
            } catch (BlockAtlasPaging.PagingException overflowsOnePage) {
                // The pack genuinely needs paging -- the takeover below is the only path that can
                // load it at all (vanilla's own stitch would throw StitcherException).
            }

            BlockAtlasPagedStitch.Takeover takeover = BlockAtlasPagedStitch.takeover(
                    this.location, contents, maxTextureSize, loweredMip, anisotropicLevel,
                    fornax$atlasBudgetBytes(device), executor);

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
            // The budget is in the message because it is the usual reason and the one nobody can
            // guess: the page ceiling is fixed, the budget depends on the machine. Falling through
            // gives vanilla's StitcherException, a fast refusal. Building pages that do not fit
            // does not fail at all, it swaps.
            long budget = fornax$atlasBudgetBytes(device);
            FornaxMod.LOGGER.warn(
                    "[Fornax] Paged block atlas: pack does not fit ({}). Ceiling is {} overflow"
                            + " page(s); this machine's budget is {} MB for overflow, {} MB per"
                            + " page. Vanilla stitch proceeds and will fail exactly as it does"
                            + " unpaged.",
                    e.getMessage(), BlockAtlasGhostLayout.MAX_OVERFLOW_PAGES,
                    budget / (1024 * 1024),
                    dev.icehunter.fornax.atlas.BlockAtlasPageBudget
                            .bytesPerPage(LabPbrSidecarSurvey.maxTextureDimension(device),
                                    LabPbrSidecarSurvey.maxTextureDimension(device)) / (1024 * 1024));
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
     * Share of real device-local VRAM the overflow pages and the sprite-bounds grid may claim
     * between them, when {@link GpuMemoryEstimator#detectedVramBytesFromDevice} can answer. Kept
     * well under 1 because plenty else sits on the same pool: page 0, the labPBR normal and
     * material atlases (measured at 358 MB and 268 MB on one pack), vanilla's own atlases, and the
     * rest of the engine's GPU-resident state. The two labPBR sidecar atlases claim their own share
     * of the SAME real-VRAM pool this fraction reserves against; see
     * {@code PbrSidecarAtlasScale#VRAM_SHARE_PER_ATLAS_FRACTION}'s doc for the coordinated split.
     */
    private static final double FORNAX_VRAM_BUDGET_FRACTION = 1.0 / 2.0;

    /**
     * Share of physical memory used only when a real VRAM reading is unavailable (GL backend, or
     * the Vulkan query itself failed). System RAM is a much looser proxy for VRAM than a real
     * device-local heap reading, so this fallback stays conservative: on Apple Silicon the two
     * pools are the same, but on a discrete GPU this can overestimate.
     */
    private static final double FORNAX_RAM_FALLBACK_FRACTION = 1.0 / 8.0;

    /**
     * The real device-local VRAM reading times {@link #FORNAX_VRAM_BUDGET_FRACTION} when available;
     * otherwise physical memory times {@link #FORNAX_RAM_FALLBACK_FRACTION}, or a 2 GB assumption
     * when neither can be read.
     */
    private static long fornax$atlasBudgetBytes(GpuDevice device) {
        java.util.OptionalLong vram = GpuMemoryEstimator.detectedVramBytesFromDevice(device);
        if (vram.isPresent()) {
            return (long) (vram.getAsLong() * FORNAX_VRAM_BUDGET_FRACTION);
        }
        try {
            java.lang.management.OperatingSystemMXBean os =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                return (long) (sun.getTotalMemorySize() * FORNAX_RAM_FALLBACK_FRACTION);
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // Falls through to the assumption below: a budget that is merely conservative beats a
            // reload that dies here, and this runs inside a resource reload.
        }
        return 2L * 1024L * 1024L * 1024L;
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
