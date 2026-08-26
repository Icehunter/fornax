package dev.icehunter.fornax.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockAtlasPageBudgetTest {
    @Test
    void bytesPerPageAccountsForAlbedoPlusTwoFullResolutionSidecarsAndTheMipChain() {
        // 4096x4096 albedo: 16,777,216 texels. Neither atlas builder downsamples an overflow page's
        // normal/material sidecars, so all three atlases cost the same texel count; the sum is then
        // scaled by the 4/3 mip-chain factor.
        long expected = Math.round(3 * 16_777_216L * 4.0 * (4.0 / 3.0));

        assertEquals(expected, BlockAtlasPageBudget.bytesPerPage(4096, 4096));
    }

    @Test
    void nonPositivePageDimensionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BlockAtlasPageBudget.bytesPerPage(0, 4096));
        assertThrows(IllegalArgumentException.class, () -> BlockAtlasPageBudget.bytesPerPage(4096, -1));
    }

    @Test
    void maxPagesReturnsZeroRatherThanLyingThatOnePageFitsOnAStarvedBudget() {
        // 1 byte cannot hold even one overflow page at any real page size. maxPages used to floor
        // at 1 regardless, which let BlockAtlasPagedStitch.takeover plan a page the budget itself
        // said could not be afforded; it now reports the honest 0 and the caller's own +1 for page
        // 0 (allocated regardless of paging) is what keeps a starved device from being refused
        // outright.
        int pages = BlockAtlasPageBudget.maxPages(1L, 1.0, 16384, 16384, 64);

        assertEquals(0, pages);
    }

    @Test
    void maxPagesIsCappedByTheHardCeilingRegardlessOfVram() {
        // An enormous VRAM budget would otherwise afford far more than the ceiling allows.
        int pages = BlockAtlasPageBudget.maxPages(1_000_000_000_000L, 1.0, 256, 256, 4);

        assertEquals(4, pages);
    }

    @Test
    void largerVramAffordsMorePagesAtTheSamePageSize() {
        long modest = 6_000_000_000L;   // ~6 GB, a GTX 1060-class budget
        long ample = 32_000_000_000L;   // ~32 GB, a high-end desktop budget

        int modestPages = BlockAtlasPageBudget.maxPages(modest, 0.5, 16384, 16384, 64);
        int amplePages = BlockAtlasPageBudget.maxPages(ample, 0.5, 16384, 16384, 64);

        assertTrue(amplePages > modestPages);
    }

    @Test
    void budgetFractionOutsideZeroToOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockAtlasPageBudget.maxPages(1000L, 0.0, 256, 256, 4));
        assertThrows(IllegalArgumentException.class,
                () -> BlockAtlasPageBudget.maxPages(1000L, 1.5, 256, 256, 4));
    }

    @Test
    void negativeVramIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockAtlasPageBudget.maxPages(-1L, 0.5, 256, 256, 4));
    }

    @Test
    void hardCeilingBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockAtlasPageBudget.maxPages(1000L, 0.5, 256, 256, 0));
    }

    @Test
    void decideKeepsTheNaturalPageCountWhenItFitsTheBudget() {
        BlockAtlasPageBudget.Decision decision = BlockAtlasPageBudget.decide(3, 8);

        assertEquals(3, decision.pageCount());
        assertFalse(decision.vramLimited());
    }

    @Test
    void decideCapsAtTheVramCeilingAndReportsTheShortfall() {
        // MILESTONES.md's own illustrative case: "this stack needs 6 pages, 3 fit".
        BlockAtlasPageBudget.Decision decision = BlockAtlasPageBudget.decide(6, 3);

        assertEquals(3, decision.pageCount());
        assertTrue(decision.vramLimited());
    }

    @Test
    void decideRejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> BlockAtlasPageBudget.decide(-1, 4));
        assertThrows(IllegalArgumentException.class, () -> BlockAtlasPageBudget.decide(2, 0));
    }
}
