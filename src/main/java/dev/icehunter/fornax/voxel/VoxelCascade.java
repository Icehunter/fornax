package dev.icehunter.fornax.voxel;

/**
 * Pure tier geometry for the voxel light cascade (clipmap LOD) -- see
 * {@code docs/superpowers/specs/2026-07-25-voxel-light-cascade-design.md}.
 *
 * <p>Every tier holds the SAME buffers at the SAME slot count ({@link #TIER_DIAMETER}^3) with the
 * SAME 16^3 voxels per brick; only the world-space size of a brick differs, doubling per tier. That
 * single-parameter difference is what keeps the shader and addressing changes small -- {@code
 * slotOf}/{@code floorMod} addressing is identical at every tier, operating on tier-local section
 * coordinates.
 *
 * <p>No GPU or Minecraft types here on purpose: this is the source of truth for the 16/32/64/128
 * tier ladder itself, so every consumer derives tier SIZES from it rather than hardcoding them.
 * It does not (yet) own every per-section constant downstream code uses -- e.g. {@code
 * BrickGridUpload}'s {@code VOXELS_PER_SECTION} and {@code VoxelDebugRaymarchPass}'s {@code
 * BLOCKS_PER_SECTION} both still hardcode 16 independently; unifying those onto this class's
 * constants is later work, not a claim this class already makes good on.
 */
public final class VoxelCascade {
    /** Tiers in the ladder: 16/32/64/128-block bricks, reaching 8/16/32/64 chunks. */
    public static final int MAX_TIERS = 4;

    /** Slots per axis in EVERY tier. 17 (odd, so a tier is centred on a slot) -> 4,913 slots/tier. */
    public static final int TIER_DIAMETER = 17;

    /** Blocks per brick edge at tier 0 -- one Minecraft section, matching the pre-cascade grid. */
    public static final int BASE_BRICK_BLOCKS = 16;

    /** Voxels per brick edge, identical at every tier (only the blocks each voxel covers changes). */
    public static final int VOXELS_PER_BRICK_AXIS = 16;

    /**
     * Slot radius: {@code (TIER_DIAMETER - 1) / 2}. Public because {@code VoxelWindow.WindowState.of}
     * (and any other consumer that windows around a centre slot rather than sizing a whole diameter)
     * takes a RADIUS, not a diameter -- deriving it from {@link #TIER_DIAMETER} at every call site
     * would just re-divide this same constant back out.
     */
    public static final int SLOT_RADIUS = (TIER_DIAMETER - 1) / 2;

    private VoxelCascade() {
    }

    /** Blocks per brick edge at {@code tier}: 16, 32, 64, 128. */
    public static int brickBlocks(int tier) {
        validateTier(tier);
        return BASE_BRICK_BLOCKS << tier;
    }

    /** Blocks one voxel spans at {@code tier}: 1, 2, 4, 8. */
    public static int blocksPerVoxel(int tier) {
        return brickBlocks(tier) / VOXELS_PER_BRICK_AXIS;
    }

    /**
     * Radius in blocks that {@code tier} covers from the camera: 128, 256, 512, 1024.
     *
     * <p>This is a Chebyshev (cube half-extent) guarantee, not merely a nominal figure: for a window
     * of {@link #SLOT_RADIUS} slots centred on the camera's containing brick, coverage is at least
     * {@code SLOT_RADIUS * brickBlocks(tier)} in EVERY direction regardless of where within its own
     * brick the camera sits -- the window's outer slot boundary is a full slot further out than the
     * camera's brick origin even in the worst case of the camera sitting at that brick's near edge.
     * That is why {@code tierFor(128, 4) == 0} is correct and not an off-by-one: 128 is the guaranteed
     * worst case, not an optimistic best case.
     *
     * <p><b>This invariant depends on each tier being centred on its OWN brick grid</b> -- a tier-N
     * window is {@link #SLOT_RADIUS} bricks-of-that-tier's-size in every direction from the camera's
     * containing tier-N brick, never derived from tier-0 section coordinates. A future consumer (the
     * clipmap window itself) must preserve that per-tier centring, or this guarantee silently stops
     * holding at coarse tiers.
     */
    public static int reachBlocks(int tier) {
        return SLOT_RADIUS * brickBlocks(tier);
    }

    /**
     * How many tiers must be allocated to cover {@code renderDistanceChunks} -- the finest ladder
     * prefix that reaches at least as far as terrain is being drawn. Always at least 1 (a headless
     * or zero render distance still needs tier 0), never more than {@link #MAX_TIERS} (a render
     * distance past T3's 64 chunks clamps rather than growing the ladder).
     */
    public static int tierCountFor(int renderDistanceChunks) {
        int neededBlocks = Math.max(renderDistanceChunks, 1) * BASE_BRICK_BLOCKS;
        for (int tier = 0; tier < MAX_TIERS; tier++) {
            if (reachBlocks(tier) >= neededBlocks) {
                return tier + 1;
            }
        }
        return MAX_TIERS;
    }

    /**
     * The finest tier whose reach contains a point {@code distanceBlocks} from the camera, given
     * {@code tierCount} tiers actually allocated -- or {@code -1} when the point lies beyond every
     * allocated tier, which callers must treat as "no voxel data here", never as tier {@code
     * tierCount - 1}.
     *
     * <p>{@code distanceBlocks} is expected to be a Chebyshev distance (cube half-extent, matching
     * {@link #reachBlocks}'s own metric), NOT Euclidean -- a shader consumer computing distance with
     * {@code length()} will pass a Euclidean value instead, since that is the natural GLSL idiom.
     * That mismatch is conservative-only and never incorrect in the unsafe direction: Euclidean
     * distance is always >= Chebyshev distance for the same point, so a Euclidean caller can only ever
     * select a coarser (or equally fine) tier than the true Chebyshev answer would, and only at cube
     * corners where the two metrics diverge -- it can never select a finer, unallocated tier and read
     * past the window's real coverage.
     */
    public static int tierFor(int distanceBlocks, int tierCount) {
        int allocated = Math.min(Math.max(tierCount, 1), MAX_TIERS);
        for (int tier = 0; tier < allocated; tier++) {
            if (distanceBlocks <= reachBlocks(tier)) {
                return tier;
            }
        }
        return -1;
    }

    /**
     * Package-private (not private) so {@link BrickGridUpload#targetName} can reject an out-of-range
     * tier BEFORE minting a registry key for it -- {@code TargetRegistry.ensureBufferSize} keys on
     * arbitrary strings, so an unvalidated tier (e.g. {@code -1} from a caller that forgot to check
     * {@link #tierFor}'s sentinel) would otherwise silently allocate a phantom buffer nothing reads
     * and nothing frees, rather than crashing at the point the bad tier was actually produced. Same
     * package as {@code BrickGridUpload} ({@code dev.icehunter.fornax.voxel}), so package-private is
     * the minimal visibility that reaches it.
     */
    static void validateTier(int tier) {
        if (tier < 0 || tier >= MAX_TIERS) {
            throw new IllegalArgumentException(
                "Tier " + tier + " out of valid range [0, " + MAX_TIERS + "). " +
                "Note: tierFor() returns -1 to indicate 'no coverage at this distance'; " +
                "check for -1 before passing tierFor's result to tier geometry methods."
            );
        }
    }
}
