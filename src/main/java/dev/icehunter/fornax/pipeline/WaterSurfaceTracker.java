package dev.icehunter.fornax.pipeline;

/**
 * Smooths the water-surface altitude published as {@code u_WaterState.z}, and — the reason this
 * exists as its own class — refuses to chase a "surface" that is actually the underside of a roof.
 *
 * <p>The engine-side scan this filters keeps the TOPMOST water in the camera's own column, which
 * correctly passes through a submerged overhang: the real surface is still water further up, so the
 * raw altitude is still right and only needs the usual smoothing. But when the column is capped by
 * LAND rather than more water — swimming under a shore, a hill, a monument wall — there is no water
 * above the roof at all. The raw altitude collapses to the underside of that roof, tens of blocks
 * below the real sea level, and the exponential filter below turns that collapse into a visible
 * multi-frame SLIDE rather than a pop — which is exactly the defect this class fixes: caustics,
 * the water veil and the depth tint all key off this one altitude, so the slide read as the whole
 * underwater look drifting for no reason.
 *
 * <p>The fix is to tell a real surface from a ceiling by whether the sky is reachable directly above
 * the measured altitude (a pure {@code canSeeSky}-shaped boolean the caller supplies — this class
 * stays free of any Minecraft type, matching {@link WetnessAccumulator}). Open sky: filter normally.
 * Roofed, with an existing baseline: hold that baseline rather than lerping toward the ceiling. Roofed
 * with no baseline yet (diving in for the first time already under an overhang): there is nothing
 * better to report than the raw reading, so it is taken as-is.
 *
 * <p>Two independent arms feed this filter — a submerged-eye scan (up from the camera) and a
 * dry-eye scan (down from the camera, so an above-water pack can still depth-test against the
 * surface). Both share one smoothed value and one "do we have a baseline" flag so crossing the
 * waterline does not pop, but each has its own snap-vs-ease trigger, matching the asymmetry the
 * mixin already had before this class existed: the submerged arm snaps on the dive-in frame rather
 * than easing from whatever a dry look was showing a moment before; the dry arm eases from any prior
 * baseline, submerged or dry.
 *
 * <p>Pure and deterministic, like {@link WetnessAccumulator} — the property that matters here is
 * reproducible without a game or a GPU, so the slide this class fixes can be asserted offline.
 */
public final class WaterSurfaceTracker {
    /** Per-frame ease rate toward the raw reading: ~0.3s to 90% at 60 fps, deliberately frame-rate dependent. */
    static final float FILTER_RATE = 0.12f;

    private float smoothedSurface;
    private boolean wasSubmerged;
    private boolean hadSurface;

    public WaterSurfaceTracker() {
        reset();
    }

    /** Drops all state, as if no surface had ever been measured. For a world/dimension change. */
    public void reset() {
        smoothedSurface = 0.0f;
        wasSubmerged = false;
        hadSurface = false;
    }

    /**
     * Submerged-eye arm: the camera is in water and {@code rawSurface} is this frame's topmost-water
     * scan (in world blocks).
     *
     * @param rawSurface this frame's raw scanned altitude
     * @param openToSky  whether the sky is reachable directly above {@code rawSurface} — true for a
     *                   real surface (even a submerged overhang further up still has open water
     *                   above it), false when the column is capped by land
     * @return the altitude to publish this frame
     */
    public float updateSubmerged(float rawSurface, boolean openToSky) {
        if (openToSky || !hadSurface) {
            smoothedSurface = wasSubmerged
                    ? smoothedSurface + (rawSurface - smoothedSurface) * FILTER_RATE
                    : rawSurface;
        }
        // Roofed, with an existing baseline: hold it. Do not lerp toward the ceiling's altitude.
        hadSurface = true;
        wasSubmerged = true;
        return smoothedSurface;
    }

    /**
     * Dry-eye arm: the camera is out of water. {@code rawFound} is whether the downward scan hit a
     * water surface at all within its budget.
     *
     * @param rawFound   whether the downward scan found water
     * @param rawSurface this frame's raw scanned altitude; meaningless when {@code rawFound} is false
     * @return the altitude to publish this frame, or 0.0f when no water was found
     */
    public float updateDry(boolean rawFound, float rawSurface) {
        wasSubmerged = false;
        if (!rawFound) {
            hadSurface = false;
            return 0.0f;
        }
        smoothedSurface = hadSurface
                ? smoothedSurface + (rawSurface - smoothedSurface) * FILTER_RATE
                : rawSurface;
        hadSurface = true;
        return smoothedSurface;
    }
}
