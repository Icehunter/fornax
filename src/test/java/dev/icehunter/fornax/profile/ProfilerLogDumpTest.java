package dev.icehunter.fornax.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfilerLogDumpTest {
    @Test
    void formatRendersHeaderRowsRuleAndFrameTotal() {
        List<FrameProfiler.Stat> stats = List.of(
                new FrameProfiler.Stat("ssao_raw", 0.910, 1.220, 240),
                new FrameProfiler.Stat("reconstruct", 1.030, 1.400, 240));

        String expected = String.join("\n",
                "[Fornax] Frame profile (ms, budget 11.100):",
                "  PASS                  AVG      P95  GRADE",
                "  ssao_raw            0.910    1.220    GRN",
                "  reconstruct         1.030    1.400    GRN",
                "  ----------------------------------------",
                "  frame               9.870    9.870    YEL");

        assertEquals(expected, ProfilerLogDump.format(stats, 9.870));
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
    }

    @Test
    void formatWithNoPassesStillRendersFrameRow() {
        String expected = String.join("\n",
                "[Fornax] Frame profile (ms, budget 11.100):",
                "  PASS                  AVG      P95  GRADE",
                "  ----------------------------------------",
                "  frame               0.500    0.500    GRN");

        assertEquals(expected, ProfilerLogDump.format(List.of(), 0.5));
    }
}
