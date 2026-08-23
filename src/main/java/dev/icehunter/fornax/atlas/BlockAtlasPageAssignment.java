package dev.icehunter.fornax.atlas;

import java.util.Collection;
import java.util.TreeSet;

/**
 * Resolves the single atlas page index a block's terrain quads must all carry, from the set of
 * pages its participating sprites landed on after {@link BlockAtlasPaging}.
 *
 * <p>A block's quads (its faces, its model's separate parts) each reference their own sprite, and
 * once sprites can spill across pages nothing guarantees a block's sprites all land on the same
 * one -- model baking, which is what finally knows a block's real sprite set, runs long after
 * stitching already decided page membership. That ordering makes this a genuine per-block
 * reconciliation problem, not something stitching itself can group away: this class exists only to
 * pick ONE page index per block, matching the {@code a_Position.w} lane's own per-block (not
 * per-quad) granularity a later phase writes it into.
 *
 * <p>The reconciliation rule is: take the LOWEST page a block's sprites resolved to. When a block's
 * sprites really do split across pages, every quad still gets a single, consistent page -- some of
 * that block's faces will sample the wrong page's texture at that position, a cosmetic defect, not
 * a crash. {@link #resolve} only computes the decision and reports whether a split occurred; a
 * later, live-wiring phase owns deciding what to log and how often (once per block state, not once
 * per quad, to avoid flooding the log for a popular block).
 *
 * <p>Pure and device-free, the {@code CameraJitter}/{@code ShadowCamera} pattern: no {@code
 * BlockState}, no cache, no logging, no Minecraft dependency at all -- just integers in, a decision
 * out.
 */
final class BlockAtlasPageAssignment {
    private BlockAtlasPageAssignment() {
    }

    /**
     * @param page  the page index every quad of this block should carry
     * @param split {@code true} when this block's sprites resolved to more than one distinct page
     */
    record Assignment(int page, boolean split) {
        Assignment {
            if (page < 0) {
                throw new IllegalArgumentException("page must not be negative");
            }
        }
    }

    /**
     * @param spritePages the page index of every sprite this block's quads reference; a block with
     *                     no sprites (nothing to reconcile) resolves to page 0
     */
    static Assignment resolve(Collection<Integer> spritePages) {
        if (spritePages.isEmpty()) {
            return new Assignment(0, false);
        }
        TreeSet<Integer> distinct = new TreeSet<>(spritePages);
        return new Assignment(distinct.first(), distinct.size() > 1);
    }
}
