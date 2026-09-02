package dev.icehunter.fornax.pack.graph;

/**
 * Vulkan-free ABI for the engine-owned, coarse nearby-climate field.
 *
 * <p>Each cell is one four-by-four block column, described by four 32-bit words so a cell is a
 * whole {@code ivec4}, the alignment rule {@link SurfaceFluidClipmapBuffer} established for
 * shader-side indexing, with room left for lanes not yet needed:
 *
 * <table>
 * <tr><th>Word</th><th>Bits</th><th>Content</th></tr>
 * <tr><td>0</td><td>0..7</td><td>precipitation type, the caller's value</td></tr>
 * <tr><td>0</td><td>8</td><td>{@link #VALID_MASK}: this cell was sampled</td></tr>
 * <tr><td>0</td><td>16..31</td><td>eight-bit tile index per axis, the self-validating tag</td></tr>
 * <tr><td>1</td><td>0..15</td><td>height-adjusted surface temperature, signed, x{@link #TEMPERATURE_SCALE}</td></tr>
 * <tr><td>1</td><td>16..23</td><td>downfall, 0..255</td></tr>
 * <tr><td>1</td><td>24..31</td><td>category tags, {@link #TAG_HOT} .. {@link #TAG_MOUNTAIN}</td></tr>
 * <tr><td>2</td><td>0..15</td><td>base biome temperature, signed, same scale</td></tr>
 * <tr><td>2, 3</td><td>rest</td><td>reserved, written zero</td></tr>
 * </table>
 *
 * <p>Word 0 alone is a complete precipitation record. The validity bit
 * makes an all-zero reset word unambiguously unknown even in the tag-zero region. The tag rejects
 * the usual toroidal aliases, but repeats after {@link #TAG_PERIOD_CELLS} cells on either axis; it
 * is not an unbounded world-cell identity.
 *
 * <p>The temperature in word 1 is the number the precipitation classification thresholded at the
 * column's own surface height, not the biome's nominal value; word 2 carries the nominal value so
 * a consumer can tell "cold biome" from "high up". Both are world facts; what they mean for the
 * air is the pack's decision.
 *
 * <p><strong>Discontinuous-recenter invariant:</strong> an uploader must clear the backing buffer
 * and schedule a complete refill of the current window before any consumer dispatch after a
 * discontinuous recenter, including a teleport. No encoded word may survive that reset, because a
 * tag match alone cannot distinguish a sample from one tag period away.
 */
public final class PrecipCoarseClipmapBuffer {
    /** Engine-buffer target name that packs may declare without a size. */
    public static final String TARGET = "precipCoarseClipmap";

    /**
     * Authored from the owner's observed roughly 100-block weather boundary: four-block cells retain
     * useful boundary detail across the accepted 512-block nearby-weather window.
     */
    public static final int CELL_STRIDE = 4;

    /** The accepted 512-block nearby-weather window contains 128 cells on each axis. */
    public static final int GRID = 128;
    public static final int COLUMNS = GRID * GRID;

    /** One {@code ivec4} per cell; see the class doc for the lane layout. */
    public static final int WORDS_PER_CELL = 4;
    public static final int BYTES_PER_CELL = WORDS_PER_CELL * Integer.BYTES;
    public static final long BYTE_SIZE = (long) COLUMNS * BYTES_PER_CELL;

    public static final int WORD_PRECIPITATION = 0;
    public static final int WORD_CLIMATE = 1;
    public static final int WORD_BASE = 2;
    public static final int WORD_RESERVED = 3;

    /**
     * The accepted nearby-weather design moves coverage in 16-block steps, avoiding per-block
     * window churn while keeping the observed boundary inside the field.
     */
    public static final int ANCHOR_SNAP_BLOCKS = 16;

    /** Tag identity retains eight tile-index bits per axis in the upper sixteen-bit ABI field. */
    private static final int TAG_AXIS_BITS = 8;
    private static final int TAG_AXIS_MASK = (1 << TAG_AXIS_BITS) - 1;
    private static final int GRID_SHIFT = Integer.numberOfTrailingZeros(GRID);
    private static final int VALUE_MASK = 0xFF;
    /** Bit eight means this word was sampled; zero is always unknown after a reset. */
    public static final int VALID_MASK = 0x00000100;
    private static final int TAG_MASK = 0xFFFF0000;

    /** Number of cells after which the retained tag bits repeat on either world axis. */
    public static final int TAG_PERIOD_CELLS = GRID * (TAG_AXIS_MASK + 1);
    /** Block-coordinate equivalent of {@link #TAG_PERIOD_CELLS}. */
    public static final int TAG_PERIOD_BLOCKS = TAG_PERIOD_CELLS * CELL_STRIDE;

    /**
     * Fixed-point scale for both temperature lanes. Vanilla biome temperatures span about -0.7 to
     * 2.0 and the height adjustment subtracts fractions of a degree per block, so 1/256 resolves
     * every step the game itself can make while a signed 16-bit lane still reaches +-127.
     */
    public static final float TEMPERATURE_SCALE = 256.0f;
    private static final int TEMPERATURE_MASK = 0xFFFF;
    private static final int DOWNFALL_SHIFT = 16;
    private static final int DOWNFALL_MASK = 0xFF;
    private static final int TAGS_SHIFT = 24;
    private static final int TAGS_MASK = 0xFF;

    // Category tags in word 1's top byte. The first four are climate conventions shared by mods,
    // the last four are vanilla biome tags; all are facts the biome declares about itself.
    public static final int TAG_HOT = 1;
    public static final int TAG_COLD = 1 << 1;
    public static final int TAG_WET = 1 << 2;
    public static final int TAG_DRY = 1 << 3;
    public static final int TAG_OCEAN = 1 << 4;
    public static final int TAG_JUNGLE = 1 << 5;
    public static final int TAG_BADLANDS = 1 << 6;
    public static final int TAG_MOUNTAIN = 1 << 7;

    private PrecipCoarseClipmapBuffer() {}

    /** Allocates the fixed raw-data buffer only while an enabled graph pass declares this target. */
    public static void ensureAllocated(TargetRegistry registry) {
        registry.ensureBufferSize(TARGET, BYTE_SIZE);
    }

    /** Releases the buffer when no enabled graph pass consumes this engine data lane. */
    public static void free(TargetRegistry registry) {
        EngineBufferUploadQueue.discard(TARGET);
        registry.releaseBuffer(TARGET);
    }

    /** Maps a block coordinate to its enclosing coarse cell with floor division across the origin. */
    public static int cellForBlock(int block) {
        return Math.floorDiv(block, CELL_STRIDE);
    }

    /** Returns the centre-side block column that represents a coarse cell. */
    public static int representativeBlock(int cell) {
        return cell * CELL_STRIDE + CELL_STRIDE / 2;
    }

    /** Returns the toroidal storage slot (cell index) for a world cell. */
    public static int slotForCell(int cellX, int cellZ) {
        return (cellZ & (GRID - 1)) * GRID + (cellX & (GRID - 1));
    }

    /** Returns the index of a world cell's first word: its slot times {@link #WORDS_PER_CELL}. */
    public static int wordOffsetForCell(int cellX, int cellZ) {
        return slotForCell(cellX, cellZ) * WORDS_PER_CELL;
    }

    /** Returns the bounded upper-sixteen-bit tag for a world cell. */
    public static int tagForCell(int cellX, int cellZ) {
        return (((cellX >> GRID_SHIFT) & TAG_AXIS_MASK) << 16)
                | (((cellZ >> GRID_SHIFT) & TAG_AXIS_MASK) << 24);
    }

    /** Packs word 0: a precipitation value with the bounded tag of the cell it describes. */
    public static int encodeCell(int cellX, int cellZ, int value) {
        return (value & VALUE_MASK) | VALID_MASK | tagForCell(cellX, cellZ);
    }

    /**
     * Whether word 0's bounded tag matches this cell.
     *
     * <p>The match is reliable only between discontinuous-recenter resets; see this class's reset
     * invariant.
     */
    public static boolean describesCell(int encoded, int cellX, int cellZ) {
        return (encoded & VALID_MASK) != 0 && (encoded & TAG_MASK) == tagForCell(cellX, cellZ);
    }

    /** Packs word 1: height-adjusted temperature, downfall in 0..1, and the tag byte. */
    public static int encodeClimate(float temperatureAdjusted, float downfall, int tags) {
        int downfall255 = Math.round(Math.max(0.0f, Math.min(1.0f, downfall)) * DOWNFALL_MASK);
        return (fixedTemperature(temperatureAdjusted) & TEMPERATURE_MASK)
                | (downfall255 << DOWNFALL_SHIFT)
                | ((tags & TAGS_MASK) << TAGS_SHIFT);
    }

    /** Packs word 2: the biome's nominal temperature. */
    public static int encodeBase(float temperatureBase) {
        return fixedTemperature(temperatureBase) & TEMPERATURE_MASK;
    }

    /** The temperature in a word's low sixteen bits, for words 1 and 2 alike. */
    public static float decodeTemperature(int word) {
        return (short) (word & TEMPERATURE_MASK) / TEMPERATURE_SCALE;
    }

    /** Downfall from word 1, back in 0..1. */
    public static float decodeDownfall(int climateWord) {
        return ((climateWord >>> DOWNFALL_SHIFT) & DOWNFALL_MASK) / (float) DOWNFALL_MASK;
    }

    /** The tag byte from word 1. */
    public static int decodeTags(int climateWord) {
        return (climateWord >>> TAGS_SHIFT) & TAGS_MASK;
    }

    private static int fixedTemperature(float temperature) {
        float scaled = temperature * TEMPERATURE_SCALE;
        return Math.round(Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled)));
    }

    /** Returns the lowest coarse-cell coordinate in the player-centred window. */
    public static int windowBaseCell(int anchorBlock) {
        return Math.floorDiv(anchorBlock, ANCHOR_SNAP_BLOCKS)
                * (ANCHOR_SNAP_BLOCKS / CELL_STRIDE) - GRID / 2;
    }
}
