package dev.icehunter.fornax.config;

/**
 * TAAU's render/output resolution tiers -- how far below native the low-res source renders before
 * temporal upscaling reconstructs it. {@link #perAxisScale()} is the actual per-dimension render
 * scale {@code SsaaManager}/{@code GameRendererMixin} apply once {@link FornaxSettings#aaMethod}
 * selects {@code TAAU}; {@link #haltonSequenceLength()} is the unrelated Halton(2,3)
 * sample count {@link dev.icehunter.fornax.pass.taa.CameraJitter} cycles through for the same
 * tier -- more aggressive upscaling needs more temporal samples to fill in, since each rendered
 * frame covers a smaller fraction of the final pixel grid.
 */
public enum TaauRatio {
    QUALITY(8, 0.77f),
    BALANCED(12, 0.67f),
    PERFORMANCE(16, 0.58f);

    private final int haltonSequenceLength;
    private final float perAxisScale;

    TaauRatio(int haltonSequenceLength, float perAxisScale) {
        this.haltonSequenceLength = haltonSequenceLength;
        this.perAxisScale = perAxisScale;
    }

    /** Halton(2,3) sample count {@link dev.icehunter.fornax.pass.taa.CameraJitter} cycles through for this ratio. */
    public int haltonSequenceLength() {
        return this.haltonSequenceLength;
    }

    /** Per-dimension render-scale factor {@code SsaaManager.applyCurrentScale()} applies for this tier. */
    public float perAxisScale() {
        return this.perAxisScale;
    }
}
