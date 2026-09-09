package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import java.util.List;

/** Cached per-section source inventory. Candidate counts are not radiance or visible coverage. */
public record VoxelSourceSummary(long atlasGeneration, int intrinsicCandidateCells,
                                 int authoredCandidateCells, int authoredCandidateFaces,
                                 int unknownCells, int flags, int nonemptyCells) {
    public static final String TARGET = "voxelSourceSummary";
    // Eight uint words are the documented diagnostic slot ABI; generation occupies two words.
    public static final int WORDS_PER_SLOT = 8;
    public static final int BYTES_PER_SLOT = WORDS_PER_SLOT * Integer.BYTES;
    public static final int PALETTE_OVERFLOW = 1;
    public static final VoxelSourceSummary EMPTY = new VoxelSourceSummary(0, 0, 0, 0, 0, 0, 0);
    private static volatile boolean enabled;

    public static void setEnabled(boolean enabled) { VoxelSourceSummary.enabled = enabled; }
    public static boolean isEnabled() { return enabled; }

    public int[] words() {
        return new int[]{(int) this.atlasGeneration, (int) (this.atlasGeneration >>> 32),
                this.intrinsicCandidateCells, this.authoredCandidateCells, this.authoredCandidateFaces,
                this.unknownCells, this.flags, this.nonemptyCells};
    }

    /** One palette entry's evidence, evaluated once before the cell loop. */
    record Entry(int authoredFaces, boolean unknown) {
        static Entry from(List<MaterialSourceIndex.Summary> faces) {
            int authored = 0;
            boolean unknown = false;
            for (var face : faces) {
                if (face.supported() && face.authoredCandidate()) authored++;
                unknown |= face.unknown();
            }
            return new Entry(authored, unknown);
        }
    }

    /** The harvester feeds its existing cell loop; no additional section scan is required. */
    static final class Accumulator {
        private final long atlasGeneration;
        private int intrinsic, authoredCells, authoredFaces, unknown, nonempty;

        Accumulator(long atlasGeneration) { this.atlasGeneration = atlasGeneration; }

        void add(boolean nonempty, int intrinsicLight, Entry entry) {
            if (!nonempty) return;
            this.nonempty++;
            if (intrinsicLight > 0) this.intrinsic++;
            if (entry == null || entry.unknown()) this.unknown++;
            if (entry != null && entry.authoredFaces() > 0) {
                this.authoredCells++;
                this.authoredFaces += entry.authoredFaces();
            }
        }

        VoxelSourceSummary finish(boolean overflow) {
            return new VoxelSourceSummary(this.atlasGeneration, this.intrinsic, this.authoredCells,
                    this.authoredFaces, this.unknown, overflow ? PALETTE_OVERFLOW : 0, this.nonempty);
        }
    }
}
