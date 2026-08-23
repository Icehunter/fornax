package dev.icehunter.fornax.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the per-axis render-scale multiplier each TAAU tier drives the render target to. */
class TaauRatioTest {
    @Test
    void perAxisScaleMatchesEachTier() {
        assertEquals(0.77f, TaauRatio.QUALITY.perAxisScale(), 1e-6f);
        assertEquals(0.67f, TaauRatio.BALANCED.perAxisScale(), 1e-6f);
        assertEquals(0.58f, TaauRatio.PERFORMANCE.perAxisScale(), 1e-6f);
    }

    @Test
    void haltonSequenceLengthUnchangedByThisTask() {
        // perAxisScale() lands alongside the existing Halton length CameraJitter already
        // depends on -- pinning both here so a future edit can't silently drop one field while
        // touching the other.
        assertEquals(8, TaauRatio.QUALITY.haltonSequenceLength());
        assertEquals(12, TaauRatio.BALANCED.haltonSequenceLength());
        assertEquals(16, TaauRatio.PERFORMANCE.haltonSequenceLength());
    }
}
