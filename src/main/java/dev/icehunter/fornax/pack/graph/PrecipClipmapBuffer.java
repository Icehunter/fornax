package dev.icehunter.fornax.pack.graph;

/**
 * The engine-owned PER-COLUMN PRECIPITATION TYPE field: for every world block column in a 128x128
 * window around the player, what vanilla says falls there -- nothing, rain, or snow.
 *
 * <p>WHY THE ENGINE HAS TO OWN THIS. Fornax already exposes precipitation type two ways, and neither
 * can reach a compute pass. The per-block lane rides {@code a_Normal.w} in the chunk mesh
 * ({@code FornaxChunkVertex}), so only a geometry stage sees it. The uniform lane
 * ({@code u_CameraSkyLight.y}, written by {@code GlobalUniformsWriteMixin}) is sampled at the CAMERA,
 * so it answers for exactly one column and a shader that needs a field gets one number. A pack doing
 * ground weather -- snow that accumulates, wetness that pools -- needs the field, and the only place
 * a field can come from is here.
 *
 * <p>The symptom that motivated it, reported from a live game: a snow accumulation pass reading the
 * camera lane told its whole 128x128 field "snow" whenever the player stood near a taiga, so a
 * snowfield ran straight across the boundary into a green, rainy forest -- and stepping back into the
 * plains melted the taiga's snow along with it. Melt is the visible direction because it destroys
 * accumulated state rather than adding to it.
 *
 * <h2>Addressing: toroidal, self-identifying, and no anchor uniform</h2>
 *
 * Element index is {@code (worldZ & 127) * 128 + (worldX & 127)} -- a pure function of the world
 * column, so a column keeps one slot for the whole session and nothing has to be re-indexed or
 * resampled when the window moves. That is the same addressing {@code snowField} uses, deliberately:
 * a consumer indexes both with one expression.
 *
 * <p>Each element carries its own 128-block TILE TAG in bits 16..31, in exactly the bit positions
 * {@code snowField}'s element tag uses, so a consumer's existing {@code wantTile} expression works
 * unchanged. The tag is what makes the CPU and GPU sides independent: neither has to agree with the
 * other about where the window is, because a stale or aliased element says so itself. Get the window
 * wrong and columns report "no data" -- which a consumer must treat as "do not integrate", never as
 * "no precipitation" -- rather than reporting a neighbour's biome as their own.
 *
 * <p>{@link #ANCHOR_SNAP} is therefore a completeness contract and not a correctness one. A consumer
 * that snaps its window differently still reads only correct data; it just finds a band of columns at
 * one rim marked unknown until the fill sweeps past. Matching it makes the coverage exact.
 *
 * <h2>Why a whole uint per column and not the packed byte the design sketch called for</h2>
 *
 * Three bits would do for the type. The tag is what costs, and it buys away the entire class of bug
 * where two sides disagree about which column an element describes -- for 64 KiB total, fixed, at any
 * render resolution. It also lands on the engine's existing single-format convention for buffer
 * targets ({@code FullscreenPassRunner}: every buffer input is R32_UINT), the same reasoning that
 * made {@code BrickGridUpload.BRICK_SUMMARY_TARGET} a whole word rather than a packed bit.
 *
 * <p>NO VULKAN AND NO MINECRAFT IMPORTS, and that is structural rather than tidy: {@link
 * GraphValidator} names {@link #TARGET} in {@code ENGINE_BUFFERS}, and a class whose {@code <clinit>}
 * dragged in LWJGL or Sodium would break every headless test that touches graph validation. This is
 * the {@link AnalyticLightListBuffer} shape for exactly that reason. The harvest lives in
 * {@code dev.icehunter.fornax.voxel.PrecipClipmapUpload} and uploads through
 * {@link EngineBufferUploadQueue}.
 */
public final class PrecipClipmapBuffer {
    public static final String TARGET = "precipClipmap";

    /** Columns per axis. Must match the consuming pack's own field extent. */
    public static final int GRID = 128;
    public static final int COLUMNS = GRID * GRID;
    private static final int BYTES_PER_COLUMN = 4;
    public static final long BYTE_SIZE = (long) COLUMNS * BYTES_PER_COLUMN; // 65536 bytes

    /**
     * The window base is the anchor floor-divided by this and multiplied back, then offset by half a
     * grid. Snapping means the COVERED SET changes only when the player crosses a 16-block boundary,
     * so a column at the far rim is not thrashed in and out of coverage by sub-block anchor jitter --
     * and every such change costs a full re-fill of the columns that entered.
     */
    public static final int ANCHOR_SNAP = 16;

    // Element layout. Bits 8..15 are reserved and written zero (garbage-VRAM law).
    /** No precipitation falls on this column: a desert, a badlands, or the Nether. */
    public static final int TYPE_NONE = 0;
    public static final int TYPE_RAIN = 1;
    public static final int TYPE_SNOW = 2;
    private static final int TYPE_MASK = 0xFF;
    /** Bits 16..31, positioned to match {@code snowField}'s element tag exactly. */
    public static final int TAG_MASK = 0xFFFF0000;

    private PrecipClipmapBuffer() {}

    public static void ensureAllocated(TargetRegistry registry) {
        registry.ensureBufferSize(TARGET, BYTE_SIZE);
    }

    public static void free(TargetRegistry registry) {
        EngineBufferUploadQueue.discard(TARGET);
        registry.releaseBuffer(TARGET);
    }

    /** Toroidal element index for a world column. Total, by construction -- every column has one. */
    public static int slotFor(int worldX, int worldZ) {
        return (worldZ & (GRID - 1)) * GRID + (worldX & (GRID - 1));
    }

    /**
     * The identity half of an element, in bits 16..31.
     *
     * <p>Eight bits per axis of the 128-block tile index distinguishes the nearest 255 aliases per
     * axis; the first pair of columns this cannot tell apart is 128 * 256 = 32768 blocks apart, which
     * is 30 times Minecraft's world radius from any point a player can reach in one session.
     */
    public static int tagFor(int worldX, int worldZ) {
        return (((worldX >> 7) & 0xFF) << 16) | (((worldZ >> 7) & 0xFF) << 24);
    }

    public static int encode(int worldX, int worldZ, int precipType) {
        return (precipType & TYPE_MASK) | tagFor(worldX, worldZ);
    }

    /** Whether {@code element} was written for this exact column rather than inherited from an alias. */
    public static boolean describes(int element, int worldX, int worldZ) {
        return (element & TAG_MASK) == tagFor(worldX, worldZ);
    }

    public static int typeOf(int element) {
        return element & TYPE_MASK;
    }

    /**
     * The window's lowest world coordinate on one axis, for an anchor coordinate.
     *
     * <p>Floor division, not truncation: {@code -1 / 16} is 0 in Java and -1 under floor, and the
     * difference is a 16-block discontinuity in the window that only appears west or north of the
     * origin -- the classic negative-coordinate bug this addressing exists to avoid having at all.
     */
    public static int windowBase(int anchor) {
        return Math.floorDiv(anchor, ANCHOR_SNAP) * ANCHOR_SNAP - GRID / 2;
    }
}
