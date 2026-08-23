package dev.icehunter.fornax.atlas;

import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.StitcherException;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Packs a resolved sprite list across as many atlas pages as it takes, so a resource pack whose
 * combined sprites cannot fit vanilla's single-atlas dimension ceiling spills onto additional
 * pages instead of {@link StitcherException} aborting the whole reload.
 *
 * <p>This is deliberately NOT a hand-rolled bin-packer: each page's actual placement decision is
 * vanilla's own {@link Stitcher}, used exactly as vanilla uses it for a single atlas today. The
 * only logic that belongs to this class is the RETRY LOOP across pages -- register everything still
 * unplaced onto a fresh {@link Stitcher}, let it place as much as fits, recover what it placed even
 * when it eventually throws {@link StitcherException} for what didn't, and hand the remainder to
 * the next page. Page 0 built this way is byte-identical to what vanilla would produce for the same
 * sprite set today: same class, same comparator, same padding math, nothing of Fornax's own in the
 * placement decision. See {@link BlockAtlasPagingTest#firstPageMatchesVanillaSinglePageStitching}.
 *
 * <p>Pure and device-free (the {@link #plan} pattern this engine uses for {@code CameraJitter}/
 * {@code ShadowCamera}): no GPU handle, no I/O, no mutable static state. {@code maxPages} is a
 * caller-supplied ceiling rather than a constant here -- see {@link BlockAtlasPageBudget} for
 * deriving it from a VRAM budget -- so this class never needs to know why a caller picked the
 * number it did.
 *
 * <p>Public since Phase 2 (package-private through Phase 1): {@code
 * dev.icehunter.fornax.mixin.vanilla.SpriteLoaderPagedStitchMixin} is the first live caller,
 * running this allocator for real against every vanilla block-atlas stitch. Nothing about the class
 * changed to earn that -- it was always meant to be driven from outside {@code atlas} once a live
 * phase existed to call it.
 */
public final class BlockAtlasPaging {
    private BlockAtlasPaging() {
    }

    /**
     * @param width  the shared canvas width every page's {@link Stitcher} was constrained to reach
     *               for -- the MAX across pages, not each page's own footprint, because every layer
     *               of one array texture must share one extent
     * @param height the shared canvas height, by the same max-across-pages rule
     * @param pages  one {@link Stitcher} per page, in placement order; page 0 is first
     */
    public record Result<T extends Stitcher.Entry>(int width, int height, List<Stitcher<T>> pages) {
        public Result {
            pages = List.copyOf(pages);
        }
    }

    /** Thrown when {@code maxPages} pages still could not hold every sprite. */
    public static final class PagingException extends RuntimeException {
        private final transient List<? extends Stitcher.Entry> unplacedSprites;
        private final int pageIndex;

        PagingException(List<? extends Stitcher.Entry> unplacedSprites, int pageIndex) {
            super("could not place " + unplacedSprites.size() + " sprite(s) within " + pageIndex
                    + " page(s)");
            this.unplacedSprites = List.copyOf(unplacedSprites);
            this.pageIndex = pageIndex;
        }

        /** The sprites still unplaced when paging gave up. */
        public List<? extends Stitcher.Entry> unplacedSprites() {
            return this.unplacedSprites;
        }

        /** How many pages had already been committed (or attempted) when paging gave up. */
        public int pageIndex() {
            return this.pageIndex;
        }
    }

    /**
     * @param entries          the resolved sprite list (see {@link BlockAtlasSpriteMeasurement})
     * @param maxTextureSize   the device's max single-texture dimension, same ceiling vanilla's own
     *                         stitch is constrained to
     * @param mipLevel         forwarded to {@link Stitcher} unchanged, same as vanilla's own stitch
     * @param anisotropicLevel forwarded to {@link Stitcher} unchanged, same as vanilla's own stitch
     * @param maxPages         the hard ceiling on how many pages this call may create
     * @throws PagingException if {@code maxPages} pages still cannot hold every entry, including
     *                          the case where a single entry cannot fit an otherwise-empty page
     */
    public static <T extends Stitcher.Entry> Result<T> plan(List<T> entries, int maxTextureSize, int mipLevel,
                                                              int anisotropicLevel, int maxPages) {
        return planPages(entries, maxTextureSize, maxTextureSize, mipLevel, anisotropicLevel, maxPages);
    }

    /**
     * The {@link #plan} variant the GHOST-STRIP layout uses (see {@link BlockAtlasGhostLayout}):
     * page 0's stitch is constrained to {@code maxTextureSize x pageZeroMaxHeight} so the strip
     * below it stays free for ghosts, while overflow pages keep the full square canvas. Callers
     * choose this shape only once they already know the pack overflows -- a fitting pack must go
     * through {@link #plan} (or vanilla's own stitch) so its canvas stays bit-identical to today.
     *
     * <p>{@code pageZeroAlignMip}/{@code overflowAlignMip} are the {@code Stitcher} mip-alignment
     * parameters, SEPARATED from the real mip level on purpose: the stitcher uses this value only
     * to round padded boxes up to {@code 1 << mip} multiples (decompile:
     * {@code smallestFittingMinTexel}), and the paged layout must round to the SPRITE-BOUNDS GRID
     * instead. That grid ({@code SpriteBoundsTexture.SIZE} cells across the canvas) can only store
     * ONE rect per cell, so any placement pitch that does not divide the cell size leaves every
     * sprite-boundary cell shared between two sprites -- last writer wins, the loser's edge strip
     * fails the shader's rect-contains-uv gate, and POM shuts off in a band along every sprite
     * edge (live-caught as displaced-looking double-border seams at block boundaries). Aligning
     * page-0 placements to whole cells -- and overflow placements to 4x that, so their
     * quarter-scale GHOST rects land on cell boundaries too -- makes the collision impossible by
     * construction. A larger alignment is strictly compatible with the real mip level's own
     * requirement (any multiple of a power of two is a multiple of the smaller ones).
     */
    public static <T extends Stitcher.Entry> Result<T> planWithReservedStrip(List<T> entries, int maxTextureSize,
                                                                             int pageZeroMaxHeight,
                                                                             int pageZeroAlignMip,
                                                                             int overflowAlignMip,
                                                                             int anisotropicLevel, int maxPages) {
        if (pageZeroMaxHeight < 1 || pageZeroMaxHeight > maxTextureSize) {
            throw new IllegalArgumentException("pageZeroMaxHeight must be in 1.." + maxTextureSize
                    + ", got " + pageZeroMaxHeight);
        }
        return planPages(entries, maxTextureSize, pageZeroMaxHeight, pageZeroAlignMip, overflowAlignMip,
                anisotropicLevel, maxPages);
    }

    private static <T extends Stitcher.Entry> Result<T> planPages(List<T> entries, int maxTextureSize,
                                                                  int pageZeroMaxHeight, int mipLevel,
                                                                  int anisotropicLevel, int maxPages) {
        return planPages(entries, maxTextureSize, pageZeroMaxHeight, mipLevel, mipLevel,
                anisotropicLevel, maxPages);
    }

    private static <T extends Stitcher.Entry> Result<T> planPages(List<T> entries, int maxTextureSize,
                                                                  int pageZeroMaxHeight, int pageZeroAlignMip,
                                                                  int overflowAlignMip,
                                                                  int anisotropicLevel, int maxPages) {
        if (maxPages < 1) {
            throw new IllegalArgumentException("maxPages must be at least 1");
        }
        List<T> remaining = BlockAtlasSpriteMeasurement.measure(entries);
        List<Stitcher<T>> pages = new ArrayList<>();
        int width = 0;
        int height = 0;

        while (!remaining.isEmpty()) {
            if (pages.size() >= maxPages) {
                throw new PagingException(remaining, pages.size());
            }

            int pageMaxHeight = pages.isEmpty() ? pageZeroMaxHeight : maxTextureSize;
            int pageAlignMip = pages.isEmpty() ? pageZeroAlignMip : overflowAlignMip;
            Stitcher<T> stitcher = new Stitcher<>(maxTextureSize, pageMaxHeight, pageAlignMip, anisotropicLevel);
            // Entries taller than this page's height cap never register on it: measured directly
            // against vanilla's Stitcher (pinned by BlockAtlasPagingTest), maxHeight is NOT a hard
            // per-sprite reject -- an oversized sprite can be placed on a canvas grown past the
            // cap, which for page 0 would mean placement inside the reserved ghost strip. Filtering
            // here keeps such sprites in `remaining` for the next (full-height) page.
            int registered = 0;
            for (T entry : remaining) {
                if (entry.height() <= pageMaxHeight) {
                    stitcher.registerSprite(entry);
                    registered++;
                }
            }
            try {
                if (registered > 0) {
                    stitcher.stitch();
                }
            } catch (StitcherException tooManyForThisPage) {
                // Placement up to the failing entry is already in the Stitcher's own storage --
                // gatherSprites below reports exactly what succeeded, nothing more.
            }
            if (stitcher.getHeight() > pageMaxHeight) {
                // Defense for the property the filter above should have made impossible: a page
                // that outgrew its cap would overwrite the ghost strip, so refuse loudly rather
                // than ship a corrupt layout.
                throw new PagingException(remaining, pages.size());
            }

            Set<Identifier> placed = new HashSet<>();
            stitcher.gatherSprites((entry, x, y, mip) -> placed.add(entry.name()));
            if (placed.isEmpty()) {
                // A fresh, otherwise-empty page placed nothing: the largest remaining sprite alone
                // cannot fit this page's canvas ceiling. With a reserved strip that can differ
                // between page 0 and the rest, but past page 0 no further page will help either.
                if (pages.isEmpty() && pageZeroMaxHeight < maxTextureSize) {
                    // Page 0's reduced canvas alone rejected (or never saw) it; a full-square
                    // overflow page may still hold it. Commit the empty page-0 stitcher and move on.
                    pages.add(stitcher);
                    continue;
                }
                throw new PagingException(remaining, pages.size());
            }

            width = Math.max(width, stitcher.getWidth());
            height = Math.max(height, stitcher.getHeight());
            pages.add(stitcher);
            remaining = remaining.stream().filter(entry -> !placed.contains(entry.name())).toList();
        }

        return new Result<>(width, height, pages);
    }
}
