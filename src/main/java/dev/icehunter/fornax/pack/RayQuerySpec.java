package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.rt.RayQueryKind;
import dev.icehunter.fornax.rt.AtlasUvEncoding;
import dev.icehunter.fornax.rt.RayTier;
import java.util.Objects;

/**
 * The fields a {@link PassType#RAY_QUERY} pass carries and no other pass type has.
 *
 * <p>The pass itself has no shader. A pack writes ray requests into a buffer target from an earlier
 * pass, declares this pass over that buffer and a hit buffer, and reads the hits from a later one;
 * the engine routes the batch to whichever traversal can answer it. That is the whole point of the
 * type: a pack asks for rays without naming a backend, and gets the best tier the machine has.
 *
 * @param kind     what the caller wants back, which decides how early a traversal may stop
 * @param rayCount how many requests the buffer holds, fixed at load so both buffers can be checked
 *                 against it; zero when {@code perRenderPixel}, where the count is read off the
 *                 render-sized buffers each frame.
 * @param minTier  the lowest tier allowed to answer. {@link RayTier#NONE} accepts any. A pack that
 *                 would rather fall back to its own raster path than take an approximate answer
 *                 raises this, and every provider below it is skipped rather than blended.
 * @param atlasUvEncoding representation of UV-known hit addresses; defaults to packed half2.
 * @param perRenderPixel {@code rays = "render"}: one ray per render pixel, over buffers declared
 *                 {@code count = "render"}.
 */
public record RayQuerySpec(RayQueryKind kind, int rayCount, RayTier minTier, AtlasUvEncoding atlasUvEncoding,
                           boolean perRenderPixel) {
    public RayQuerySpec(RayQueryKind kind, int rayCount, RayTier minTier) {
        this(kind, rayCount, minTier, AtlasUvEncoding.PACKED_HALF, false);
    }

    public RayQuerySpec(RayQueryKind kind, int rayCount, RayTier minTier, AtlasUvEncoding atlasUvEncoding) {
        this(kind, rayCount, minTier, atlasUvEncoding, false);
    }

    public RayQuerySpec {
        Objects.requireNonNull(kind, "ray query kind");
        Objects.requireNonNull(minTier, "ray query minimum tier");
        Objects.requireNonNull(atlasUvEncoding, "atlas UV encoding");
        if (perRenderPixel ? rayCount != 0 : rayCount <= 0) {
            throw new IllegalArgumentException(perRenderPixel
                    ? "a per-render-pixel query carries no fixed ray count, got " + rayCount
                    : "ray count must be positive, got " + rayCount);
        }
    }
}
