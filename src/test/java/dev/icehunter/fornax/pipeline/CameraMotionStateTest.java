package dev.icehunter.fornax.pipeline;

import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link CameraMotionState}'s one non-obvious property: the delta stays exact arbitrarily far
 * from the world origin, because the subtraction happens in double and only the small RESULT is
 * narrowed.
 *
 * <p>This is the whole reason the lane exists as a delta rather than as a second absolute camera
 * position. A {@code float} at Minecraft's world border (+/-29,999,984) has a quantum of 2 blocks --
 * more than a hundred frames of sprinting -- so an "upload the previous absolute position and
 * subtract in the shader" design would deliver a staircase of zeroes and jumps, and the reprojection
 * would jitter by tens of pixels while the player stood still. The failure would be invisible near
 * spawn and only appear far out, which is exactly the kind of bug that never gets attributed.
 */
class CameraMotionStateTest {

    private static final float SPRINT_STEP = 5.612f / 60.0f; // blocks per frame, sprint-jumping

    @Test
    void deltaIsExactNearTheOrigin() {
        PreviousFrameCameraTransform.commit(new CameraTransform(10.0, 64.0, -20.0),
                new Matrix4f(), new Matrix4f());
        CameraMotionState.commit(10.0 + SPRINT_STEP, 64.25, -20.0 - SPRINT_STEP);

        assertEquals(SPRINT_STEP, CameraMotionState.deltaX(), 1e-7f);
        assertEquals(0.25f, CameraMotionState.deltaY(), 1e-7f);
        assertEquals(-SPRINT_STEP, CameraMotionState.deltaZ(), 1e-7f);
    }

    @Test
    void deltaSurvivesTheWorldBorder() {
        // Minecraft's world border limit. A float here holds whole even numbers only.
        double farOut = 29_999_984.0;
        PreviousFrameCameraTransform.commit(new CameraTransform(farOut, 64.0, -farOut),
                new Matrix4f(), new Matrix4f());
        CameraMotionState.commit(farOut + SPRINT_STEP, 64.0, -farOut + SPRINT_STEP);

        assertEquals(SPRINT_STEP, CameraMotionState.deltaX(), 1e-6f);
        assertEquals(SPRINT_STEP, CameraMotionState.deltaZ(), 1e-6f);

        // ...and the design this replaces would have failed here: narrowing the two absolute
        // positions FIRST and subtracting afterwards loses the step entirely.
        float narrowedPrev = (float) farOut;
        float narrowedNow = (float) (farOut + SPRINT_STEP);
        assertEquals(0.0f, narrowedNow - narrowedPrev,
                "float subtraction at the world border must be shown to lose the whole step, or this "
                        + "test is not testing anything");
    }

    @Test
    void aStationaryCameraProducesExactlyZero() {
        PreviousFrameCameraTransform.commit(new CameraTransform(1234.5, 71.0, -9876.25),
                new Matrix4f(), new Matrix4f());
        CameraMotionState.commit(1234.5, 71.0, -9876.25);

        assertTrue(CameraMotionState.deltaX() == 0.0f && CameraMotionState.deltaY() == 0.0f
                        && CameraMotionState.deltaZ() == 0.0f,
                "a camera that did not move must reproject onto itself bit-exactly, or every static "
                        + "frame resamples history through a sub-pixel offset");
    }
}
