package dev.icehunter.fornax.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The material atlas's mip chain reduces a CODE, two magnitudes and a split byte, and only one of
 * those four is a box filter. See {@link LabPbrMaterialReduction} for what a plain box mean costs.
 */
class LabPbrMaterialReductionTest {
    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    private static int green(int argb) {
        return (argb >> 8) & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }

    // The user's pack, measured: an ore's stone matrix is exactly green 10 with smoothness 28, and
    // its metal specks are one code -- 255 on gold/iron/diamond, 234 (copper) on copper ore -- with
    // smoothness 96..132.
    private static final int MATRIX = argb(0, 28, 10, 0);
    private static final int METAL = argb(0, 130, 255, 0);
    private static final int COPPER = argb(0, 96, 234, 0);

    /**
     * The whole point: three matrix texels and one metal texel reduce to the MATRIX, not to the
     * ~71 green a box mean would give, which decodes as a dielectric with F0 0.28.
     */
    @Test
    void aLoneMetalTexelDoesNotDragTheMatrixUp() {
        int out = LabPbrMaterialReduction.reduce(METAL, MATRIX, MATRIX, MATRIX);

        assertEquals(10, green(out));
        assertEquals(28, red(out), "smoothness comes from the class that won, not from all four");
    }

    /** ...and symmetrically, one matrix texel does not drag a metal footprint down out of the band. */
    @Test
    void aLoneMatrixTexelDoesNotDragTheMetalDown() {
        int out = LabPbrMaterialReduction.reduce(METAL, METAL, METAL, MATRIX);

        assertEquals(255, green(out));
        assertEquals(130, red(out));
    }

    /**
     * Half and half goes to the metal. {@link LabPbrMaterialReduction#CLASS_QUORUM} carries the
     * measurement: a strict majority takes iron ore's 6.2% coverage to 0.4% by level 4.
     */
    @Test
    void aTiedFootprintKeepsTheMetal() {
        int out = LabPbrMaterialReduction.reduce(METAL, METAL, MATRIX, MATRIX);

        assertEquals(255, green(out));
        assertEquals(130, red(out));
    }

    /**
     * No input, and therefore no OUTPUT, may land between the two populations. This is the property
     * the whole class exists for: a value strictly between the matrix and the metal band is a
     * material the sprite does not contain, and it is brighter than either one.
     */
    @Test
    void noCombinationOfMatrixAndMetalCanInventAnIntermediateGreen() {
        int[] pool = {MATRIX, METAL, COPPER};
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                for (int c = 0; c < 3; c++) {
                    for (int d = 0; d < 3; d++) {
                        int g = green(LabPbrMaterialReduction.reduce(pool[a], pool[b], pool[c], pool[d]));
                        assertTrue(g == 10 || g >= LabPbrMaterialReduction.METAL_MIN,
                                "invented green " + g + " from " + a + b + c + d);
                    }
                }
            }
        }
    }

    /**
     * Metal codes are NAMES and are never averaged. Copper ore ships 232 (aluminium) on 75 texels
     * beside 234 (copper) on 13,604 of them, and the mean of those two is 233 -- CHROME, a metal
     * the pack never mentions.
     */
    @Test
    void metalCodesAreNeverAveragedIntoAThirdMetal() {
        int out = LabPbrMaterialReduction.reduce(
                argb(0, 96, 234, 0), argb(0, 96, 234, 0),
                argb(0, 96, 234, 0), argb(0, 96, 232, 0));

        assertEquals(234, green(out), "the majority code, not (234*3 + 232)/4");
    }

    /** A tie between two named metals goes UP, toward the band that names nobody. */
    @Test
    void aTieBetweenTwoMetalsGoesToTheLessSpecificCode() {
        int out = LabPbrMaterialReduction.reduce(
                argb(0, 96, 232, 0), argb(0, 96, 234, 0),
                argb(0, 96, 232, 0), argb(0, 96, 234, 0));

        assertEquals(234, green(out));
    }

    /**
     * The dielectric side keeps its mean, and that asymmetry is the point: below the band green is
     * an ordinary F0 magnitude and interpolating it is exactly right.
     */
    @Test
    void dielectricGreensStillAverage() {
        int out = LabPbrMaterialReduction.reduce(
                argb(0, 0, 10, 0), argb(0, 0, 12, 0),
                argb(0, 0, 14, 0), argb(0, 0, 16, 0));

        assertEquals(13, green(out));
    }

    /**
     * A sprite with no metal anywhere -- coal ore, plain stone, and the 970 other {@code _s} maps in
     * the measured pack that never enter the band -- reduces exactly as a box filter would, in every
     * channel. That degeneracy is what makes this safe to apply to the whole atlas rather than only
     * to the 354 sprites that carry both populations.
     */
    @Test
    void aSingleClassFootprintIsAPlainBoxMean() {
        int out = LabPbrMaterialReduction.reduce(
                argb(8, 20, 10, 4), argb(9, 21, 10, 5),
                argb(10, 22, 11, 6), argb(11, 23, 11, 7));

        assertEquals(10, alpha(out), "(8+9+10+11)/4 = 9.5 -> 10");
        assertEquals(22, red(out), "(20+21+22+23)/4 = 21.5 -> 22");
        assertEquals(11, green(out), "(10+10+11+11)/4 = 10.5 -> 11");
        assertEquals(6, blue(out), "(4+5+6+7)/4 = 5.5 -> 6");
    }

    /**
     * Blue is its own split and answers its own question: porosity below 65, subsurface above.
     * Three porous texels and one translucent one stay porous rather than becoming a 26%
     * subsurface material with no porosity at all.
     */
    @Test
    void blueDecidesItsOwnClassIndependentlyOfGreen() {
        int out = LabPbrMaterialReduction.reduce(
                argb(0, 0, 255, 30), argb(0, 0, 255, 30),
                argb(0, 0, 255, 30), argb(0, 0, 255, 200));

        assertEquals(255, green(out), "green is all metal");
        assertEquals(30, blue(out), "and blue is all porosity, decided separately");
    }

    /** The subsurface side of the same split, so the rule is not merely "prefer the low value". */
    @Test
    void aSubsurfaceMajorityKeepsSubsurface() {
        int out = LabPbrMaterialReduction.reduce(
                argb(0, 0, 10, 200), argb(0, 0, 10, 210),
                argb(0, 0, 10, 220), argb(0, 0, 10, 2));

        assertEquals(210, blue(out));
    }

    /**
     * Emission is the one channel that is NOT partitioned, and this pins that down: the metal speck
     * carrying all the emission has its light spread over the footprint it actually covers, rather
     * than being handed whole to a reduced texel that green happened to call metal.
     */
    @Test
    void emissionIsAreaAveragedAcrossBothClasses() {
        int out = LabPbrMaterialReduction.reduce(
                argb(200, 130, 255, 0), argb(0, 28, 10, 0),
                argb(0, 28, 10, 0), argb(0, 28, 10, 0));

        assertEquals(10, green(out), "the footprint is matrix");
        assertEquals(50, alpha(out), "but it still emits 200/4");
    }

    /**
     * A box mean of four values that are each at most 254 cannot reach 255, so the emission
     * sentinel cannot be re-manufactured by this reduction the way a cubic resampler's negative
     * lobes re-manufacture it. Stated as a test because the resampler path needed an explicit cap.
     */
    @Test
    void theEmissionSentinelCannotReappearFromAnAuthoredSprite() {
        int out = LabPbrMaterialReduction.reduce(
                argb(254, 0, 0, 0), argb(254, 0, 0, 0),
                argb(254, 0, 0, 0), argb(254, 0, 0, 0));

        assertEquals(254, alpha(out));
        assertTrue(alpha(out) < LabPbrEmissionSentinel.UNAUTHORED);
    }

    @Test
    void anEntirelyUnprovidedEmissionFootprintStaysUnprovided() {
        int missing = argb(255, 0, 0, 0);

        assertEquals(255, alpha(LabPbrMaterialReduction.reduce(
                missing, missing, missing, missing)));
    }

    @Test
    void unprovidedEmissionDoesNotBecomeAHighNumericMagnitude() {
        int authored = argb(200, 0, 0, 0);
        int missing = argb(255, 0, 0, 0);

        int out = LabPbrMaterialReduction.reduce(authored, missing, missing, missing);

        assertEquals(50, alpha(out),
                "one authored texel contributes over one quarter of the footprint; 255 contributes zero");
    }

    /** Channel lanes do not leak into each other. */
    @Test
    void channelsAreIndependent() {
        int out = LabPbrMaterialReduction.reduce(
                argb(1, 2, 3, 4), argb(1, 2, 3, 4),
                argb(1, 2, 3, 4), argb(1, 2, 3, 4));

        assertEquals(argb(1, 2, 3, 4), out);
    }

    /**
     * The same guarantee as {@link #metalCodesAreNeverAveragedIntoAThirdMetal}, chained across two
     * mip levels rather than trusted to hold for a single {@code reduce()} call: a real atlas mip
     * chain feeds one level's output back in as the next level's input, so an invented code at level
     * 1 would silently become a level-0-looking value that level 2 (and everything above it) treats
     * as authored. Every level-0 texel here carries one of exactly two named metal codes; at no
     * level, including the second, may the green channel be anything else.
     */
    @Test
    void noMipLevelInventsAGreenByteAbsentFromLevelZeroAcrossTwoChainedLevels() {
        int iron = argb(0, 96, 230, 0);
        int silver = argb(0, 96, 237, 0);
        java.util.Set<Integer> levelZeroGreens = java.util.Set.of(230, 237);

        int level1A = LabPbrMaterialReduction.reduce(iron, iron, iron, silver);
        int level1B = LabPbrMaterialReduction.reduce(silver, silver, silver, iron);
        int level1C = LabPbrMaterialReduction.reduce(iron, iron, silver, silver);
        int level1D = LabPbrMaterialReduction.reduce(iron, iron, iron, iron);

        for (int texel : new int[] {level1A, level1B, level1C, level1D}) {
            assertTrue(levelZeroGreens.contains(green(texel)),
                    "level 1 invented green " + green(texel));
        }

        int level2 = LabPbrMaterialReduction.reduce(level1A, level1B, level1C, level1D);
        assertTrue(levelZeroGreens.contains(green(level2)),
                "level 2 invented green " + green(level2) + " despite every level-1 input already "
                        + "being confined to " + levelZeroGreens);
    }

    /** The band edge itself: 229 is the highest dielectric and 230 is the first conductor. */
    @Test
    void theBandEdgeIsWhereLabPbrPutsIt() {
        assertEquals(230, LabPbrMaterialReduction.METAL_MIN);

        int justBelow = argb(0, 0, 229, 0);
        int justAbove = argb(0, 0, 230, 0);

        assertEquals(229, green(LabPbrMaterialReduction.reduce(justBelow, justBelow, justBelow, justAbove)));
        assertEquals(230, green(LabPbrMaterialReduction.reduce(justAbove, justAbove, justBelow, justBelow)));
    }
}
