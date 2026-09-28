package dev.icehunter.fornax.voxel;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises the deferred uploader's copied geometry through the source consumers without Vulkan.
 * It cannot establish GPU visibility or render quality. */
class VoxelCopiedUploadTest {
    // Same copy boundary as BrickGridUpload.uploadSlots: mutable payload arrays are detached,
    // while immutable source evidence, policy and RT geometry retain their identities.
    private static SectionHarvester.Result snapshot(SectionHarvester.Result source) {
        var entries = new ArrayList<SectionPalette.Entry>();
        for (var entry : source.palette().entries()) entries.add(new SectionPalette.Entry(
                entry.shapeKind(), List.copyOf(entry.boxes()), entry.faceColors().clone(),
                entry.emissiveStrength(), entry.lightTransmissive(), entry.emissionColor(),
                entry.cutout(), entry.uvRect().clone(), entry.extinction(), entry.faceSealMask(),
                entry.faceTextureWords().clone()));
        return new SectionHarvester.Result(source.paletteIndices().clone(), new SectionPalette(entries),
                source.lightmap().clone(), source.sourceSummary(), source.harvestGeneration(),
                source.sourceEvidence(), source.sourcePolicy(), source.rtGeometry());
    }

    private static VoxelSectionState.Snapshot token(int geometry, int content) {
        return new VoxelSectionState.Snapshot(0, 0, 0, 5, geometry, content);
    }

    @Test void copiedLightOnlyUploadKeepsEmitterAdmissionWithoutRejectingUnchangedGeometry() {
        var source = VoxelEmitterPoolTest.result(7, 17);
        var pool = new VoxelEmitterPool(2);
        pool.reset(5, 7);
        pool.commit(0, snapshot(source), token(1, 1));
        pool.advance(VoxelEmitterPool.CELL_SCAN_BUDGET);
        pool.markPublished(pool.preparePublication());
        var light = source.lightmap().clone();
        light[17] = 15;
        assertDoesNotThrow(() -> pool.commit(0, snapshot(source.withLightmap(light)), token(1, 2)));
        assertEquals(2, pool.stats().stored());
        assertNull(pool.preparePublication());
        assertEquals(0, pool.advance(VoxelEmitterPool.CELL_SCAN_BUDGET));
    }

    @Test void copiedLightOnlyUploadDoesNotRepublishTheSourceWindow() {
        var source = VoxelEmitterPoolTest.result(7, 17);
        var window = new VoxelSourceWindow();
        window.reset(5, 7);
        window.commit(0, snapshot(source), token(1, 1));
        window.markPublished(window.preparePublication());
        var light = source.lightmap().clone();
        light[17] = 15;
        window.commit(0, snapshot(source.withLightmap(light)), token(1, 2));
        assertNull(window.preparePublication(), "Light bytes do not change source membership");
    }

    @Test void aGeometryReplacementStillRequiresANewGeometryRevision() {
        var source = VoxelEmitterPoolTest.result(7, 17);
        var pool = new VoxelEmitterPool(2);
        pool.reset(5, 7);
        pool.commit(0, snapshot(source), token(1, 1));
        var changed = snapshot(source);
        changed.paletteIndices()[17] = 0;
        assertThrows(IllegalArgumentException.class, () -> pool.commit(0, changed, token(1, 2)));
        assertDoesNotThrow(() -> pool.commit(0, changed, token(2, 3)));
        assertEquals(0, pool.stats().stored());
    }
}
