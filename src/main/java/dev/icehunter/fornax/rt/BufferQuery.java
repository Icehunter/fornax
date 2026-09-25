package dev.icehunter.fornax.rt;

import java.util.Objects;

/**
 * One batch of caller-generated rays: the buffer form of a query, as opposed to the image form a
 * celestial fill answers.
 *
 * <p>Buffers are native handles rather than typed objects because the three providers reach them
 * through three different APIs. A record is only answered when its tier word is nonzero, so a
 * provider writes the records it can and leaves the rest for the tier below.
 *
 * <p>{@code passName} is the declaring graph pass's own name (e.g. {@code "gi_lamp_trace"}), not a
 * provider or tier identifier: a Metal-tier provider uses it only to label the GPU row it
 * publishes for the trace it ran on Metal's own queue, separate from the Vulkan-side bracket the
 * graph loop already times around the pass.
 */
public record BufferQuery(RayQueryKind kind, long requestBuffer, long hitBuffer, int rayCount,
                          String passName, AtlasUvEncoding atlasUvEncoding) {

    public BufferQuery(RayQueryKind kind, long requestBuffer, long hitBuffer, int rayCount, String passName) {
        this(kind, requestBuffer, hitBuffer, rayCount, passName, AtlasUvEncoding.PACKED_HALF);
    }

    public BufferQuery {
        Objects.requireNonNull(kind, "query kind");
        Objects.requireNonNull(passName, "pass name");
        Objects.requireNonNull(atlasUvEncoding, "atlas UV encoding");
        if (rayCount < 0) {
            throw new IllegalArgumentException("ray count must be nonnegative, got " + rayCount);
        }
        if (rayCount > 0 && (requestBuffer == 0 || hitBuffer == 0)) {
            throw new IllegalArgumentException("a nonempty query needs both buffers");
        }
    }
}
