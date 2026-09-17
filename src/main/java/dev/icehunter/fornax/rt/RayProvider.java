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

    /** Whether this provider answers that kind of query at all. */
    boolean answers(RayQueryKind kind);

    /**
     * Evaluated once per frame by the router, before any query reaches this provider. A provider
     * that is not ready is skipped for the whole frame, so this is where per-frame availability
     * lives: hardware support, a built acceleration structure, an attached grid.
     */
    RayReadiness readiness();

    /**
     * Image-form celestial visibility. Writes only texels whose A channel is currently zero, and
     * writes value, tier and validity in one store so a texel never carries a tier without validity.
     */
    void fillCelestialVisibility(CelestialFill request);

    /** Buffer-form query. Writes only records whose tier word is currently zero. */
    void answer(BufferQuery query);

    @Override
    void close();
}
