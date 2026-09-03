package dev.icehunter.fornax.pipeline;

/**
 * Session-lifetime holder for the world's {@link DayCrossfadeAccumulator}, plus the wall-clock
 * timing it needs. Static-holder shape, matching {@link WetnessState}.
 *
 * <p>The accumulator itself is deliberately free of any clock so it stays a pure function of
 * (dayIndex, deltaSeconds) and can be unit-tested exhaustively. This class is the thin, untestable
 * part that reads the real clock, kept as small as possible for that reason.
 */
public final class DayCrossfadeState {
    private static final DayCrossfadeAccumulator ACCUMULATOR = new DayCrossfadeAccumulator();
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private static long lastNanos;

    private DayCrossfadeState() {
    }

    /**
     * Advances the crossfade toward {@code dayIndex} by however long has really elapsed, and
     * returns it.
     *
     * @param dayIndex this dimension's current day index (see {@code u_WorldClock.x})
     * @return crossfade progress, 0..1, for {@code u_WorldClock.z}
     */
    public static float step(float dayIndex) {
        long now = System.nanoTime();
        if (lastNanos != 0L) {
            ACCUMULATOR.step(dayIndex, (float) ((now - lastNanos) / NANOS_PER_SECOND));
        } else {
            ACCUMULATOR.step(dayIndex, 0.0f);
        }
        lastNanos = now;
        return ACCUMULATOR.progress();
    }

    /**
     * Drops straight to fully settled on {@code dayIndex} and restarts the clock, for a
     * discontinuity where a fade would be wrong rather than merely fast: changing dimension, or
     * joining a world on a different day.
     */
    public static void reset(float dayIndex) {
        ACCUMULATOR.reset(dayIndex);
        lastNanos = 0L;
    }
}
