package dev.icehunter.fornax.rt;

import java.util.Objects;

/**
 * Whether a provider can answer anything this frame, and why not when it cannot.
 *
 * <p>The reason is not decoration. A tier that silently declines is indistinguishable from a tier
 * that is answering and finding nothing, and the two call for opposite fixes. It is logged on
 * transition, never per frame.
 */
public record RayReadiness(boolean ready, String reason) {

    public RayReadiness {
        Objects.requireNonNull(reason, "readiness reason");
        if (ready && !reason.isEmpty()) {
            throw new IllegalArgumentException("a ready provider states no reason, got: " + reason);
        }
        if (!ready && reason.isEmpty()) {
            throw new IllegalArgumentException("a provider that is not ready must say why");
        }
    }

    private static final RayReadiness ANSWERING = new RayReadiness(true, "");

    /** Named for what it means rather than for the field, which the record accessor already owns. */
    public static RayReadiness answering() {
        return ANSWERING;
    }

    /** @param reason short, present tense, naming the missing thing: "no acceleration structure" */
    public static RayReadiness notReady(String reason) {
        return new RayReadiness(false, reason);
    }
}
