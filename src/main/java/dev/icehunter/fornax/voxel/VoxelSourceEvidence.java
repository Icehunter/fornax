package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import java.util.Arrays;
import java.util.List;

/** Evidence kept only on the CPU: one packed int per palette entry. The masks record facts per
 * face direction, never color, light amount, area, or whether a face shows. No per-cell objects
 * are kept. */
public final class VoxelSourceEvidence {
    public static final VoxelSourceEvidence UNAVAILABLE = new VoxelSourceEvidence(null, 0, 0, false, false);
    public static final VoxelSourceEvidence EMPTY = new VoxelSourceEvidence(new int[]{0}, 0, 0, false, false);
    // Four raw vanilla emission bits, one non-empty bit, then four masks in direction-ID order.
    // Each mask needs six bits, one per direction. The direction layout fills 29 bits; bit 29
    // marks a known-zero source on its own.
    private static final int NONEMPTY = 1 << 4;
    // Bit 29 records proof that a raw quad is absent, without touching the direction masks used
    // for diagnostics.
    private static final int KNOWN_ZERO_SOURCE = 1 << 29;
    private static final int SUPPORTED_SHIFT = 5, AUTHORED_SHIFT = 11, MISSING_SHIFT = 17, UNKNOWN_SHIFT = 23;
    private static final int FACE_MASK = (1 << 6) - 1;
    private final int[] palette;
    private final int eligibleFaces, unsupportedFaces;
    private final boolean paletteOverflow, incompletePalette;

    private VoxelSourceEvidence(int[] palette, int eligibleFaces, int unsupportedFaces,
                                boolean paletteOverflow, boolean incompletePalette) {
        this.palette = palette;
        this.eligibleFaces = eligibleFaces;
        this.unsupportedFaces = unsupportedFaces;
        this.paletteOverflow = paletteOverflow;
        this.incompletePalette = incompletePalette;
    }

    public boolean available() { return this.palette != null; }
    public boolean paletteOverflow() { return this.paletteOverflow; }
    public boolean incompletePalette() { return this.incompletePalette; }
    public int paletteSize() { return available() ? this.palette.length : 0; }
    public int paletteBytes() { return paletteSize() * Integer.BYTES; }
    public boolean knownZeroSource(int entry) { return (word(entry) & NONEMPTY) == 0 || (word(entry) & KNOWN_ZERO_SOURCE) != 0; }
    public int intrinsicEmission(int entry) { return word(entry) & 15; }
    public int supportedMask(int entry) { return mask(word(entry), SUPPORTED_SHIFT); }
    public int authoredMask(int entry) { return mask(word(entry), AUTHORED_SHIFT); }
    public int missingMask(int entry) { return mask(word(entry), MISSING_SHIFT); }
    public int unknownMask(int entry) { return mask(word(entry), UNKNOWN_SHIFT); }
    public int eligibleFaces() { return this.eligibleFaces; }
    public int unsupportedFaces() { return this.unsupportedFaces; }
    int eligibleMask(int entry) { return eligibleMaskForWord(word(entry)); }

    private int word(int entry) {
        if (!available()) throw new IllegalStateException("voxel source evidence is unavailable");
        return this.palette[entry];
    }

    private static int mask(int word, int shift) { return word >>> shift & FACE_MASK; }

    private static int eligibleMaskForWord(int word) {
        if ((word & NONEMPTY) == 0) return 0;
        int known = (mask(word, SUPPORTED_SHIFT) | mask(word, MISSING_SHIFT)) & ~mask(word, UNKNOWN_SHIFT);
        // Raw intrinsic discovery is conservative even for an authored all-zero material map.
        // A later alpha-aware energy evaluator must apply suppression before emitting any light.
        return known & ((word & 15) != 0 ? FACE_MASK : mask(word, AUTHORED_SHIFT));
    }

    /** Used only during diagnostic harvesting. Counts come from the harvester's own cell walk. */
    static final class Builder {
        private final int[] palette = new int[SectionHarvester.MAX_PALETTE_ENTRIES];
        private int size, eligible, unsupported, nonemptyCells;
        private boolean missingEntry;

        // Input comes from VoxelFaceTexture.packSources: invalid mappings carry UNSUPPORTED_GEOMETRY,
        // and cropped mappings carry CROPPED_UV; both are unknown.
        // A valid texture-word bit alone is insufficient, and missing-map never overrides unknown.
        void add(boolean nonempty, int intrinsic, List<MaterialSourceIndex.Summary> faces) {
            if (intrinsic < 0 || intrinsic > 15)
                throw new IllegalArgumentException("raw voxel intrinsic emission must be in 0..15");
            if (size == palette.length) throw new IllegalArgumentException("voxel source palette exceeds the index cap");
            if (faces.size() != (nonempty ? 6 : 0))
                throw new IllegalArgumentException("nonempty voxel source evidence requires all six directions");
            if (!nonempty && intrinsic != 0)
                throw new IllegalArgumentException("empty voxel source evidence cannot carry intrinsic emission");
            int word = intrinsic | (nonempty ? NONEMPTY : 0);
            for (int face = 0; face < faces.size(); face++) {
                var summary = faces.get(face);
                if (summary.supported()) word |= 1 << (SUPPORTED_SHIFT + face);
                if (summary.authoredCandidate()) word |= 1 << (AUTHORED_SHIFT + face);
                if ((summary.flags() & MaterialSourceIndex.MISSING_MAP) != 0) word |= 1 << (MISSING_SHIFT + face);
                if (summary.unknown()) word |= 1 << (UNKNOWN_SHIFT + face);
            }
            palette[size++] = word;
        }

        void add(boolean nonempty, int intrinsic, List<MaterialSourceIndex.Summary> faces, boolean knownZero) {
            if (knownZero && intrinsic != 0)
                throw new IllegalArgumentException("known-zero source proof requires zero raw intrinsic emission");
            add(nonempty, intrinsic, faces);
            if (knownZero) palette[size - 1] |= KNOWN_ZERO_SOURCE;
        }

        /** A geometry-only variant keeps the raw source evidence; cells are counted on their own. */
        void copy(int entry) {
            if (entry < 0 || entry >= size) throw new IllegalArgumentException("missing source evidence entry");
            if (size == palette.length) throw new IllegalArgumentException("voxel source palette exceeds the index cap");
            palette[size++] = palette[entry];
        }

        void addCell(boolean nonempty, int entry) {
            if (entry < -1 || entry >= size) throw new IllegalArgumentException("voxel source cell has no palette entry");
            if (entry >= 0 && nonempty != ((palette[entry] & NONEMPTY) != 0))
                throw new IllegalArgumentException("voxel source cell and palette disagree on emptiness");
            missingEntry |= entry == -1;
            if (!nonempty) return;
            nonemptyCells++;
            if (entry == -1) unsupported += 6; // An overflow state has no trustworthy directional evidence.
            else {
                eligible += Integer.bitCount(eligibleMaskForWord(palette[entry]));
                // Unknown faces count even when an unreadable source cannot report positive texels.
                unsupported += Integer.bitCount(mask(palette[entry], UNKNOWN_SHIFT));
            }
        }

        VoxelSourceEvidence finish(boolean overflow) {
            boolean rejected = overflow || missingEntry;
            return new VoxelSourceEvidence(Arrays.copyOf(palette, size), rejected ? 0 : eligible,
                    rejected ? nonemptyCells * 6 : unsupported, overflow, missingEntry);
        }
    }
}
