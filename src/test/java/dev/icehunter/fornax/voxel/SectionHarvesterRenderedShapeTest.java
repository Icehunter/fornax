package dev.icehunter.fornax.voxel;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SectionHarvester#renderedOrSelection} is the decision {@code buildEntry} uses to pick the
 * boxes it sends on for a PARTIAL cell. It is tested directly here because {@code buildEntry} itself
 * needs a live Minecraft client (its model-set lookups go through {@code Minecraft.getInstance()}).
 */
class SectionHarvesterRenderedShapeTest {
    // Fixture boxes; only which list comes back matters here.
    private static final List<VoxelShapeClassifier.PackedBox> SELECTION =
            List.of(new VoxelShapeClassifier.PackedBox(6, 0, 6, 10, 16, 10));
    private static final List<VoxelShapeClassifier.PackedBox> RENDERED =
            List.of(new VoxelShapeClassifier.PackedBox(7, 6, 0, 9, 9, 9));

    @Test void partialWithRenderedBoxesPublishesTheRenderedBoxes() {
        assertEquals(RENDERED, SectionHarvester.renderedOrSelection(VoxelShapeKind.PARTIAL, SELECTION, RENDERED));
    }

    @Test void partialWithoutARebuildKeepsTheSelectionShape() {
        assertEquals(SELECTION, SectionHarvester.renderedOrSelection(VoxelShapeKind.PARTIAL, SELECTION, null));
    }

    @Test void fullAndCrossAndEmptyIgnoreTheRebuild() {
        for (VoxelShapeKind kind : List.of(VoxelShapeKind.FULL, VoxelShapeKind.CROSS, VoxelShapeKind.EMPTY)) {
            assertEquals(SELECTION, SectionHarvester.renderedOrSelection(kind, SELECTION, RENDERED),
                    kind + " must never take the rebuilt boxes; only PARTIAL cells rebuild");
        }
    }

    /** A fence's selection shape is one solid slab spanning both rails and the gap between them,
     * while the two rendered rails leave that gap open. Point (8, 10, 3) in sixteenths sits in the
     * gap; point (8, 13, 3) sits inside the upper rail. */
    @Test void renderedFenceRailsLeaveTheGapOpen() {
        List<VoxelShapeClassifier.PackedBox> selection =
                List.of(new VoxelShapeClassifier.PackedBox(6, 6, 0, 10, 15, 9));
        List<VoxelShapeClassifier.PackedBox> rendered = VoxelModelShape.reconstruct(
                List.of(VoxelModelShapeTest.part(VoxelModelShapeTest.fence(1))));
        assertNotNull(rendered, "the single-connection fence fixture must reconstruct");

        List<VoxelShapeClassifier.PackedBox> published =
                SectionHarvester.renderedOrSelection(VoxelShapeKind.PARTIAL, selection, rendered);

        assertTrue(VoxelModelShapeTest.inside(selection, 8, 10, 3), "the selection slab covers the rail gap");
        assertFalse(VoxelModelShapeTest.inside(published, 8, 10, 3),
                "sending on the rendered boxes must leave the gap open, unlike the selection shape");
        assertTrue(VoxelModelShapeTest.inside(published, 8, 13, 3), "the boxes sent on still cover the solid rail");
    }
}
