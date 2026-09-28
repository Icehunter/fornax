package dev.icehunter.fornax.voxel;

import java.util.Arrays;

/** Exact geometry comparison across the deferred uploader's defensive array copies.
 * Lightmap bytes may change without replacing the geometry revision. */
final class VoxelGeometryPayload {
    private VoxelGeometryPayload() { }

    static boolean same(SectionHarvester.Result a, SectionHarvester.Result b) {
        if (a == b) return true;
        if (a.sourceEvidence() != b.sourceEvidence() || a.rtGeometry() != b.rtGeometry()
                || !a.sourcePolicy().equals(b.sourcePolicy())
                || !a.sourceSummary().equals(b.sourceSummary())
                || a.harvestGeneration() != b.harvestGeneration()
                || !Arrays.equals(a.paletteIndices(), b.paletteIndices())) return false;
        if (a.palette() == b.palette()) return true;
        var left = a.palette().entries();
        var right = b.palette().entries();
        if (left.size() != right.size()) return false;
        for (int entry = 0; entry < left.size(); entry++) {
            if (!sameEntry(left.get(entry), right.get(entry))) return false;
        }
        return true;
    }

    private static boolean sameEntry(SectionPalette.Entry a, SectionPalette.Entry b) {
        return a == b || a.shapeKind() == b.shapeKind() && a.boxes().equals(b.boxes())
                && Arrays.equals(a.faceColors(), b.faceColors())
                && Double.compare(a.emissiveStrength(), b.emissiveStrength()) == 0
                && a.lightTransmissive() == b.lightTransmissive()
                && a.emissionColor() == b.emissionColor() && a.cutout() == b.cutout()
                && Arrays.equals(a.uvRect(), b.uvRect())
                && Float.compare(a.extinction(), b.extinction()) == 0
                && a.faceSealMask() == b.faceSealMask()
                && Arrays.equals(a.faceTextureWords(), b.faceTextureWords());
    }
}
