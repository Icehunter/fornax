package dev.icehunter.fornax.metalfx;

import org.joml.Vector2f;

/**
 * Pure conversion contract between Fornax motion/jitter data and MetalFX pixel-space inputs.
 * Keeping the arithmetic here makes the sign convention executable in tests instead of an
 * eyeball-only constant embedded in the frame loop.
 */
final class MetalFxConventions {
    private MetalFxConventions() {
    }

    static Vector2f jitterPixels(Vector2f jitterNdc, int width, int height,
            boolean flipX, boolean flipY, Vector2f destination) {
        return destination.set(
                (flipX ? -1f : 1f) * jitterNdc.x * width * 0.5f,
                (flipY ? 1f : -1f) * jitterNdc.y * height * 0.5f);
    }

    static Vector2f motionScale(int width, int height, boolean flip, Vector2f destination) {
        float sign = flip ? 1f : -1f;
        return destination.set(sign * width, sign * height);
    }

    static Vector2f reprojectPreviousPixel(Vector2f currentUv, Vector2f currentMinusPreviousUv,
            int width, int height, boolean flip, Vector2f destination) {
        Vector2f scale = motionScale(width, height, flip, destination);
        return scale.set(
                currentUv.x * width + currentMinusPreviousUv.x * scale.x,
                currentUv.y * height + currentMinusPreviousUv.y * scale.y);
    }
}
