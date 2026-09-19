package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static dev.icehunter.fornax.voxel.VoxelSourceWindowTest.word;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The same cube shape must not turn into a point only because its color is animated. */
class VoxelAnimatedIntrinsicSourceTest {
    private static SectionHarvester.Result cube(int intrinsic, List<MaterialSourceIndex.Summary> faces) {
        var evidence = new VoxelSourceEvidence.Builder();
        evidence.add(false, 0, List.of());
        evidence.add(true, intrinsic, faces);
        byte[] cells = new byte[4096]; cells[0] = 1;
        for (byte cell : cells) evidence.addCell(cell != 0, cell);
        var air = new SectionPalette.Entry(VoxelShapeKind.EMPTY, List.of(), new int[6], 0, false, 0);
        var source = new SectionPalette.Entry(VoxelShapeKind.FULL, List.of(),
                new int[]{0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff}, 0, false, 0);
        var summary = new VoxelSourceSummary.Accumulator(7);
        summary.add(true, intrinsic, VoxelSourceSummary.Entry.from(faces));
        return new SectionHarvester.Result(cells, new SectionPalette(List.of(air, source)), new byte[4096],
                summary.finish(false), VoxelHarvestLifecycle.generation(), evidence.finish(false),
                new VoxelSourcePolicy(2, 0, 2, true));
    }

    private static ByteBuffer publish(int intrinsic, List<MaterialSourceIndex.Summary> faces) {
        var window = VoxelSourceWindowTest.inventory();
        window.commit(0, cube(intrinsic, faces), VoxelSourceWindowTest.token(0, 0, 0, 9, 11));
        return window.preparePublication().bytes();
    }

    private static List<MaterialSourceIndex.Summary> faces(int flags) {
        return Collections.nCopies(6, MaterialSourceIndex.unavailable(flags));
    }

    /** A row is one run of one face, so which faces a cube offers is the rows put together. */
    private static int offeredFaces(ByteBuffer bytes) {
        int union = 0;
        for (int row = 0; row < word(bytes, 2); row++) {
            union |= word(bytes, VoxelSourceWindow.CELL_BASE + row * VoxelSourceWindow.CELL_WORDS + 4) & 63;
        }
        return union;
    }

    @Test void animatedAlbedoWithNoMaterialMapKeepsTheSameSixSourceFacesAsStaticAlbedo() {
        var plain = publish(15, faces(MaterialSourceIndex.MISSING_MAP));
        var animated = publish(15, faces(MaterialSourceIndex.MISSING_MAP | MaterialSourceIndex.ANIMATED));
        int facts = word(animated, VoxelSourceWindow.CELL_BASE + 4);
        assertEquals(6, word(animated, 2));
        assertEquals(0, word(animated, 9));
        assertEquals(63, offeredFaces(animated), "six cube faces, with neither unknown nor point flags");
        assertEquals(0, facts & 64, "no unknown flag");
        assertEquals(word(plain, VoxelSourceWindow.CELL_BASE + 4) & 0x3fffff, facts & 0x3fffff,
                "raw intensity and actual missing-map facts stay identical across animation");
        assertEquals(63, facts >>> 22 & 63, "all six faces use intrinsic emission without an authored-animation claim");
        assertEquals(0, word(animated, VoxelSourceWindow.CELL_BASE + 7), "face zero, one cell by one");
    }

    @Test void presentAnimatedMaterialDoesNotBecomeFalselyMissingOrLoseIntrinsicArea() {
        var animated = publish(15, faces(MaterialSourceIndex.ANIMATED));
        int facts = word(animated, VoxelSourceWindow.CELL_BASE + 4);
        assertEquals(63, offeredFaces(animated));
        assertEquals(0, facts >>> 16 & 63, "unavailable authored animation is not proof that the map is absent");
        assertEquals(63, facts >>> 22 & 63);
    }

    @Test void onlyTheAnimatedFaceSuppressesAuthoredSampling() {
        var mixed = new ArrayList<>(faces(MaterialSourceIndex.MISSING_MAP));
        mixed.set(2, MaterialSourceIndex.unavailable(MaterialSourceIndex.ANIMATED));
        var mixedBytes = publish(12, mixed);
        int facts = word(mixedBytes, VoxelSourceWindow.CELL_BASE + 4);
        assertEquals(63, offeredFaces(mixedBytes));
        assertEquals(12, facts >>> 8 & 15);
        assertEquals(59, facts >>> 16 & 63); // Five maps are missing. The north face map exists but is animated.
        assertEquals(4, facts >>> 22 & 63);
    }

    @Test void animationNeverCertifiesUnsupportedGeometryOrAnUnreadableAtlas() {
        for (int unsupported : new int[]{MaterialSourceIndex.UNSUPPORTED_GEOMETRY,
                MaterialSourceIndex.CROPPED_UV, MaterialSourceIndex.UNREADABLE,
                MaterialSourceIndex.UNKNOWN_SPRITE, MaterialSourceIndex.NO_ATLAS,
                MaterialSourceIndex.UNSUPPORTED_ATLAS_PAGE}) {
            int facts = word(publish(15, faces(MaterialSourceIndex.ANIMATED | unsupported)),
                    VoxelSourceWindow.CELL_BASE + 4);
            // Animation excuses only itself. A face carrying any other doubt offers no source,
            // whichever way it is refused: the policy may deny the entry outright, or the face may
            // stay unknown. What must never happen is a face of it being offered.
            assertEquals(0, facts & 63, "a face with another doubt must offer no source");
            // Nothing is offered at all, so there is no row to read that from.
            assertEquals(0, word(publish(15, faces(MaterialSourceIndex.ANIMATED | unsupported)), 2));
        }
    }

    @Test void animatedMaterialWithoutIntrinsicLightRemainsUnknown() {
        var bytes = publish(0, faces(MaterialSourceIndex.ANIMATED));
        assertEquals(0, word(bytes, 2));
        assertEquals(1, word(bytes, 9));
    }
}
