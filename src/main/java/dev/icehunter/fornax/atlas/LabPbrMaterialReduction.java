package dev.icehunter.fornax.atlas;

/**
 * Reduces four labPBR {@code _s} texels to one, per CHANNEL, for the material atlas's mip chain.
 *
 * <p>A box filter is the right answer for a colour and the wrong answer for this map. labPBR's
 * specular map is four independent lanes and only two of them are magnitudes:
 *
 * <ul>
 *   <li><b>green</b> is a CATEGORICAL CODE above 229 -- "metal number N", with hardcoded optical
 *       constants -- and a plain 0..229 dielectric F0 below it.</li>
 *   <li><b>blue</b> is ONE byte carrying TWO quantities on either side of 64: porosity below,
 *       subsurface scattering above (see {@code plagueDecodeMaterial}).</li>
 *   <li><b>red</b> is perceptual smoothness. <b>alpha</b> is emission magnitude {@code 0..254},
 *       with categorical per-texel absence at {@code 255}.</li>
 * </ul>
 *
 * <p>Averaging a code with a magnitude produces a value that means neither, and it does so at every
 * boundary between the two populations -- which on an ore is the outline of every speck. Measured
 * on a 256x labPBR pack, every ore's green is strictly BINARY:
 * a stone matrix at exactly 10 and a metal population at its one code (255, or 234 on copper). The
 * mean of a metal texel and a matrix texel is about 120, which the shader reads back as a
 * DIELECTRIC with F0 0.47 -- 12x the matrix's 0.039 and brighter than either input. That is the
 * white rim, and 354 of that pack's 1402 {@code _s} maps carry both populations and so carry the
 * hazard: every ore, every copper block, every copper door and trapdoor, and the tools.
 *
 * <p><b>A naive box-filtered mip chain makes it dramatically worse, which is why this class exists
 * rather than a call to the normal atlas's {@code averageChannels}.</b> Fraction of texels holding
 * an invented green -- a value the sprite has NOWHERE -- through a chain built each way:
 *
 * <pre>
 *   copper_ore   L0      L1      L2      L3      L4      L5      L6
 *   box mean    0.00%   6.04%  13.48%  22.95%  36.33%  53.12%  93.75%
 *   this class  0.00%   0.00%   0.00%   0.00%   0.00%   0.00%   0.00%
 * </pre>
 *
 * <p>The control that says the rule is doing the work and not merely doing nothing: coal ore and
 * plain stone are 0.00% under BOTH filters at every level, because they have no metal texels at all
 * -- and coal is the one ore the user reported as looking correct.
 */
public final class LabPbrMaterialReduction {
    /** labPBR's first conductor code. 230..237 are named metals; 238..255 are "some other metal". */
    public static final int METAL_MIN = 230;

    /** labPBR's porosity/subsurface split in the blue channel: 0..64 porosity, 65..255 subsurface. */
    public static final int POROSITY_MAX = 64;

    /**
     * Texels of the four that must belong to a class for that class to win the reduced texel.
     *
     * <p>Two of four, so half a footprint is enough -- the tie goes to the class the pack went out
     * of its way to author. That is a measurement rather than a preference. Metal coverage through
     * the chain, against the pack's own level-0 coverage, on the two rules that differ only here:
     *
     * <pre>
     *                       L0      L1      L2      L3      L4
     *   copper, >= 2 of 4  20.87%  21.84%  23.12%  24.80%  26.95%
     *   copper, >= 3 of 4  20.87%  19.86%  18.65%  17.09%  14.06%
     *   iron,   >= 2 of 4   6.24%   6.67%   7.40%   8.01%   8.98%
     *   iron,   >= 3 of 4   6.24%   5.51%   4.25%   2.54%   0.39%
     * </pre>
     *
     * <p>The two are near mirror images on copper, but a strict majority erases a sparse ore: iron's
     * specks are 6.2% of its face and a 3-of-4 rule takes them to 0.4% by level 4 -- an iron ore
     * block that stops being metal about fifteen blocks away. Half-or-more drifts by a comparable
     * amount in the other direction and keeps the ore.
     */
    public static final int CLASS_QUORUM = 2;

    private LabPbrMaterialReduction() {
    }

    /**
     * Reduces a 2x2 block of {@code _s} texels to one, in ARGB (which is what
     * {@code NativeImage.getPixel} returns -- it converts from the buffer's native ABGR on the way
     * out, so red is byte 2, green byte 1, blue byte 0).
     *
     * <p>Per channel, and the reason for each:
     *
     * <ul>
     *   <li><b>GREEN -- by class, then mean or MODE depending on which class won.</b> A texel is
     *       metal at 230 or above, and whichever class holds {@link #CLASS_QUORUM} or more of the
     *       four wins. A dielectric winner takes the MEAN of the winning greens, because below the
     *       band green is an ordinary F0 magnitude and interpolating it is exactly right. A metal
     *       winner takes the MODE instead, because up there each value is an IDENTITY: 230..237 name
     *       eight specific metals with measured optical constants, so the mean of 232 (aluminium)
     *       and 234 (copper) is 233, which is CHROME -- a metal the pack never mentioned. Ties go to
     *       the LARGER code, since 238..255 all mean the same unnamed "some metal, take its
     *       reflectance from the albedo": drifting up asserts less than naming a metal.
     *       <br>Rare but real: 40 of the 432 metal-bearing sprites in the measured pack carry more
     *       than one code, and on copper ore it is 75 texels of 13,679 -- authoring dither, which is
     *       precisely the kind of thing a mean smears and a mode ignores.</li>
     *   <li><b>RED -- mean over the winning class only, not over all four.</b> Red describes the
     *       material green just named, and averaging across the classes describes neither. Measured
     *       on the same pack: an ore's metal texels carry smoothness 96..132 while its stone matrix
     *       carries 28, so a plain mean hands a reduced texel that green calls METAL the stone's
     *       gloss -- a dull metal, which is a different artefact from the one being fixed rather
     *       than a smaller amount of it.</li>
     *   <li><b>BLUE -- by its own class, independently of green.</b> Porosity and subsurface are
     *       separate labPBR lanes and a sprite's metalness says nothing about which one its blue
     *       byte means. Same quorum, applied to the 64/65 split: a porosity 30 averaged with a
     *       subsurface 200 gives 115, which decodes as 26% subsurface and no porosity at all.
     *       Exercised by 9 sprites in the measured pack ({@code spruce_leaves}, the birch and
     *       copper doors, {@code burning_skull}), so it is rare rather than theoretical.</li>
     *   <li><b>ALPHA -- area-weighted authored emission with categorical absence.</b> An all-255
     *       footprint remains 255. Otherwise authored {@code 0..254} values contribute over the
     *       footprint area and each 255 contributes zero; absence is never averaged as magnitude.</li>
     * </ul>
     *
     * @param p0 top-left texel, ARGB
     * @param p1 top-right texel, ARGB
     * @param p2 bottom-left texel, ARGB
     * @param p3 bottom-right texel, ARGB
     * @return the reduced texel, ARGB
     */
    public static int reduce(int p0, int p1, int p2, int p3) {
        int g0 = (p0 >> 8) & 0xFF;
        int g1 = (p1 >> 8) & 0xFF;
        int g2 = (p2 >> 8) & 0xFF;
        int g3 = (p3 >> 8) & 0xFF;

        int metals = bit(g0 >= METAL_MIN) + bit(g1 >= METAL_MIN) + bit(g2 >= METAL_MIN) + bit(g3 >= METAL_MIN);
        boolean metalWins = metals >= CLASS_QUORUM;

        // A member mask rather than four booleans: the same set decides green AND red, which is the
        // whole point of the class partition -- they have to describe the same material.
        boolean m0 = (g0 >= METAL_MIN) == metalWins;
        boolean m1 = (g1 >= METAL_MIN) == metalWins;
        boolean m2 = (g2 >= METAL_MIN) == metalWins;
        boolean m3 = (g3 >= METAL_MIN) == metalWins;

        int green = metalWins
                ? modeOf(g0, g1, g2, g3, m0, m1, m2, m3)
                : meanOf(g0, g1, g2, g3, m0, m1, m2, m3);
        int red = meanOf((p0 >> 16) & 0xFF, (p1 >> 16) & 0xFF, (p2 >> 16) & 0xFF, (p3 >> 16) & 0xFF,
                m0, m1, m2, m3);

        int b0 = p0 & 0xFF;
        int b1 = p1 & 0xFF;
        int b2 = p2 & 0xFF;
        int b3 = p3 & 0xFF;
        int porous = bit(b0 <= POROSITY_MAX) + bit(b1 <= POROSITY_MAX) + bit(b2 <= POROSITY_MAX) + bit(b3 <= POROSITY_MAX);
        boolean porousWins = porous >= CLASS_QUORUM;
        int blue = meanOf(b0, b1, b2, b3,
                (b0 <= POROSITY_MAX) == porousWins,
                (b1 <= POROSITY_MAX) == porousWins,
                (b2 <= POROSITY_MAX) == porousWins,
                (b3 <= POROSITY_MAX) == porousWins);

        int a0 = p0 >>> 24;
        int a1 = p1 >>> 24;
        int a2 = p2 >>> 24;
        int a3 = p3 >>> 24;
        boolean allEmissionAbsent = a0 == LabPbrEmissionSentinel.UNAUTHORED
                && a1 == LabPbrEmissionSentinel.UNAUTHORED
                && a2 == LabPbrEmissionSentinel.UNAUTHORED
                && a3 == LabPbrEmissionSentinel.UNAUTHORED;
        int alpha = allEmissionAbsent ? LabPbrEmissionSentinel.UNAUTHORED : roundedMean(
                emissionMagnitude(a0) + emissionMagnitude(a1)
                        + emissionMagnitude(a2) + emissionMagnitude(a3), 4);

        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int bit(boolean b) {
        return b ? 1 : 0;
    }

    private static int emissionMagnitude(int alpha) {
        return alpha == LabPbrEmissionSentinel.UNAUTHORED ? 0 : alpha;
    }

    /** Rounded mean of the values whose membership flag is set. At least one always is. */
    private static int meanOf(int v0, int v1, int v2, int v3, boolean m0, boolean m1, boolean m2, boolean m3) {
        int sum = 0;
        int n = 0;
        if (m0) {
            sum += v0;
            n++;
        }
        if (m1) {
            sum += v1;
            n++;
        }
        if (m2) {
            sum += v2;
            n++;
        }
        if (m3) {
            sum += v3;
            n++;
        }
        return roundedMean(sum, n);
    }

    private static int roundedMean(int sum, int count) {
        return (sum + count / 2) / count;
    }

    /**
     * The most common of the member values, ties going to the largest. The only reduction available
     * to a value that is a NAME rather than a quantity -- see {@link #reduce}'s green rule.
     */
    private static int modeOf(int v0, int v1, int v2, int v3, boolean m0, boolean m1, boolean m2, boolean m3) {
        int[] values = {v0, v1, v2, v3};
        boolean[] members = {m0, m1, m2, m3};
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < 4; i++) {
            if (!members[i]) {
                continue;
            }
            int count = 0;
            for (int j = 0; j < 4; j++) {
                if (members[j] && values[j] == values[i]) {
                    count++;
                }
            }
            if (count > bestCount || (count == bestCount && values[i] > best)) {
                best = values[i];
                bestCount = count;
            }
        }
        return best;
    }
}
