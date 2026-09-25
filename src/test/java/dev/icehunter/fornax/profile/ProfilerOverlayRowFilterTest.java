package dev.icehunter.fornax.profile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the profiler overlay's row filters: the panel a player sees for a given pair of toggles. */
class ProfilerOverlayRowFilterTest {
    private static List<FrameProfiler.Stat> passes(int count) {
        List<FrameProfiler.Stat> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // Slowest last, so a top-N filter that kept insertion order by accident would fail below.
            out.add(new FrameProfiler.Stat("pass" + i, i * 0.1, i * 0.2, 240));
        }
        return out;
    }

    @Test
    void passesOffHidesEveryPassRow() {
        assertTrue(ProfilerOverlay.visiblePasses(passes(4), false, false).isEmpty());
        assertTrue(ProfilerOverlay.visiblePasses(passes(4), false, true).isEmpty());
    }

    @Test
    void everyRowSurvivesWithBothFiltersOff() {
        List<FrameProfiler.Stat> all = passes(30);
        assertEquals(all, ProfilerOverlay.visiblePasses(all, true, false));
    }

    @Test
    void topOnlyKeepsTheSlowestRowsInGraphOrder() {
        List<FrameProfiler.Stat> kept = ProfilerOverlay.visiblePasses(passes(30), true, true);

        assertEquals(ProfilerOverlay.TOP_PASS_ROWS, kept.size());
        // pass20..pass29 are the ten slowest, and they come back in the order the graph ran them.
        List<String> labels = kept.stream().map(FrameProfiler.Stat::label).toList();
        assertEquals(List.of("pass20", "pass21", "pass22", "pass23", "pass24",
                "pass25", "pass26", "pass27", "pass28", "pass29"), labels);
    }

    @Test
    void topOnlyLeavesAShortPanelAlone() {
        List<FrameProfiler.Stat> few = passes(ProfilerOverlay.TOP_PASS_ROWS);
        assertEquals(few, ProfilerOverlay.visiblePasses(few, true, true));
    }

    /** RayQueryInterop publishes a Metal-tier trace timing as {@code "<pass name> metal"}: a
     * second GPU row for a RAY_QUERY pass, not a pass of its own. So the active-name check that
     * gates {@code passesOnly} must resolve it back to the declaring pass's own name. */
    @Test
    void stripMetalSuffixResolvesTheRowBackToItsDeclaringPassName() {
        assertEquals("gi_lamp_trace", ProfilerOverlay.stripMetalSuffix("gi_lamp_trace metal"));
        assertEquals("gi_lamp_trace", ProfilerOverlay.stripMetalSuffix("gi_lamp_trace"));
        assertEquals("", ProfilerOverlay.stripMetalSuffix(""));
    }
}
