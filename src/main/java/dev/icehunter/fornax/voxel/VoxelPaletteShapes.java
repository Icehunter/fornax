package dev.icehunter.fornax.voxel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import org.jetbrains.annotations.Nullable;

/** Section-local geometry variants. Original entries remain available for unsupported cells.
 * The fixed palette cap never aliases a new shape to another cell's geometry. */
final class VoxelPaletteShapes {
    private record Key(int base, List<VoxelShapeClassifier.PackedBox> boxes) { }
    private final List<SectionPalette.Entry> entries;
    private final Map<Key, Integer> variants = new HashMap<>();
    private record BoundaryKey(int base, VoxelBoundaryCapture.Boundary boundary) { }
    private final Map<BoundaryKey, Integer> boundaries = new HashMap<>();
    private final IntConsumer copyMetadata;
    private final IntConsumer copyBoundaryMetadata;
    private boolean overflow;

    VoxelPaletteShapes(List<SectionPalette.Entry> entries, IntConsumer copyMetadata) {
        this(entries, copyMetadata, copyMetadata);
    }

    VoxelPaletteShapes(List<SectionPalette.Entry> entries, IntConsumer copyMetadata, IntConsumer copyBoundaryMetadata) {
        this.entries = entries;
        this.copyMetadata = copyMetadata;
        this.copyBoundaryMetadata = copyBoundaryMetadata;
    }

    int refine(int baseIndex, @Nullable List<VoxelShapeClassifier.PackedBox> boxes) {
        var base = entries.get(baseIndex);
        if (boxes == null || base.shapeKind() != VoxelShapeKind.PARTIAL || base.boxes().equals(boxes)) return baseIndex;
        var key = new Key(baseIndex, List.copyOf(boxes));
        var existing = variants.get(key);
        if (existing != null) return existing;
        if (entries.size() >= SectionHarvester.MAX_PALETTE_ENTRIES) {
            overflow = true;
            return baseIndex;
        }
        int index = entries.size();
        copyMetadata.accept(baseIndex);
        // A base's cutout rect lives in its last two box slots (see BrickGridUpload's
        // PALETTE_ENTRY_WORDS layout comment), which stay free only up to SectionHarvester's
        // CUTOUT_MAX_BOXES. A narrowed box list over that count falls back to solid, the rule
        // SectionHarvester.buildEntry applies, so the packer never gets a cutout entry it has to
        // throw on.
        boolean keepCutout = base.cutout() && key.boxes().size() <= SectionHarvester.CUTOUT_MAX_BOXES;
        entries.add(new SectionPalette.Entry(base.shapeKind(), key.boxes(), base.faceColors(),
                base.emissiveStrength(), base.lightTransmissive(), base.emissionColor(), keepCutout,
                keepCutout ? base.uvRect() : SectionPalette.NO_UV_RECT, base.extinction(),
                FaceSealResolver.resolve(base.shapeKind(), key.boxes()),
                VoxelFaceTexture.withoutBoundaryProof(base.faceTextureWords())));
        variants.put(key, index);
        return index;
    }

    /** Only positions actually observed by the renderer enter this path. An explicitly rejected
     * capture removes static fallback proof; no capture leaves trusted static models available. */
    int boundary(int baseIndex, VoxelBoundaryCapture.Boundary boundary) {
        var base = entries.get(baseIndex);
        if (boundary == null && (base.faceTextureWords()[0] & VoxelFaceTexture.CLOSED_BOX_BOUNDARY) == 0) return baseIndex;
        var key = new BoundaryKey(baseIndex, boundary);
        Integer existing = boundaries.get(key);
        if (existing != null) return existing;
        if (entries.size() >= SectionHarvester.MAX_PALETTE_ENTRIES) {
            overflow = true;
            // Without an index for this contextual cell, remove the base proof for every cell
            // sharing it. Losing optional transmission is preferable to certifying wrong geometry.
            entries.set(baseIndex, copyBoundary(base, null));
            return baseIndex;
        }
        int index = entries.size();
        copyBoundaryMetadata.accept(baseIndex);
        entries.add(copyBoundary(base, boundary));
        boundaries.put(key, index);
        return index;
    }

    private static SectionPalette.Entry copyBoundary(SectionPalette.Entry base, VoxelBoundaryCapture.Boundary boundary) {
        var kind = boundary == null ? base.shapeKind() : boundary.kind;
        var boxes = boundary == null ? base.boxes() : boundary.boxes;
        int[] words = boundary == null ? VoxelFaceTexture.withoutBoundaryProof(base.faceTextureWords()) : boundary.words();
        boolean keepCutout = (boundary == null ? base.cutout() : boundary.cutout)
                && (kind != VoxelShapeKind.PARTIAL || boxes.size() <= SectionHarvester.CUTOUT_MAX_BOXES);
        return new SectionPalette.Entry(kind, boxes, boundary == null ? base.faceColors() : boundary.faceColors(), base.emissiveStrength(),
                base.lightTransmissive(), base.emissionColor(), keepCutout,
                keepCutout ? (boundary == null ? base.uvRect() : boundary.uvRect()) : SectionPalette.NO_UV_RECT,
                boundary == null ? base.extinction() : boundary.extinction,
                FaceSealResolver.resolve(kind, boxes), words);
    }

    boolean overflowed() { return overflow; }
}
