package dev.icehunter.fornax.rt;

import java.util.Objects;

/**
 * One batch of caller-generated rays: the buffer form of a query, as opposed to the image form a
 * celestial fill answers.
 *
 * <p>Buffers are native handles rather than typed objects because the three providers reach them
 * through three different APIs. A record is only answered when its tier word is nonzero, so a
 * provider writes the records it can and leaves the rest for the tier below.
 */
public record BufferQuery(RayQueryKind kind, long requestBuffer, long hitBuffer, int rayCount) {

    public BufferQuery {
        Objects.requireNonNull(kind, "query kind");
        if (rayCount < 0) {
            throw new IllegalArgumentException("ray count must be nonnegative, got " + rayCount);
        }
        if (rayCount > 0 && (requestBuffer == 0 || hitBuffer == 0)) {
            throw new IllegalArgumentException("a nonempty query needs both buffers");
        }
    }
}
