package dev.icehunter.fornax.atlas;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-{@code Block} cache wrapping {@link BlockAtlasPageAssignment#resolve}.
 *
 * <p>Caching itself is the observable property these tests pin: a second {@link
 * BlockAtlasPageAssignmentCache#assignmentFor} call for the SAME block, given DIFFERENT sprite pages,
 * must still return the FIRST call's result -- proving the resolution genuinely happened once, not
 * merely that {@link BlockAtlasPageAssignment#resolve} itself is deterministic. That same
 * once-per-block code path is what makes the split warning fire at most once per block (see the
 * class's own doc); this suite does not capture log output directly, since the caching behavior it
 * rides on is already fully covered here.
 */
class BlockAtlasPageAssignmentCacheTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void resolvesAndCachesAUniformBlock() {
        BlockAtlasPageAssignmentCache cache = new BlockAtlasPageAssignmentCache();

        BlockAtlasPageAssignment.Assignment first = cache.assignmentFor(Blocks.STONE, List.of(2, 2, 2));

        assertEquals(2, first.page());
        assertFalse(first.split());
    }

    @Test
    void resolvesAndCachesASplitBlock() {
        BlockAtlasPageAssignmentCache cache = new BlockAtlasPageAssignmentCache();

        BlockAtlasPageAssignment.Assignment result = cache.assignmentFor(Blocks.STONE, List.of(3, 1, 2));

        assertEquals(1, result.page(), "lowest page wins, same rule as BlockAtlasPageAssignment.resolve");
        assertTrue(result.split());
    }

    /**
     * A second call for the same block, with a DIFFERENT sprite-pages input, still returns the
     * FIRST call's result object -- this is what "cached" means here, not merely "deterministic
     * output for the same input". A cache that recomputed every call would be indistinguishable from
     * no cache at all by output alone; only a differing second input can tell them apart.
     */
    @Test
    void secondCallForTheSameBlockReturnsTheCachedResultRegardlessOfNewInput() {
        BlockAtlasPageAssignmentCache cache = new BlockAtlasPageAssignmentCache();

        BlockAtlasPageAssignment.Assignment first = cache.assignmentFor(Blocks.STONE, List.of(0, 0));
        BlockAtlasPageAssignment.Assignment second = cache.assignmentFor(Blocks.STONE, List.of(9, 9, 9));

        assertSame(first, second, "the second call's different input must not recompute the assignment");
    }

    @Test
    void differentBlocksAreCachedIndependently() {
        BlockAtlasPageAssignmentCache cache = new BlockAtlasPageAssignmentCache();

        BlockAtlasPageAssignment.Assignment stone = cache.assignmentFor(Blocks.STONE, List.of(1));
        BlockAtlasPageAssignment.Assignment dirt = cache.assignmentFor(Blocks.DIRT, List.of(2));

        assertEquals(1, stone.page());
        assertEquals(2, dirt.page());
    }

    @Test
    void clearForgetsEveryCachedAssignment() {
        BlockAtlasPageAssignmentCache cache = new BlockAtlasPageAssignmentCache();
        cache.assignmentFor(Blocks.STONE, List.of(7));

        cache.clear();
        BlockAtlasPageAssignment.Assignment after = cache.assignmentFor(Blocks.STONE, List.of(1));

        assertEquals(1, after.page(), "a cleared cache must recompute from the new input, not the stale one");
    }
}
