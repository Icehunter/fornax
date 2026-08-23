package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.FornaxMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Classifies a block's real shape (vanilla's own {@code getShape(BlockGetter, BlockPos)}, a genuine
 * {@code VoxelShape} -- never a hand-authored per-block table) for voxel harvesting. A voxel's shape
 * determines how a ray interacts with it: {@code FULL} always stops a ray at the cell boundary;
 * {@code PARTIAL} needs real box intersection against the stored sub-cube bounds; {@code EMPTY}
 * never occludes at all.
 *
 * <p><b>{@code getShape()}, not {@code getOcclusionShape()}.</b> An earlier version of this class
 * used {@code getOcclusionShape()}, which is semantically the wrong API for "does this block have
 * real solid geometry" -- vanilla's occlusion shape exists purely as a mesh face-culling fast path,
 * and deliberately returns {@code Shapes.empty()} for the vast majority of non-full-cube blocks
 * regardless of their real geometry (confirmed live: torches, doors, and glass panes all classified
 * as {@code EMPTY} and were invisible in the debug raymarch, even though {@code getShape()} for each
 * returns real, non-empty geometry -- a standing torch's real shape is a small box roughly
 * {@code [0.375,0,0.375]-[0.625,0.625,0.625]}, an oak door's is a thin panel, a glass pane's is a
 * thin cross; only {@code getOcclusionShape()} collapses all three to empty). Called with
 * {@link EmptyBlockGetter#INSTANCE}/{@link BlockPos#ZERO} -- the same synthetic, position-independent
 * context vanilla's own default {@code getOcclusionShape()} implementation uses internally -- since
 * this class's {@code classify(BlockState)} signature is deliberately state-only (matching how
 * {@code getOcclusionShape()} was equally position-independent), and every block's real shape that
 * matters here is fully determined by its own state properties, never by querying neighbors through
 * the passed {@code BlockGetter} (confirmed for every block family this milestone harvests: doors,
 * fences/panes/bars, slabs, stairs, and torches all resolve their shape from a static lookup keyed
 * only by their own state properties).</p>
 */
public final class VoxelShapeClassifier {
    /** Generous upper bound on distinct boxes a single block's shape can resolve to -- confirmed via
     * bytecode tracing of vanilla's own shape construction: stairs need up to 3, a fully-connected
     * fence/iron-bars/wall needs 5 (a post plus 4 independent, non-mergeable arms). This cost lives
     * in the per-section PALETTE (a handful of distinct block variants per section), not per-voxel,
     * so a generous cap over every known case is cheap. A shape whose real decomposition exceeds this
     * (diagonal fences, cauldrons, hoppers, lecterns -- see {@link #classify}) is never dropped, only
     * coarsened: {@link #classify} merges the excess into one extra union box so the result is always
     * exactly {@code MAX_BOXES} boxes, never fewer boxes than real geometry. */
    public static final int MAX_BOXES = 8;

    /** 1/16-block-resolution box bounds, matching Minecraft's own model coordinate granularity
     * (0-16 per axis, inclusive). */
    public record PackedBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    /** {@code boxes} is always a COMPLETE description of the shape's real occlusion footprint --
     * every AABB {@code shape.toAabbs()} produced is covered by exactly one entry here, either
     * verbatim (shape had {@code <= MAX_BOXES} real boxes) or folded into a union box (see {@link
     * #classify}'s merge step for a shape with more). There is deliberately no "this is incomplete,
     * distrust it" signal anymore (removed 2026-07-20, light-over-shadow fix): an earlier version
     * dropped the excess outright and flagged the entry {@code truncated} so the shader would fall
     * back to full-cube occlusion, which fixed the light-leak but over-shadowed badly -- confirmed
     * live on Diagonal Fences' thin, mostly-open geometry rendering as a solid blob. Merging instead
     * of dropping means every consumer can just walk {@code boxes} and trust a miss as a real miss. */
    public record ClassifiedShape(VoxelShapeKind kind, List<PackedBox> boxes) {
    }

    private static final ClassifiedShape EMPTY_RESULT = new ClassifiedShape(VoxelShapeKind.EMPTY, List.of());
    private static final ClassifiedShape FULL_RESULT = new ClassifiedShape(VoxelShapeKind.FULL, List.of());

    private VoxelShapeClassifier() {
    }

    public static ClassifiedShape classify(BlockState state) {
        VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) {
            return EMPTY_RESULT;
        }

        List<AABB> aabbs = shape.toAabbs();
        if (isFullCube(aabbs)) {
            return FULL_RESULT;
        }

        if (aabbs.size() > MAX_BOXES) {
            FornaxMod.LOGGER.warn(
                    "[Fornax] Voxel shape for {} has {} boxes, more than {}; merging the {} smallest into "
                            + "one bounding box (accuracy note, not a correctness issue -- the union still "
                            + "covers every merged box, nothing is dropped)",
                    state, aabbs.size(), MAX_BOXES, aabbs.size() - (MAX_BOXES - 1));
        }
        return new ClassifiedShape(VoxelShapeKind.PARTIAL, mergeToMaxBoxes(aabbs));
    }

    /** Packs {@code aabbs} into at most {@link #MAX_BOXES} {@link PackedBox}es, merging any excess
     * into ONE extra union box rather than dropping it -- see {@link ClassifiedShape}'s own doc for
     * why dropping was wrong (light-over-shadow fix, 2026-07-20). Package-visible (not private) so
     * this merge behaviour -- independent of any real {@code BlockState}'s {@code getShape()} -- is
     * directly unit-testable against synthetic AABB lists, the same "pure function, tested directly"
     * precedent {@link BrickGridUpload#packPaletteFlagsWord} already sets in this codebase.
     *
     * <p>When {@code aabbs.size() > MAX_BOXES}, sorts by volume DESCENDING and keeps the
     * {@code MAX_BOXES - 1} LARGEST boxes exact, merging only the smallest, least-visually-significant
     * remainder into one union box for the final slot. This beats a naive "keep {@code toAabbs()}'s
     * own order, merge whatever's left" pass for free: {@code toAabbs()}'s order reflects vanilla's
     * internal shape-tree construction, not spatial or visual significance, so an arbitrary tail can
     * just as easily merge two major, far-apart arms (inflating the union to cover most of the cell)
     * as it can merge two minor ones. Sorting by volume guarantees the parts that occlude the most
     * light stay exact and only the small detail geometry gets coarsened -- for a diagonal fence this
     * keeps the post and full-length arms as real boxes and folds only its smaller connector nubs into
     * the union. A repeated-nearest-pair clustering pass could squeeze the union tighter still, but for
     * every confirmed-live shape that exceeds MAX_BOXES (fences, cauldrons, hoppers, lecterns -- all
     * well under 20 real boxes) the volume sort already keeps every materially-sized box exact; the
     * extra complexity is not worth it here. */
    static List<PackedBox> mergeToMaxBoxes(List<AABB> aabbs) {
        if (aabbs.size() <= MAX_BOXES) {
            List<PackedBox> boxes = new ArrayList<>(aabbs.size());
            for (AABB box : aabbs) {
                boxes.add(pack(box));
            }
            return List.copyOf(boxes);
        }

        List<AABB> byVolumeDesc = new ArrayList<>(aabbs);
        byVolumeDesc.sort(Comparator.comparingDouble(VoxelShapeClassifier::volume).reversed());
        int keepExact = MAX_BOXES - 1;
        List<PackedBox> boxes = new ArrayList<>(MAX_BOXES);
        for (int i = 0; i < keepExact; i++) {
            boxes.add(pack(byVolumeDesc.get(i)));
        }
        AABB union = byVolumeDesc.get(keepExact);
        for (int i = keepExact + 1; i < byVolumeDesc.size(); i++) {
            union = union.minmax(byVolumeDesc.get(i));
        }
        boxes.add(pack(union));
        return List.copyOf(boxes);
    }

    private static double volume(AABB box) {
        return box.getXsize() * box.getYsize() * box.getZsize();
    }

    /** A shape is "full" if it's exactly one box spanning the entire 0-1 unit cell on every axis --
     * anything smaller, offset, or compound is PARTIAL, even if it's a single box (e.g. a slab). */
    private static boolean isFullCube(List<AABB> aabbs) {
        if (aabbs.size() != 1) {
            return false;
        }
        AABB box = aabbs.get(0);
        double eps = 1e-6;
        return Math.abs(box.minX) < eps && Math.abs(box.minY) < eps && Math.abs(box.minZ) < eps
                && Math.abs(box.maxX - 1.0) < eps && Math.abs(box.maxY - 1.0) < eps && Math.abs(box.maxZ - 1.0) < eps;
    }

    private static PackedBox pack(AABB box) {
        return new PackedBox(
                to16ths(box.minX), to16ths(box.minY), to16ths(box.minZ),
                to16ths(box.maxX), to16ths(box.maxY), to16ths(box.maxZ));
    }

    /** Package-visible (not private) so {@link FaceColorResolver#resolveCrossGeometry} can reuse the
     * exact same 1/16-block-resolution rounding/clamping for a cross block's harvested bounding box
     * -- one source of truth for float-to-PackedBox-coordinate conversion, not a second copy. */
    static int to16ths(double coord) {
        int v = (int) Math.round(coord * 16.0);
        return Math.max(0, Math.min(16, v));
    }
}
