package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bucketing/percentile math is pure and directly testable without touching the atomic counters
 * themselves -- {@link PaletteSizeHistogram#record} is exercised live by real harvests instead (see
 * {@code SectionHarvesterTest}'s coverage of {@code SectionHarvester.harvest}, which now calls
 * {@code record} on every path).
 */
class PaletteSizeHistogramTest {
    @Test
    void bucketBoundsAreTheDocumentedSequence() {
        assertArrayEquals(
                new int[] {1, 2, 4, 8, 16, 32, 48, 64, 96, 128, 192, 256},
                PaletteSizeHistogram.BUCKET_UPPER_BOUNDS);
    }

    @Test
    void exactBoundaryValuesLandInTheirOwnBucket() {
        int[] bounds = PaletteSizeHistogram.BUCKET_UPPER_BOUNDS;
        for (int i = 0; i < bounds.length; i++) {
            assertEquals(i, PaletteSizeHistogram.bucketIndexFor(bounds[i]),
                    "an exact bound value (" + bounds[i] + ") must land in its own bucket, not the next one");
        }
    }

    @Test
    void valueOneAboveABoundaryLandsInTheNextBucket() {
        assertEquals(0, PaletteSizeHistogram.bucketIndexFor(1));
        assertEquals(1, PaletteSizeHistogram.bucketIndexFor(2));
        assertEquals(2, PaletteSizeHistogram.bucketIndexFor(3), "3 exceeds bucket 1's bound of 2");
        assertEquals(2, PaletteSizeHistogram.bucketIndexFor(4));
        assertEquals(3, PaletteSizeHistogram.bucketIndexFor(5), "5 exceeds bucket 2's bound of 4");
        assertEquals(4, PaletteSizeHistogram.bucketIndexFor(9), "9 exceeds bucket 3's bound of 8");
        assertEquals(7, PaletteSizeHistogram.bucketIndexFor(64));
        assertEquals(8, PaletteSizeHistogram.bucketIndexFor(65), "65 exceeds bucket 7's bound of 64");
    }

    @Test
    void theRealHarvesterCapLandsInItsOwnBucketNotTheFallback() {
        // MAX_PALETTE_ENTRIES (96) is itself one of BUCKET_UPPER_BOUNDS's own values, unlike back when
        // the constant equaled the array's last bound (256) -- so the harvester's cap must resolve to
        // that exact bucket, not fall through past it to the last bucket (256) the way an out-of-range
        // value would. Computed dynamically (not a hardcoded index) so this stays correct if either
        // MAX_PALETTE_ENTRIES or the bucket table changes again.
        int expectedIndex = -1;
        for (int i = 0; i < PaletteSizeHistogram.BUCKET_UPPER_BOUNDS.length; i++) {
            if (PaletteSizeHistogram.BUCKET_UPPER_BOUNDS[i] == SectionHarvester.MAX_PALETTE_ENTRIES) {
                expectedIndex = i;
                break;
            }
        }
        assertTrue(expectedIndex >= 0,
                "MAX_PALETTE_ENTRIES must be one of BUCKET_UPPER_BOUNDS's own values for this test (and "
                        + "the histogram) to mean anything -- got " + SectionHarvester.MAX_PALETTE_ENTRIES);
        assertEquals(expectedIndex, PaletteSizeHistogram.bucketIndexFor(SectionHarvester.MAX_PALETTE_ENTRIES));
    }

    @Test
    void aValueAboveTheLastBoundStillFallsBackToTheLastBucketInsteadOfThrowing() {
        // Defensive only -- SectionHarvester.harvest never actually produces a size above
        // MAX_PALETTE_ENTRIES, let alone above this array's last bound (256), but the bucketing
        // function itself must not throw/ArrayIndexOutOfBounds on a hypothetical caller.
        assertEquals(PaletteSizeHistogram.BUCKET_UPPER_BOUNDS.length - 1,
                PaletteSizeHistogram.bucketIndexFor(9000));
    }

    @Test
    void percentileBucketUpperBoundPicksTheSmallestBoundCoveringTheTargetFraction() {
        // 10 sections total: 5 in bucket size<=1, 3 in size<=4, 2 in size<=256. Cumulative fractions:
        // <=1 covers 50%, <=4 covers 80%, <=256 covers 100%.
        long[] counts = new long[PaletteSizeHistogram.BUCKET_UPPER_BOUNDS.length];
        counts[0] = 5; // size<=1
        counts[2] = 3; // size<=4
        counts[11] = 2; // size<=256
        long total = 10;

        assertEquals(1, PaletteSizeHistogram.percentileBucketUpperBound(counts, total, 0.50),
                "p50 lands exactly on the 50% cumulative bucket (size<=1)");
        assertEquals(4, PaletteSizeHistogram.percentileBucketUpperBound(counts, total, 0.51),
                "just past 50% needs the next bucket that actually covers it");
        assertEquals(4, PaletteSizeHistogram.percentileBucketUpperBound(counts, total, 0.80));
        assertEquals(256, PaletteSizeHistogram.percentileBucketUpperBound(counts, total, 0.95));
        assertEquals(256, PaletteSizeHistogram.percentileBucketUpperBound(counts, total, 0.99));
    }

    @Test
    void percentileBucketUpperBoundWithZeroTotalReturnsZeroRatherThanDividingByZero() {
        long[] counts = new long[PaletteSizeHistogram.BUCKET_UPPER_BOUNDS.length];
        assertEquals(0, PaletteSizeHistogram.percentileBucketUpperBound(counts, 0, 0.50));
    }

    @Test
    void vramBytesForCandidateScalesWithEntrySizeAndSlotCount() {
        // 64 entries at diameter 25 (15625 slots) x 64 bytes/entry = 64,000,000 bytes = 61.03... MiB,
        // matching the worked example in the task brief ("64 entries -> 61.0 MiB").
        long bytes = PaletteSizeHistogram.vramBytesForCandidate(64);
        assertEquals(64L * 64L * 15625L, bytes);
        double mib = bytes / (1024.0 * 1024.0);
        assertTrue(Math.abs(mib - 61.035) < 0.01, "expected ~61.0 MiB, got " + mib);
    }
}
