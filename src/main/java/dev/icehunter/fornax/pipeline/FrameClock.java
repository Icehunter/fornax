package dev.icehunter.fornax.pipeline;

/**
 * Wall-clock frame-interval tracker for MetalFX frame generation.
 * EMA-smoothed; single-frame hitches (alt-tab, world load) are clamped so
 * they cannot poison the interpolator's deltaTime.
 */
public final class FrameClock {
    private static final long MIN_INTERVAL_NANOS = 1_000_000L;    // 1 ms
    private static final long MAX_INTERVAL_NANOS = 250_000_000L;  // 250 ms
    private static final float EMA_ALPHA = 0.1f;

    private long lastMarkNanos;
    private boolean hasLastMark;
    private double emaNanos;
    private boolean ready;

    public void markFrame(long nanoTime) {
        if (hasLastMark) {
            long interval = Math.clamp(nanoTime - lastMarkNanos,
                    MIN_INTERVAL_NANOS, MAX_INTERVAL_NANOS);
            emaNanos = ready
                    ? emaNanos + EMA_ALPHA * (interval - emaNanos)
                    : interval;
            ready = true;
        }
        lastMarkNanos = nanoTime;
        hasLastMark = true;
    }

    public boolean ready() {
        return ready;
    }

    public float deltaTimeSeconds() {
        return ready ? (float) (emaNanos / 1.0e9) : 0.0f;
    }

    public long emaIntervalNanos() {
        return ready ? (long) emaNanos : 0L;
    }

    public void reset() {
        hasLastMark = false;
        ready = false;
        emaNanos = 0;
    }
}
