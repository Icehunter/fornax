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
    private final IntConsumer copyMetadata;
    private boolean overflow;

    VoxelPaletteShapes(List<SectionPalette.Entry> entries, IntConsumer copyMetadata) {
        this.entries = entries;
        this.copyMetadata = copyMetadata;
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
                FaceSealResolver.resolve(base.shapeKind(), key.boxes()), base.faceTextureWords()));
        variants.put(key, index);
        return index;
    }

    boolean overflowed() { return overflow; }
}
