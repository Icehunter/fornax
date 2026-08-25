package dev.icehunter.fornax.atlas;

import java.util.OptionalLong;

/**
 * Sizes a labPBR sidecar atlas from the SIDECARS' own resolution rather than the albedo's.
 *
 * <p><b>The bug this fixes.</b> Both sidecar atlases used to be built at exactly half the block
 * atlas's dimensions, and each sprite's rectangle at half that sprite's ALBEDO size. A pack shipping
 * 512px {@code _n} maps over 64px colour therefore had every one of them resampled down to a 32px
 * rectangle -- a 16x loss, silently, with nothing in the log to say so. The user built such a pack
 * specifically to measure how much of the look is carried by map resolution rather than colour
 * resolution; before this, that experiment could only ever have measured nothing.
 *
 * <p>Scale zero is identity. Source data is reduced only when the device dimension limit or the
 * explicit resident-byte budget requires it; there is no unconditional quality divisor.
 *
 * <p><b>The layout stays normalised, and that is not negotiable.</b> The sidecar atlas mirrors the
 * block atlas's UV layout exactly -- {@code spriteToAtlas}, {@code SpriteBoundsTexture} and
 * {@code SpriteHeightRanges} all key off normalised UV, and a pixel-space assumption across that
 * boundary has already cost one long diagnosis (every height range filed at half its true position).
 * So a sprite's slot is a fixed FRACTION of the atlas, and the only way to give it more texels is to
 * make the whole atlas bigger. Hence a single global {@link #log2Scale} rather than per-sprite
 * packing.
 *
 * <p>REJECTED: packing each sidecar at its own natural size into a free-form atlas with an
 * indirection table. It wastes nothing on a mixed pack, and it requires every shader that samples a
 * sidecar to first remap its UV through that table -- replacing the one unit both atlases agree on
 * with a lookup, in exactly the place the last unit mismatch hid. Not worth it: the waste it avoids
 * is a sprite whose maps are LOWER-resolution than the loudest sprite's, which on a real pack is
 * rare (the user's packs are uniform: every ratio is 1, or every ratio is 8).
 */
public final class PbrSidecarAtlasScale {
    /** Scale zero retains the sidecar's surveyed source resolution. */
    public static final int PBR_ATLAS_LOG2_DIVISOR = 0;

    /**
     * The most bytes one sidecar atlas may hold resident, including its mip chain, when a real VRAM
     * reading is unavailable (GL backend, or the Vulkan query in {@link
     * dev.icehunter.fornax.util.GpuMemoryEstimator#detectedVramBytesFromDevice} failed): the
     * fallback path. {@link #effectiveMaxAtlasBytes} is the primary path once a live device is in
     * hand, and derives the ceiling from that device's real memory instead of this fixed number.
     *
     * <p>Derived against the hardware this actually has to run on, not a round number. The user's
     * machines span an RTX 5090, an M5 Pro, a MacBook Air and a GTX 1060 with 3-6 GB, and the 1060 is
     * the binding one. At this ceiling the worst real case -- their 64x-colour + 512x-maps pack --
     * lands both sidecar atlases at 8192x8192: 358 MB for the normal atlas with its mip chain and
     * 268 MB for the material atlas, 626 MB together, on top of a 89 MB block atlas. That is
     * comfortable on 6 GB, tight but survivable on 3 GB, and nothing on the others. Doubling it
     * would buy one more scale step (512px maps stored at 256px instead of 128px) for 2.5 GB, which
     * only the 5090 could hold.
     *
     * <p>Note what the block-atlas sidecar filter already bought here: excluding {@code _n}/{@code _s}
     * from vanilla's stitch takes that pack's block atlas from "will not load at all" to 89 MB, so
     * this budget is spent on maps that are actually sampled rather than on a second dead copy of
     * them.
     */
    static final long MAX_ATLAS_BYTES = 512L * 1024L * 1024L;

    /**
     * Share of real device-local VRAM ONE sidecar atlas allocation may claim, when a live reading is
     * available. Not 1/2 or 1/4: {@code SpriteLoaderPagedStitchMixin.FORNAX_VRAM_BUDGET_FRACTION}
     * already claims up to 1/2 of the same real-VRAM pool for the block atlas's overflow pages and
     * sprite-bounds grid, leaving 1/2 for everything else this engine and vanilla put on the GPU
     * (vanilla's own atlases, the G-buffer, MetalFX interop images). Of that remaining half, this
     * reserves half again for that non-sidecar overhead, leaving 1/4 of total real VRAM for BOTH
     * sidecar lanes (normal + material) combined. Each lane can itself be briefly doubled during a
     * resource-pack switch, per {@link LabPbrAtlasPair}'s own doc ("the replacement is visible before
     * the previous GPU objects are closed"), so up to 4 atlas-sized allocations (old normal, new
     * normal, old material, new material) can be resident at once. 1/4 total split 4 ways is 1/16
     * per allocation.
     */
    private static final double VRAM_SHARE_PER_ATLAS_FRACTION = 1.0 / 16.0;

    private static final int BYTES_PER_TEXEL = 4; // RGBA8_UNORM

    private PbrSidecarAtlasScale() {
    }

    /**
     * The resident-byte ceiling one sidecar atlas allocation should actually be built against: the
     * tighter of what the user's {@code SidecarMapResolution} tier asked for and what this device's
     * real VRAM can spare (see {@link #VRAM_SHARE_PER_ATLAS_FRACTION}). Falls back to {@link
     * #MAX_ATLAS_BYTES} when {@code realVramBytes} is empty (VRAM detection unavailable), same as
     * before this method existed.
     *
     * <p>This is what closes {@code SidecarMapResolution.FULL}'s own uncapped {@code Long.MAX_VALUE}:
     * FULL means "don't cap below what the pack authored," not "ignore how much VRAM actually
     * exists," and those are different claims: only the first one is FULL's to make.
     *
     * @param realVramBytes    {@link dev.icehunter.fornax.util.GpuMemoryEstimator#detectedVramBytesFromDevice},
     *                         empty when unavailable
     * @param tierMaxAtlasBytes the active {@code SidecarMapResolution} tier's own ceiling
     */
    public static long effectiveMaxAtlasBytes(OptionalLong realVramBytes, long tierMaxAtlasBytes) {
        long fromVram = realVramBytes.isPresent()
                ? (long) (realVramBytes.getAsLong() * VRAM_SHARE_PER_ATLAS_FRACTION)
                : MAX_ATLAS_BYTES;
        return Math.min(tierMaxAtlasBytes, fromVram);
    }

    /**
     * Chooses the atlas scale as a base-2 exponent over the block-atlas layout.
     *
     * <p>{@code 0} retains matching-resolution maps exactly. Positive values retain maps authored
     * above albedo resolution. Negative values are the explicit device/budget degradation path.
     *
     * @param blockAtlasWidth  the stitched block atlas's width
     * @param blockAtlasHeight the stitched block atlas's height
     * @param maxSidecarRatio  the largest {@code sidecarWidth / albedoWidth} over every sprite that
     *                         has a sidecar; 1 when the maps match the colour
     * @param maxDimension     the device's largest supported texture dimension
     * @param mipFactor        resident-size multiplier for this atlas's mip chain: {@code 4/3} for a
     *                         full chain, {@code 1} for a single level
     */
    public static int chooseLog2Scale(int blockAtlasWidth, int blockAtlasHeight, int maxSidecarRatio,
                                      int maxDimension, double mipFactor) {
        return chooseLog2Scale(blockAtlasWidth, blockAtlasHeight, maxSidecarRatio, maxDimension,
                               mipFactor, 0, MAX_ATLAS_BYTES);
    }

    /**
     * As {@link #chooseLog2Scale(int, int, int, int, double)}, with an explicit ceiling on the
     * scale exponent -- the user's {@code SidecarMapResolution} tier.
     *
     * <p>RELATIVE to what the pack asked for, not an absolute ceiling, and the distinction is
     * load-bearing. A pack may ship maps ABOVE its albedo resolution -- the surveyed ratio is then
     * greater than 1 -- and an absolute "half" would cut such a pack to an eighth of what it
     * authored. Applying the offset to the surveyed ratio keeps every tier meaning what its name
     * says however the pack is built. The loop below still only ever steps DOWN from there, and
     * {@code maxDimension} and the byte ceiling still bound the result.
     *
     * <p>Passing 0 reproduces the historical behaviour exactly: the offset vanishes, the surveyed
     * ratio stands, and the byte budget alone chooses as it always did. That is what the
     * five-argument form above passes.
     *
     * @param log2ScaleOffset offset on the scale the pack asked for; 0 keeps it and each step down
     *                        halves the sidecar per axis relative to what was authored
     * @param maxAtlasBytes resident-byte ceiling for this atlas including its mip chain. The FULL
     *                      tier passes {@code Long.MAX_VALUE}: a ceiling exists so a machine is not
     *                      surprised, and a user who explicitly asked for full resolution is not
     *                      being surprised. maxDimension and MIN_LOG2_SCALE still bound it.
     */
    public static int chooseLog2Scale(int blockAtlasWidth, int blockAtlasHeight, int maxSidecarRatio,
                                      int maxDimension, double mipFactor, int log2ScaleOffset,
                                      long maxAtlasBytes) {
        // The tier adjusts what the pack asked for, before the budget loop starts stepping down.
        int scale = ceilLog2(Math.max(1, maxSidecarRatio)) + log2ScaleOffset;
        // Step DOWN from what the pack asked for until it fits, never up from what fits: a pack that
        // ships no high-resolution maps must land on exactly 0 and take the historical path, and
        // searching upward from below would depend on the budget arithmetic to stop it there.
        while (scale > MIN_LOG2_SCALE
                && !fits(blockAtlasWidth, blockAtlasHeight, scale, maxDimension, mipFactor, maxAtlasBytes)) {
            scale--;
        }
        // The loop's short-circuit evaluates `scale > MIN_LOG2_SCALE` before `fits(...)`, so it can
        // exit at the floor without ever confirming the floor itself fits -- an unfitting scale would
        // otherwise be returned unqualified. That is not hypothetical: a block atlas wide enough
        // relative to maxDimension (e.g. 32768 against a 2048 device limit) reaches the floor still
        // over budget, `atlasDimension` still comes out positive there, so neither caller's existing
        // "invalid atlas size" bail-out catches it, and allocation proceeds to fail later, past where
        // either caller can skip gracefully.
        if (!fits(blockAtlasWidth, blockAtlasHeight, scale, maxDimension, mipFactor, maxAtlasBytes)) {
            throw new IllegalStateException(
                    "No labPBR atlas scale fits even at the floor (" + MIN_LOG2_SCALE + "): "
                            + blockAtlasWidth + "x" + blockAtlasHeight + " block atlas exceeds "
                            + "maxDimension=" + maxDimension + " or the resident-byte budget at every "
                            + "scale down to the floor");
        }
        return scale;
    }

    /**
     * The floor on {@link #chooseLog2Scale}. At -3 a sidecar sprite is 1/8 of its albedo on each
     * axis; below that the maps carry so little that leaving them out entirely would be more honest
     * than pretending to have them, and no device this runs on gets near it.
     */
    private static final int MIN_LOG2_SCALE = -3;

    private static boolean fits(int blockAtlasWidth, int blockAtlasHeight, int log2Scale,
                                int maxDimension, double mipFactor, long maxAtlasBytes) {
        int width = atlasDimension(blockAtlasWidth, log2Scale);
        int height = atlasDimension(blockAtlasHeight, log2Scale);
        if (width > maxDimension || height > maxDimension) {
            return false;
        }
        return (long) width * height * BYTES_PER_TEXEL * mipFactor <= maxAtlasBytes;
    }

    /** This atlas's dimension for a given block-atlas dimension, at {@code log2Scale}. */
    public static int atlasDimension(int blockAtlasDimension, int log2Scale) {
        return scaled(blockAtlasDimension, log2Scale);
    }

    /**
     * A sprite's rectangle size in this atlas, from its size in the block atlas.
     *
     * <p>Deliberately derived from the ALBEDO's size and the global scale rather than from the
     * sidecar's own dimensions directly: the rectangle has to be exactly the sprite's normalised
     * slot scaled to this atlas, or the layout stops matching the block atlas's and every shader
     * samples the wrong place. A sidecar bigger or smaller than the slot is resampled INTO it.
     */
    public static int spriteExtent(int albedoExtent, int log2Scale) {
        return scaled(albedoExtent, log2Scale);
    }

    /** Scales a zero-based atlas coordinate; unlike an extent, zero must remain zero. */
    public static int atlasCoordinate(int coordinate, int log2Scale) {
        int shift = log2Scale - PBR_ATLAS_LOG2_DIVISOR;
        return shift >= 0 ? coordinate << shift : coordinate >> -shift;
    }

    private static int scaled(int value, int log2Scale) {
        return Math.max(1, atlasCoordinate(value, log2Scale));
    }

    /** {@code ceil(log2(value))} for {@code value >= 1}; 0 for 1. */
    static int ceilLog2(int value) {
        if (value <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(value - 1);
    }
}
