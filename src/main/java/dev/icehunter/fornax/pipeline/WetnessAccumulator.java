package dev.icehunter.fornax.pipeline;

/**
 * Surface wetness: how wet the world IS, as distinct from how hard it is raining right now.
 *
 * <p>Vanilla's rain level is instantaneous — it ramps over a handful of ticks and then sits at its
 * target. Real surfaces do not: they darken over a while as rain soaks in and stay damp long after
 * it stops. A pack driving wetness straight off the rain level therefore snaps the whole world wet
 * and dry, which reads as a rendering glitch rather than as weather. This holds the missing state.
 *
 * <p>Exposing it is engine work rather than pack work on purpose. It is the same {@code wetness}
 * semantic OptiFine and Iris publish, so every pack gets it and none has to invent its own; and a
 * pack CANNOT build it, because a shader has no frame-to-frame memory to accumulate into. That makes
 * it data the engine owes the pack, not a styling choice the engine is imposing — the distinction
 * this project holds to.
 *
 * <p><b>Wetting and drying run at different rates, and that asymmetry is the whole point.</b> Rain
 * soaks a surface far faster than air dries it; equal rates read as the world breathing in and out.
 * The constants are time-to-63% (one exponential time constant), which is why they are seconds
 * rather than per-frame factors.
 *
 * <p>Pure, deterministic and free of any Minecraft or GPU type, so the behaviour that actually
 * matters — that it is frame-rate independent — is unit-testable without a game or a device.
 */
public final class WetnessAccumulator {
    /** Seconds for wetness to close ~63% of the gap while it is raining. */
    static final float WET_TIME_CONSTANT = 6.0f;

    /** Seconds for the same while it is drying. Deliberately much slower — see the class doc. */
    static final float DRY_TIME_CONSTANT = 45.0f;

    /**
     * Largest frame delta that is integrated as-is, in seconds.
     *
     * <p>A hitch, a breakpoint or an alt-tab produces one enormous delta. Integrating it verbatim
     * jumps wetness most of the way to its target in a single frame, so the player returns to a
     * world that changed state while they were not looking. Clamping trades exactness during a stall
     * — where nothing is being displayed anyway — for continuity across it.
     */
    static final float MAX_STEP_SECONDS = 0.25f;

    private float wetness;

    /** Fresh accumulator, starting dry. */
    public WetnessAccumulator() {
        this.wetness = 0.0f;
    }

    /** Current wetness, 0 (bone dry) to 1 (saturated). */
    public float wetness() {
        return wetness;
    }

    /**
     * Advances toward {@code rainLevel} by {@code deltaSeconds}.
     *
     * <p>Exponential ease rather than a linear ramp: {@code 1 - exp(-dt/tau)} is the closed-form
     * solution to "close a fixed FRACTION of the remaining gap per unit time", so the result depends
     * only on elapsed time and not on how that time was sliced into frames. A naive
     * {@code w += (target - w) * rate} does not have that property — it converges faster at high
     * frame rates, which would make wetness a function of the player's hardware.
     *
     * @param rainLevel    vanilla's current rain level, 0..1; clamped defensively
     * @param deltaSeconds real time since the last call; non-positive is ignored, large is clamped
     */
    public void step(float rainLevel, float deltaSeconds) {
        if (!(deltaSeconds > 0.0f)) {
            return; // Also rejects NaN, which would otherwise poison wetness permanently.
        }
        float target = Math.clamp(rainLevel, 0.0f, 1.0f);
        float dt = Math.min(deltaSeconds, MAX_STEP_SECONDS);
        float tau = target > wetness ? WET_TIME_CONSTANT : DRY_TIME_CONSTANT;

        wetness += (target - wetness) * (1.0f - (float) Math.exp(-dt / tau));
        wetness = Math.clamp(wetness, 0.0f, 1.0f);
    }

    /**
     * Drops straight to {@code rainLevel} with no easing.
     *
     * <p>For a discontinuity where easing would be wrong rather than merely fast: changing dimension,
     * or loading a world that was saved in the rain. Easing across those shows the previous world's
     * weather bleeding into the new one.
     */
    public void reset(float rainLevel) {
        wetness = Math.clamp(rainLevel, 0.0f, 1.0f);
    }
}
