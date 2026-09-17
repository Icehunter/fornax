package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.rt.RayTier;

/**
 * Binary layout of the ray-query request and hit buffers a caller submits to {@code
 * rt_ray_query.metal}.
 *
 * <p>A request is two {@code float4}s so both sides see Metal's natural vector alignment with no
 * packing pass: origin xyz + tMin, then direction xyz + tMax. A hit is eight 32-bit words: a float
 * distance, a flag word, a surface word, a packed atlas UV, the surface normal as three floats, and
 * the tier that answered.
 *
 * <p><b>Word 7, the tier, is what makes a record readable.</b> The hit buffer is allocated and
 * readable on hardware with no ray tracing at all, where nothing ever writes it, so it reads back
 * zero-filled. Zero tier is {@link RayTier#NONE}: ignore the record, whatever the other seven words
 * say. Reading {@code distance} first cannot carry that, because zero is a legal distance and a
 * negative distance is a legal answer (a traced miss). Test {@link #isAnswered(int)} first, then
 * {@link #isMiss(float)}.
 *
 * <p>The normal is declared {@code packed_float3} on the kernel side. A plain {@code float3} member
 * carries 16 bytes of size, which would push the tier to byte 32 and make the record 48 bytes while
 * this class still said 8 words. Packed keeps it at bytes 16..27 with the tier at 28.
 *
 * <p>New fields APPEND from v3 onward. Inserting one silently re-points every later field with no
 * error anywhere, for the same reason inserting a pack graph input does.
 */
public final class RayQueryAbi {

    /** Bumped whenever any offset below moves. rt_ray_query.metal refuses a mismatched buffer. */
    public static final int ABI_VERSION = 3;

    /** origin.xyz, tMin, direction.xyz, tMax. */
    public static final int REQUEST_WORDS = 8;

    /** distance (float); flags, surface, atlasUv (uint); normal xyz (float); tier (uint). */
    public static final int HIT_WORDS = 8;

    /**
     * 4 Mi rays: 128 MiB of request and 64 MiB of hit buffer. Above a one-ray-per-pixel pass at
     * 1440p (3.7 Mi) and below anything that would exhaust a unified-memory budget by accident. A
     * caller needing more dispatches more than once rather than growing this.
     */
    public static final int MAX_RAYS = 4 * 1024 * 1024;

    /** Word offset of the hit distance within a hit record. Meaningful only once answered. */
    public static final int HIT_DISTANCE_WORD = 0;

    /** Word offset of the flag word. See the FLAG_ constants. */
    public static final int HIT_FLAGS_WORD = 1;

    /**
     * Word offset of the surface word: the geometry record's own first word, which every tier's
     * primitive data starts with. For the voxel tiers it is the expand word a pack already decodes
     * for palette colour, emission and extinction; for exact meshes it is zero, since a mesh
     * triangle has no palette entry and its identity is its atlas UV instead.
     */
    public static final int HIT_SURFACE_WORD = 2;

    /**
     * Word offset of the hit's atlas UV, packed as two halves in one word, low half u. Zero unless
     * the flag word has {@link #FLAG_UV_KNOWN} set: a record without it has no UV at all, which is
     * different from a UV of (0, 0).
     */
    public static final int HIT_ATLAS_UV_WORD = 3;

    /**
     * Word offset of the surface normal's x component; y and z follow. A voxel face is axis
     * aligned, so this is exact rather than interpolated.
     *
     * <p>Reads as the ZERO VECTOR when the kernel could not name a face for the hit, which is not
     * the same as a miss: the ray met a surface whose orientation is not recoverable from its
     * primitive data. Normalizing it produces NaN, so callers test its length first.
     */
    public static final int HIT_NORMAL_WORD = 4;

    /**
     * Word offset of the answering tier, a {@link RayTier} ordinal. Zero means nothing answered,
     * which is what an untraced buffer and an ABI-version mismatch both produce.
     */
    public static final int HIT_TIER_WORD = 7;

    /** Flag bit 0: the ray met the triangle's front face. */
    public static final int FLAG_FRONT_FACING = 1;

    /** Bit position of the 4-bit face field in the flag word. */
    public static final int FLAG_FACE_SHIFT = 8;

    /** Mask of the face field once shifted down: 0..5 name a direction, 15 means unknown. */
    public static final int FLAG_FACE_MASK = 0xF;

    /** The face value a kernel writes when the hit's geometry names no direction. */
    public static final int FACE_UNKNOWN = 0xF;

    /** Flag bit 12: {@link #HIT_ATLAS_UV_WORD} carries a real UV. */
    public static final int FLAG_UV_KNOWN = 1 << 12;

    /** What word 0 reads as when the ray met nothing along its whole interval. */
    public static final float MISS_DISTANCE = -1.0f;

    private RayQueryAbi() {
    }

    /** Bytes a request buffer needs to carry {@code rays} rays. */
    public static long requestByteSize(int rays) {
        return (long) checked(rays) * REQUEST_WORDS * Float.BYTES;
    }

    /** Bytes a hit buffer needs to carry {@code rays} results. */
    public static long hitByteSize(int rays) {
        return (long) checked(rays) * HIT_WORDS * Float.BYTES;
    }

    /**
     * True when some traversal wrote this record. Every other accessor here is meaningless until
     * this returns true, including {@link #isMiss(float)}.
     */
    public static boolean isAnswered(int tier) {
        return tier != RayTier.NONE.ordinal();
    }

    /**
     * True when the ray met nothing along its interval. Precondition: the record is answered. An
     * unanswered record carries distance zero, which this reports as a hit.
     */
    public static boolean isMiss(float distance) {
        return !(distance >= 0.0f);
    }

    /**
     * True when a hit's normal is the zero vector, meaning the kernel met a surface it could not
     * name a face for. Distinct from a miss, and the reason callers must not normalize blindly.
     */
    public static boolean isNormalUnknown(float x, float y, float z) {
        return x * x + y * y + z * z < 0.5f;
    }

    private static int checked(int rays) {
        if (rays < 0 || rays > MAX_RAYS) {
            throw new IllegalArgumentException("ray count " + rays + " outside 0.." + MAX_RAYS);
        }
        return rays;
    }
}
