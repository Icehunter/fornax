package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.rt.RayQueryKind;
import dev.icehunter.fornax.rt.RayTier;

import java.util.Objects;

/**
 * The three fields a {@link PassType#RAY_QUERY} pass carries and no other pass type has.
 *
 * <p>The pass itself has no shader. A pack writes ray requests into a buffer target from an earlier
 * pass, declares this pass over that buffer and a hit buffer, and reads the hits from a later one;
 * the engine routes the batch to whichever traversal can answer it. That is the whole point of the
 * type: a pack asks for rays without naming a backend, and gets the best tier the machine has.
 *
 * @param kind     what the caller wants back, which decides how early a traversal may stop
 * @param rayCount how many requests the buffer holds. Fixed at load, because both buffers are sized
 *                 from it and a size that moved per frame could not be validated against them.
 * @param minTier  the lowest tier allowed to answer. {@link RayTier#NONE} accepts any. A pack that
 *                 would rather fall back to its own raster path than take an approximate answer
 *                 raises this, and every provider below it is skipped rather than blended.
 */
public record RayQuerySpec(RayQueryKind kind, int rayCount, RayTier minTier) {

    public RayQuerySpec {
        Objects.requireNonNull(kind, "ray query kind");
        Objects.requireNonNull(minTier, "ray query minimum tier");
        if (rayCount <= 0) {
            throw new IllegalArgumentException("ray count must be positive, got " + rayCount);
        }
    }
}
