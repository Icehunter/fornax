package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Latch semantics for {@link FornaxRenderState}: readers observe ONLY the last latched value, never
 * an intermediate one -- the pipeline/render-pass consistency invariant reduces to "every consumer
 * reads this one field, and it only moves at the renderer-recreation boundary".
 */
class FornaxRenderStateTest {
    @AfterEach
    void reset() {
        FornaxRenderState.latch(false);
    }

    @Test
    void defaultsToInactive() {
        // Fresh-process semantics: nothing may render deferred before the first world-load latch.
        assertFalse(FornaxRenderState.isActive());
    }

    @Test
    void readersObserveOnlyTheLatchedValue() {
        FornaxRenderState.latch(true);
        assertTrue(FornaxRenderState.isActive());

        // A live-config flip without a latch advance must be invisible to readers -- there is no
        // other input to isActive(), which is precisely the point: the config is NOT consulted.
        assertTrue(FornaxRenderState.isActive());

        FornaxRenderState.latch(false);
        assertFalse(FornaxRenderState.isActive());
    }

    @Test
    void latchingTheSameValueIsIdempotent() {
        FornaxRenderState.latch(true);
        FornaxRenderState.latch(true);
        assertTrue(FornaxRenderState.isActive());
    }
}
