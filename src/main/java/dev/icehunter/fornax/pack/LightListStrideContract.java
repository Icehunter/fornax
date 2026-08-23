package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.graph.AnalyticLightListBuffer;
import dev.icehunter.fornax.voxel.BrickGridUpload;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load-time validation that every pack shader mirroring the analytic-light-list buffer layout (M1)
 * or the brick-summary bit layout agrees with the values {@link AnalyticLightListBuffer} and
 * {@link BrickGridUpload} actually use -- the same {@link PaletteStrideContract}/
 * {@link LightCellStrideContract} discipline applied to this milestone's own new hand-mirrors.
 *
 * <p>Two independent hazards, both silent-garbage-not-error if they drift:
 * <ul>
 *   <li>{@code light_list_build.comp} hand-mirrors {@link AnalyticLightListBuffer#MAX_LIGHTS} (the
 *       over-cap guard on its atomic append) and {@link AnalyticLightListBuffer#WORDS_PER_LIGHT} (the
 *       per-light stride it writes at). A stale {@code MAX_LIGHTS} either drops real lights early
 *       (too small) or writes past the buffer's real capacity -- a genuine SSBO out-of-bounds write,
 *       Vulkan UB (too large). A stale {@code WORDS_PER_LIGHT} makes every light after the first
 *       overlap the next light's words, corrupting the whole list.</li>
 *   <li>{@code light_list_build.comp} ALSO hand-mirrors {@code SUMMARY_HAS_EMITTER}/
 *       {@code SUMMARY_PENDING} -- the SAME two bit constants {@code light_inject.comp} and
 *       {@code light_propagate.comp} already hand-mirror today, entirely unguarded by any existing
 *       contract (grep-confirmed: neither {@link PaletteStrideContract} nor
 *       {@link LightCellStrideContract} touches them). Introducing a THIRD copy without a guard would
 *       repeat the same class of bug this contract family exists to catch; this class closes that gap
 *       for all three copies at once (the pattern is a generic {@code const uint NAME = 0xHEXu;}
 *       match, not file-scoped, so it validates every pack source that declares either name).</li>
 * </ul>
 */
public final class LightListStrideContract {
    private static final Pattern MAX_LIGHTS_DECL =
            Pattern.compile("const\\s+uint\\s+MAX_LIGHTS\\s*=\\s*(\\d+)u\\s*;");
    private static final Pattern WORDS_PER_LIGHT_DECL =
            Pattern.compile("const\\s+uint\\s+WORDS_PER_LIGHT\\s*=\\s*(\\d+)u\\s*;");
    private static final Pattern SUMMARY_HAS_EMITTER_DECL =
            Pattern.compile("const\\s+uint\\s+SUMMARY_HAS_EMITTER\\s*=\\s*0[xX]([0-9a-fA-F]+)u\\s*;");
    private static final Pattern SUMMARY_PENDING_DECL =
            Pattern.compile("const\\s+uint\\s+SUMMARY_PENDING\\s*=\\s*0[xX]([0-9a-fA-F]+)u\\s*;");

    private LightListStrideContract() {}

    /**
     * @param sources pack shader sources keyed pack-root-relative, the same map
     *                {@link ShaderImports#validate} takes
     */
    public static void validate(Map<String, String> sources) {
        for (Map.Entry<String, String> source : sources.entrySet()) {
            checkIntDecl(source, MAX_LIGHTS_DECL, "MAX_LIGHTS", AnalyticLightListBuffer.MAX_LIGHTS,
                    "AnalyticLightListBuffer.MAX_LIGHTS", "over-caps or under-caps the analytic light "
                            + "list's atomic append, either dropping real lights early or writing past "
                            + "the buffer's real capacity (Vulkan UB)");
            checkIntDecl(source, WORDS_PER_LIGHT_DECL, "WORDS_PER_LIGHT",
                    AnalyticLightListBuffer.WORDS_PER_LIGHT, "AnalyticLightListBuffer.WORDS_PER_LIGHT",
                    "would make every light after the first overlap the next light's words, corrupting "
                            + "the whole analytic light list");
            checkHexDecl(source, SUMMARY_HAS_EMITTER_DECL, "SUMMARY_HAS_EMITTER",
                    BrickGridUpload.SUMMARY_HAS_EMITTER, "BrickGridUpload.SUMMARY_HAS_EMITTER",
                    "would make this shader read the wrong bit of the brick-summary word and silently "
                            + "skip/scan the wrong bricks for emitters");
            checkHexDecl(source, SUMMARY_PENDING_DECL, "SUMMARY_PENDING", BrickGridUpload.SUMMARY_PENDING,
                    "BrickGridUpload.SUMMARY_PENDING", "would make this shader fail to recognize a "
                            + "not-yet-harvested brick as pending, reading its stale/zeroed data as real");
        }
    }

    private static void checkIntDecl(Map.Entry<String, String> source, Pattern pattern, String name,
                                      int expected, String engineRef, String consequence) {
        Matcher matcher = pattern.matcher(source.getValue());
        while (matcher.find()) {
            int mirrored = Integer.parseInt(matcher.group(1));
            if (mirrored != expected) {
                throw new FornaxPackError(source.getKey(), name,
                        "declares " + name + " = " + mirrored + ", but this engine's " + engineRef
                                + " is " + expected + ". " + consequence + ". Update the pack to match "
                                + "this engine version.");
            }
        }
    }

    private static void checkHexDecl(Map.Entry<String, String> source, Pattern pattern, String name,
                                      int expected, String engineRef, String consequence) {
        Matcher matcher = pattern.matcher(source.getValue());
        while (matcher.find()) {
            int mirrored = (int) Long.parseLong(matcher.group(1), 16);
            if (mirrored != expected) {
                throw new FornaxPackError(source.getKey(), name,
                        "declares " + name + " = 0x" + matcher.group(1) + "u, but this engine's "
                                + engineRef + " is 0x" + Integer.toHexString(expected) + "u. "
                                + consequence + ". Update the pack to match this engine version.");
            }
        }
    }
}
