package dev.icehunter.fornax.pipeline;

import net.minecraft.world.level.block.Block;

import java.util.Map;

/**
 * The live block -> CLASS FLAGS lookup the terrain mesher reads, plus the flag vocabulary itself.
 *
 * <p><b>What a class flag is, and why it is not IPBR.</b> A flag says which vanilla CATEGORY a block
 * belongs to, as vanilla itself declares that category -- today, "this block is in Minecraft's own
 * {@code #minecraft:coal_ores} block tag". It is the same shape of fact as {@link
 * MaterialIdContext#setPrecipitation} (what falls on this biome) and {@link
 * MaterialIdContext#setLightEmission} (how much light this block emits): a question asked of the game
 * and answered by the game, carried through the chunk mesh so a fragment can see it.
 *
 * <p>It is NOT a per-block-id style table. Nothing here states a smoothness, an F0, an emission
 * strength, a colour or a look; the flag carries no opinion whatsoever about how coal ore should
 * render, and a pack is free to ignore it. The distinction that matters: the conventional approach
 * is a {@code block.properties}-style file that enumerates individual BLOCKS and attaches material
 * properties to each, a list written and maintained by a human. This reads a tag Mojang ships, and a
 * datapack that removes coal ore from the tag changes the answer with no code involved -- there is
 * nothing for anyone to maintain and nothing hosted here for a resource pack to override.
 *
 * <p><b>Keyed by Block, rebuilt on tag load.</b> Deliberately the same lifecycle as {@link
 * dev.icehunter.fornax.pack.material.BlockMaterials}: block tags are datapack content and are not
 * bound at client init, so this is (re)installed from {@code MaterialResolution.refresh()}, which
 * already runs both on pack activation and on {@code CommonLifecycleEvents.TAGS_LOADED}. An unmapped
 * block reads {@link #NONE}, which is what every block in the world did before this lane existed.
 */
public final class BlockClasses {
    /** No category. Every block that is not in a class this engine knows about. */
    public static final int NONE = 0;

    /**
     * The block is a COAL ORE, per vanilla's own {@code #minecraft:coal_ores} tag (stone and
     * deepslate variants; confirmed against the 26.2 jar in {@link BlockClassResolver}).
     *
     * <p>Deliberately COAL and not a broader ORE flag, because coal is the only class any consumer
     * asks about today. A flag named for more than its one consumer reads would claim eight tags'
     * worth of behaviour that nothing exercises; the day a second consumer wants "is this any ore",
     * the spare bits below are where that flag goes, resolved from the other seven {@code
     * #minecraft:*_ores} tags at that point and tested by whatever actually reads it.
     */
    public static final int COAL = 1 << 0;

    /**
     * How many flag bits the chunk vertex lane can carry.
     *
     * <p>{@link FornaxChunkVertex} packs these flags into {@code a_Position.w} ABOVE the four bits
     * vanilla's 0..15 light emission level occupies, in a 16-bit UNORM channel. That leaves twelve
     * bits total above the emission nibble, but only SEVEN of them (bits 4-10) are this class's to
     * spend: bits 11-15 are reserved for the block-atlas PAGE INDEX (see {@link
     * FornaxChunkVertex#PAGE_INDEX_BIT_OFFSET}), M13's paged-block-atlas milestone. Of the seven,
     * one ({@link #COAL}) is spoken for and SIX are spare. Spare capacity is stated as a number here
     * rather than left implicit because the next block-level fact that wants carrying should be able
     * to see at a glance whether it fits without a vertex format change.
     */
    public static final int WIDTH = 7;

    /** Every representable flag. A value outside this mask cannot survive the vertex lane. */
    public static final int MASK = (1 << WIDTH) - 1;

    private static volatile Map<Block, Integer> byBlock = Map.of();

    private BlockClasses() {}

    public static void install(Map<Block, Integer> map) { byBlock = Map.copyOf(map); }

    public static void clear() { byBlock = Map.of(); }

    public static int flagsForBlock(Block block) { return byBlock.getOrDefault(block, NONE); }
}
