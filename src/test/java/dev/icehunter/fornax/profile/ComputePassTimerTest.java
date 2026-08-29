package dev.icehunter.fornax.profile;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputePassTimerTest {
    private static final class FakeQueries implements ComputePassTimer.QueryResults {
        private final Long[] values = new Long[6];
        private boolean closed;

        void resolve(int slot, long start, long end) {
            values[slot * 2] = start;
            values[slot * 2 + 1] = end;
        }

        @Override
        public OptionalLong tryRead(int queryIndex) {
            Long value = values[queryIndex];
            return value == null ? OptionalLong.empty() : OptionalLong.of(value);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void matchingCompletedSlotRecordsElapsedMilliseconds() {
        FrameProfiler profiler = new FrameProfiler();
        FakeQueries queries = new FakeQueries();
        ComputePassTimer timer = new ComputePassTimer(profiler, "clouds", queries, 2.0f, 64);

        queries.resolve(2, 1_000, 2_500);
        timer.markSubmitted(2);
        timer.drainCompleted(2);

        FrameProfiler.Stat stat = profiler.snapshot().getFirst();
        assertEquals("clouds", stat.label());
        // 1,500 ticks * 2 ns/tick = 3,000 ns = 0.003 ms.
        assertEquals(0.003, stat.avgMs(), 1e-12);
    }

    @Test
    void slotIsNotReadBeforeItsMatchingSubmissionCompletes() {
        FrameProfiler profiler = new FrameProfiler();
        FakeQueries queries = new FakeQueries();
        ComputePassTimer timer = new ComputePassTimer(profiler, "clouds", queries, 1.0f, 64);

        queries.resolve(1, 10, 20);
        timer.markSubmitted(1);
        timer.drainCompleted(0);

        assertTrue(profiler.snapshot().isEmpty());
    }

    @Test
    void unresolvedCompletedQueriesRecordNothing() {
        FrameProfiler profiler = new FrameProfiler();
        FakeQueries queries = new FakeQueries();
        ComputePassTimer timer = new ComputePassTimer(profiler, "clouds", queries, 1.0f, 64);

        timer.markSubmitted(0);
        timer.drainCompleted(0);

        assertTrue(profiler.snapshot().isEmpty());
    }

    @Test
    void allThreeFrameSlotsUseIndependentQueryPairs() {
        FrameProfiler profiler = new FrameProfiler();
        FakeQueries queries = new FakeQueries();
        ComputePassTimer timer = new ComputePassTimer(profiler, "clouds", queries, 1_000.0f, 64);

        for (int slot = 0; slot < 3; slot++) {
            queries.resolve(slot, slot * 100L, slot * 100L + slot + 1L);
            timer.markSubmitted(slot);
        }
        timer.drainCompleted(2);
        timer.drainCompleted(0);
        timer.drainCompleted(1);

        FrameProfiler.Stat stat = profiler.snapshot().getFirst();
        assertEquals(3, stat.samples());
        // Durations are 1, 2 and 3 ticks at 1,000 ns/tick: average 0.002 ms.
        assertEquals(0.002, stat.avgMs(), 1e-12);
    }

    @Test
    void gpuDurationAndCpuDependencyWaitRemainSeparateRows() {
        FrameProfiler profiler = new FrameProfiler();
        FakeQueries queries = new FakeQueries();
        ComputePassTimer timer = new ComputePassTimer(profiler, "clouds", queries, 1_000_000.0f, 64);

        profiler.recordValue("compute wait clouds", 8.0);
        queries.resolve(0, 4, 6);
        timer.markSubmitted(0);
        timer.drainCompleted(0);

        assertEquals("clouds", profiler.snapshot().getFirst().label());
        assertEquals(2.0, profiler.snapshot().getFirst().avgMs(), 1e-12);
        assertEquals("compute wait clouds", profiler.valueSnapshot().getFirst().label());
        assertEquals(8.0, profiler.valueSnapshot().getFirst().value(), 1e-12);
    }

    @Test
    void closeReleasesTheOwnedQuerySource() {
        FakeQueries queries = new FakeQueries();
        ComputePassTimer timer = new ComputePassTimer(new FrameProfiler(), "clouds", queries, 1.0f, 64);

        timer.close();

        assertTrue(queries.closed);
    }

    @Test
    void zeroValidBitsDisablesTimingWithoutReadingQueries() {
        FrameProfiler profiler = new FrameProfiler();
        FakeQueries queries = new FakeQueries();
        ComputePassTimer timer = new ComputePassTimer(profiler, "clouds", queries, 1.0f, 0);

        queries.resolve(0, 10, 20);
        timer.markSubmitted(0);
        timer.drainCompleted(0);

        assertTrue(profiler.snapshot().isEmpty());
    }

    @Test
    void partialWidthMasksUndefinedHighBits() {
        FrameProfiler profiler = new FrameProfiler();
        FakeQueries queries = new FakeQueries();
        ComputePassTimer timer = new ComputePassTimer(profiler, "clouds", queries, 1_000.0f, 8);

        queries.resolve(0, 0x120AL, 0xAB14L);
        timer.markSubmitted(0);
        timer.drainCompleted(0);

        // Only the low 8 bits are defined: 0x14 - 0x0a = 10 ticks = 0.01 ms.
        assertEquals(0.01, profiler.snapshot().getFirst().avgMs(), 1e-12);
    }

    @Test
    void partialWidthCounterWrapUsesModuloDifference() {
        FrameProfiler profiler = new FrameProfiler();
        FakeQueries queries = new FakeQueries();
        ComputePassTimer timer = new ComputePassTimer(profiler, "clouds", queries, 1_000.0f, 8);

        queries.resolve(0, 250, 5);
        timer.markSubmitted(0);
        timer.drainCompleted(0);

        // An 8-bit counter advances 6 ticks to zero, then 5 more: 11 ticks = 0.011 ms.
        assertEquals(0.011, profiler.snapshot().getFirst().avgMs(), 1e-12);
    }

    @Test
    void sixtyFourBitCounterWrapAvoidsShiftBySixtyFour() {
        FrameProfiler profiler = new FrameProfiler();
        FakeQueries queries = new FakeQueries();
        ComputePassTimer timer = new ComputePassTimer(profiler, "clouds", queries, 1_000.0f, 64);

        queries.resolve(0, Long.MAX_VALUE - 2, Long.MIN_VALUE + 2);
        timer.markSubmitted(0);
        timer.drainCompleted(0);

        // Unsigned 64-bit modulo distance across the sign boundary is 5 ticks = 0.005 ms.
        assertEquals(0.005, profiler.snapshot().getFirst().avgMs(), 1e-12);
    }
}
