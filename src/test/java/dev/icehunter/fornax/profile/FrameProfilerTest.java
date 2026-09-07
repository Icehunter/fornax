package dev.icehunter.fornax.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameProfilerTest {
    @Test
    void presentationCountsDistinguishRealAndGeneratedCallsAndReset() {
        FrameProfiler p = new FrameProfiler();
        p.recordPresentation(false);
        p.recordPresentation(true);
        p.recordPresentation(false);
        assertEquals(List.of(new FrameProfiler.ValueStat("real present calls", 2.0),
                new FrameProfiler.ValueStat("generated present calls", 1.0)), p.valueSnapshot());
        p.reset();
        assertTrue(p.valueSnapshot().isEmpty());
        p.recordPresentation(true);
        assertEquals(List.of(new FrameProfiler.ValueStat("generated present calls", 1.0)), p.valueSnapshot());
    }


    @Test
    void gpuTimingsKeepSourceFramesWhenResultsArriveOutOfOrder() {
        FrameProfiler p = new FrameProfiler();
        long first = p.beginRenderFrame();
        long second = p.beginRenderFrame();
        p.recordGpu("clouds", second, "Vulkan compute", 90, 120, 2.0, 36, 0.000060);
        p.recordGpu("resolve", first, "Vulkan graphics", 20, 40, 1.0, 64, 0.000020);
        p.recordGpu("clouds", first, "Vulkan compute", 10, 19, 2.0, 36, 0.000018);

        List<FrameProfiler.GpuTiming> snapshot = p.snapshotGpuTimings();
        assertEquals(List.of(first, first, second), snapshot.stream().map(FrameProfiler.GpuTiming::frameId).toList());
        assertEquals(List.of("clouds", "resolve", "clouds"), snapshot.stream().map(FrameProfiler.GpuTiming::label).toList());
        FrameProfiler.GpuTiming timing = snapshot.getFirst();
        assertEquals("Vulkan compute", timing.queue());
        assertEquals(10, timing.beginTicks());
        assertEquals(19, timing.endTicks());
        assertEquals(2.0, timing.timestampPeriodNs());
        assertEquals(36, timing.timestampValidBits());
        assertEquals(0.000018, timing.elapsedMs());
        assertEquals(2, p.snapshot().getFirst().samples(), "GPU samples must still feed the rolling statistics");
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        p.beginRenderFrame();
        assertEquals(3, snapshot.size(), "an exported snapshot must not track later mutations");
    }

    @Test
    void gpuTimelineRetainsRenderFramesInsteadOfReadbackArrivalFrames() {
        FrameProfiler p = new FrameProfiler();
        long first = p.beginRenderFrame();
        p.recordGpu("first", first, "Vulkan compute", 1, 2, 1.0, 64, 1.0);
        for (int i = 1; i < FrameProfiler.WINDOW; i++) {
            p.beginRenderFrame();
        }
        assertEquals(1, p.snapshotGpuTimings().size());
        p.beginRenderFrame();
        assertTrue(p.snapshotGpuTimings().isEmpty());
        p.recordGpu("late", first, "Vulkan compute", 1, 2, 1.0, 64, 1.0);
        assertTrue(p.snapshotGpuTimings().isEmpty());
        assertTrue(p.snapshot().stream().noneMatch(stat -> stat.label().equals("late")));
        long current = p.currentRenderFrameId();
        p.recordGpu("current", current, "Vulkan graphics", 3, 4, 1.0, 64, 1.0);
        assertEquals(current, p.snapshotGpuTimings().getFirst().frameId());
    }

    @Test
    void resetDoesNotReuseFrameIdsOrAdmitOldPendingResults() {
        FrameProfiler p = new FrameProfiler();
        assertEquals(0, p.currentRenderFrameId());
        long before = p.beginRenderFrame();
        p.recordGpu("frame", before, "Vulkan graphics", 1, 2, 1.0, 64, 1.0);
        p.reset();
        assertEquals(0, p.currentRenderFrameId());
        assertTrue(p.snapshotGpuTimings().isEmpty());
        long after = p.beginRenderFrame();
        assertTrue(after > before, "outstanding timer rings must never alias a new frame after reset");
        p.recordGpu("old", before, "Vulkan graphics", 1, 2, 1.0, 64, 1.0);
        p.recordGpu("future", after + 1, "Vulkan compute", 1, 2, 1.0, 64, 1.0);
        assertTrue(p.snapshotGpuTimings().isEmpty());
        assertTrue(p.snapshot().isEmpty());
        p.recordGpu("new", after, "Vulkan graphics", 3, 4, 1.0, 64, 1.0);
        assertEquals("new", p.snapshotGpuTimings().getFirst().label());
    }

    @Test
    void rawWrappedTicksArePreservedWithTheirCounterWidth() {
        FrameProfiler p = new FrameProfiler();
        long frame = p.beginRenderFrame();
        // The timer already decoded the eight-bit counter's wrap: (3 - 250) mod 256 = 9 ticks.
        p.recordGpu("wrapped", frame, "Vulkan compute", 250, 3, 1.0, 8, 0.000009);
        FrameProfiler.GpuTiming timing = p.snapshotGpuTimings().getFirst();
        assertEquals(250, timing.beginTicks());
        assertEquals(3, timing.endTicks());
        assertEquals(8, timing.timestampValidBits());
        assertEquals(0.000009, timing.elapsedMs());
    }

    @Test
    void gpuTimelineHasAFinitePerFrameCapacity() {
        FrameProfiler p = new FrameProfiler();
        long frame = p.beginRenderFrame();
        for (int i = 0; i <= FrameProfiler.MAX_GPU_TIMINGS_PER_FRAME; i++) {
            p.recordGpu("pass", frame, "Vulkan graphics", i, i + 1, 1.0, 64, 1.0);
        }
        assertEquals(FrameProfiler.MAX_GPU_TIMINGS_PER_FRAME, p.snapshotGpuTimings().size());
        assertEquals(1.0, p.valueSnapshot().stream().filter(value -> value.label().equals("gpu timeline drops"))
                .findFirst().orElseThrow().value());
    }

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
