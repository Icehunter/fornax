package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalActorFrameStateTest {
    @Test
    void stableActorPublishesPerFrameMotionAndShape() {
        LocalActorFrameState state = new LocalActorFrameState();
        Object level = new Object();

        state.update(new LocalActorFrameState.Input(level, 7, 1,
                10.0, 64.0, 20.0, 0.0f, 1.0f, 0.3f, 0.9f,
                1, true, 1.0f / 60.0f));
        assertTrue(state.snapshot().reset());

        state.update(new LocalActorFrameState.Input(level, 7, 1,
                10.25, 63.9, 20.5, 0.0f, 1.0f, 0.3f, 0.9f,
                1, true, 1.0f / 60.0f));
        LocalActorFrameState.Snapshot snapshot = state.snapshot();
        assertFalse(snapshot.reset());
        assertEquals(0.25f, snapshot.deltaX(), 1.0e-6f);
        assertEquals(-0.1f, snapshot.deltaY(), 1.0e-5f);
        assertEquals(0.5f, snapshot.deltaZ(), 1.0e-6f);
        assertEquals(1, snapshot.actorKind());
        assertEquals(1, snapshot.fluidKind());
        assertEquals(1.0f, snapshot.surfaceContact(), 0.0f);
        assertEquals(0.3f, snapshot.halfWidth(), 0.0f);
        assertEquals(0.9f, snapshot.halfLength(), 0.0f);
    }

    @Test
    void actorChangeAndTeleportResetHistoryInsteadOfPublishingHugeMotion() {
        LocalActorFrameState state = new LocalActorFrameState();
        Object level = new Object();
        state.update(new LocalActorFrameState.Input(level, 7, 1,
                0.0, 64.0, 0.0, 1.0f, 0.0f, 0.3f, 0.3f,
                0, false, 1.0f / 60.0f));

        state.update(new LocalActorFrameState.Input(level, 8, 2,
                100.0, 64.0, 100.0, 1.0f, 0.0f, 0.8f, 1.4f,
                1, true, 0.5f));
        LocalActorFrameState.Snapshot snapshot = state.snapshot();
        assertTrue(snapshot.reset());
        assertEquals(0.0f, snapshot.deltaX(), 0.0f);
        assertEquals(0.0f, snapshot.deltaZ(), 0.0f);
        assertEquals(0.05f, snapshot.deltaSeconds(), 0.0f,
                "frame delta must be clamped before shader simulation consumes it");
        assertEquals(2, snapshot.actorKind());
    }
}
