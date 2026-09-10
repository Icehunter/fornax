package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelPaletteShapesTest {
    // Selection envelope and rendered post/rails, measured for a north-connected fence.
    private static final List<VoxelShapeClassifier.PackedBox> SELECTION = List.of(box(6,0,0,10,16,10));
    private static final List<VoxelShapeClassifier.PackedBox> MODEL = List.of(
            box(6,0,6,10,16,10), box(7,6,0,9,9,9), box(7,12,0,9,15,9));

    private static VoxelShapeClassifier.PackedBox box(int x0,int y0,int z0,int x1,int y1,int z1) {
        return new VoxelShapeClassifier.PackedBox(x0,y0,z0,x1,y1,z1);
    }
    private static SectionPalette.Entry entry(int color) {
        return new SectionPalette.Entry(VoxelShapeKind.PARTIAL, SELECTION,
                new int[]{color,color,color,color,color,color}, 0.5, false, color);
    }
    private static List<SectionPalette.Entry> entries(SectionPalette.Entry... entries) {
        return new ArrayList<>(List.of(entries));
    }

    @Test void sameStateAtDifferentPositionsKeepsFallbackAndDistinctProvenShapes() {
        var entries = entries(entry(1));
        var copies = new ArrayList<Integer>();
        var variants = new VoxelPaletteShapes(entries, copies::add);
        int first = variants.refine(0, MODEL);
        var post = List.of(MODEL.getFirst());
        int second = variants.refine(0, post);
        assertEquals(1, first); assertEquals(2, second);
        assertEquals(0, variants.refine(0, null));
        assertEquals(SELECTION, entries.get(0).boxes());
        assertEquals(MODEL, entries.get(first).boxes());
        assertEquals(post, entries.get(second).boxes());
        assertEquals(List.of(0,0), copies);
    }

    @Test void equalShapesShareEntriesButDifferentSourceStatesNeverShareMetadata() {
        var entries = entries(entry(1), entry(2));
        var copies = new ArrayList<Integer>();
        var variants = new VoxelPaletteShapes(entries, copies::add);
        int first = variants.refine(0, MODEL);
        assertEquals(first, variants.refine(0, new ArrayList<>(MODEL)));
        int second = variants.refine(1, MODEL);
        assertEquals(List.of(0,1), copies);
        assertSame(entries.get(0).faceColors(), entries.get(first).faceColors());
        assertSame(entries.get(1).faceColors(), entries.get(second).faceColors());
        assertEquals(2, entries.get(second).emissionColor());
        assertEquals(0.5, entries.get(second).emissiveStrength());
    }

    @Test void shapeRefinementRecomputesSealsWithoutChangingTextureOrMaterialData() {
        var base = entry(7);
        var entries = entries(base);
        var variants = new VoxelPaletteShapes(entries, index -> { });
        int index = variants.refine(0, List.of(box(0,0,0,16,16,16)));
        var changed = entries.get(index);
        assertEquals(FaceSealResolver.ALL, changed.faceSealMask());
        assertEquals(0, base.faceSealMask());
        assertSame(base.faceTextureWords(), changed.faceTextureWords());
        assertSame(base.uvRect(), changed.uvRect());
        assertEquals(base.cutout(), changed.cutout());
        assertEquals(base.lightTransmissive(), changed.lightTransmissive());
        assertEquals(base.extinction(), changed.extinction());
    }

    @Test void capRetainsOriginalFallbackAndStillFindsPreviouslyStoredVariants() {
        var entries = new ArrayList<SectionPalette.Entry>(Collections.nCopies(
                SectionHarvester.MAX_PALETTE_ENTRIES - 1, entry(1)));
        var copies = new ArrayList<Integer>();
        var variants = new VoxelPaletteShapes(entries, copies::add);
        int last = variants.refine(0, MODEL);
        assertEquals(SectionHarvester.MAX_PALETTE_ENTRIES - 1, last);
        assertFalse(variants.overflowed());
        assertEquals(0, variants.refine(0, List.of(MODEL.getFirst())));
        assertTrue(variants.overflowed());
        assertEquals(last, variants.refine(0, MODEL));
        assertEquals(List.of(0), copies);
        assertEquals(SectionHarvester.MAX_PALETTE_ENTRIES, entries.size());
    }

    @Test void copiedPolicyAndEvidenceStillEnumerateTheSameSourceAtTheVariantCell() {
        var entries = entries(entry(1), entry(2));
        var policy = new VoxelSourcePolicy.Builder();
        policy.add(false); policy.add(true);
        var evidence = new VoxelSourceEvidence.Builder();
        var missing = Collections.nCopies(6, MaterialSourceIndex.unavailable(MaterialSourceIndex.MISSING_MAP));
        evidence.add(true, 7, missing);
        evidence.add(true, 15, missing);
        var variants = new VoxelPaletteShapes(entries, base -> { policy.copy(base); evidence.copy(base); });
        int excluded = variants.refine(0, MODEL), included = variants.refine(1, MODEL);
        evidence.addCell(true, excluded); evidence.addCell(true, included);
        var finished = evidence.finish(false);
        assertEquals(entries.size(), finished.paletteSize());
        assertEquals(entries.size(), policy.finish(false).paletteSize());
        assertFalse(policy.finish(false).allows(excluded));
        assertTrue(policy.finish(false).allows(included));
        assertEquals(7, finished.intrinsicEmission(excluded));
        assertEquals(15, finished.intrinsicEmission(included));
        assertEquals(63, finished.missingMask(included)); // Six directional evidence bits.
        assertEquals(12, finished.eligibleFaces()); // Two intrinsically emissive cells, six faces each.
        assertTrue(policy.finish(false).complete());
        assertFalse(finished.incompletePalette());
    }

    @Test void sourcePolicyCopiesAcrossTheSixtyFourEntryWordBoundary() {
        var policy = new VoxelSourcePolicy.Builder();
        for (int i=0; i<Long.SIZE; i++) policy.add(i==0);
        policy.copy(0); policy.copy(1); policy.copy(Long.SIZE);
        var finished = policy.finish(false);
        assertTrue(finished.allows(64)); assertFalse(finished.allows(65)); assertTrue(finished.allows(66));
    }
}
