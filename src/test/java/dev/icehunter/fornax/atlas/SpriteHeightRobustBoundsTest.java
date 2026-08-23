package dev.icehunter.fornax.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trimmed labPBR height bounds a sprite publishes alongside its true min/max.
 *
 * <p>WHY THIS IS WORTH A TEST. The true range is defined by two texels, so a single stray one sets
 * the rescale for a whole sprite -- and the failure is silent and plausible-looking: brick mortar
 * reads flat because the useful signal was squeezed into the top of a range one outlier held open.
 * The trimmed pair exists to remove that, and it is only worth anything if it is exact. These are
 * counted percentiles over an 8-bit histogram, so "exact" is checkable rather than approximate.
 *
 * <p>The DEGENERATE cases carry as much weight as the ordinary one. A quarter of the sprites in the
 * user's packs have a uniform height field, and one build has two thirds; a rule that divides by
 * zero, or that quietly widens a collapsed range to keep its own arithmetic tidy, would invent
 * relief that no texture contains. What these pin is that such a sprite reports a zero-width span --
 * the signal a shader falls back on -- rather than anything cleverer.
 */
class SpriteHeightRobustBoundsTest {
    private static int[] histogramOf(int... values) {
        int[] histogram = new int[256];
        for (int value : values) {
            histogram[value]++;
        }
        return histogram;
    }

    private static int[] uniformOver(int lo, int hi, int perValue) {
        int[] histogram = new int[256];
        for (int value = lo; value <= hi; value++) {
            histogram[value] = perValue;
        }
        return histogram;
    }

    private static long total(int[] histogram) {
        long n = 0;
        for (int count : histogram) {
            n += count;
        }
        return n;
    }

    @Test
    void oneStrayTexelMovesTheTrueRangeAndCannotMoveTheTrimmedOne() {
        // The shape the whole change is for, at the shape `block/bricks` really has: a dense bulk
        // with a thin deep tail. 990 texels spread over 200..255, ten sitting far below at 40.
        int[] histogram = uniformOver(200, 255, 18);   // 56 values x 18 = 1008 texels
        histogram[40] = 10;
        long texels = total(histogram);

        assertEquals(40, NormalMapAtlasReloadListener.trueBound(histogram, true),
                "the true minimum is the stray texel, by definition");
        assertEquals(255, NormalMapAtlasReloadListener.trueBound(histogram, false));

        int[] robust = NormalMapAtlasReloadListener.robustBounds(histogram, texels);
        assertTrue(robust[0] >= 200,
                "the trimmed low bound must sit inside the bulk, not on the stray texels; got "
                        + robust[0]);
        // And the point of it: the span the pack rescales against shrinks by more than a third,
        // which is the contrast that was being spent on 1% of the texels.
        int trueSpan = 255 - 40;
        int robustSpan = robust[1] - robust[0];
        assertTrue(robustSpan * 2 < trueSpan,
                "trimming should more than halve this span: true " + trueSpan + ", trimmed "
                        + robustSpan);
    }

    @Test
    void theTrimIsCountedExactlyRatherThanEstimated() {
        // 1000 texels, one per value over 0..999 -> but alpha is 8-bit, so: 200 texels spread one
        // per value over 28..227. Nearest-rank p1 of 200 samples is the 2nd smallest, p99 the 198th.
        int[] histogram = uniformOver(28, 227, 1);
        int[] robust = NormalMapAtlasReloadListener.robustBounds(histogram, 200);
        assertArrayEquals(new int[] {29, 225}, robust,
                "p1 of 200 samples is rank 2 (value 29); p99 is rank 198 (value 225)");
    }

    @Test
    void theBoundsAreAlwaysRealAlphaCodesInsideTheTrueRange() {
        int[] histogram = histogramOf(10, 10, 10, 200, 200, 250, 250, 250, 250, 255);
        long texels = total(histogram);
        int[] robust = NormalMapAtlasReloadListener.robustBounds(histogram, texels);
        int trueLow = NormalMapAtlasReloadListener.trueBound(histogram, true);
        int trueHigh = NormalMapAtlasReloadListener.trueBound(histogram, false);
        assertTrue(robust[0] >= trueLow && robust[1] <= trueHigh,
                "the trimmed pair can never reach outside the true pair");
        assertTrue(histogram[robust[0]] > 0 && histogram[robust[1]] > 0,
                "both bounds must be alpha codes some texel actually carries -- they rescale texels");
    }

    @Test
    void aUniformSpriteReportsAZeroSpanRatherThanAnInventedOne() {
        // A quarter of the sprites in the user's packs are exactly this, and one build is two
        // thirds. A zero span is the shader's signal to leave the height alone; widening it here to
        // avoid a divide would give a flat texture relief it does not have.
        int[] histogram = uniformOver(214, 214, 4096);
        int[] robust = NormalMapAtlasReloadListener.robustBounds(histogram, 4096);
        assertArrayEquals(new int[] {214, 214}, robust);
        assertEquals(214, NormalMapAtlasReloadListener.trueBound(histogram, true));
        assertEquals(214, NormalMapAtlasReloadListener.trueBound(histogram, false));
    }

    @Test
    void aSpriteWhoseTrimmedRangeCollapsesStillPublishesAUsableTrueRange() {
        // `wildflowers_stem` at 64x, the one sprite in 524 where trimming loses to the true range:
        // over 99% of it sits at a single value with a thin bright stem above. p1 == p99, so the
        // trimmed pair is degenerate -- and the true pair, published unchanged beside it, is not.
        // This is the case the fallback ladder in the shader exists for.
        int[] histogram = uniformOver(214, 214, 4090);
        for (int value = 215; value <= 255; value++) {
            histogram[value] = 1;
        }
        long texels = total(histogram);
        int[] robust = NormalMapAtlasReloadListener.robustBounds(histogram, texels);
        assertEquals(robust[0], robust[1], "the trimmed pair collapses on this sprite");
        assertTrue(NormalMapAtlasReloadListener.trueBound(histogram, false)
                > NormalMapAtlasReloadListener.trueBound(histogram, true),
                "the true pair must still be usable, or the sprite loses its rescale entirely");
    }

    @Test
    void singleTexelAndEmptyRectsDegradeInsteadOfDividingByZero() {
        int[] one = histogramOf(137);
        assertArrayEquals(new int[] {137, 137},
                NormalMapAtlasReloadListener.robustBounds(one, 1));
        assertArrayEquals(new int[] {0, 0},
                NormalMapAtlasReloadListener.robustBounds(new int[256], 0),
                "an empty rect reports the same zero span an unrecorded sprite does");
    }

    @Test
    void aSmallSpriteStillTrimsAtLeastOneTexelAtEachEnd() {
        // 16x16 is the smallest block sprite. 1% of 256 texels is 2.56, so nearest-rank trims 3 at
        // the bottom and 3 at the top -- the rule must not round down to zero and silently become
        // the true range on exactly the sprites where one bad texel matters most.
        int[] histogram = uniformOver(0, 255, 1);
        int[] robust = NormalMapAtlasReloadListener.robustBounds(histogram, 256);
        assertTrue(robust[0] > 0 && robust[1] < 255,
                "a 16x16 sprite must still be trimmed at both ends; got "
                        + robust[0] + ".." + robust[1]);
    }

    @Test
    void theTrimPercentIsTheOneTheMeasurementsChose() {
        // Pinned so the constant cannot drift away from the data recorded in its own doc. p2 costs
        // 2.5x more of a well-authored sprite's range for 7% more recovered span; p5 destroys them.
        assertEquals(1.0, NormalMapAtlasReloadListener.ROBUST_TRIM_PERCENT, 0.0);
    }
}
