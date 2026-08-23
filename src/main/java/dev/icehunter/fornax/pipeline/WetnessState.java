package dev.icehunter.fornax.pipeline;

/**
 * Session-lifetime holder for the world's {@link WetnessAccumulator}, plus the wall-clock timing it
 * needs. Static-holder shape, matching {@code SkyFrameState} and {@code NoiseTexture}.
 *
 * <p>The accumulator itself is deliberately free of any clock so it stays a pure function of
 * (rainLevel, deltaSeconds) and can be unit-tested exhaustively. This class is the thin, untestable
 * part that reads the real clock — kept as small as possible for exactly that reason.
 *
 * <p><b>Stepping more than once per frame is harmless</b>, which is worth stating because the
 * uniform write this hangs off may run several times a frame. The accumulator eases by elapsed TIME,
 * not per call, so two half-deltas advance it the same distance as one whole one — the same property
 * that makes it frame-rate independent. A per-call ramp would have needed a "did I already run this
 * frame" guard and would have been wrong the moment that guard was missed.
 */
public final class WetnessState {
    private static final WetnessAccumulator ACCUMULATOR = new WetnessAccumulator();
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private static long lastNanos;

    private WetnessState() {
    }

    /**
     * Advances wetness toward {@code rainLevel} by however long has really elapsed, and returns it.
     *
     * @param rainLevel vanilla's instantaneous rain level for this frame, 0..1
     * @return current surface wetness, 0..1, for {@code u_FrameState.w}
     */
    public static float step(float rainLevel) {
        long now = System.nanoTime();
        if (lastNanos != 0L) {
            ACCUMULATOR.step(rainLevel, (float) ((now - lastNanos) / NANOS_PER_SECOND));
        }
        lastNanos = now;
        return ACCUMULATOR.wetness();
    }

    /**
     * Drops wetness straight to {@code rainLevel} and restarts the clock.
     *
     * <p>For discontinuities where easing would be wrong rather than merely fast — changing
     * dimension, or joining a world that was already raining. Easing across those bleeds the previous
     * world's weather into the new one, and leaves one enormous delta waiting to be integrated.
     */
    public static void reset(float rainLevel) {
        ACCUMULATOR.reset(rainLevel);
        lastNanos = 0L;
    }

    /** Current wetness without advancing it. */
    public static float wetness() {
        return ACCUMULATOR.wetness();
    }
}
