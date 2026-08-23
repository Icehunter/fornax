package dev.icehunter.fornax.metalfx;

import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetalFxConventionsTest {
    @Test
    void jitterConvertsNdcUpToInputPixelsDown() {
        Vector2f actual = MetalFxConventions.jitterPixels(
                new Vector2f(0.25f, 0.5f), 800, 600, false, false, new Vector2f());
        assertEquals(100.0f, actual.x, 1e-6f);
        assertEquals(-150.0f, actual.y, 1e-6f);
    }

    @Test
    void motionReprojectsCurrentMinusPreviousBackToPreviousPixel() {
        int width = 800;
        int height = 600;
        Vector2f previousUv = new Vector2f(0.40f, 0.45f);
        Vector2f currentUv = new Vector2f(0.425f, 0.46f);
        Vector2f motion = new Vector2f(currentUv).sub(previousUv);

        Vector2f actual = MetalFxConventions.reprojectPreviousPixel(
                currentUv, motion, width, height, false, new Vector2f());
        assertEquals(previousUv.x * width, actual.x, 1e-4f);
        assertEquals(previousUv.y * height, actual.y, 1e-4f);
    }

    // --- flip branches: -Dfornax.metalfx.jitterFlipX/Y and -Dfornax.metalfx.mvFlip (also
    // fornax.framegen.mvFlip/jitterFlip on the FrameGenPass interpolator-feed path) toggle these
    // booleans at runtime with no automated coverage until now -- pin that "flip" really does
    // invert the sign relative to the default (false) convention above, so a future edit to
    // MetalFxConventions can't silently break the escape hatch without a red test.

    @Test
    void jitterFlipXNegatesOnlyTheXComponent() {
        Vector2f baseline = MetalFxConventions.jitterPixels(
                new Vector2f(0.25f, 0.5f), 800, 600, false, false, new Vector2f());
        Vector2f flipped = MetalFxConventions.jitterPixels(
                new Vector2f(0.25f, 0.5f), 800, 600, true, false, new Vector2f());
        assertEquals(-baseline.x, flipped.x, 1e-6f);
        assertEquals(baseline.y, flipped.y, 1e-6f);
    }

    @Test
    void jitterFlipYNegatesOnlyTheYComponent() {
        Vector2f baseline = MetalFxConventions.jitterPixels(
                new Vector2f(0.25f, 0.5f), 800, 600, false, false, new Vector2f());
        Vector2f flipped = MetalFxConventions.jitterPixels(
                new Vector2f(0.25f, 0.5f), 800, 600, false, true, new Vector2f());
        assertEquals(baseline.x, flipped.x, 1e-6f);
        assertEquals(-baseline.y, flipped.y, 1e-6f);
    }

    @Test
    void motionScaleFlipInvertsBothComponents() {
        Vector2f baseline = MetalFxConventions.motionScale(800, 600, false, new Vector2f());
        Vector2f flipped = MetalFxConventions.motionScale(800, 600, true, new Vector2f());
        assertEquals(-baseline.x, flipped.x, 1e-6f);
        assertEquals(-baseline.y, flipped.y, 1e-6f);
    }

    @Test
    void motionReprojectionWithFlipStillRecoversThePreviousPixelGivenMatchingMotionSign() {
        // The flip convention only makes physical sense when the motion vector itself was produced
        // under the same convention (a flipped scale expects a flipped-sign delta as input) --
        // negating the delta alongside the flip should reproduce the exact same previousUv recovery
        // the non-flipped test above pins, proving flip=true is a real sign inversion of the
        // contract rather than dead/no-op code.
        int width = 800;
        int height = 600;
        Vector2f previousUv = new Vector2f(0.40f, 0.45f);
        Vector2f currentUv = new Vector2f(0.425f, 0.46f);
        Vector2f motion = new Vector2f(previousUv).sub(currentUv); // sign-flipped delta

        Vector2f actual = MetalFxConventions.reprojectPreviousPixel(
                currentUv, motion, width, height, true, new Vector2f());
        assertEquals(previousUv.x * width, actual.x, 1e-4f);
        assertEquals(previousUv.y * height, actual.y, 1e-4f);
    }
}
