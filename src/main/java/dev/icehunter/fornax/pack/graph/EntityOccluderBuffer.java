package dev.icehunter.fornax.pack.graph;

/**
 * Vulkan-free layout for the limited set of nearby bodies a pack's local coloured-light shadow
 * march may block against.
 *
 * <p>The engine's voxel grid is built only from block states, so a march reading it alone never
 * sees the player, a mob or an item standing inside a light's radius. This sends where each body
 * is, how big it is, which way it faces and what kind it is; the pack decides entirely on its own
 * how any of that shapes a shadow. Nothing here sets a soft edge, how blurry it is, or which kinds
 * a given light should block against.
 *
 * <p>Positions are sent camera-relative, worked out as a difference in {@code double} before
 * they become float, for the same reason {@link WaterActorBuffer} does: a march centred on the
 * camera needs an offset from its own origin, and handing it two absolute world coordinates to
 * subtract in {@code float} would throw away the low bits of the number it cares about once the
 * player is far from the world origin.
 *
 * <p>SLOT 0 IS ALWAYS THE LOCAL PLAYER whenever sent, first person included, and it is {@code
 * client.player} itself, not the root vehicle it may be riding. The body blocks its own held
 * light, not whatever it is riding. A pack checks {@code kind}, never slot, to find it. Every
 * other slot is a different body, nearest first by distance from the camera to the box's centre,
 * so a cap cuts off the bodies least likely to be seen. A spectator, an invisible body, a dead
 * body, or one at a position that is not finite is never sent. Passengers are sent too, unlike
 * {@link WaterActorBuffer}'s boats: each body casts its own shadow, while a boat and its rider
 * only ever raise one shared wake.
 *
 * <p>Layout, std430, all 32-bit floats, read by the pack as {@code R32_UINT} texels and
 * read back as floats:
 *
 * <pre>
 *   vec4 header;                  // x live count, y frame counter (wraps at 2^24 so it stays
 *                                 //   exact as a float), z RANGE_BLOCKS, w unused
 *   struct {
 *     vec4 boundsMin;             // xyz box min, camera-relative, w kind
 *     vec4 boundsMax;             // xyz box max, camera-relative, w body yaw (radians)
 *     vec4 motion;                // xyz centre change since the last frame, w eye height
 *   } occluders[MAX_OCCLUDERS];
 * </pre>
 */
public final class EntityOccluderBuffer {
    public static final String TARGET = "entityOccluders";

    public static final int HEADER_FLOATS = 4;
    public static final int FLOATS_PER_OCCLUDER = 12;

    /**
     * How many bodies a pack's shadow march may block against at once.
     *
     * <p>What sets the limit is what the reader pays, the same way
     * {@link WaterActorBuffer#MAX_ACTORS} states its own: every record sent costs a box check
     * plus three texel reads, paid once for each shadow ray per pixel, and the header count only
     * skips the work for the empty case. A village or an animal pen needs more than eight bodies
     * at once, so the bound sits at 64; a pack that wants more than that needs a spatial grid
     * instead of a flat list.
     */
    public static final int MAX_OCCLUDERS = 64;

    public static final long BYTE_SIZE =
            (long) (HEADER_FLOATS + MAX_OCCLUDERS * FLOATS_PER_OCCLUDER) * Float.BYTES;

    /**
     * At 48 blocks a player-height body still spans tens of pixels at 1080p; beyond that its
     * shadow shrinks below a pixel and sending it would only fill the list.
     */
    public static final double RANGE_BLOCKS = 48.0;

    public static final int KIND_NONE = 0;
    public static final int KIND_PLAYER = 1;
    public static final int KIND_OTHER_PLAYER = 2;
    public static final int KIND_LIVING = 3;
    public static final int KIND_ITEM = 4;
    public static final int KIND_OTHER = 5;

    private EntityOccluderBuffer() {}

    public static void ensureAllocated(TargetRegistry registry) {
        registry.ensureBufferSize(TARGET, BYTE_SIZE);
    }

    public static void free(TargetRegistry registry) {
        EngineBufferUploadQueue.discard(TARGET);
        registry.releaseBuffer(TARGET);
    }
}
