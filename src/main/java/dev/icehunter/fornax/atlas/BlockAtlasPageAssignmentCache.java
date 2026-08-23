package dev.icehunter.fornax.atlas;

import dev.icehunter.fornax.FornaxMod;
import net.minecraft.world.level.block.Block;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live wrapper around {@link BlockAtlasPageAssignment#resolve}: caches one {@link
 * BlockAtlasPageAssignment.Assignment} per {@link Block} and logs the split warning at most once per
 * block, ever, instead of once per quad.
 *
 * <p>This is the reconciliation step {@link BlockAtlasPages}' own doc describes as missing: given
 * (a) a block's participating sprites' pages and (b) the {@link Block} they belong to, this class
 * resolves and remembers the single page every quad of that block should carry. Nothing calls {@link
 * #assignmentFor} yet -- Phase 3 needs a live BlockState -> sprite-set lookup (from model baking) to
 * supply the sprite-pages collection this class's own signature already anticipates, and a reload
 * listener to call {@link #clear} and then {@link BlockAtlasPages#install} with this cache's results.
 * Delivered now, fully tested against fake inputs, the same way {@link BlockAtlasPageAssignment}
 * itself shipped in Phase 1 with zero live callers -- so Phase 3 only has to supply the two real data
 * sources, not design the caching or the warning-suppression policy.
 *
 * <p>{@link ConcurrentHashMap}-backed rather than following {@link BlockAtlasPages}' plain-map-swap
 * pattern: that class's map is installed WHOLESALE once per reload and read many times, so a volatile
 * reference is enough; this class's cache is instead built up INCREMENTALLY, one block at a time, as
 * whatever future caller walks the registry -- concurrent writers are a real possibility if that walk
 * ever parallelizes the way chunk building already does.
 */
final class BlockAtlasPageAssignmentCache {
    private final Map<Block, BlockAtlasPageAssignment.Assignment> assignments = new ConcurrentHashMap<>();
    private final Set<Block> warnedSplit = ConcurrentHashMap.newKeySet();

    /**
     * Resolves (and caches) {@code block}'s page assignment from its participating sprites' pages,
     * logging a split warning the FIRST time (and only the first time) this block resolves to a
     * split -- a block whose sprites are split across pages would otherwise warn once per quad, once
     * per chunk, forever, for the lifetime of a pack that simply has an unlucky sprite set.
     *
     * @param block       the block these sprite pages belong to
     * @param spritePages the page index of every sprite this block's model(s) reference
     */
    BlockAtlasPageAssignment.Assignment assignmentFor(Block block, Collection<Integer> spritePages) {
        return assignments.computeIfAbsent(block, key -> {
            BlockAtlasPageAssignment.Assignment assignment = BlockAtlasPageAssignment.resolve(spritePages);
            if (assignment.split() && warnedSplit.add(key)) {
                FornaxMod.LOGGER.warn(
                        "[Fornax] Paged block atlas: {} has sprites split across atlas pages; every"
                                + " face will sample page {} (some faces will show the wrong page's"
                                + " texture at this position)",
                        key, assignment.page());
            }
            return assignment;
        });
    }

    /** Drops every cached assignment and split-warning record -- called before a fresh reload's
     * results replace this one, so a stale warning from a previous pack never suppresses a real one
     * from the next. */
    void clear() {
        assignments.clear();
        warnedSplit.clear();
    }
}
