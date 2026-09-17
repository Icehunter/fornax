package dev.icehunter.fornax.rt;

/**
 * What a caller wants back from a ray, which is what decides how much of the hit record a provider
 * has to fill and how early its traversal may stop.
 */
public enum RayQueryKind {
    /**
     * Does anything opaque-after-alpha-test lie on the segment. A provider may accept the first hit
     * it meets rather than the nearest, and may leave the surface fields zero. This is the minimum
     * a shadow or an occlusion term needs: blocked, and how far along the ray.
     */
    VISIBILITY,
    /**
     * The nearest accepted hit, with distance, normal, flags, surface word and atlas UV. This is the
     * minimum a bounce or a reflection needs: where the ray landed, which way the surface faces, and
     * enough identity to fetch colour. Albedo is not returned; picking a filter and a mip is the
     * pack's decision.
     */
    CLOSEST_HIT;
}
