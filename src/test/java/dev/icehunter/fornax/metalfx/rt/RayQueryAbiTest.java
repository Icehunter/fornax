package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.rt.RayTier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layout contract for the ray-query buffers. Needs no device, so this is the part of the ray-query
 * path that CI actually runs: the Linux runner skips every Metal-gated test in this package.
 */
class RayQueryAbiTest {

    /** 8 floats: origin xyz + tMin, direction xyz + tMax. Two float4s, Metal's own alignment. */
    @Test
    void oneRayRequestIsThirtyTwoBytes() {
        assertEquals(32L, RayQueryAbi.requestByteSize(1));
    }

    /** 8 words: distance, flags, surface, atlasUv, normal xyz, tier. */
    @Test
    void oneHitRecordIsThirtySixBytes() {
        assertEquals(36L, RayQueryAbi.hitByteSize(1));
    }

    @Test
    void byteSizesScaleLinearlyWithRayCount() {
        assertEquals(32L * 1024L, RayQueryAbi.requestByteSize(1024));
        assertEquals(36L * 1024L, RayQueryAbi.hitByteSize(1024));
    }

    @Test
    void aRayCountAboveTheCapIsRejectedRatherThanSilentlyTruncated() {
        assertThrows(IllegalArgumentException.class,
                () -> RayQueryAbi.requestByteSize(RayQueryAbi.MAX_RAYS + 1));
        assertThrows(IllegalArgumentException.class,
                () -> RayQueryAbi.hitByteSize(-1));
    }

    /**
     * Pinned because rt_ray_query.metal compares an incoming constant against this exact value and
     * writes misses when it disagrees. A layout change that forgets the bump would be read by the
     * kernel as a valid buffer at the wrong offsets.
     */
    @Test
    void abiVersionIsFour() {
        assertEquals(4, RayQueryAbi.ABI_VERSION);
    }

    /**
     * The hit buffer exists and is readable on hardware with no ray tracing, where nothing ever
     * writes it, so it reads back zero-filled. The distance alone cannot separate that from an
     * answer: zero is a legal distance and a negative one is a legal answer, a traced miss. The
     * tier word is what carries validity, and it reads zero exactly when nothing wrote the record.
     */
    @Test
    void anUntracedZeroFilledBufferReadsAsUnansweredBecauseItsTierWordIsZero() {
        assertFalse(RayQueryAbi.isAnswered(0), "tier zero is RayTier.NONE: ignore the record");
        assertTrue(RayQueryAbi.isAnswered(RayTier.SOFTWARE_VOXEL.ordinal()));
        assertTrue(RayQueryAbi.isAnswered(RayTier.HARDWARE_VOXEL.ordinal()));
        assertTrue(RayQueryAbi.isAnswered(RayTier.HARDWARE_MESH.ordinal()));
        assertFalse(RayQueryAbi.isMiss(0.0f), "zero is a legitimate self-hit, not a miss");
    }

    /** A traced miss is an answer, and it is the negative distance that says so. */
    @Test
    void aTracedMissIsANegativeDistanceCarriedByAnAnsweredRecord() {
        assertTrue(RayQueryAbi.MISS_DISTANCE < 0.0f, "the miss sentinel must be negative");
        assertTrue(RayQueryAbi.isMiss(RayQueryAbi.MISS_DISTANCE));
        assertTrue(RayQueryAbi.isMiss(Float.NaN), "a NaN distance is not a usable hit");
        assertTrue(RayQueryAbi.isAnswered(RayTier.HARDWARE_VOXEL.ordinal()));
    }

    /** Word offsets are a contract with the kernel's RayHit struct; they may append, never move. */
    @Test
    void hitWordOffsetsAreDistanceFlagsSurfaceAtlasUvThenNormalThenTier() {
        assertEquals(0, RayQueryAbi.HIT_DISTANCE_WORD);
        assertEquals(1, RayQueryAbi.HIT_FLAGS_WORD);
        assertEquals(2, RayQueryAbi.HIT_SURFACE_WORD);
        assertEquals(3, RayQueryAbi.HIT_ATLAS_UV_WORD);
        // 4, not 3+1 by coincidence: MSL aligns a float3 member to 16 bytes, so the kernel's own
        // struct puts the normal here whatever this constant says. They must agree.
        assertEquals(4, RayQueryAbi.HIT_NORMAL_WORD);
        // 7, immediately after the normal's three components. This only holds while the kernel
        // declares the normal packed_float3: a plain float3 is 16 bytes of size, which would put
        // the tier at word 8, outside a record this class still calls 8 words long.
        assertEquals(7, RayQueryAbi.HIT_TIER_WORD);
        assertTrue(RayQueryAbi.HIT_NORMAL_WORD + 3 <= RayQueryAbi.HIT_TIER_WORD,
                "the normal's three components must sit between the atlas UV and the tier");
        assertTrue(RayQueryAbi.HIT_TIER_WORD < RayQueryAbi.HIT_WORDS,
                "the tier must sit inside a hit record");
    }

    /**
     * The flag fields do not overlap. Face is four bits at 8, so it can carry 15 for "no face"
     * without colliding with the front-facing bit below it or the UV bit above it.
     */
    @Test
    void theFlagFieldsOccupyDistinctBits() {
        int face = RayQueryAbi.FLAG_FACE_MASK << RayQueryAbi.FLAG_FACE_SHIFT;
        assertEquals(0, face & RayQueryAbi.FLAG_FRONT_FACING);
        assertEquals(0, face & RayQueryAbi.FLAG_UV_KNOWN);
        assertEquals(0, RayQueryAbi.FLAG_FRONT_FACING & RayQueryAbi.FLAG_UV_KNOWN);
        assertEquals(1 << 13, RayQueryAbi.FLAG_ATLAS_TEXEL_U16);
        assertEquals(0, (face | RayQueryAbi.FLAG_FRONT_FACING | RayQueryAbi.FLAG_UV_KNOWN)
                & RayQueryAbi.FLAG_ATLAS_TEXEL_U16);
        assertEquals(RayQueryAbi.FLAG_FACE_MASK, RayQueryAbi.FACE_UNKNOWN,
                "the unknown face is the widest value the field can hold, so it can never be "
                        + "confused with a real face index");
    }

    /**
     * A zero normal means the kernel met a surface it could not name a face for. That is not a
     * miss, and it is not safe to normalize, which is why it has its own test rather than living
     * as a comment.
     */
    @Test
    void aZeroNormalReadsAsUnknownAndAnAxisNormalDoesNot() {
        assertTrue(RayQueryAbi.isNormalUnknown(0.0f, 0.0f, 0.0f));
        assertFalse(RayQueryAbi.isNormalUnknown(0.0f, -1.0f, 0.0f));
        assertFalse(RayQueryAbi.isNormalUnknown(1.0f, 0.0f, 0.0f));
    }
}
