package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavioural contract for the water-plane scan and its guard, both pure: no level, no player,
 * no GPU, matching {@link WaterSurfaceTracker}'s testing style. */
class WaterPlaneProbeTest {

    /** A column of blocks by absolute Y: {@code heights[y - baseY]} is the fluid's rendered
     * height there, or negative when that block is not water. */
    private static WaterPlaneProbe.ScanResult scanColumn(int feetY, int baseY, double[] heights) {
        return WaterPlaneProbe.scan(feetY, WaterPlaneProbe.SCAN_BOUND_BLOCKS,
                y -> {
                    int index = y - baseY;
                    return index >= 0 && index < heights.length ? heights[index] : -1.0;
                });
    }

    @Test
    void findsWaterAtTheFeetBlockItself() {
        // baseY 90, feet at 100: heights[10] is the feet block's reading.
        double[] heights = new double[20];
        java.util.Arrays.fill(heights, -1.0);
        heights[10] = 0.889;

        var result = scanColumn(100, 90, heights);
        assertTrue(result.valid());
        assertEquals(100.889f, result.height(), 0.0001f);
    }

    @Test
    void findsWaterBelowFeetWithinTheBound() {
        double[] heights = new double[20];
        java.util.Arrays.fill(heights, -1.0);
        // Feet at 100, water 5 blocks down at 95, within the 8-block bound.
        heights[5] = 1.0;

        var result = scanColumn(100, 90, heights);
        assertTrue(result.valid());
        assertEquals(96.0f, result.height(), 0.0001f, "block 95 plus a full-height source");
    }

    @Test
    void stopsAtTheBoundAndReportsNoWaterBeyondIt() {
        double[] heights = new double[20];
        java.util.Arrays.fill(heights, -1.0);
        // Feet at 100, water 9 blocks down (91): one past SCAN_BOUND_BLOCKS (8).
        heights[1] = 1.0;

        var result = scanColumn(100, 90, heights);
        assertFalse(result.valid(), "water exists but sits one block past the scan bound");
        assertEquals(0.0f, result.height());
    }

    @Test
    void aWaterColumnRightAtTheBoundStillCounts() {
        double[] heights = new double[20];
        java.util.Arrays.fill(heights, -1.0);
        // Feet at 100, water 8 blocks down (92): the last block the bound covers.
        heights[2] = 0.5;

        var result = scanColumn(100, 90, heights);
        assertTrue(result.valid());
        assertEquals(92.5f, result.height(), 0.0001f);
    }

    @Test
    void noWaterAnywhereInRangeGivesAnInvalidResult() {
        double[] heights = new double[20];
        java.util.Arrays.fill(heights, -1.0);

        var result = scanColumn(100, 90, heights);
        assertFalse(result.valid());
        assertEquals(0.0f, result.height());
    }

    @Test
    void theTopmostWaterInRangeWinsOverAFartherOne() {
        double[] heights = new double[20];
        java.util.Arrays.fill(heights, -1.0);
        heights[10] = 0.2; // feet block itself, checked first
        heights[3] = 0.9;  // farther down; must not override the feet-block hit

        var result = scanColumn(100, 90, heights);
        assertTrue(result.valid());
        assertEquals(100.2f, result.height(), 0.0001f, "the scan stops at the first hit, nearest the feet");
    }

    @Test
    void shouldProbeRequiresALevelAPlayerAndADryEye() {
        assertTrue(WaterPlaneProbe.shouldProbe(true, true, false));
        assertFalse(WaterPlaneProbe.shouldProbe(false, true, false), "no level");
        assertFalse(WaterPlaneProbe.shouldProbe(true, false, false), "no player");
        assertFalse(WaterPlaneProbe.shouldProbe(true, true, true), "submerged eye has no plane to look at from outside");
    }

    @Test
    void noLevelOrNoFeetBlockReturnsTheSharedZero() {
        assertSame(WaterPlaneProbe.ZERO, WaterPlaneProbe.read(null, null));
    }

    // --- The standing-surface scan, using the same scan() core. Its callback returns whatever a
    // real one would compute from BlockState.getShape: a bottom slab's 0.5, farmland's 15/16, a
    // full block's 1.0. These tests exercise the scan mechanism against those numbers without
    // needing a level or a block at all. ---------------------------------------------------------

    @Test
    void fullBlockUnderfootStopsAtTheNextBlocksOwnTop() {
        // Feet at 100 (air, standing position); a full block at 99, shape max Y 1.0, so the
        // standing surface sits at 99 + 1.0 = 100, at feet level.
        double[] heights = new double[20];
        java.util.Arrays.fill(heights, -1.0);
        heights[9] = 1.0; // block 99

        var result = scanColumn(100, 90, heights);
        assertTrue(result.valid());
        assertEquals(100.0f, result.height(), 0.0001f, "a full block's own top sits at feet level");
    }

    @Test
    void bottomSlabUnderfootLandsAtHalfABlock() {
        double[] heights = new double[20];
        java.util.Arrays.fill(heights, -1.0);
        heights[9] = 0.5; // block 99, a bottom slab's shape max Y

        var result = scanColumn(100, 90, heights);
        assertTrue(result.valid());
        assertEquals(99.5f, result.height(), 0.0001f);
    }

    @Test
    void farmlandLandsFifteenSixteenthsUpNotAFullBlock() {
        double[] heights = new double[20];
        java.util.Arrays.fill(heights, -1.0);
        heights[9] = 15.0 / 16.0; // block 99, farmland's shape max Y

        var result = scanColumn(100, 90, heights);
        assertTrue(result.valid());
        assertEquals(99.0f + 15.0f / 16.0f, result.height(), 0.0001f,
                "farmland draws (and this scan reports) 1/16 short of a full block");
    }

    // --- combine(): the two-plane logic. No level, no block, two constructed ScanResults. --------

    private static WaterPlaneProbe.Values combine(WaterPlaneProbe.ScanResult water,
                                                   WaterPlaneProbe.ScanResult stand) {
        return WaterPlaneProbe.combine(water, stand);
    }

    private static WaterPlaneProbe.ScanResult valid(float height) {
        return new WaterPlaneProbe.ScanResult(true, height);
    }

    private static WaterPlaneProbe.ScanResult invalid() {
        return new WaterPlaneProbe.ScanResult(false, 0.0f);
    }

    @Test
    void dockPlankOverWaterKeepsBothLanesAndRendersThePlank() {
        // The plank sits above the water it's built over, so its standing height is the higher
        // number. Render picks the standing plane whenever it validates, the plank, not the
        // water: rendering about the lower plane was rejected by the offline model (section 5, 87
        // of 252 plank receivers survived compared to 252 of 252 for the standing plane). The
        // water consumer reads z (waterHeight) and shifts its lookups from it; it does not change
        // which plane this class renders.
        var result = combine(valid(90.5f), valid(93.0f)); // water 90.5, plank top 93.0
        assertEquals(1.0f, result.valid());
        assertEquals(93.0f, result.renderHeight(), 0.0001f, "the standing plane, not the water");
        assertEquals(90.5f, result.waterHeight(), 0.0001f);
        assertEquals(93.0f, result.standHeight(), 0.0001f);
    }

    @Test
    void bridgeOverARiverIsTheSameShapeAsTheDockPlank() {
        // A bridge deck over a river: the same relationship as a dock plank, at a different
        // scale, to confirm this is not a dock-specific special case.
        var result = combine(valid(60.9f), valid(65.0f));
        assertEquals(1.0f, result.valid());
        assertEquals(65.0f, result.renderHeight(), 0.0001f, "the deck, not the river");
    }

    @Test
    void wadingHitsTheSameWaterBlockFromBothScansAtTheSameHeight() {
        // Standing in shallow water: both scans hit the same fluid block at the same Y, so both
        // lanes read identically and the render height equals either one.
        var result = combine(valid(100.889f), valid(100.889f));
        assertEquals(1.0f, result.valid());
        assertEquals(100.889f, result.renderHeight(), 0.0001f);
        assertEquals(result.waterHeight(), result.standHeight(), 0.0001f, "h_stand == h_water while wading");
    }

    @Test
    void airborneWithinTheBoundStillFindsWhicheverPlaneIsThere() {
        // Jumping, with the ground (standing plane) 3 blocks down and no water anywhere: only the
        // standing plane validates, and render must equal its height (the original single-plane
        // contract), not run through arithmetic against the other's sentinel value.
        var result = combine(invalid(), valid(97.0f));
        assertEquals(1.0f, result.valid());
        assertEquals(97.0f, result.renderHeight(), 0.0001f);
        assertEquals(WaterPlaneProbe.NO_PLANE, result.waterHeight());
    }

    @Test
    void airborneBeyondTheBoundIsInvalid() {
        // Neither scan found anything within SCAN_BOUND_BLOCKS: both come back invalid.
        var result = combine(invalid(), invalid());
        assertEquals(0.0f, result.valid());
        assertEquals(WaterPlaneProbe.NO_PLANE, result.waterHeight());
        assertEquals(WaterPlaneProbe.NO_PLANE, result.standHeight());
    }

    @Test
    void noSurfaceAtAllIsTheSameInvalidResultAsAirborneBeyondTheBound() {
        // A void world: identical inputs, identical answer. Nothing distinguishes "nothing below"
        // from "the bound ran out first" at the combine() level, by design.
        var result = combine(invalid(), invalid());
        assertEquals(0.0f, result.valid());
        assertEquals(0.0f, result.renderHeight());
    }

    @Test
    void onlyWaterValidatingIsBitIdenticalToTheOriginalSinglePlaneDesign() {
        var result = combine(valid(62.5f), invalid());
        assertEquals(1.0f, result.valid());
        assertEquals(62.5f, result.renderHeight(), 0.0f, "exact, not merely close: no arithmetic applied");
        assertEquals(WaterPlaneProbe.NO_PLANE, result.standHeight());
    }
}
