package dev.icehunter.fornax.pipeline;

import net.minecraft.world.level.biome.Biome;

/**
 * Per-build-thread scratch for the per-BLOCK facts the chunk vertex encoder needs but cannot
 * derive from a vertex: the material category id, what falls out of the sky on this block's biome,
 * and how much light the block itself emits.
 *
 * <p>Set at {@code BlockRenderer.renderModel} HEAD and cleared at RETURN, which scopes it to exactly
 * one block on the calling build thread. A ThreadLocal rather than a field because chunk building is
 * parallel and each worker is meshing a different block at the same instant.
 */
public final class MaterialIdContext {
    private static final int ID_SLOT = 0;
    private static final int PRECIPITATION_SLOT = 1;
    private static final int LIGHT_EMISSION_SLOT = 2;
    private static final int BLOCK_CLASS_SLOT = 3;
    private static final int PAGE_SLOT = 4;

    /** Vanilla's light emission range: {@code Block.getLightEmission()} returns 0..15 inclusive. */
    public static final int MAX_LIGHT_EMISSION = 15;

    /** Nothing falls on this biome -- desert, savanna, badlands, the nether. */
    public static final int PRECIPITATION_NONE = 0;
    /** Rain falls here, so surfaces get wet and puddles form. */
    public static final int PRECIPITATION_RAIN = 1;
    /**
     * Snow falls here. Precipitation, but NOT wetness: snow settles on a surface, it does not soak
     * it, so a consumer asking "is this wet" must test for RAIN specifically and never for
     * "not NONE". The distinction is the entire reason this lane is a type and not a boolean --
     * see {@link #setPrecipitation(Biome.Precipitation)}.
     */
    public static final int PRECIPITATION_SNOW = 2;

    private static final ThreadLocal<int[]> CURRENT = ThreadLocal.withInitial(() -> new int[5]);

    private MaterialIdContext() {}

    public static void set(int id) {
        CURRENT.get()[ID_SLOT] = id & 0xFFFF;
    }

    public static int get() {
        return CURRENT.get()[ID_SLOT];
    }

    /**
     * What falls on this block's biome, as {@link #PRECIPITATION_NONE}/{@link #PRECIPITATION_RAIN}/
     * {@link #PRECIPITATION_SNOW}.
     *
     * <p>Per-BLOCK rather than per-camera, which is the whole point: rain level is world-global while
     * vanilla only drops precipitation in biomes whose {@code has_precipitation} is true and turns it
     * to snow by biome temperature and altitude, so a camera-based answer is wrong on both sides of
     * every border -- it wets a savanna beach whenever the player stands on the ocean beside it, and
     * (the bug this type replaced the boolean to fix) it puddles a whole snowfield while the player
     * stands in the rain next to it, then dries the entire world the instant they step across.
     * Carried through the mesh, this is correct at every border with no extra bandwidth -- it rides
     * {@code a_Normal.w}, a byte the vertex format has always documented as reserved and which has
     * room for the type without a format change.
     *
     * <p>A boolean here was not merely imprecise, it was unusable: "precipitation falls" cannot
     * distinguish rain (which wets) from snow (which does not), so a shader gating wetness on it had
     * no choice but to reach for a camera-local snow test instead.
     */
    public static void setPrecipitation(int precipitationType) {
        CURRENT.get()[PRECIPITATION_SLOT] = precipitationType & 0xFF;
    }

    /**
     * Convenience over vanilla's own enum, so both mesh paths (block and fluid) map it identically
     * rather than each keeping a copy of the switch that could drift.
     */
    public static void setPrecipitation(Biome.Precipitation precipitation) {
        setPrecipitation(switch (precipitation) {
            case NONE -> PRECIPITATION_NONE;
            case RAIN -> PRECIPITATION_RAIN;
            case SNOW -> PRECIPITATION_SNOW;
        });
    }

    public static int getPrecipitation() {
        return CURRENT.get()[PRECIPITATION_SLOT];
    }

    /**
     * How much light this block emits, {@code Block.getLightEmission()}'s own 0..15 level.
     *
     * <p>A GENERIC ENGINE FACT ABOUT A BLOCK, not a curated style table, and the distinction is the
     * whole reason this lane is allowed to exist. A shaderpack that wants glowstone to glow has two
     * routes: ship a per-block-id emission table inside the pack (the conventional {@code
     * block.properties}-style approach -- a list someone wrote and maintains, which is the
     * thing Fornax will not host), or ask vanilla what the block already says about itself. This is
     * the second. It carries no opinion about how a block should LOOK: 15 for glowstone and 0 for
     * stone are Minecraft's numbers, the same ones the vanilla lightmap is built from, and a pack is
     * free to render them however it likes or ignore them entirely. Same shape and same
     * justification as {@link #setPrecipitation}, which asks vanilla what falls on a biome.
     *
     * <p>Per-BLOCK for the same reason too. A resource pack's labPBR {@code _s} alpha is the only
     * other emission signal a pack has, and it is per-TEXEL: it can say which part of a torch glows
     * but not whether a torch is a light source at all, and the survey that motivated this lane found
     * the user's pack shipping a flat "no emission" alpha on glowstone, shroomlight, lanterns,
     * campfires, glow lichen and amethyst -- every classic light source -- while carrying a noisy
     * dither on coal and iron ore, which emit no light whatsoever. Texel data alone therefore lights
     * exactly the wrong blocks. The two signals answer different questions and a pack needs both.
     *
     * <p>Not clamped silently: a value outside 0..15 means vanilla's contract changed, and the
     * encoder's byte-width assumptions downstream would be wrong in a way no test would catch.
     */
    public static void setLightEmission(int level) {
        if (level < 0 || level > MAX_LIGHT_EMISSION) {
            throw new IllegalArgumentException(
                    "light emission " + level + " outside vanilla's 0.." + MAX_LIGHT_EMISSION);
        }
        CURRENT.get()[LIGHT_EMISSION_SLOT] = level;
    }

    public static int getLightEmission() {
        return CURRENT.get()[LIGHT_EMISSION_SLOT];
    }

    /**
     * Which vanilla CATEGORIES this block belongs to, as {@link BlockClasses} flags.
     *
     * <p>Read from {@link BlockClasses}, which is built from vanilla's own block tags on tag load.
     * See that class for why a tag is not a per-block-id material table, and {@link
     * BlockClassResolver} for the tags themselves.
     *
     * <p>Not clamped silently, for the same reason {@link #setLightEmission} is not: the flags share
     * a 16-bit vertex lane with the emission level, so a flag above {@link BlockClasses#MASK} would
     * not be dropped -- it would be shifted off the top of the channel and read back as a DIFFERENT
     * class, which is the kind of failure a screenshot cannot distinguish from a shader bug.
     */
    public static void setBlockClass(int flags) {
        if (flags < 0 || flags > BlockClasses.MASK) {
            throw new IllegalArgumentException(
                    "block class flags " + flags + " outside 0.." + BlockClasses.MASK);
        }
        CURRENT.get()[BLOCK_CLASS_SLOT] = flags;
    }

    public static int getBlockClass() {
        return CURRENT.get()[BLOCK_CLASS_SLOT];
    }

    /**
     * Which block-atlas PAGE this block's terrain quads should sample from (M13's paged block
     * atlas), from {@link dev.icehunter.fornax.atlas.BlockAtlasPages#pageForState}.
     *
     * <p>Same lifecycle and same reason for existing as {@link #setBlockClass}: a generic engine
     * fact about a block, set per-block on the calling build thread, not a curated table. Unlike
     * emission and block class, nothing downstream reads this slot yet -- {@link
     * FornaxChunkVertex#packBlockFacts} does not write a page index into {@code a_Position.w} in
     * this phase (see that method's own doc for the reserved-but-unwritten bit slice), so this
     * accessor exists to wire the lookup path end to end before the encoder consumes it, not because
     * anything renders differently today. {@link
     * dev.icehunter.fornax.atlas.BlockAtlasPages#pageForState} always answers 0 until a later phase
     * populates its cache, so every call site's observable behavior is unchanged regardless.
     *
     * <p>Not range-checked against {@link FornaxChunkVertex#MAX_ATLAS_PAGES} the way {@link
     * #setBlockClass} is checked against {@link BlockClasses#MASK}: there is no packed lane for this
     * value to overflow yet, so there is nothing yet for an out-of-range page to corrupt.
     */
    public static void setAtlasPage(int page) {
        if (page < 0) {
            throw new IllegalArgumentException("atlas page " + page + " must not be negative");
        }
        CURRENT.get()[PAGE_SLOT] = page;
    }

    public static int getAtlasPage() {
        return CURRENT.get()[PAGE_SLOT];
    }

    public static void clear() {
        int[] slots = CURRENT.get();
        slots[ID_SLOT] = 0;
        // Zero, unlike the precipitation slot beside it, and for the mirror-image reason: NOT
        // emitting is what every block in the world did before this lane existed, so zero is the
        // neutral state here. An unresolved block that defaulted to "emits light" would stamp a
        // glowing patch into a mesh that then persists until the chunk is rebuilt.
        slots[LIGHT_EMISSION_SLOT] = 0;
        // Zero for the same reason as the emission slot above: belonging to no category is what
        // every block in the world did before this lane existed, so it is the neutral state. A
        // leftover COAL flag would stamp itself into whatever block is meshed next and persist in
        // that mesh until the chunk rebuilt -- the exact failure mode the precipitation lane
        // already had to fix once on this seam.
        slots[BLOCK_CLASS_SLOT] = BlockClasses.NONE;
        // Zero, same reasoning as the block-class slot above: page 0 is what every block resolved to
        // before this lane existed (there was only ever one page), so it is the neutral default. A
        // leftover nonzero page would stamp itself into whatever block is meshed next.
        slots[PAGE_SLOT] = 0;
        // Defaults to RAIN rather than 0, unchanged in spirit from when this lane was a boolean: an
        // unresolved block should behave as it did before the lane existed -- wet -- so it stays
        // consistent with the rest of the world instead of standing out as a permanently dry patch.
        // NONE would be the "safe" default only if dryness were the neutral state, and it is not:
        // every block in the world was wet in rain before this lane, so NONE is the visible change.
        slots[PRECIPITATION_SLOT] = PRECIPITATION_RAIN;
    }
}
