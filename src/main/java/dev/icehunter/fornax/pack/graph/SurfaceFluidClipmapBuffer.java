package dev.icehunter.fornax.pack.graph;

import java.util.function.IntUnaryOperator;

/**
 * Vulkan-free ABI for an engine-owned, player-centred surface-fluid field.
 *
 * <p>Every toroidal column stores eight exact 32-bit words -- two {@code ivec4}s, so shader-side
 * indexing stays aligned: signed world X, signed world Z, {@code floatBits(surfaceY)}, a packed
 * status word, then {@code floatBits(solidTopY)} and three reserved words written zero. Exact coordinates make stale aliases
 * unknown rather than allowing a disconnected body to masquerade as the current one. Loaded dry
 * columns are valid records with kind {@link #FLUID_DRY}; an all-zero/unmatched record is unknown
 * and consumers must fail closed.
 *
 * <p>{@code surfaceY} is the top of whatever the column's exposed surface is: the fluid surface for
 * a wet column, the solid top for a dry one. {@code solidTopY} is the top plane of the highest
 * NON-FLUID motion-blocking block, or {@link #NO_SOLID_TOP} where the topmost such block is the
 * fluid itself. Open water therefore has no solid top, while a deck laid over water has one above
 * its own fluid surface -- which is the difference a wash test turns on.
 *
 * <p>The two are separate because a column can be both at once. A plank deck laid over a lake has
 * water beneath it and a walkable top above: its exposed fluid surface sits under the deck while
 * the surface a wave would wash over is the deck itself. A single height cannot answer both, and a
 * consumer given only the fluid one reads the whole structure as open water.
 *
 * <p>Status word layout, low bit first: kind in bits 0-7, quantized flow X in 8-15 and flow Z in
 * 16-23 (signed bytes, {@link #FLOW_SCALE} per unit), the flowing flag in bit 24, bits 25-30
 * reserved and written zero, {@link #VALID_BIT} in bit 31. Flow is vanilla's own fluid flow vector,
 * not a derived gradient, and is zero for dry columns and fluid source blocks.
 */
public final class SurfaceFluidClipmapBuffer {
    public static final String TARGET = "surfaceFluidClipmap";

    public static final int GRID = 64;
    public static final int COLUMNS = GRID * GRID;
    public static final int WORDS_PER_COLUMN = 8;
    public static final int BYTES_PER_COLUMN = WORDS_PER_COLUMN * Integer.BYTES;
    public static final long BYTE_SIZE = (long) COLUMNS * BYTES_PER_COLUMN;
    public static final int ANCHOR_SNAP = 16;

    public static final int FLUID_DRY = 0;
    public static final int FLUID_WATER = 1;
    public static final int FLUID_LAVA = 2;
    public static final int VALID_BIT = 0x8000_0000;
    public static final int FLUID_KIND_MASK = 0xFF;

    public static final int FLOW_X_SHIFT = 8;
    public static final int FLOW_Z_SHIFT = 16;
    public static final int FLOW_FLAG = 1 << 24;
    /** Signed-byte quantization of a unit flow vector; 1/127 is far under a texel of visible turn. */
    public static final float FLOW_SCALE = 127.0f;

    /**
     * {@code solidTopY} for a column whose topmost motion-blocking block is fluid -- open water, or
     * lava. Far below any buildable Y so a plain {@code >} comparison against a water line excludes
     * it without a separate flag, and finite rather than an infinity so no driver has to agree with
     * us about how one compares.
     */
    public static final float NO_SOLID_TOP = -30000.0f;

    public static final int TIER_OFF = 0;
    public static final int TIER_STANDARD = 1;
    public static final int TIER_QUALITY = 2;

    /**
     * How far below a column's motion-blocking top the fluid search may reach. It buys overhangs:
     * at depth 1 a dock plank or a bridge deck hides the water under it and the column reads dry.
     * It is NOT a vertical extent for the field as a whole -- every column is searched from its own
     * top, so two bodies at unrelated elevations resolve in the same window regardless of tier.
     */
    private static final int STANDARD_SEARCH_DEPTH = 4;
    private static final int QUALITY_SEARCH_DEPTH = 8;

    /** Vanilla resolves a flow vector from the four horizontal neighbours and the block under each. */
    private static final int FLOW_READS_PER_HIT = 8;

    private SurfaceFluidClipmapBuffer() {}

    public static void ensureAllocated(TargetRegistry registry) {
        registry.ensureBufferSize(TARGET, BYTE_SIZE);
    }

    public static void free(TargetRegistry registry) {
        EngineBufferUploadQueue.discard(TARGET);
        registry.releaseBuffer(TARGET);
    }

    public static int slotFor(int worldX, int worldZ) {
        return (worldZ & (GRID - 1)) * GRID + (worldX & (GRID - 1));
    }

    public static int wordOffsetFor(int worldX, int worldZ) {
        return slotFor(worldX, worldZ) * WORDS_PER_COLUMN;
    }

    public static int windowBase(int anchor) {
        return Math.floorDiv(anchor, ANCHOR_SNAP) * ANCHOR_SNAP - GRID / 2;
    }

    public static void writeRecord(int[] words, int offset, int worldX, int worldZ,
                                   float surfaceY, float solidTopY, int fluidKind,
                                   double flowX, double flowZ) {
        words[offset] = worldX;
        words[offset + 1] = worldZ;
        words[offset + 2] = Float.floatToRawIntBits(surfaceY);
        words[offset + 3] = VALID_BIT | (fluidKind & FLUID_KIND_MASK) | packFlow(flowX, flowZ);
        words[offset + 4] = Float.floatToRawIntBits(solidTopY);
        words[offset + 5] = 0;
        words[offset + 6] = 0;
        words[offset + 7] = 0;
    }

    public static boolean describes(int[] words, int offset, int worldX, int worldZ) {
        return (words[offset + 3] & VALID_BIT) != 0
                && words[offset] == worldX && words[offset + 1] == worldZ;
    }

    public static float surfaceY(int[] words, int offset) {
        return Float.intBitsToFloat(words[offset + 2]);
    }

    /** Top plane of the column's highest motion-blocking block, whether or not it also holds fluid. */
    public static float solidTopY(int[] words, int offset) {
        return Float.intBitsToFloat(words[offset + 4]);
    }

    public static int fluidKind(int[] words, int offset) {
        return words[offset + 3] & FLUID_KIND_MASK;
    }

    public static float flowX(int[] words, int offset) {
        return (byte) (words[offset + 3] >>> FLOW_X_SHIFT) / FLOW_SCALE;
    }

    public static float flowZ(int[] words, int offset) {
        return (byte) (words[offset + 3] >>> FLOW_Z_SHIFT) / FLOW_SCALE;
    }

    /** True only where the flow quantized to something non-zero, so a still source reads false. */
    public static boolean isFlowing(int[] words, int offset) {
        return (words[offset + 3] & FLOW_FLAG) != 0;
    }

    static int packFlow(double flowX, double flowZ) {
        int quantizedX = quantizeFlow(flowX);
        int quantizedZ = quantizeFlow(flowZ);
        int flowing = (quantizedX != 0 || quantizedZ != 0) ? FLOW_FLAG : 0;
        return ((quantizedX & 0xFF) << FLOW_X_SHIFT) | ((quantizedZ & 0xFF) << FLOW_Z_SHIFT) | flowing;
    }

    private static int quantizeFlow(double component) {
        return (int) Math.round(Math.max(-1.0, Math.min(1.0, component)) * FLOW_SCALE);
    }

    /** {@link #findExposedFluidY} found no fluid within the tier's search depth. */
    public static final int NO_SURFACE = Integer.MIN_VALUE;

    /**
     * World Y of the first exposed fluid at or below {@code top - 1}, or {@link #NO_SURFACE}.
     *
     * <p>{@code top} is the column's own motion-blocking heightmap value, which names the first
     * free Y above the highest such block. Searching from it makes elevation a per-column property:
     * a river and a lake eight blocks apart resolve in the same window at any tier, neither being
     * measured against the other. Exposed means the block above holds a different fluid, so only
     * the topmost surface of a stack is reported.
     *
     * @param fluidKindAt world Y to fluid kind; must answer for {@code top} as well as below it
     */
    public static int findExposedFluidY(int top, int searchDepth, IntUnaryOperator fluidKindAt) {
        for (int drop = 0; drop < searchDepth; drop++) {
            int y = top - 1 - drop;
            int kind = fluidKindAt.applyAsInt(y);
            if (kind != FLUID_DRY && fluidKindAt.applyAsInt(y + 1) != kind) return y;
        }
        return NO_SURFACE;
    }

    public static int searchDepth(int tier) {
        return switch (tier) {
            case TIER_STANDARD -> STANDARD_SEARCH_DEPTH;
            case TIER_QUALITY -> QUALITY_SEARCH_DEPTH;
            default -> 0;
        };
    }

    public static int rowsPerFrame(int tier) {
        return switch (tier) {
            case TIER_STANDARD -> 8;
            case TIER_QUALITY -> 16;
            default -> 0;
        };
    }

    /**
     * Worst case: every candidate depth costs a read for the candidate and one for the block above
     * it, and every column then resolves a flow vector. Typical cost is far lower -- open water
     * hits on the first candidate, and dry columns never reach the flow lookup.
     */
    public static int maxFluidStateReadsPerFrame(int tier) {
        return rowsPerFrame(tier) * GRID * (2 * searchDepth(tier) + FLOW_READS_PER_HIT);
    }
}
