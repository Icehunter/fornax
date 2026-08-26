package dev.icehunter.fornax.atlas;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.mixin.vanilla.TextureAtlasSpriteInvoker;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.StitcherException;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Builds the {@link SpriteLoader.Preparations} for a paged block-atlas stitch: the piece of the
 * takeover that replaces vanilla's own {@code SpriteLoader.stitch} body once
 * {@code SpriteLoaderPagedStitchMixin} has decided the pack overflows. Every step here mirrors the
 * decompiled vanilla body (mc26.2) exactly except where paging is the point:
 *
 * <ol>
 * <li>{@link #lowerMipLevel} is vanilla's own two-stage mip reduction (per-sprite lowest-one-bit
 *     cap, then the minimum-dimension log2 floor), reproduced with the same WARN lines -- the
 *     lowered value feeds the {@code Stitcher}s' padding math, so an approximation here would
 *     change every placement.</li>
 * <li>Page 0's placements become plain vanilla {@link TextureAtlasSprite}s (via
 *     {@link TextureAtlasSpriteInvoker} -- the constructor is protected) against the full square
 *     canvas; spilled placements become {@link BlockAtlasGhostSprite}s whose vanilla-facing
 *     geometry is their ghost rect in the reserved strip.</li>
 * <li>{@code readyForUpload} is the same async per-sprite {@code increaseMipLevel} sweep vanilla
 *     schedules, over the union map, so spilled sprites' contents grow their mip chains exactly
 *     like placed ones -- their staging-texture uploads (and the ghost blit's deeper
 *     {@code textureLod} reads) depend on those levels existing.</li>
 * </ol>
 *
 * <p>The returned {@code Preparations} reports the full square {@code canvasSize} for both
 * dimensions: page 0's texture must include the ghost strip, and every overflow layer shares that
 * extent by construction.
 */
public final class BlockAtlasPagedStitch {
    private BlockAtlasPagedStitch() {
    }

    /** One takeover's outputs: what vanilla's caller gets, and what later paged phases need. */
    public record Takeover(SpriteLoader.Preparations preparations, BlockAtlasPagedLayout layout) {
    }

    /**
     * Vanilla's mip-level lowering, decompile-exact: each sprite's lowest-one-bit dimension caps
     * the mip block size (with vanilla's own WARN), then the smallest sprite dimension floors the
     * final level (with vanilla's other WARN). Returns the level the {@code Stitcher}s and the
     * {@code Preparations} must actually use.
     */
    public static int lowerMipLevel(Identifier atlasLocation, List<SpriteContents> contents, int requestedMipLevel) {
        int minDimension = Integer.MAX_VALUE;
        int mipBlockSize = 1 << requestedMipLevel;
        for (SpriteContents sprite : contents) {
            minDimension = Math.min(minDimension, Math.min(sprite.width(), sprite.height()));
            int lowestBit = Math.min(Integer.lowestOneBit(sprite.width()), Integer.lowestOneBit(sprite.height()));
            if (lowestBit < mipBlockSize) {
                FornaxMod.LOGGER.warn("Texture {} with size {}x{} limits mip level from {} to {}",
                        sprite.name(), sprite.width(), sprite.height(),
                        Mth.log2(mipBlockSize), Mth.log2(lowestBit));
                mipBlockSize = lowestBit;
            }
        }
        int finalBlockSize = Math.min(minDimension, mipBlockSize);
        int loweredLevel = Mth.log2(finalBlockSize);
        if (loweredLevel < requestedMipLevel) {
            FornaxMod.LOGGER.warn("{}: dropping miplevel from {} to {}, because of minimum power of two: {}",
                    atlasLocation, requestedMipLevel, loweredLevel, finalBlockSize);
            return loweredLevel;
        }
        return requestedMipLevel;
    }

    /**
     * The full paged stitch: plans the strip-reserving page layout via
     * {@link BlockAtlasPaging#planWithReservedStrip}, gives every spilled STATIC sprite a
     * page-cell ghost, packs every spilled ANIMATED sprite's ghost into the
     * {@linkplain BlockAtlasGhostLayout#ANIMATED_CELL animated cell} with its own quarter-scale
     * {@code Stitcher} (an animated sprite's overflow-page placement, if any, is simply never
     * composited or sampled -- see {@link BlockAtlasGhostSprite#animated}), and materializes
     * vanilla-consumable {@code Preparations} plus the published layout.
     *
     * <p>Callers reach this only once the pack is known to overflow one page; a
     * {@link BlockAtlasPaging.PagingException} out of here means the pack cannot be paged at all
     * (too many pages, or the animated cell itself overflowed) and the caller must fall back to
     * vanilla's own stitch and its own failure behavior.
     */
    public static Takeover takeover(Identifier atlasLocation, List<SpriteContents> contents,
                                    int canvasSize, int mipLevel, int anisotropicLevel,
                                    long budgetBytes, Executor executor) {
        // Placement alignment boosted to the sprite-bounds grid's cell size (and 4x that for
        // overflow pages, so quarter-scale ghost rects land on cell boundaries too) -- see
        // BlockAtlasPaging.planWithReservedStrip's doc for the one-rect-per-cell collision this
        // makes impossible. The REAL mip level still flows into Preparations unchanged below.
        //
        // The grid resolution is picked here rather than fixed, because that alignment decides how
        // much of each page gets filled: a 32-texel cell fits 1024 sprites of 256px where perfect
        // packing fits 4096, a 4-texel cell fits 3122. Trying the cheap grid first means only packs
        // that need the expensive one pay for it.
        int gridSize = 0;
        BlockAtlasPaging.Result<SpriteContents> result = null;
        BlockAtlasPaging.PagingException lastFailure = null;
        for (int candidate : dev.icehunter.fornax.pipeline.SpriteBoundsTexture.SIZE_LADDER) {
            int cellTexels = Math.max(1, canvasSize / candidate);
            int pageZeroAlignMip = Mth.log2(Math.max(1 << mipLevel, cellTexels));
            int overflowAlignMip = Mth.log2(Math.max(1 << mipLevel,
                    cellTexels << BlockAtlasGhostLayout.GHOST_SHIFT));
            // The grid comes out of the same budget as the pages, so a finer grid leaves room for
            // fewer of them. Without this the ladder would trade a 1536 MB page for a 512 MB grid
            // and call it a saving.
            long gridBytes = (long) candidate * candidate * 4 * Float.BYTES * 2;
            // The budget buys OVERFLOW pages. Page 0 is the atlas vanilla allocates with or without
            // paging, so charging it here would refuse a pack over memory it was going to spend
            // either way. planWithReservedStrip counts page 0 in its cap, hence the +1.
            int allowedPages = 1 + BlockAtlasPageBudget.maxPages(
                    Math.max(0L, budgetBytes - gridBytes), 1.0, canvasSize, canvasSize,
                    BlockAtlasGhostLayout.MAX_OVERFLOW_PAGES);
            try {
                result = BlockAtlasPaging.planWithReservedStrip(
                        contents, canvasSize, BlockAtlasGhostLayout.pageZeroStitchHeight(canvasSize),
                        pageZeroAlignMip, overflowAlignMip, anisotropicLevel, allowedPages);
                gridSize = candidate;
                break;
            } catch (BlockAtlasPaging.PagingException tooCoarse) {
                lastFailure = tooCoarse;
            }
        }
        if (result == null) {
            // Nothing held it. Rethrow the finest grid's failure: the coarser ones overstate how
            // far short the pack fell.
            throw lastFailure;
        }
        // NOT applied here: SpriteBoundsTexture.useGridSize() closes a live GPU texture, and this
        // method runs on the stitch's background executor, not the render thread (confirmed via the
        // reload pipeline's own CompletableFuture chain -- SpriteLoader.loadAndStitch schedules
        // stitch() on the background executor, only the later sprite-map update on the game one).
        // gridSize instead rides BlockAtlasPagedLayout to BlockAtlasOverflow.rebuild, which is
        // render-thread-only and applies it there.
        if (gridSize != dev.icehunter.fornax.pipeline.SpriteBoundsTexture.DEFAULT_SIZE) {
            // Worth a line: this is where one pack quietly costs hundreds of MB more than another
            // at the same resolution, and the reason is sprite count, not sprite size.
            FornaxMod.LOGGER.info("[Fornax] Paged block atlas: sprite-bounds grid raised to {}"
                            + " ({} MB). {} sprite(s) need a finer placement pitch than {} allows",
                    gridSize, (long) gridSize * gridSize * 16 * 2 / (1024 * 1024), contents.size(),
                    dev.icehunter.fornax.pipeline.SpriteBoundsTexture.DEFAULT_SIZE);
        }
        int overflowPages = result.pages().size() - 1;
        if (overflowPages < 1) {
            throw new IllegalArgumentException(
                    "takeover reached for a pack that fits one page -- the fit check owns that path");
        }

        Map<Identifier, TextureAtlasSprite> regions = new HashMap<>();
        List<BlockAtlasGhostSprite> ghosts = new ArrayList<>();
        List<SpriteContents> spilledAnimated = new ArrayList<>();
        result.pages().get(0).gatherSprites((entry, x, y, padding) ->
                regions.put(entry.name(), TextureAtlasSpriteInvoker.fornax$create(
                        atlasLocation, entry, canvasSize, canvasSize, x, y, padding)));
        for (int page = 1; page < result.pages().size(); page++) {
            int overflowPage = page;
            result.pages().get(page).gatherSprites((entry, x, y, padding) -> {
                if (entry.isAnimated()) {
                    spilledAnimated.add(entry);
                    return;
                }
                BlockAtlasGhostSprite ghost = BlockAtlasGhostSprite.spilled(
                        atlasLocation, entry, canvasSize, overflowPage, x, y, padding);
                regions.put(entry.name(), ghost);
                ghosts.add(ghost);
            });
        }
        placeAnimatedGhosts(atlasLocation, spilledAnimated, canvasSize, mipLevel, anisotropicLevel, gridSize,
                regions, ghosts);

        TextureAtlasSprite missing = regions.get(MissingTextureAtlasSprite.getLocation());
        CompletableFuture<Void> readyForUpload = CompletableFuture.runAsync(
                () -> regions.values().forEach(sprite -> sprite.contents().increaseMipLevel(mipLevel)), executor);
        SpriteLoader.Preparations preparations = new SpriteLoader.Preparations(
                canvasSize, canvasSize, mipLevel, missing, regions, readyForUpload);
        BlockAtlasPagedLayout layout = new BlockAtlasPagedLayout(
                canvasSize, mipLevel, overflowPages, ghosts, gridSize);
        return new Takeover(preparations, layout);
    }

    /**
     * A spilled animated sprite's quarter-scale PADDED footprint, registered with the animated
     * cell's own {@code Stitcher}. The page stitches' padding is in full-scale texels, so the cell
     * stitch runs with zero stitcher padding and this entry reserves the whole quarter-scale padded
     * box itself (rounded up) -- its placement origin is then directly the ghost origin the
     * standard ghost math (content at {@code origin + padding/4}) expects.
     */
    private record GhostCellEntry(SpriteContents contents, int pagePadding) implements Stitcher.Entry {
        @Override
        public int width() {
            return (contents.width() + 2 * pagePadding + 3) >> BlockAtlasGhostLayout.GHOST_SHIFT;
        }

        @Override
        public int height() {
            return (contents.height() + 2 * pagePadding + 3) >> BlockAtlasGhostLayout.GHOST_SHIFT;
        }

        @Override
        public Identifier name() {
            return contents.name();
        }
    }

    /**
     * Packs spilled animated sprites' ghosts into the animated cell with vanilla's own
     * {@code Stitcher} at quarter scale (same mip alignment and padding as the page stitches, so
     * ghost origins stay mip-shift exact). Throws {@link BlockAtlasPaging.PagingException} if the
     * cell cannot hold them all -- the caller treats that as "cannot page this pack".
     */
    private static void placeAnimatedGhosts(Identifier atlasLocation, List<SpriteContents> spilledAnimated,
                                            int canvasSize, int mipLevel, int anisotropicLevel,
                                            int boundsGridSize,
                                            Map<Identifier, TextureAtlasSprite> regions,
                                            List<BlockAtlasGhostSprite> ghosts) {
        if (spilledAnimated.isEmpty()) {
            return;
        }
        int cellSize = canvasSize >> BlockAtlasGhostLayout.GHOST_SHIFT;
        // Stitcher padding 0: each GhostCellEntry reserves its own quarter-scale PADDED box (see
        // that record's doc), so the placement origin IS the ghost origin. Alignment boosted to
        // the sprite-bounds grid's cell size (these placements are direct strip coordinates) --
        // same collision rationale as the page stitchers above.
        int boundsCellTexels = Math.max(1, canvasSize / boundsGridSize);
        int cellAlignMip = Mth.log2(Math.max(
                1 << Math.max(0, mipLevel - BlockAtlasGhostLayout.GHOST_SHIFT), boundsCellTexels));
        Stitcher<GhostCellEntry> cellStitcher = new Stitcher<>(cellSize, cellSize, cellAlignMip, 0);
        Map<Identifier, SpriteContents> byName = new HashMap<>();
        for (SpriteContents animated : spilledAnimated) {
            GhostCellEntry entry = new GhostCellEntry(animated, anisotropicLevel);
            if (entry.height() > cellSize || entry.width() > cellSize) {
                throw new BlockAtlasPaging.PagingException(List.of(entry), 0);
            }
            cellStitcher.registerSprite(entry);
            byName.put(animated.name(), animated);
        }
        try {
            cellStitcher.stitch();
        } catch (StitcherException cellOverflow) {
            // Partial placement recovery is pointless here: an animated sprite without a ghost has
            // NO correct rendering anywhere, so the whole takeover must be refused.
            throw new BlockAtlasPaging.PagingException(List.copyOf(spilledAnimated), 0);
        }
        int[] placedCount = {0};
        cellStitcher.gatherSprites((entry, x, y, padding) -> {
            SpriteContents animated = byName.get(entry.name());
            BlockAtlasGhostSprite ghost = BlockAtlasGhostSprite.animated(
                    atlasLocation, animated, canvasSize, x, y, anisotropicLevel);
            regions.put(animated.name(), ghost);
            ghosts.add(ghost);
            placedCount[0]++;
        });
        if (placedCount[0] != spilledAnimated.size() || cellStitcher.getHeight() > cellSize) {
            throw new BlockAtlasPaging.PagingException(List.copyOf(spilledAnimated), 0);
        }
    }
}
