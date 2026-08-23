package dev.icehunter.fornax.voxel;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelShapeClassifierTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void stoneClassifiesAsFullWithNoBoxes() {
        VoxelShapeClassifier.ClassifiedShape shape = VoxelShapeClassifier.classify(Blocks.STONE.defaultBlockState());
        assertEquals(VoxelShapeKind.FULL, shape.kind());
        assertTrue(shape.boxes().isEmpty());
    }

    @Test
    void bottomSlabClassifiesAsPartialWithOneBox() {
        BlockState bottomSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        VoxelShapeClassifier.ClassifiedShape shape = VoxelShapeClassifier.classify(bottomSlab);
        assertEquals(VoxelShapeKind.PARTIAL, shape.kind());
        assertEquals(1, shape.boxes().size());
        VoxelShapeClassifier.PackedBox box = shape.boxes().get(0);
        assertEquals(0, box.minY());
        assertEquals(8, box.maxY(), "a bottom slab's real shape is the lower half of the voxel, 0-8 in 1/16ths");
    }

    @Test
    void doubleSlabClassifiesAsFull() {
        BlockState doubleSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE);
        VoxelShapeClassifier.ClassifiedShape shape = VoxelShapeClassifier.classify(doubleSlab);
        assertEquals(VoxelShapeKind.FULL, shape.kind());
    }

    @Test
    void airClassifiesAsEmpty() {
        VoxelShapeClassifier.ClassifiedShape shape = VoxelShapeClassifier.classify(Blocks.AIR.defaultBlockState());
        assertEquals(VoxelShapeKind.EMPTY, shape.kind());
    }

    @Test
    void torchClassifiesAsPartialWithARealSmallBox() {
        // A torch's real shape (getShape(), NOT getOcclusionShape() -- see this class's own javadoc
        // for why those two differ) is a small box roughly centered in the cell, never full and
        // never empty. Confirmed live: using getOcclusionShape() collapsed this to EMPTY, making
        // torches invisible in the debug raymarch.
        VoxelShapeClassifier.ClassifiedShape shape = VoxelShapeClassifier.classify(Blocks.TORCH.defaultBlockState());
        assertEquals(VoxelShapeKind.PARTIAL, shape.kind());
        assertEquals(1, shape.boxes().size());
    }

    @Test
    void doorClassifiesAsPartialWithARealThinBox() {
        // A closed door's real shape is a thin panel against one face of the cell -- getOcclusionShape()
        // collapsed this to EMPTY too, making doors invisible in the debug raymarch.
        VoxelShapeClassifier.ClassifiedShape shape = VoxelShapeClassifier.classify(Blocks.OAK_DOOR.defaultBlockState());
        assertEquals(VoxelShapeKind.PARTIAL, shape.kind());
        assertEquals(1, shape.boxes().size());
    }

    @Test
    void glassPaneClassifiesAsPartialWithARealThinCrossBox() {
        // A glass pane's connectible-shape lookup is keyed purely by its own state properties (like
        // a fence's), so it needs no real BlockGetter/BlockPos context -- getOcclusionShape() still
        // collapsed it to EMPTY (vanilla's face-culling optimization for transparent blocks).
        VoxelShapeClassifier.ClassifiedShape shape = VoxelShapeClassifier.classify(Blocks.GLASS_PANE.defaultBlockState());
        assertEquals(VoxelShapeKind.PARTIAL, shape.kind());
        assertEquals(1, shape.boxes().size());
    }

    @Test
    void solidGlassClassifiesAsFull() {
        // A full glass block's real getShape() is a genuine full cube (players can't walk through
        // glass) -- getOcclusionShape() returned empty (vanilla skips face-culling through
        // transparent blocks), which wrongly classified it as having no geometry at all.
        VoxelShapeClassifier.ClassifiedShape shape = VoxelShapeClassifier.classify(Blocks.GLASS.defaultBlockState());
        assertEquals(VoxelShapeKind.FULL, shape.kind());
    }

    @Test
    void stairsResolveToMultipleBoxes() {
        VoxelShapeClassifier.ClassifiedShape shape = VoxelShapeClassifier.classify(Blocks.OAK_STAIRS.defaultBlockState());
        assertEquals(VoxelShapeKind.PARTIAL, shape.kind());
        assertTrue(shape.boxes().size() >= 2, "a stair's real shape is a compound of at least 2 boxes, got " + shape.boxes().size());
    }

    // --- Merge-not-drop fix (regression fix, 2026-07-20) ----------------------------------------------
    // An earlier version dropped boxes past MAX_BOXES and flagged the entry `truncated` so the shader
    // would fall back to full-cube occlusion -- that closed the light leak but over-shadowed badly
    // (confirmed live: Diagonal Fences' thin, mostly-open geometry rendered as a solid blob). Now the
    // excess is always merged into one extra union box instead: never fewer boxes than needed to cover
    // the real geometry, and never a flag telling a downstream consumer to distrust the list.

    @Test
    void exactlyMaxBoxesIsUnchanged() {
        List<AABB> eightBoxes = List.of(
                new AABB(0, 0, 0, 0.1, 0.1, 0.1),
                new AABB(0.2, 0, 0, 0.3, 0.1, 0.1),
                new AABB(0.4, 0, 0, 0.5, 0.1, 0.1),
                new AABB(0.6, 0, 0, 0.7, 0.1, 0.1),
                new AABB(0, 0.2, 0, 0.1, 0.3, 0.1),
                new AABB(0.2, 0.2, 0, 0.3, 0.3, 0.1),
                new AABB(0.4, 0.2, 0, 0.5, 0.3, 0.1),
                new AABB(0.6, 0.2, 0, 0.7, 0.3, 0.1));
        assertEquals(VoxelShapeClassifier.MAX_BOXES, eightBoxes.size(), "test setup: exactly MAX_BOXES boxes");

        List<VoxelShapeClassifier.PackedBox> boxes = VoxelShapeClassifier.mergeToMaxBoxes(eightBoxes);

        assertEquals(VoxelShapeClassifier.MAX_BOXES, boxes.size(), "no merge needed, none dropped either");
        for (int i = 0; i < eightBoxes.size(); i++) {
            AABB original = eightBoxes.get(i);
            VoxelShapeClassifier.PackedBox packed = boxes.get(i);
            assertEquals(VoxelShapeClassifier.to16ths(original.minX), packed.minX(), "box " + i + " minX unchanged");
            assertEquals(VoxelShapeClassifier.to16ths(original.minY), packed.minY(), "box " + i + " minY unchanged");
            assertEquals(VoxelShapeClassifier.to16ths(original.minZ), packed.minZ(), "box " + i + " minZ unchanged");
            assertEquals(VoxelShapeClassifier.to16ths(original.maxX), packed.maxX(), "box " + i + " maxX unchanged");
            assertEquals(VoxelShapeClassifier.to16ths(original.maxY), packed.maxY(), "box " + i + " maxY unchanged");
            assertEquals(VoxelShapeClassifier.to16ths(original.maxZ), packed.maxZ(), "box " + i + " maxZ unchanged");
        }
    }

    @Test
    void oneOverMaxBoxesMergesTheOverflowIntoTheLastSlot() {
        // MAX_BOXES + 1 boxes: MAX_BOXES - 1 survive exact, and the two smallest (equal volume here,
        // so which two is unambiguous by construction) are merged into the final slot's union box.
        List<AABB> boxes = new java.util.ArrayList<>();
        for (int i = 0; i < VoxelShapeClassifier.MAX_BOXES - 1; i++) {
            // Large, distinct boxes -- each one full 1/16th taller than the last so volumes are strictly
            // ordered and never tie with the two small ones added below.
            boxes.add(new AABB(0, 0, 0, 1.0, (i + 2) / 16.0, 1.0));
        }
        AABB small1 = new AABB(0.0, 0.0, 0.0, 1.0 / 16.0, 1.0 / 16.0, 1.0 / 16.0);
        AABB small2 = new AABB(15.0 / 16.0, 15.0 / 16.0, 15.0 / 16.0, 1.0, 1.0, 1.0);
        boxes.add(small1);
        boxes.add(small2);
        assertEquals(VoxelShapeClassifier.MAX_BOXES + 1, boxes.size(), "test setup: MAX_BOXES + 1 boxes");

        List<VoxelShapeClassifier.PackedBox> merged = VoxelShapeClassifier.mergeToMaxBoxes(boxes);

        assertEquals(VoxelShapeClassifier.MAX_BOXES, merged.size(), "capped at MAX_BOXES, nothing dropped");
        VoxelShapeClassifier.PackedBox last = merged.get(merged.size() - 1);
        // The union of small1 [0,0,0]-[1,1,1] (1/16ths) and small2 [15,15,15]-[16,16,16] spans the
        // full corner-to-corner diagonal -- [0,0,0]-[16,16,16].
        assertEquals(0, last.minX(), "merged box min covers the smaller of the two merged boxes' min");
        assertEquals(0, last.minY());
        assertEquals(0, last.minZ());
        assertEquals(16, last.maxX(), "merged box max covers the larger of the two merged boxes' max");
        assertEquals(16, last.maxY());
        assertEquals(16, last.maxZ());
    }

    @Test
    void manyBoxesMergeToMaxBoxesAndTheUnionContainsEveryMergedBox() {
        // 20 boxes, scattered so the merged/kept split is not spatially trivial: real containment (not
        // just count) is asserted below, the actual no-leak guarantee this merge strategy exists for.
        List<AABB> boxes = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double lo = i / 32.0;
            double hi = lo + (1 + i % 3) / 32.0; // varying sizes so volume-sort is meaningfully exercised
            boxes.add(new AABB(lo, lo, lo, Math.min(hi, 1.0), Math.min(hi, 1.0), Math.min(hi, 1.0)));
        }

        List<VoxelShapeClassifier.PackedBox> merged = VoxelShapeClassifier.mergeToMaxBoxes(boxes);
        assertEquals(VoxelShapeClassifier.MAX_BOXES, merged.size());

        // The no-leak guarantee: every one of the real 20 input boxes must be contained inside SOME
        // surviving merged box (its own exact box if it was kept, or the union box if it was folded in).
        for (AABB original : boxes) {
            VoxelShapeClassifier.PackedBox originalPacked = new VoxelShapeClassifier.PackedBox(
                    VoxelShapeClassifier.to16ths(original.minX), VoxelShapeClassifier.to16ths(original.minY),
                    VoxelShapeClassifier.to16ths(original.minZ), VoxelShapeClassifier.to16ths(original.maxX),
                    VoxelShapeClassifier.to16ths(original.maxY), VoxelShapeClassifier.to16ths(original.maxZ));
            boolean contained = merged.stream().anyMatch(m ->
                    m.minX() <= originalPacked.minX() && m.minY() <= originalPacked.minY()
                            && m.minZ() <= originalPacked.minZ() && m.maxX() >= originalPacked.maxX()
                            && m.maxY() >= originalPacked.maxY() && m.maxZ() >= originalPacked.maxZ());
            assertTrue(contained, "every real input box must be contained in a surviving box -- " + originalPacked
                    + " was not covered by any of " + merged);
        }
    }

    @Test
    void hopperShapeExceedsMaxBoxesAndMergesRatherThanDrops() {
        // Confirmed live (adversarial review finding S2, real game logs): minecraft:hopper's real
        // getShape() decomposes into more than MAX_BOXES real AABBs via vanilla's own Shapes.or merge
        // (a funnel body + spout + four inner walls does not collapse to <= 8 boxes).
        VoxelShapeClassifier.ClassifiedShape shape = VoxelShapeClassifier.classify(Blocks.HOPPER.defaultBlockState());
        assertEquals(VoxelShapeKind.PARTIAL, shape.kind());
        assertEquals(VoxelShapeClassifier.MAX_BOXES, shape.boxes().size(),
                "boxes list caps at MAX_BOXES via merge even though the real shape has more");
    }

    @Test
    void lecternShapeExceedsMaxBoxesAndMergesRatherThanDrops() {
        // Confirmed live: minecraft:lectern (the slanted-top podium) also exceeds MAX_BOXES.
        VoxelShapeClassifier.ClassifiedShape shape = VoxelShapeClassifier.classify(Blocks.LECTERN.defaultBlockState());
        assertEquals(VoxelShapeKind.PARTIAL, shape.kind());
        assertEquals(VoxelShapeClassifier.MAX_BOXES, shape.boxes().size(),
                "boxes list caps at MAX_BOXES via merge even though the real shape has more");
    }
}
