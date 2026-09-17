package dev.icehunter.fornax.rt;

/**
 * One traversal that can answer rays, at one tier.
 *
 * <p>The contract that makes the cascade work is negative: <b>a provider writes only records that
 * are still unanswered.</b> For the image form that is a texel whose A channel is zero; for the
 * buffer form, a record whose tier word is zero. A provider that overwrites an answer erases a
 * better one, because the router walks tiers from high fidelity down.
 *
 * <p>Leaving a ray unanswered is normal and is the signal the next tier reads: outside this
 * provider's coverage, outside the warp domain, a degenerate direction, a section not yet
 * certified. None of those are failures and none of them are reported as such.
 *
 * <p>Every method here runs on the render thread.
 */
public interface RayProvider extends AutoCloseable {

    /** Which tier this provider answers at. Two installed providers may not share one. */
    RayTier tier();

    /**
     * Called once at the top of every frame, before {@link #readiness()} and before any query.
     * This is where per-frame state resets: a provider that publishes profiler rows zeroes them
     * here, so a frame it sits out reports zero rather than last frame's numbers.
     */
    default void beginFrame() {
    }

    /** Whether this provider answers that kind of query at all. */
    boolean answers(RayQueryKind kind);

    /**
     * Evaluated once per frame by the router, before any query reaches this provider. A provider
     * that is not ready is skipped for the whole frame, so this is where per-frame availability
     * lives: hardware support, a built acceleration structure, an attached grid.
     *
     * <p><b>It must be answerable at the top of the frame.</b> The router asks once, at
     * {@link #beginFrame()} time, and does not ask again. A provider that reports on state it only
     * receives later in the frame reports "not ready" every time and is never called at all, with
     * no error anywhere: the tier below answers everything. State that arrives mid-frame
     * belongs in a guard inside the query method, not here.
     */
    RayReadiness readiness();

    /**
     * Image-form celestial visibility. Writes only texels whose A channel is currently zero, and
     * writes value, tier and validity in one store so a texel never carries a tier without validity.
     */
    void fillCelestialVisibility(CelestialFill request);

    /**
     * Hands whatever this tier traced to the consumer that reads it, if this tier owns that step.
     * Separate from the fill because the two want opposite frame positions: the trace wants to be
     * early so the GPU has time, the delivery wants to be late so nothing waits on it.
     *
     * @return true when this tier delivered an image. The router asks tiers from the lowest up and
     *         stops at the first that says yes, because the lowest tier to touch the shared image
     *         is the one holding its final contents. A tier that failed or sat out says no, and the
     *         tier above it delivers instead.
     */
    default boolean publishCelestialVisibility() {
        return false;
    }

    /** Buffer-form query. Writes only records whose tier word is currently zero. */
    void answer(BufferQuery query);

    @Override
    void close();
}
