package dev.icehunter.fornax.pack.material;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.BlocksSpec;
import dev.icehunter.fornax.pack.CategorySpec;
import dev.icehunter.fornax.pack.FornaxPackError;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a pack's category block/tag references into a Block -> ID map. The registry/tag access is
 * abstracted behind {@link Lookup} so the resolution logic (declaration order, tag fan-out, conflict
 * handling) is unit-tested without a Minecraft bootstrap beyond {@code Bootstrap.bootStrap()}; the
 * real wiring ({@link MaterialResolution}) backs {@link Lookup} with BuiltInRegistries.BLOCK.
 *
 * <p>Entry well-formedness is enforced here, not by the pack parser: {@code CategorySpec.blocks}
 * carries raw strings straight from blocks.toml (see its javadoc), so something like {@code
 * "minecraft:"} -- a syntactically-parseable Identifier with an empty path -- would otherwise sail
 * through as a "no such block/tag" miss instead of the author error it actually is. A well-formed
 * id/tag that simply isn't registered (e.g. a modded block from an absent mod) is a different case
 * entirely -- packs must still load with optional mods missing -- so that one only warns and skips.
 */
public final class BlockMaterialResolver {
    /** Registry/tag access seam. Returns empty for a missing id/tag rather than throwing. */
    public interface Lookup {
        Optional<Block> block(String id);   // "minecraft:iron_block"
        List<Block> tagMembers(String id);  // "c:storage_blocks/iron"
    }

    private BlockMaterialResolver() {}

    public static Map<Block, Integer> resolve(BlocksSpec blocks, MaterialCategories cats, Lookup lookup) {
        Map<Block, Integer> out = new LinkedHashMap<>();
        for (CategorySpec cat : blocks.categories().values()) {
            int id = cats.idOf(cat.name());
            for (String entry : cat.blocks()) {
                for (Block b : membersOf(cat.name(), entry, lookup)) {
                    Integer prev = out.putIfAbsent(b, id);
                    if (prev != null && prev != id) {
                        FornaxMod.LOGGER.warn("[Fornax] block {} claimed by multiple categories; "
                                + "keeping first (id {})", entry, prev);
                    }
                }
            }
        }
        return out;
    }

    private static List<Block> membersOf(String categoryName, String entry, Lookup lookup) {
        boolean forcedTag = entry.startsWith("#");
        String id = forcedTag ? entry.substring(1) : entry;
        requireWellFormed(categoryName, entry, id);

        if (forcedTag) {
            return warnIfEmpty(entry, lookup.tagMembers(id));
        }
        var direct = lookup.block(entry);
        if (direct.isPresent()) return List.of(direct.get());
        return warnIfEmpty(entry, lookup.tagMembers(entry));
    }

    /**
     * {@link Identifier#tryParse} alone isn't a sufficient guard: it happily accepts an empty path
     * (e.g. {@code "minecraft:"} or even {@code ""}, defaulting the missing namespace to {@code
     * minecraft}), producing an Identifier no block or tag could ever match. Reject that here rather
     * than let it quietly fall through to the "unknown block/tag" warn-and-skip path meant for
     * legitimately-absent modded content.
     */
    private static void requireWellFormed(String categoryName, String rawEntry, String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null || parsed.getPath().isEmpty()) {
            throw new FornaxPackError("blocks.toml", "categories." + categoryName + ".blocks",
                    "not a valid block/tag id: '" + rawEntry + "'");
        }
    }

    private static List<Block> warnIfEmpty(String entry, List<Block> members) {
        if (members.isEmpty()) {
            FornaxMod.LOGGER.warn("[Fornax] material category entry '{}' matched no blocks "
                    + "(unknown block/tag, or tag not yet loaded)", entry);
        }
        return members;
    }
}
