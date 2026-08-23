package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.voxel.BrickGridUpload;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load-time validation that every pack shader mirroring the light volume's cells-per-section-axis
 * agrees with the values {@link BrickGridUpload} actually packs with, at BOTH Light Detail tiers.
 *
 * <p>Sibling to {@link PaletteStrideContract} -- same hazard, same fix shape: a shader resolves a
 * light cell as {@code (cy*CELLS_PER_AXIS + cz)*CELLS_PER_AXIS + cx}, so the axis count is baked into
 * the addressing math of every consumer. GLSL cannot see Java's constants, so each of the three real
 * pack mirrors ({@code light_inject.comp}, {@code light_propagate.comp}, {@code gbuffer_resolve.fsh})
 * hand-declares {@code CELLS_PER_AXIS} (or, in {@code gbuffer_resolve.fsh}, its own {@code
 * EL_CELLS_PER_AXIS} copy) inside a {@code #if LIGHT_CELL_DETAIL == 1 ... #else ... #endif} compile
 * branch -- one literal for the High tier, one for Standard. A mirror left stale does not fail, it
 * silently reads a DIFFERENT cell than the one Java wrote and renders plausible-looking garbage (a
 * shape no tuning could explain), exactly the {@link PaletteStrideContract} hazard.
 *
 * <p>Unlike {@link PaletteStrideContract}'s constants (fixed engine values, guarding only against
 * cross-repo version skew), this one genuinely varies with the user's selected tier -- so this check
 * does NOT compare against "whichever tier is currently selected." Because {@code
 * DefineRewriter} only rewrites the {@code LIGHT_CELL_DETAIL} {@code #define}'s value, never resolves
 * the shader's own {@code #if}/{@code #else} branches (that happens in the real GLSL preprocessor at
 * SPIR-V compile time), BOTH branches are always present, in full, in the raw source text this class
 * sees -- so this check validates BOTH literals unconditionally, every pack load, regardless of which
 * tier is currently active. That is strictly stronger than a "does the active value match" check: it
 * catches a stale/mistyped literal in the tier the user ISN'T currently using too, before they ever
 * switch to it and get silent garbage with no correlated code change to blame.
 */
public final class LightCellStrideContract {
    /** Matches the hand-mirrored {@code #if LIGHT_CELL_DETAIL == 1 ... const int (EL_)?CELLS_PER_AXIS
     * = N; ... #else ... const int (same name) = N; ... #endif} shape, capturing the constant's own
     * name (group 1, so the High and Standard declarations are required to name the SAME constant --
     * a backreference, not a second independent match) and both branches' literal (groups 2 and 3).
     * Tolerates the whitespace and trailing end-of-line comment variations across the three mirrors,
     * same discipline as {@link PaletteStrideContract}'s own patterns. */
    private static final Pattern CELLS_PER_AXIS_BRANCHES = Pattern.compile(
            "#if\\s+LIGHT_CELL_DETAIL\\s*==\\s*1\\s*\n"
                    + "\\s*const\\s+int\\s+((?:EL_)?CELLS_PER_AXIS)\\s*=\\s*(\\d+)\\s*;.*\n"
                    + "\\s*#else\\s*\n"
                    + "\\s*const\\s+int\\s+\\1\\s*=\\s*(\\d+)\\s*;.*\n"
                    + "\\s*#endif");

    private LightCellStrideContract() {}

    /**
     * @param sources pack shader sources keyed pack-root-relative ("shaders/compute/light_inject.comp"),
     *                the same map {@link ShaderImports#validate} and {@link PaletteStrideContract#validate} take
     */
    public static void validate(Map<String, String> sources) {
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Matcher m = CELLS_PER_AXIS_BRANCHES.matcher(source.getValue());
            while (m.find()) {
                String constantName = m.group(1);
                int highMirrored = Integer.parseInt(m.group(2));
                int standardMirrored = Integer.parseInt(m.group(3));
                if (highMirrored != BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_HIGH) {
                    throw new FornaxPackError(source.getKey(), constantName,
                            "declares " + constantName + " = " + highMirrored + " under `#if "
                                    + "LIGHT_CELL_DETAIL == 1`, but this engine's High Light Detail tier "
                                    + "packs the light volume at "
                                    + BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_HIGH
                                    + " cells per section axis. Every light-volume read in this shader's "
                                    + "High-detail branch would address the wrong cell and render "
                                    + "plausible-looking garbage. Update the pack to match this engine "
                                    + "version.");
                }
                if (standardMirrored != BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_STANDARD) {
                    throw new FornaxPackError(source.getKey(), constantName,
                            "declares " + constantName + " = " + standardMirrored + " in its Standard "
                                    + "(`#else`) branch, but this engine's Standard Light Detail tier "
                                    + "packs the light volume at "
                                    + BrickGridUpload.LIGHT_CELLS_PER_SECTION_AXIS_STANDARD
                                    + " cells per section axis. Every light-volume read in this shader's "
                                    + "default configuration would address the wrong cell and render "
                                    + "plausible-looking garbage. Update the pack to match this engine "
                                    + "version.");
                }
            }
        }
    }
}
