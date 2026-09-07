package dev.icehunter.fornax.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilerLogDumpTest {
    @Test
    void dumpUsesRollingFrameStatisticsAndSampleCounts() {
        FrameProfiler p = new FrameProfiler();
        p.record("frame", 2.0);
        p.record("frame", 8.0);
        p.record("cpu world recording", 4.0);
        p.recordValue("real present calls", 12.0);

        String dump = ProfilerLogDump.format(p, "presentation calls; scanout is unmeasured");
        String frame = dump.lines().filter(line -> line.stripLeading().startsWith("frame "))
                .findFirst().orElseThrow();
        assertTrue(frame.matches("\\s*frame\\s+5\\.000\\s+8\\.000\\s+2\\s+GRN"), frame);
        assertTrue(dump.contains("SAMPLES"));
        assertTrue(dump.contains("cpu world recording"));
        assertTrue(dump.contains("real present calls"));
        assertTrue(dump.contains("12.000"));
        assertTrue(dump.contains("presentation calls; scanout is unmeasured"));
    }

    @Test
    void legacyScalarIsLabelledLatestInsteadOfInventingAnAverageAndPercentile() {
        String dump = ProfilerLogDump.format(List.of(new FrameProfiler.Stat("resolve", 1.0, 2.0, 3)), 9.870);
        assertTrue(dump.contains("Latest frame: 9.870 ms"));
        assertFalse(dump.lines().anyMatch(line -> line.stripLeading().startsWith("frame ")));
        assertFalse(dump.contains("9.870    9.870"));
    }

    @Test
    void dumpIncludesOnlyThirtyMostRecentDistinctSourceFrames() {
        FrameProfiler p = new FrameProfiler();
        for (int i = 0; i < 35; i++) {
            long frame = p.beginRenderFrame();
            p.recordGpu("clouds", frame, "Vulkan compute", frame * 10, frame * 10 + 3, 2.0, 36, 0.000006);
            p.recordGpu("frame", frame, "Vulkan graphics", frame * 10, frame * 10 + 5, 1.0, 64, 0.000005);
        }
        String dump = ProfilerLogDump.format(p, "");
        List<String> rows = dump.lines().filter(line -> line.startsWith("  gpu frame=")).toList();
        assertEquals(60, rows.size(), "thirty source IDs, two resolved rows for each");
        assertFalse(rows.stream().anyMatch(line -> line.startsWith("  gpu frame=5 ")));
        assertTrue(rows.getFirst().startsWith("  gpu frame=6 label=clouds queue=Vulkan compute "));
        assertTrue(rows.getLast().contains("frame=35 label=frame queue=Vulkan graphics"));
        assertTrue(dump.contains("beginTicks=60 endTicks=63 periodNs=2.000000 validBits=36"));
        assertTrue(dump.contains("partial"), "a frame row does not prove every queue has drained");
        assertTrue(dump.contains("may share a physical queue"));
        assertTrue(dump.contains("not total GPU busy time"));
        assertTrue(dump.contains("excludes HUD and presentation"));
    }

    @Test
    void dumpKeepsComputeOnlyResolvedFramesAndDoesNotClaimCompletion() {
        FrameProfiler p = new FrameProfiler();
        long frame = p.beginRenderFrame();
        p.recordGpu("clouds", frame, "Vulkan compute", 20, 30, 1.0, 64, 0.000010);
        String dump = ProfilerLogDump.format(p, "");
        assertTrue(dump.contains("gpu frame=1 label=clouds"));
        assertFalse(dump.contains("completed frames"));
    }

    @Test
    void emptyDumpHasNoFabricatedZeroFrameStatistic() {
        String dump = ProfilerLogDump.format(new FrameProfiler(), "");
        assertTrue(dump.contains("No timing samples"));
        assertFalse(dump.lines().anyMatch(line -> line.stripLeading().startsWith("frame ")));
    }

    @Test
    void gradeBoundaries() {
        assertEquals("RED", ProfilerLogDump.grade(11.1));
        assertEquals("YEL", ProfilerLogDump.grade(6.7));
        assertEquals("GRN", ProfilerLogDump.grade(6.65));
    }

    @Test
    void gradeExactBoundaryValues() {
        // 90% and 60% of the 11.1ms budget exactly.
        assertEquals("RED", ProfilerLogDump.grade(9.99));
        assertEquals("YEL", ProfilerLogDump.grade(6.66));
        assertEquals("GRN", ProfilerLogDump.grade(6.65));
    }
}
