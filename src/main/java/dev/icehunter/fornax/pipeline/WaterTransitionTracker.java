package dev.icehunter.fornax.pipeline;

/** Tracks camera water crossings as one signed, decaying transition envelope. */
public final class WaterTransitionTracker {
    private static final double ENTRY_SECONDS = 1.0;
    private static final double EXIT_SECONDS = 1.0;

    private Object level;
    private boolean initialized;
    private boolean inWater;
    private int direction;
    private double startedAt;

    /** Negative means entering water, positive means exiting, zero means no active transition. */
    public float update(Object level, boolean inWater, double timeSeconds) {
        if (!initialized || this.level != level) {
            this.level = level;
            this.inWater = inWater;
            direction = 0;
            startedAt = timeSeconds;
            initialized = true;
            return 0.0f;
        }
        if (this.inWater != inWater) {
            this.inWater = inWater;
            direction = inWater ? -1 : 1;
            startedAt = timeSeconds;
        }
        if (direction == 0) {
            return 0.0f;
        }
        double duration = direction < 0 ? ENTRY_SECONDS : EXIT_SECONDS;
        float envelope = (float) Math.max(0.0, 1.0 - (timeSeconds - startedAt) / duration);
        if (envelope <= 0.0f) {
            direction = 0;
            return 0.0f;
        }
        return direction * envelope;
    }
}
