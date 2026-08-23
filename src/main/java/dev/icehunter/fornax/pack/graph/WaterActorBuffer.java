package dev.icehunter.fornax.pack.graph;

/**
 * Vulkan-free ABI for the bounded set of entities a pack's water simulation may react to.
 *
 * <p>This is deliberately an engine ABI and not a water implementation, the same way
 * {@link dev.icehunter.fornax.pipeline.LocalActorFrameState} is: the engine answers "which bodies are
 * in the water near the camera, where, how fast, how big, and did any of them just cross the
 * surface", and the pack decides entirely on its own what that does to a pressure field. Nothing
 * here encodes an impulse shape, a radius policy or a splash magnitude.
 *
 * <p>Positions are published RELATIVE to the local actor, differenced in {@code double} before
 * narrowing. A pack's actor-centred field needs an offset from its own centre, and handing it two
 * absolute world coordinates to subtract in {@code float} would throw away the low bits of exactly
 * the quantity it cares about once the player is a few hundred thousand blocks out.
 *
 * <p>SLOT 0 IS ALWAYS THE LOCAL ACTOR, present even on the frame it stops touching water -- that
 * frame is its exit, and a consumer that dropped it there would never see the crossing. Every other
 * slot is a different body, nearest first, so a cap truncates the wakes least likely to be looked
 * at. A pack that already drives the local actor from the globals block can therefore start its
 * loop at 1 and add nothing to what it already ships.
 *
 * <p>Layout, std430, all 32-bit floats:
 *
 * <pre>
 *   vec4 header;                  // x live actor count, yzw reserved
 *   struct {
 *     vec4 position;              // xz offset from the local actor in blocks, y world Y, w kind
 *     vec4 motion;                // xz frame displacement in blocks, y vertical speed (blocks/s),
 *                                 // w surface-contact delta this frame (-1, 0 or +1)
 *     vec4 shape;                 // xy forward heading (world X, world Z), z half width,
 *                                 // w half length -- all in blocks
 *     vec4 fluid;                 // x fluid kind, y surface contact (0/1), zw reserved
 *   } actors[MAX_ACTORS];
 * </pre>
 */
public final class WaterActorBuffer {
    public static final String TARGET = "waterActors";

    /**
     * How many bodies may drive a pack simulation at once.
     *
     * <p>Eight, and the bound is the CONSUMER's cost rather than the collection's. A pack field is
     * typically solved per cell, so every actor published is a per-cell test in someone's inner
     * loop: at the 512-square field Plague runs, eight actors is already eight bounding rejects
     * across a quarter of a million cells twice a frame. Collection itself is trivially cheap, so
     * raising this is a decision about the pack's dispatch, not about this file.
     */
    public static final int MAX_ACTORS = 8;

    public static final int HEADER_FLOATS = 4;
    public static final int FLOATS_PER_ACTOR = 16;
    public static final long BYTE_SIZE =
            (long) (HEADER_FLOATS + MAX_ACTORS * FLOATS_PER_ACTOR) * Float.BYTES;

    /** Beyond this the actor is outside any plausible actor-centred field and is not published. */
    public static final double RANGE_BLOCKS = 34.0;

    public static final int KIND_NONE = 0;
    public static final int KIND_PLAYER = 1;
    public static final int KIND_BOAT = 2;
    public static final int KIND_OTHER = 3;

    private WaterActorBuffer() {}

    public static void ensureAllocated(TargetRegistry registry) {
        registry.ensureBufferSize(TARGET, BYTE_SIZE);
    }

    public static void free(TargetRegistry registry) {
        EngineBufferUploadQueue.discard(TARGET);
        registry.releaseBuffer(TARGET);
    }
}
