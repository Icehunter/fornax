package dev.icehunter.fornax.rt;

import java.util.Objects;

/**
 * One frame's celestial-visibility request: everything a provider needs to turn a shadow-map texel
 * into a world-space ray, with no reference to which traversal will do it.
 *
 * <p>The target is not carried here. There is exactly one celestial image in this engine,
 * {@code TerrainShadowResult}, and it is statically owned; threading it through the request would
 * suggest a provider could be pointed at a second one, which nothing in the engine supports.
 *
 * <p>Matrices are column-major 16-float arrays, the layout every Metal and Vulkan consumer here
 * already takes. They are copied on construction: a provider may run after the caller's frame state
 * has moved on.
 */
public record CelestialFill(float[] inverseLightVp, float[] lightVp,
        double cameraX, double cameraY, double cameraZ,
        int resolution, float radiusBlocks, float bias, float filterGuardUv) {

    public CelestialFill {
        inverseLightVp = copyMatrix(inverseLightVp, "inverseLightVp");
        lightVp = copyMatrix(lightVp, "lightVp");
        if (resolution <= 0) {
            throw new IllegalArgumentException("resolution must be positive, got " + resolution);
        }
        // Doubles, not floats: a provider rebases the camera onto a coarse grid before it
        // narrows to float, and a world coordinate past a few million blocks loses whole blocks
        // if it is narrowed first.
        requireFinite(cameraX, "cameraX");
        requireFinite(cameraY, "cameraY");
        requireFinite(cameraZ, "cameraZ");
        requireFinite(radiusBlocks, "radiusBlocks");
        requireFinite(bias, "bias");
        requireFinite(filterGuardUv, "filterGuardUv");
        if (radiusBlocks < 0 || filterGuardUv < 0) {
            throw new IllegalArgumentException("radius and filter guard are nonnegative");
        }
        if (bias < 0) {
            throw new IllegalArgumentException("bias must be nonnegative, got " + bias);
        }
        // The upper bound is deliberately NOT checked here. The radial warp divides by
        // 1 - bias * |q|, so the domain is [0, 1), but the traversal is where that division
        // happens and where a violation can degrade to raster; refusing it at construction would
        // turn a recoverable failure into an exception thrown at a render-thread call site that
        // has no fallback.
        
    }

    /** Defensive copy on the way out too, so a provider cannot hand the next one a mutated matrix. */
    @Override
    public float[] inverseLightVp() {
        return inverseLightVp.clone();
    }

    @Override
    public float[] lightVp() {
        return lightVp.clone();
    }

    private static float[] copyMatrix(float[] matrix, String name) {
        Objects.requireNonNull(matrix, name);
        if (matrix.length != 16) {
            throw new IllegalArgumentException(name + " must be 16 floats, got " + matrix.length);
        }
        for (float value : matrix) {
            requireFinite(value, name);
        }
        return matrix.clone();
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
    }
}
