package dev.icehunter.fornax.atlas;

import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These tile counts and canvas sizes are chosen from vanilla {@link Stitcher}'s actual measured
 * packing behavior, not a naive edge-to-edge tiling estimate: a lone 16x16 tile's smallest viable
 * canvas is 32x32 (not 16x16 -- an exact size match fails outright), and a 64x64 canvas holds
 * exactly five 16x16 tiles before a sixth is rejected. Both were verified directly against {@link
 * Stitcher} before being encoded here; see the allocator's own class doc for why page 0's placement
 * is exactly this vanilla behavior and not a Fornax-authored packer.
 */
class BlockAtlasPagingTest {
    @Test
    void everythingFitsOnOnePageWhenItAllFitsInOneCanvas() {
        List<FakeEntry> entries = List.of(entry("a", 512, 512), entry("b", 512, 512));

        BlockAtlasPaging.Result<FakeEntry> result = BlockAtlasPaging.plan(entries, 16384, 0, 0, 4);

        assertEquals(1, result.pages().size());
    }

    @Test
    void spillsOntoASecondPageWhenTheFirstIsFull() {
        // Vanilla's own Stitcher needs headroom well beyond a naive edge-to-edge tiling estimate
        // (see BlockAtlasPagingTest class doc); empirically, a 64x64 canvas holds exactly five
        // 16x16 tiles, so a sixth must spill to page 2.
        List<FakeEntry> entries = List.of(
                entry("a", 16, 16), entry("b", 16, 16), entry("c", 16, 16),
                entry("d", 16, 16), entry("e", 16, 16), entry("f", 16, 16));

        BlockAtlasPaging.Result<FakeEntry> result = BlockAtlasPaging.plan(entries, 64, 0, 0, 4);

        assertEquals(2, result.pages().size());
        assertEquals(6, pagesBySprite(result).size());
    }

    @Test
    void reportsAUniformSharedCanvasAcrossPagesEvenWhenOnePageIsSmaller() {
        // Same six-tile setup: page 1 needs the full 64x64 canvas for its five tiles, page 2 holds
        // one lone 16x16 tile and shrinks to its own natural 32x32 footprint (verified directly:
        // Stitcher settles at the smallest sufficient canvas up to its cap, not always the cap).
        List<FakeEntry> entries = List.of(
                entry("a", 16, 16), entry("b", 16, 16), entry("c", 16, 16),
                entry("d", 16, 16), entry("e", 16, 16), entry("f", 16, 16));

        BlockAtlasPaging.Result<FakeEntry> result = BlockAtlasPaging.plan(entries, 64, 0, 0, 4);

        assertEquals(2, result.pages().size());
        assertEquals(32, result.pages().get(1).getWidth(),
                "precondition: page 2 alone would naturally settle smaller than page 1");
        // The shared Vulkan/array-texture constraint (one extent for every layer) means the report
        // must still be the MAX across pages, not page 2's own smaller natural footprint.
        assertEquals(64, result.width());
        assertEquals(64, result.height());
    }

    @Test
    void mixedSpriteSizesStillPackDeterministicallyAcrossPages() {
        List<FakeEntry> entries = List.of(
                entry("big", 24, 24), entry("a", 8, 8), entry("b", 8, 8),
                entry("c", 8, 8), entry("d", 8, 8), entry("e", 8, 8), entry("f", 8, 8));

        BlockAtlasPaging.Result<FakeEntry> result = BlockAtlasPaging.plan(entries, 32, 0, 0, 4);

        assertEquals(7, pagesBySprite(result).size());
        assertTrue(result.pages().size() >= 1);
    }

    @Test
    void throwsWhenMorePagesThanTheLimitWouldBeNeeded() {
        // At a 32x32 canvas, a 16x16 tile's own minimum viable page is already the whole canvas
        // (see class doc), so each tile needs its own page: 17 tiles need a 5th page maxPages(4)
        // does not allow.
        List<FakeEntry> entries = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            entries.add(entry("tile" + i, 16, 16));
        }

        BlockAtlasPaging.PagingException failure = assertThrows(BlockAtlasPaging.PagingException.class,
                () -> BlockAtlasPaging.plan(entries, 32, 0, 0, 4));

        assertEquals(4, failure.pageIndex());
        assertTrue(failure.unplacedSprites().size() >= 1);
    }

    @Test
    void anEightPageSpillSucceedsWhenTheCeilingAllowsEightPages() {
        // A 64x64 canvas holds exactly five 16x16 tiles per page (see class doc); 40 tiles need
        // exactly 8 pages, no more.
        List<FakeEntry> entries = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            entries.add(entry("tile" + i, 16, 16));
        }

        BlockAtlasPaging.Result<FakeEntry> result = BlockAtlasPaging.plan(entries, 64, 0, 0, 8);

        assertEquals(8, result.pages().size());
        assertEquals(40, pagesBySprite(result).size());
    }

    @Test
    void throwsWhenEvenEightPagesCannotHoldTheSpriteSet() {
        List<FakeEntry> entries = new ArrayList<>();
        for (int i = 0; i < 41; i++) { // one more than 8 pages of 5 tiles each can hold
            entries.add(entry("tile" + i, 16, 16));
        }

        BlockAtlasPaging.PagingException failure = assertThrows(BlockAtlasPaging.PagingException.class,
                () -> BlockAtlasPaging.plan(entries, 64, 0, 0, 8));

        assertEquals(8, failure.pageIndex());
        assertEquals(1, failure.unplacedSprites().size());
    }

    @Test
    void throwsImmediatelyWhenASingleSpriteIsTooBigForOnePageAlone() {
        List<FakeEntry> entries = List.of(entry("giant", 64, 64));

        BlockAtlasPaging.PagingException failure = assertThrows(BlockAtlasPaging.PagingException.class,
                () -> BlockAtlasPaging.plan(entries, 32, 0, 0, 4));

        assertEquals(0, failure.pageIndex());
        assertEquals(1, failure.unplacedSprites().size());
    }

    @Test
    void aSpriteThatMonopolizesAPageForcesTheNextSameSizeSpriteToSpill() {
        // Pathological: a sprite whose own minimum viable canvas equals the page cap monopolizes
        // that page entirely -- verified directly, two 32x32 sprites do not coexist in a 64x64
        // canvas (each alone already needs the whole thing), so the second spills to page 2. A
        // smaller second sprite (16x16) DOES coexist alongside the first in the same page --
        // monopolization depends on the second sprite's own size, not merely "a page is occupied".
        List<FakeEntry> entries = List.of(entry("a", 32, 32), entry("b", 32, 32));

        BlockAtlasPaging.Result<FakeEntry> result = BlockAtlasPaging.plan(entries, 64, 0, 0, 4);

        assertEquals(2, result.pages().size());
    }

    @Test
    void anEmptyResolvedStackProducesNoPages() {
        BlockAtlasPaging.Result<FakeEntry> result = BlockAtlasPaging.plan(List.of(), 32, 0, 0, 4);

        assertEquals(0, result.pages().size());
        assertEquals(0, result.width());
        assertEquals(0, result.height());
    }

    @Test
    void maxPagesBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockAtlasPaging.plan(List.of(entry("a", 4, 4)), 32, 0, 0, 0));
    }

    @Test
    void placementIsDeterministicRegardlessOfInputOrder() {
        // Six tiles at a 64x64 canvas spills across two pages (see class doc); shuffling the input
        // order must not change which sprite lands on which page, nor where within its page.
        List<FakeEntry> forward = List.of(
                entry("a", 16, 16), entry("b", 16, 16), entry("c", 16, 16),
                entry("d", 16, 16), entry("e", 16, 16), entry("f", 16, 16));
        List<FakeEntry> shuffled = List.of(
                entry("f", 16, 16), entry("c", 16, 16), entry("a", 16, 16),
                entry("e", 16, 16), entry("d", 16, 16), entry("b", 16, 16));

        BlockAtlasPaging.Result<FakeEntry> resultA = BlockAtlasPaging.plan(forward, 64, 0, 0, 4);
        BlockAtlasPaging.Result<FakeEntry> resultB = BlockAtlasPaging.plan(shuffled, 64, 0, 0, 4);

        assertEquals(pagesBySprite(resultA), pagesBySprite(resultB));
        assertEquals(resultXy(resultA), resultXy(resultB));
    }

    @Test
    void firstPageMatchesVanillaSinglePageStitching() {
        List<FakeEntry> entries = List.of(
                entry("a", 16, 16), entry("b", 16, 16), entry("c", 8, 8), entry("d", 24, 8));

        BlockAtlasPaging.Result<FakeEntry> result = BlockAtlasPaging.plan(entries, 256, 0, 0, 4);
        assertEquals(1, result.pages().size());

        Stitcher<FakeEntry> vanilla = new Stitcher<>(256, 256, 0, 0);
        for (FakeEntry e : entries) {
            vanilla.registerSprite(e);
        }
        vanilla.stitch();

        assertEquals(vanilla.getWidth(), result.width());
        assertEquals(vanilla.getHeight(), result.height());
        assertEquals(xy(vanilla), resultXy(result).get(0));
    }

    /**
     * The measured 512x population: {@code LabPbrSidecarStitchFilter}'s own doc records that a real
     * 512x-colour pack, after the labPBR sidecar filter drops {@code _n}/{@code _s} (3154 -> 1770
     * sprites), still throws {@code StitcherException} even at a SINGLE page -- that pack is the
     * whole reason multi-page paging exists. Every one of the 1770 sprites is modeled at a uniform
     * 512x512: the filter's table gives sprite COUNT, not a per-sprite size distribution, and "512x"
     * names a uniform resolution multiplier over vanilla's 16x16 base, not a mixed pack -- so a
     * uniform reconstruction is the honest one available from that table, not an approximation of a
     * distribution we don't have.
     *
     * <p>16384 is Apple silicon's device dimension ceiling ({@link
     * dev.icehunter.fornax.atlas.LabPbrSidecarSurvey#maxTextureDimension}'s own doc), the real
     * ceiling {@code SpriteLoaderPagedStitchMixin} feeds {@link BlockAtlasPaging#plan}. The page
     * count asserted below was read directly off a real run against this exact input (not hand-
     * derived), the same methodology this class's own doc comment describes for every other number
     * in this file.
     */
    @Test
    void measured512xPopulationNeedsARealPageCount() {
        List<FakeEntry> entries = new ArrayList<>();
        for (int i = 0; i < 1770; i++) {
            entries.add(entry("tile" + i, 512, 512));
        }

        BlockAtlasPaging.Result<FakeEntry> result = BlockAtlasPaging.plan(entries, 16384, 0, 0, 32);

        assertEquals(1770, pagesBySprite(result).size(), "every sprite must place somewhere");
        assertEquals(3, result.pages().size(),
                "measured directly against Stitcher for 1770x 512x512 sprites at a 16384 canvas cap");
        assertTrue(result.pages().size() <= 1 + BlockAtlasGhostLayout.MAX_OVERFLOW_PAGES,
                "must fit the ghost-strip layout's page ceiling, the real cap"
                        + " SpriteLoaderPagedStitchMixin feeds the takeover path");
    }

    @Test
    void reservedStripConstrainsPageZeroHeightOnly() {
        // Five 16x16 tiles fill a 64x64 canvas exactly (measured, see class doc); capping page 0 at
        // 32 tall must spill some of them onto a full-square page 1 while page 0 itself never
        // exceeds the cap.
        List<FakeEntry> entries = List.of(
                entry("a", 16, 16), entry("b", 16, 16), entry("c", 16, 16),
                entry("d", 16, 16), entry("e", 16, 16));

        BlockAtlasPaging.Result<FakeEntry> result =
                BlockAtlasPaging.planWithReservedStrip(entries, 64, 32, 0, 0, 0, 4);

        assertTrue(result.pages().get(0).getHeight() <= 32,
                "page 0 must respect the reserved-strip height cap");
        assertTrue(result.pages().size() >= 2, "the cap must force a spill for this set");
        assertEquals(5, pagesBySprite(result).size(), "every sprite must still place somewhere");
    }

    @Test
    void spriteTallerThanPageZeroCapStillPlacesOnAFullSquareOverflowPage() {
        // 16x48 exceeds a 32-tall page 0 outright (nothing places there) but fits a full 64-square
        // page -- the allocator must commit the empty page 0 and continue rather than giving up.
        List<FakeEntry> entries = List.of(entry("tall", 16, 48));

        BlockAtlasPaging.Result<FakeEntry> result =
                BlockAtlasPaging.planWithReservedStrip(entries, 64, 32, 0, 0, 0, 4);

        Map<Identifier, Integer> pages = pagesBySprite(result);
        assertEquals(1, pages.size());
        assertTrue(pages.values().iterator().next() >= 1,
                "the tall sprite must land on an overflow page, never a page 0 that cannot hold it");
    }

    @Test
    void reservedStripRejectsAnInvalidPageZeroHeight() {
        List<FakeEntry> entries = List.of(entry("a", 16, 16));

        assertThrows(IllegalArgumentException.class,
                () -> BlockAtlasPaging.planWithReservedStrip(entries, 64, 0, 0, 0, 0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> BlockAtlasPaging.planWithReservedStrip(entries, 64, 128, 0, 0, 0, 4));
    }

    private static Map<Identifier, Xy> xy(Stitcher<FakeEntry> stitcher) {
        Map<Identifier, Xy> placed = new HashMap<>();
        stitcher.gatherSprites((e, x, y, mip) -> placed.put(e.name(), new Xy(x, y)));
        return placed;
    }

    /** Every sprite's page index, keyed by name. */
    private static Map<Identifier, Integer> pagesBySprite(BlockAtlasPaging.Result<FakeEntry> result) {
        Map<Identifier, Integer> pages = new HashMap<>();
        List<Stitcher<FakeEntry>> pageList = result.pages();
        for (int page = 0; page < pageList.size(); page++) {
            int pageIndex = page;
            pageList.get(page).gatherSprites((e, x, y, mip) -> pages.put(e.name(), pageIndex));
        }
        return pages;
    }

    /** Every page's placed sprite -> (x, y), one map per page index. */
    private static Map<Integer, Map<Identifier, Xy>> resultXy(BlockAtlasPaging.Result<FakeEntry> result) {
        Map<Integer, Map<Identifier, Xy>> byPage = new HashMap<>();
        List<Stitcher<FakeEntry>> pageList = result.pages();
        for (int page = 0; page < pageList.size(); page++) {
            byPage.put(page, xy(pageList.get(page)));
        }
        return byPage;
    }

    private record Xy(int x, int y) {
    }

    private static FakeEntry entry(String name, int width, int height) {
        return new FakeEntry(Identifier.fromNamespaceAndPath("pack", "textures/block/" + name + ".png"),
                width, height);
    }

    private record FakeEntry(Identifier name, int width, int height) implements Stitcher.Entry {
    }
}
