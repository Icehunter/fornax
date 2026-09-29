package dev.icehunter.fornax.voxel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.Nullable;

/** Section-local geometry variants. Original entries remain available for unsupported cells.
 * The fixed palette cap never aliases a new shape to another cell's geometry. */
final class VoxelPaletteShapes {
    private record Key(int base, List<VoxelShapeClassifier.PackedBox> boxes) { }
    private final List<SectionPalette.Entry> entries;
    private final Map<Key, Integer> variants = new HashMap<>();
    private record BoundaryKey(int base, VoxelBoundaryCapture.Boundary boundary, VoxelFaceAppearance.Resolved appearance) { }
    private final Map<BoundaryKey, Integer> boundaries = new HashMap<>();
    private final IntConsumer copyMetadata;
    private final BiConsumer<Integer, VoxelFaceAppearance.@Nullable Resolved> copyBoundaryMetadata;
    private boolean overflow;

    VoxelPaletteShapes(List<SectionPalette.Entry> entries, IntConsumer copyMetadata) {
        this(entries, copyMetadata, (base, appearance) -> copyMetadata.accept(base));
    }

    VoxelPaletteShapes(List<SectionPalette.Entry> entries, IntConsumer copyMetadata,
                       BiConsumer<Integer, VoxelFaceAppearance.@Nullable Resolved> copyBoundaryMetadata) {
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
        if (entries.size() >= SectionHarvester.maxPaletteEntries()) {
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
        return observed(baseIndex, boundary, null);
    }

    /** One variant combines independently observed material and certified optical geometry.
     * Appearance failure/overflow must never resurrect an unresolved contextual fallback map. */
    int observed(int baseIndex, @Nullable VoxelBoundaryCapture.Boundary boundary,
                 @Nullable VoxelFaceAppearance.Resolved appearance) {
        var base = entries.get(baseIndex);
        if (base.shapeKind() != VoxelShapeKind.FULL
                || (boundary != null && boundary.kind != VoxelShapeKind.FULL)) appearance = null;
        if (appearance == null && boundary == null
                && (base.faceTextureWords()[0] & VoxelFaceTexture.CLOSED_BOX_BOUNDARY) == 0) return baseIndex;
        var key = new BoundaryKey(baseIndex, boundary, appearance);
        Integer existing = boundaries.get(key);
        if (existing != null) return existing;
        var resolved = copyBoundary(base, boundary);
        if (appearance != null && resolved.shapeKind() == VoxelShapeKind.FULL)
            resolved = copyAppearance(resolved, appearance);
        // Even equal UV words may carry newly observed source evidence. Keep that immutable
        // material identity with the variant instead of inheriting the static model's summary.
        if (appearance == null && sameEntry(base, resolved)) return baseIndex;
        if (entries.size() >= SectionHarvester.maxPaletteEntries()) {
            overflow = true;
            var unavailable = copyBoundary(base, null);
            if (appearance != null)
                unavailable = copyAppearance(unavailable, VoxelFaceAppearance.UNAVAILABLE.resolve(index -> -1));
            entries.set(baseIndex, unavailable);
            return baseIndex;
        }
        int index = entries.size();
        copyBoundaryMetadata.accept(baseIndex, appearance);
        entries.add(resolved);
        boundaries.put(key, index);
        return index;
    }

    private static SectionPalette.Entry copyAppearance(SectionPalette.Entry base, VoxelFaceAppearance.Resolved appearance) {
        int[] words = appearance.words(), proof = base.faceTextureWords();
        for (int face = 0; face < 6; face++) {
            int at = face * VoxelFaceTexture.FACE_WORDS;
            // Material facts never certify geometry. Retain only the independently supplied proof.
            words[at] = (words[at] & ~VoxelFaceTexture.CLOSED_BOX_BOUNDARY)
                    | (proof[at] & VoxelFaceTexture.CLOSED_BOX_BOUNDARY);
        }
        return new SectionPalette.Entry(base.shapeKind(), base.boxes(), appearance.colors(), base.emissiveStrength(),
                base.lightTransmissive(), base.emissionColor(), base.cutout(), base.uvRect(), base.extinction(),
                base.faceSealMask(), words);
    }

    private static boolean sameEntry(SectionPalette.Entry a, SectionPalette.Entry b) {
        return a.shapeKind() == b.shapeKind() && a.boxes().equals(b.boxes())
                && java.util.Arrays.equals(a.faceColors(), b.faceColors())
                && Double.compare(a.emissiveStrength(), b.emissiveStrength()) == 0
                && a.lightTransmissive() == b.lightTransmissive() && a.emissionColor() == b.emissionColor()
                && a.cutout() == b.cutout() && java.util.Arrays.equals(a.uvRect(), b.uvRect())
                && Float.compare(a.extinction(), b.extinction()) == 0 && a.faceSealMask() == b.faceSealMask()
                && java.util.Arrays.equals(a.faceTextureWords(), b.faceTextureWords());
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
