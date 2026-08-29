package dev.icehunter.fornax.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameProfilerTest {
    @Test
    void avgAndP95AndSampleCount() {
        FrameProfiler p = new FrameProfiler();
        p.record("a", 10);
        p.record("a", 20);
        p.record("a", 30);

        List<FrameProfiler.Stat> snap = p.snapshot();
        FrameProfiler.Stat a = snap.get(0);
        assertEquals("a", a.label());
        assertEquals(20.0, a.avgMs(), 1e-9);
        assertEquals(30.0, a.p95Ms(), 1e-9);
        assertEquals(3, a.samples());
    }

    @Test
    void overflowPastWindowEvictsOldest() {
        FrameProfiler p = new FrameProfiler();
        for (int i = 0; i <= FrameProfiler.WINDOW; i++) { // 241 values: 0..240
            p.record("a", i);
        }

        FrameProfiler.Stat a = p.snapshot().get(0);
        assertEquals(FrameProfiler.WINDOW, a.samples());
        // Oldest value (0) evicted; window now holds 1..240.
        double expectedAvg = 0;
        for (int i = 1; i <= FrameProfiler.WINDOW; i++) {
            expectedAvg += i;
        }
        expectedAvg /= FrameProfiler.WINDOW;
        assertEquals(expectedAvg, a.avgMs(), 1e-9);
    }

    @Test
    void frameTotalMsReturnsLatestFrameLabelValue() {
        FrameProfiler p = new FrameProfiler();
        p.record(FrameProfiler.LABEL_FRAME, 12.5);
        p.record(FrameProfiler.LABEL_FRAME, 16.0);
        assertEquals(16.0, p.frameTotalMs(), 1e-9);
    }

    @Test
    void insertionOrderAcrossLabelsPreserved() {
        FrameProfiler p = new FrameProfiler();
        p.record("terrain", 1);
        p.record("frame", 2);
        p.record("terrain", 3);

        List<FrameProfiler.Stat> snap = p.snapshot();
        assertEquals(2, snap.size());
        assertEquals("terrain", snap.get(0).label());
        assertEquals("frame", snap.get(1).label());
    }

    @Test
    void resetClearsAllState() {
        FrameProfiler p = new FrameProfiler();
        p.record("a", 10);
        p.record(FrameProfiler.LABEL_FRAME, 5);
        p.reset();
        assertEquals(0, p.snapshot().size());
        assertEquals(0.0, p.frameTotalMs(), 1e-9);
    }

    @Test
    void recordValueOverwritesRatherThanAveraging() {
        FrameProfiler p = new FrameProfiler();
        p.recordValue("voxel_pending", 24);
        p.recordValue("voxel_pending", 3);

        List<FrameProfiler.ValueStat> snap = p.valueSnapshot();
        assertEquals(1, snap.size(), "a second recordValue for the same label replaces, not accumulates");
        assertEquals(3.0, snap.get(0).value(), 1e-9);
    }

    @Test
    void valueSnapshotPreservesFirstSeenLabelOrder() {
        FrameProfiler p = new FrameProfiler();
        p.recordValue("voxel_pending", 1);
        p.recordValue("voxel_cleared", 2);
        p.recordValue("voxel_pending", 5); // re-published -- must not move to the end

        List<FrameProfiler.ValueStat> snap = p.valueSnapshot();
        assertEquals(2, snap.size());
        assertEquals("voxel_pending", snap.get(0).label());
        assertEquals("voxel_cleared", snap.get(1).label());
    }

    @Test
    void valuesAreIndependentOfMillisecondTimingSamples() {
        FrameProfiler p = new FrameProfiler();
        p.record("voxel_pending", 999); // a timing sample under the SAME label as a value below
        p.recordValue("voxel_pending", 7);

        assertEquals(1, p.snapshot().size(), "the timing sample must still show up in snapshot()");
        assertEquals(999.0, p.snapshot().get(0).avgMs(), 1e-9);
        assertEquals(1, p.valueSnapshot().size(), "the value must still show up in valueSnapshot()");
        assertEquals(7.0, p.valueSnapshot().get(0).value(), 1e-9);
    }

    @Test
    void resetClearsValuesToo() {
        FrameProfiler p = new FrameProfiler();
        p.recordValue("voxel_pending", 24);
        p.reset();
        assertEquals(0, p.valueSnapshot().size());
    }

    @Test
    void computeGpuTimeAndCpuDependencyWaitUseDistinctTimingRows() {
        FrameProfiler p = new FrameProfiler();
        p.record("clouds", 2.5);
        p.recordValue("compute wait clouds", 7.0);

        List<FrameProfiler.Stat> snap = p.snapshot();
        assertEquals(1, snap.size());
        assertEquals("clouds", snap.get(0).label());
        assertEquals(2.5, snap.get(0).avgMs(), 1e-9);
        assertEquals(1, p.valueSnapshot().size());
        assertEquals("compute wait clouds", p.valueSnapshot().getFirst().label());
        assertEquals(7.0, p.valueSnapshot().getFirst().value(), 1e-9);
    }
}
