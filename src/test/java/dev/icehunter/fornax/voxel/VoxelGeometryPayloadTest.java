package dev.icehunter.fornax.voxel;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelGeometryPayloadTest {
    private static SectionHarvester.Result withPalette(SectionHarvester.Result source, SectionPalette palette) {
        return new SectionHarvester.Result(source.paletteIndices(), palette, source.lightmap(),
                source.sourceSummary(), source.harvestGeneration(), source.sourceEvidence(),
                source.sourcePolicy(), source.rtGeometry());
    }

    @Test void allPaletteFieldsParticipateInGeometryIdentity() {
        var source = VoxelEmitterPoolTest.result(7, 17);
        var base = source.palette().entries().get(1);
        var colors = base.faceColors().clone(); colors[0] ^= 1;
        var uv = base.uvRect().clone(); uv[0] = 1;
        var texture = base.faceTextureWords().clone(); texture[0] ^= 1;
        // Eleven alternatives cover the eleven fields of SectionPalette.Entry independently.
        for (int field = 0; field < 11; field++) {
            var entry = new SectionPalette.Entry(
                    field == 0 ? VoxelShapeKind.PARTIAL : base.shapeKind(),
                    field == 1 ? List.of(new VoxelShapeClassifier.PackedBox(0, 0, 0, 1, 1, 1)) : base.boxes(),
                    field == 2 ? colors : base.faceColors(),
                    field == 3 ? base.emissiveStrength() + 1 : base.emissiveStrength(),
                    field == 4 ? !base.lightTransmissive() : base.lightTransmissive(),
                    field == 5 ? base.emissionColor() ^ 1 : base.emissionColor(),
                    field == 6 ? !base.cutout() : base.cutout(),
                    field == 7 ? uv : base.uvRect(),
                    field == 8 ? base.extinction() + 1 : base.extinction(),
                    field == 9 ? base.faceSealMask() ^ 1 : base.faceSealMask(),
                    field == 10 ? texture : base.faceTextureWords());
            var entries = new ArrayList<>(source.palette().entries()); entries.set(1, entry);
            assertFalse(VoxelGeometryPayload.same(source, withPalette(source, new SectionPalette(entries))),
                    "Palette field " + field + " must not pass as a light-only update");
        }
        var entries = new ArrayList<>(source.palette().entries()); entries.removeLast();
        assertFalse(VoxelGeometryPayload.same(source, withPalette(source, new SectionPalette(entries))));
    }

    @Test void evidenceLifetimePolicyAndRayGeometryCannotChangeInAContentUpdate() {
        var source = VoxelEmitterPoolTest.result(7, 17);
        var replacement = VoxelEmitterPoolTest.result(7, 17);
        for (int field = 0; field < 5; field++) {
            var changed = new SectionHarvester.Result(source.paletteIndices(), source.palette(), source.lightmap(),
                    field == 0 ? VoxelSourceSummary.EMPTY : source.sourceSummary(),
                    field == 1 ? source.harvestGeneration() + 1 : source.harvestGeneration(),
                    field == 2 ? replacement.sourceEvidence() : source.sourceEvidence(),
                    field == 3 ? new VoxelSourcePolicy(0, 0, 2, true) : source.sourcePolicy(),
                    field == 4 ? RtSectionGeometry.UNKNOWN : source.rtGeometry());
            assertFalse(VoxelGeometryPayload.same(source, changed), "Snapshot field " + field);
        }
    }

    @Test void onlyWorldLightBytesAreExcludedFromGeometryIdentity() {
        var source = VoxelEmitterPoolTest.result(7, 17);
        var light = source.lightmap().clone(); light[17] = 15;
        assertTrue(VoxelGeometryPayload.same(source, source.withLightmap(light)));
        byte[] cells = source.paletteIndices().clone(); cells[17] = 0;
        var changed = new SectionHarvester.Result(cells, source.palette(), light, source.sourceSummary(),
                source.harvestGeneration(), source.sourceEvidence(), source.sourcePolicy(), source.rtGeometry());
        assertFalse(VoxelGeometryPayload.same(source, changed));
    }
}
