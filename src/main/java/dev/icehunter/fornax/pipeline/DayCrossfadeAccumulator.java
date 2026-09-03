package dev.icehunter.fornax.pipeline;

/**
 * How far a per-day value has crossfaded into the new day, 0 (still yesterday) to 1 (fully today).
 *
 * <p>A pack that draws one random value per Minecraft day, such as morning mist's daily variance,
 * has a value that changes at a single tick: the instant the day index increments. Blending that
 * change across the game clock's own fraction-of-a-day does not help: at a high clock rate that
 * fraction sweeps past in a fraction of a real second, so the value still reads as a cut, not a
 * fade. A shader has no frame-to-frame memory to time a fade in real seconds by itself, the same
 * reason {@link WetnessAccumulator} exists rather than being pack code. This is that same shape
 * applied to a day boundary instead of a rain level: pure state that eases by wall-clock time,
 * engine-owned because nothing else can own it.
 *
 * <p>Unlike wetness, the target here is not a continuously-moving value to chase: it is a single
 * discrete event (the day index changed) that must fully resolve, not merely approach
 * asymptotically. An exponential ease never actually reaches 1, which would leave a pack's
 * {@code mix(hash(dayIndex - 1), hash(dayIndex), progress)} forever a hair short of today's value.
 * A fixed-duration smoothstep ramp is used instead, so the crossfade provably completes.
 *
 * <p>Pure, deterministic and free of any Minecraft or GPU type, so the property that matters most,
 * frame-rate independence, is unit-testable without a game or a device.
 */
public final class DayCrossfadeAccumulator {
    /**
     * Real seconds for the crossfade to complete: long enough that it does not look like a cut,
     * short enough that a variance nobody would notice anyway does not visibly linger stale for
     * half a minute.
     */
    static final float CROSSFADE_SECONDS = 20.0f;

    private float lastDayIndex;
    private float sinceChangeSeconds;
    private boolean initialized;

    /**
     * Advances toward {@code dayIndex} by {@code deltaSeconds} of real time and returns the
     * crossfade progress, 0..1.
     *
     * <p>The first call for a fresh accumulator settles immediately rather than ramping from zero:
     * there is no "yesterday" for the very first day a world is loaded on.
     *
     * @param dayIndex     the day this frame is on; a change from the previous call restarts the
     *                     ramp
     * @param deltaSeconds real time since the last call; non-positive or NaN is ignored
     */
    public void step(float dayIndex, float deltaSeconds) {
        if (!initialized) {
            lastDayIndex = dayIndex;
            sinceChangeSeconds = CROSSFADE_SECONDS;
            initialized = true;
            return;
        }
        if (dayIndex != lastDayIndex) {
            lastDayIndex = dayIndex;
            sinceChangeSeconds = 0.0f;
            return;
        }
        if (deltaSeconds > 0.0f) {
            sinceChangeSeconds += deltaSeconds;
        }
    }

    /** Current crossfade progress, 0 (the instant the day changed) to 1 (fully settled). */
    public float progress() {
        float t = Math.clamp(sinceChangeSeconds / CROSSFADE_SECONDS, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Drops straight to fully settled with no ramp, for a discontinuity where a fade would be
     * wrong rather than merely fast: changing dimension, or joining a world on a different day.
     */
    public void reset(float dayIndex) {
        lastDayIndex = dayIndex;
        sinceChangeSeconds = CROSSFADE_SECONDS;
        initialized = true;
    }
}
