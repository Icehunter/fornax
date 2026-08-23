package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.pack.material.BlockMaterialResolver;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link BlockClasses} lookup from vanilla's own block TAGS.
 *
 * <p>Tag access is behind {@link BlockMaterialResolver.Lookup} -- the same seam the pack's material
 * resolution already uses -- so the tag list, the fan-out and the flag arithmetic are unit-tested
 * without a live registry, and the one real implementation ({@code MaterialResolution.LOOKUP}) is
 * shared rather than duplicated.
 *
 * <p><b>Why a tag and not a block list.</b> A hand-written list of block ids is exactly the thing
 * Fornax does not host (see {@link BlockClasses}); it would also be wrong twice over -- it cannot
 * see a modded coal ore that joins the tag, and it would need editing if Mojang added one. The tag
 * is the game's own answer to "what is a coal ore", it already exists, and a datapack that edits it
 * changes the answer here with no code involved.
 */
public final class BlockClassResolver {
    /**
     * The tags that make a block a {@link BlockClasses#COAL} ore.
     *
     * <p><b>CONFIRMED AGAINST THE 26.2 CLIENT JAR, not assumed.</b> {@code
     * data/minecraft/tags/block/coal_ores.json} in {@code minecraft-merged-deobf-26.2.jar} lists
     * exactly {@code minecraft:coal_ore} and {@code minecraft:deepslate_coal_ore}. The tag has no
     * constant in {@code net.minecraft.tags.BlockTags} this version, so it is addressed by id --
     * which is also what lets the list live behind the same string-keyed {@code Lookup} seam the
     * material resolver already uses.
     *
     * <p>A list rather than a single string only so that the resolve loop below stays shaped for
     * the next class without restructuring; today it has one entry and should grow one only when a
     * consumer exists for what the new entry would mean.
     */
    static final List<String> COAL_TAGS = List.of("minecraft:coal_ores");

    private BlockClassResolver() {}

    /**
     * Resolves every known class into one Block -> flags map.
     *
     * <p>Flags are OR-ed, never assigned, so a block reached through two tags lands on one entry
     * and a future second class can overlap the first without either having to know about the
     * other. With one class of one tag this is unobservable today; it is the shape the first
     * overlapping class would otherwise have to retrofit.
     */
    public static Map<Block, Integer> resolve(BlockMaterialResolver.Lookup lookup) {
        Map<Block, Integer> out = new LinkedHashMap<>();
        for (String tag : COAL_TAGS) {
            for (Block block : lookup.tagMembers(tag)) {
                out.merge(block, BlockClasses.COAL, (a, b) -> a | b);
            }
        }
        return out;
    }
}
