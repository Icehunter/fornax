package dev.icehunter.fornax.atlas;

/** LabPBR 1.3 emission-alpha classification without rewriting authored source bytes. */
public final class LabPbrEmissionSentinel {
    /** Per-texel code meaning that the resource pack did not provide emission at this texel. */
    public static final int UNAUTHORED = 0xFF;

    private LabPbrEmissionSentinel() {
    }

    /**
     * Reports whether a sprite contains any authored emission value.
     *
     * <p>Values {@code 0..254} are literal authored magnitudes, including constant planes. Value
     * {@code 255} is absence per texel. This method is deliberately observational: level-zero
     * transport remains byte-identical to the resource pack.</p>
     *
     * @param argb sprite texels in row-major ARGB order; never mutated
     * @return {@code true} when at least one alpha is in {@code 0..254}
     */
    public static boolean resolve(int[] argb) {
        for (int texel : argb) {
            if ((texel >>> 24) != UNAUTHORED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compatibility helper retained for existing callers. Source and reduced values are no longer
     * rewritten merely because their alpha is {@code 255}; that code remains categorical absence.
     */
    public static int capBelowSentinel(int argb) {
        return argb;
    }
}
