package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameUniformValuesTest {
    @Test void selectedLanesPreservePublishedBitsAndNeverReadThePriorFrame() {
        FrameUniformValues values = new FrameUniformValues();
        values.beginFrame();
        assertFalse(values.ready());
        values.skyState(-0f, 2f, 3f, 4f);
        assertFalse(values.ready());
        values.frameState(5f, 6f, Math.nextUp(.5f), 8f);
        assertTrue(values.ready());
        assertEquals(Float.floatToRawIntBits(-0f), values.bits("u_SkyState.x"));
        assertEquals(Float.floatToRawIntBits(Math.nextUp(.5f)), values.bits("u_FrameState.z"));
        values.beginFrame();
        assertFalse(values.ready());
    }

    @Test void onlyExplicitScalarLanesAreAccepted() {
        assertTrue(FrameUniformValues.supports("u_SkyState.x"));
        assertTrue(FrameUniformValues.supports("u_FrameState.w"));
        assertFalse(FrameUniformValues.supports("u_SkyState"));
        assertFalse(FrameUniformValues.supports("u_FrameState.q"));
        assertFalse(FrameUniformValues.supports("u_Unknown.x"));
    }
}
