package dev.icehunter.fornax.profile;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins PassTimer's drain-before-write ring invariant: values written in frame N must be readable at
 * frame N+FRAMES_IN_FLIGHT and must NOT be clobbered by that frame's own writes before the drain.
 * The fake pool reproduces the real backend's semantics exactly -- write() host-resets the index
 * immediately (Vulkan calls vkResetQueryPool on the host before queuing the GPU write), and a
 * written value only becomes readable once resolve() runs (the GPU catching up later).
 */
class PassTimerRingTest {
    private static final int POOL_SIZE = PassTimer.MAX_BRACKETS * 2;

    /** Mirrors GpuQueryPool + CommandEncoder.writeTimestamp semantics; see TimestampPool's contract. */
    private static final class FakePool implements TimestampPool {
        private final Long[] resolved = new Long[POOL_SIZE];
        private final Long[] pending = new Long[POOL_SIZE];
        private final TickSource ticks;

        FakePool(TickSource ticks) {
            this.ticks = ticks;
        }

        @Override
        public void write(int index) {
            resolved[index] = null; // host reset: any unread value at this index is lost NOW
            pending[index] = ticks.next();
        }

        /** The GPU catching up: pending values become readable. */
        void resolve() {
            for (int i = 0; i < POOL_SIZE; i++) {
                if (pending[i] != null) {
                    resolved[i] = pending[i];
                    pending[i] = null;
                }
            }
        }

        @Override
        public OptionalLong tryRead(int index) {
            return resolved[index] == null ? OptionalLong.empty() : OptionalLong.of(resolved[index]);
        }

        @Override
        public void close() {
        }
    }

    private static final class TickSource {
        long now;

        long next() {
            now += 100; // every consecutive timestamp is exactly 100 ticks after the previous one
            return now;
        }
    }

    private static FakePool[] newRing(TickSource ticks) {
        FakePool[] ring = new FakePool[3];
        for (int i = 0; i < ring.length; i++) {
            ring[i] = new FakePool(ticks);
        }
        return ring;
    }

    private static void runFrame(PassTimer timer, String passLabel) {
        timer.beginFrame();
        timer.bracketBegin(FrameProfiler.LABEL_FRAME);
        timer.bracketBegin(passLabel);
        timer.bracketEnd(passLabel);
        timer.bracketEnd(FrameProfiler.LABEL_FRAME);
        timer.endFrame();
    }

    @Test
    void valuesWrittenInFrameNSurviveTheRingWrapAndGetRecorded() {
        FrameProfiler profiler = new FrameProfiler();
        TickSource ticks = new TickSource();
        FakePool[] ring = newRing(ticks);
        PassTimer timer = new PassTimer(profiler, ring, 1.0f);

        // 6 frames; the GPU resolves each frame's queries well before its slot comes around again
        // (frame N's slot is reused at frame N+3). Frames 4/5/6 rotate onto slots whose values are
        // fully resolved -- if the drain ran AFTER those frames' own (host-resetting) writes, every
        // read would hit a freshly-reset index and nothing would ever be recorded.
        for (int frame = 0; frame < 6; frame++) {
            runFrame(timer, "resolve");
            for (FakePool p : ring) {
                p.resolve();
            }
        }

        List<FrameProfiler.Stat> stats = profiler.snapshot();
        assertEquals(2, stats.size(), "both labels must have recorded samples after the ring wrapped");
        FrameProfiler.Stat frameStat = stats.get(0);
        FrameProfiler.Stat passStat = stats.get(1);
        assertEquals(FrameProfiler.LABEL_FRAME, frameStat.label());
        assertEquals("resolve", passStat.label());
        assertEquals(3, frameStat.samples(), "frames 1-3 drained when slots 0-2 came around at frames 4-6");
        assertEquals(3, passStat.samples());
        // Timestamp order within a frame: frameBegin, passBegin, passEnd, frameEnd, 100 ticks apart.
        // period 1ns/tick: pass = 100 ticks = 1e-4 ms; frame = 300 ticks = 3e-4 ms.
        assertEquals(3.0e-4, frameStat.avgMs(), 1e-12);
        assertEquals(1.0e-4, passStat.avgMs(), 1e-12);
    }

    @Test
    void pendingValuesSkipCleanly() {
        FrameProfiler profiler = new FrameProfiler();
        TickSource ticks = new TickSource();
        FakePool[] ring = newRing(ticks);
        PassTimer timer = new PassTimer(profiler, ring, 1.0f);

        // GPU never catches up (resolve() never called): every drain must skip silently.
        for (int frame = 0; frame < 6; frame++) {
            runFrame(timer, "resolve");
        }
        assertEquals(0, profiler.snapshot().size());
    }

    @Test
    void mismatchedEndDropsThatFrameOnlyAndNeverMispairs() {
        FrameProfiler profiler = new FrameProfiler();
        TickSource ticks = new TickSource();
        FakePool[] ring = newRing(ticks);
        PassTimer timer = new PassTimer(profiler, ring, 1.0f);

        // Frame 0: mispaired end (ends a label that was never begun while another is open).
        timer.beginFrame();
        timer.bracketBegin(FrameProfiler.LABEL_FRAME);
        timer.bracketBegin("a");
        timer.bracketEnd("b"); // mismatch -- poisons the frame
        timer.bracketEnd(FrameProfiler.LABEL_FRAME);
        timer.endFrame();

        // Frames 1..6 are well-formed; resolve after each so their values are ready on wrap.
        for (int frame = 1; frame <= 6; frame++) {
            runFrame(timer, "resolve");
            for (FakePool p : ring) {
                p.resolve();
            }
        }

        // The poisoned frame contributed nothing; the well-formed frames all recorded.
        List<FrameProfiler.Stat> stats = profiler.snapshot();
        assertEquals(2, stats.size());
        for (FrameProfiler.Stat s : stats) {
            assertTrue(s.samples() >= 3, s.label() + " should have recorded from the healthy frames");
            assertEquals(s.samples() == 0 ? 0 : s.avgMs(), s.avgMs()); // no NaN/garbage
        }
        // Every recorded duration is one of the two legal bracket widths -- a silent mispair would
        // produce a different (wrong) width.
        assertEquals(3.0e-4, stats.get(0).avgMs(), 1e-12);
        assertEquals(1.0e-4, stats.get(1).avgMs(), 1e-12);
    }

    @Test
    void unclosedBracketAtEndFrameDropsTheFrame() {
        FrameProfiler profiler = new FrameProfiler();
        TickSource ticks = new TickSource();
        FakePool[] ring = newRing(ticks);
        PassTimer timer = new PassTimer(profiler, ring, 1.0f);

        // Frame 0 leaves LABEL_FRAME open (e.g. a pack whose graph never hits the close path).
        timer.beginFrame();
        timer.bracketBegin(FrameProfiler.LABEL_FRAME);
        timer.endFrame();

        for (int frame = 1; frame <= 6; frame++) {
            runFrame(timer, "resolve");
            for (FakePool p : ring) {
                p.resolve();
            }
        }

        FrameProfiler.Stat frameStat = profiler.snapshot().get(0);
        assertEquals(FrameProfiler.LABEL_FRAME, frameStat.label());
        assertEquals(3.0e-4, frameStat.avgMs(), 1e-12); // only healthy frames contributed
    }

    /** Opens {@code count} labeled sibling brackets, each begun then immediately ended -- the same
     * flat per-pass shape {@code GraphRunner.finish()}'s loop produces. */
    private static void openAndCloseSiblings(PassTimer timer, int count, String labelPrefix) {
        for (int p = 0; p < count; p++) {
            String label = labelPrefix + p;
            timer.bracketBegin(label);
            timer.bracketEnd(label);
        }
    }

    @Test
    void bracketsPastMaxBracketsBecomePhantomsButHealthyOnesStillDrainNormally() {
        FrameProfiler profiler = new FrameProfiler();
        TickSource ticks = new TickSource();
        FakePool[] ring = newRing(ticks);
        PassTimer timer = new PassTimer(profiler, ring, 1.0f);

        // Comfortably past MAX_BRACKETS but well under MAX_ENTRIES: guarantees an overrun into the
        // phantom region without tripping the (separate) bookkeeping-row cap.
        int totalPasses = PassTimer.MAX_BRACKETS + 8;
        for (int frame = 0; frame < 6; frame++) {
            timer.beginFrame();
            timer.bracketBegin(FrameProfiler.LABEL_FRAME);
            openAndCloseSiblings(timer, totalPasses, "pass");
            timer.bracketEnd(FrameProfiler.LABEL_FRAME);
            timer.endFrame();
            for (FakePool p : ring) {
                p.resolve();
            }
        }

        // LABEL_FRAME consumes the first timed slot, so exactly MAX_BRACKETS-1 of the per-pass
        // brackets are timed before the cap engages; the rest become phantoms and never reach the
        // profiler at all -- not as a fabricated 0ms row, simply absent.
        List<FrameProfiler.Stat> stats = profiler.snapshot();
        assertEquals(PassTimer.MAX_BRACKETS, stats.size(),
                "LABEL_FRAME + the first (MAX_BRACKETS - 1) passes should have recorded; the rest are phantoms");
        for (FrameProfiler.Stat s : stats) {
            assertEquals(3, s.samples(), s.label() + " should have recorded from all 3 ring slots");
            if (!FrameProfiler.LABEL_FRAME.equals(s.label())) {
                // Every timed sibling pass begins and ends 100 ticks apart, unlike LABEL_FRAME (which
                // spans every write in between and so has no fixed expected width here).
                assertEquals(1.0e-4, s.avgMs(), 1e-12,
                        s.label() + " must have recorded a real duration, not a phantom's absence");
            }
        }
    }

    @Test
    void mispairInsideThePhantomRegionStillPoisonsTheFrame() {
        FrameProfiler profiler = new FrameProfiler();
        TickSource ticks = new TickSource();
        FakePool[] ring = newRing(ticks);
        PassTimer timer = new PassTimer(profiler, ring, 1.0f);

        // Frame 0: overrun into the phantom region, then a genuine nesting bug -- ending a label
        // that was never begun while a phantom bracket is still open. A phantom being "free" (no
        // query index) must not make a real caller bug past the cap silently tolerated; only an
        // end that legitimately closes its own dropped begin should be.
        timer.beginFrame();
        timer.bracketBegin(FrameProfiler.LABEL_FRAME);
        openAndCloseSiblings(timer, PassTimer.MAX_BRACKETS, "warmup"); // saturates the timed budget
        timer.bracketBegin("phantomOpen"); // past the cap -> phantom, left open
        timer.bracketEnd("wrongLabel"); // mismatch: open top is "phantomOpen", not "wrongLabel"
        timer.bracketEnd(FrameProfiler.LABEL_FRAME);
        timer.endFrame();

        for (int frame = 1; frame <= 6; frame++) {
            runFrame(timer, "resolve");
            for (FakePool p : ring) {
                p.resolve();
            }
        }

        // Same shape as mismatchedEndDropsThatFrameOnlyAndNeverMispairs: the poisoned frame
        // contributes nothing, the well-formed frames all recorded.
        List<FrameProfiler.Stat> stats = profiler.snapshot();
        assertEquals(2, stats.size());
        for (FrameProfiler.Stat s : stats) {
            assertTrue(s.samples() >= 3, s.label() + " should have recorded from the healthy frames");
        }
    }

    @Test
    void unclosedPhantomBracketAtEndFrameDropsTheFrame() {
        FrameProfiler profiler = new FrameProfiler();
        TickSource ticks = new TickSource();
        FakePool[] ring = newRing(ticks);
        PassTimer timer = new PassTimer(profiler, ring, 1.0f);

        // Frame 0: overrun into the phantom region, then leave the phantom bracket open (never
        // ended) -- must drop the frame exactly like an unclosed TIMED bracket already does
        // (unclosedBracketAtEndFrameDropsTheFrame above); free-to-open must not mean free-to-leak.
        timer.beginFrame();
        timer.bracketBegin(FrameProfiler.LABEL_FRAME);
        openAndCloseSiblings(timer, PassTimer.MAX_BRACKETS, "warmup");
        timer.bracketBegin("neverClosedPhantom");
        timer.endFrame(); // LABEL_FRAME and neverClosedPhantom both still open -> openDepth != 0

        for (int frame = 1; frame <= 6; frame++) {
            runFrame(timer, "resolve");
            for (FakePool p : ring) {
                p.resolve();
            }
        }

        FrameProfiler.Stat frameStat = profiler.snapshot().get(0);
        assertEquals(FrameProfiler.LABEL_FRAME, frameStat.label());
        assertEquals(3.0e-4, frameStat.avgMs(), 1e-12); // only healthy frames contributed
    }
}
