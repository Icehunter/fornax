package dev.icehunter.fornax.pack.graph;

/**
 * Vulkan-free ABI for an engine-owned, player-centred surface-fluid field.
 *
 * <p>Every toroidal column stores four exact 32-bit words: signed world X, signed world Z,
 * {@code floatBits(surfaceY)}, and a fluid kind. Exact coordinates make stale aliases unknown rather
 * than allowing a disconnected body to masquerade as the current one. Loaded dry columns are valid
 * records with kind {@link #FLUID_DRY}; an all-zero/unmatched record is unknown and consumers must
 * fail closed.
 */
public final class SurfaceFluidClipmapBuffer {
    public static final String TARGET = "surfaceFluidClipmap";

    public static final int GRID = 64;
    public static final int COLUMNS = GRID * GRID;
    public static final int WORDS_PER_COLUMN = 4;
    public static final int BYTES_PER_COLUMN = WORDS_PER_COLUMN * Integer.BYTES;
    public static final long BYTE_SIZE = (long) COLUMNS * BYTES_PER_COLUMN;
    public static final int ANCHOR_SNAP = 16;

    public static final int FLUID_DRY = 0;
    public static final int FLUID_WATER = 1;
    public static final int FLUID_LAVA = 2;
    public static final int VALID_BIT = 0x8000_0000;
    public static final int FLUID_KIND_MASK = 0xFF;

    public static final int TIER_OFF = 0;
    public static final int TIER_STANDARD = 1;
    public static final int TIER_QUALITY = 2;

    private static final int[] NO_OFFSETS = {};
    private static final int[] STANDARD_OFFSETS = {0, -1, 1};
    private static final int[] QUALITY_OFFSETS = {0, -1, 1, -2, 2, -3, 3, -4, 4};

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
                                   float surfaceY, int fluidKind) {
        words[offset] = worldX;
        words[offset + 1] = worldZ;
        words[offset + 2] = Float.floatToRawIntBits(surfaceY);
        words[offset + 3] = VALID_BIT | (fluidKind & FLUID_KIND_MASK);
    }

    public static boolean describes(int[] words, int offset, int worldX, int worldZ) {
        return (words[offset + 3] & VALID_BIT) != 0
                && words[offset] == worldX && words[offset + 1] == worldZ;
    }

    public static float surfaceY(int[] words, int offset) {
        return Float.intBitsToFloat(words[offset + 2]);
    }

    public static int fluidKind(int[] words, int offset) {
        return words[offset + 3] & FLUID_KIND_MASK;
    }

    public static int[] verticalOffsets(int tier) {
        return switch (tier) {
            case TIER_STANDARD -> STANDARD_OFFSETS.clone();
            case TIER_QUALITY -> QUALITY_OFFSETS.clone();
            default -> NO_OFFSETS.clone();
        };
    }

    public static int rowsPerFrame(int tier) {
        return switch (tier) {
            case TIER_STANDARD -> 8;
            case TIER_QUALITY -> 16;
            default -> 0;
        };
    }

    /** Worst case: one read for the candidate and one for the block above it. */
    public static int maxFluidStateReadsPerFrame(int tier) {
        return 2 * rowsPerFrame(tier) * GRID * verticalOffsets(tier).length;
    }
}
