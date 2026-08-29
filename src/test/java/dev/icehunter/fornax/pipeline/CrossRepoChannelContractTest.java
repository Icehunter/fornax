package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the channel-order CONTRACT between a Plague shader branch's real {@code vec4(...)} write and
 * the matching {@link EnvSpecularRatioReadback} formatter that reads it back. The two sides compile
 * independently, so a reorder produces no build error -- just confident, plausible, WRONG numbers.
 * {@link dev.icehunter.fornax.config.GBufferDebugView#UW_GLINT_5} shipped with exactly this defect
 * once: its formatter's R/G/B order silently stopped matching {@code water_composite.fsh:308} after
 * a pack-side restructure, caught only because the same measured value appeared under two different
 * labels in consecutive readings.
 *
 * <p>Scope: instruments where a pack repo authors the channel order a Fornax formatter names, not
 * which repo owns the underlying render target. {@code SHADOW_QUERY_1..3} read the shadow map
 * through a Fornax-owned target name ({@code sunShadowMap}/{@code sunShadowMapRaw}), but the
 * {@code vec4(...)} that fills R/G/B/A is written in Plague's {@code gbuffer_resolve.fsh} -- the same
 * exposure UW_GLINT_5 had. Ordinals where Fornax's own {@code gbuffer_resolve.fsh} (the
 * LabPBR-decode-audit ordinals shipped in this repo) authors the order are out of scope: that
 * coupling already fails at compile time within one repo.
 *
 * <p>Comparison is whitespace-normalized -- both the expected literal and the line read from the
 * pack file have whitespace runs collapsed before substring-matching -- so a reformat can't
 * false-positive fail this test, but a channel reorder still does.
 *
 * <p>Skips (does not fail) when the sibling pack repo isn't checked out next to this one, matching
 * {@code GeometryProgramPathTest.realPlagueBannerPatternProgramResolves}'s convention.
 *
 * <p>Adding a new cross-repo instrument (a UW_GLINT/SHADOW_QUERY-shaped ordinal): add a row to
 * {@link #ROWS} when its formatter is written. The row makes {@link EnvSpecularRatioReadback}'s
 * "quote the exact write" comment convention checkable, not just readable.
 */
class CrossRepoChannelContractTest {
    private static final Path PLAGUE = Path.of("../plague").toAbsolutePath().normalize();

    /**
     * One cross-repo channel contract. {@code expectedStatement} is the exact (pre-normalization)
     * source line the pack is expected to still contain -- copy it verbatim from the pack file when
     * adding a row, the same way each formatter's own comment quotes it.
     */
    private record Row(String ordinal, String packRelativeFile, String expectedStatement) {
    }

    private static final List<Row> ROWS = List.of(
            new Row("UW_GLINT_1", "shaders/post/water_composite.fsh",
                    "fragColor = vec4(uwSunAlignment, uwMoonAlignment, uwFresnel, 1.0);"),
            new Row("UW_GLINT_2", "shaders/post/water_composite.fsh",
                    "fragColor = vec4(uwEyeFilter, 1.0);"),
            new Row("UW_GLINT_3", "shaders/post/water_composite.fsh",
                    "fragColor = vec4(uwSunGlint, uwMoonGlint, u_UnderwaterSunGlitterStrength, 1.0);"),
            new Row("UW_GLINT_4", "shaders/post/water_composite.fsh",
                    "fragColor = vec4(uwGlintContribution, 1.0);"),
            new Row("UW_GLINT_5", "shaders/post/water_composite.fsh",
                    "fragColor = vec4(waveNormal.y, NdotV, worldPos.y, 1.0);"),
            new Row("GLINT_OCCLUSION_QUERY", "shaders/post/glint_occlusion.fsh",
                    "fragColor = vec4(activeVisibility, trueSunVisibility, moonVisibility, 1.0);"),
            // sunDir splats into xyz, ndotl into w -- a vec3-plus-scalar shape, not four independent
            // scalars, but still an exact substring once whitespace-normalized.
            new Row("SHADOW_QUERY_1", "shaders/post/gbuffer_resolve.fsh",
                    "fragColor = vec4(sunDir, ndotl);"),
            // Contains a ternary (dbgInRange ? 1.0 : 0.0) -- normalize() only strips whitespace, so
            // the '?'/':' characters pass through untouched and this still matches as a plain literal
            // substring; no special handling needed.
            new Row("SHADOW_QUERY_2", "shaders/post/gbuffer_resolve.fsh",
                    "fragColor = vec4(dbgShadowUv, dbgInRange ? 1.0 : 0.0, visibility);"),
            new Row("SHADOW_QUERY_3", "shaders/post/gbuffer_resolve.fsh",
                    "fragColor = vec4(dbgRawDepth, 0.0, dbgStoredDepth, 0.0);"));

    @Test
    void formatterChannelOrderMatchesTheRealPackWrite() throws IOException {
        if (!Files.isRegularFile(PLAGUE.resolve("pack.toml"))) {
            return; // pack not present next to this checkout -- see this class's own doc comment
        }

        List<String> violations = new ArrayList<>();
        for (Row row : ROWS) {
            Path file = PLAGUE.resolve(row.packRelativeFile());
            if (!Files.isRegularFile(file)) {
                violations.add(row.ordinal() + ": expected pack file '" + row.packRelativeFile()
                        + "' does not exist under " + PLAGUE + " -- the file itself moved or was renamed");
                continue;
            }
            String source = Files.readString(file);
            if (normalize(source).contains(normalize(row.expectedStatement()))) {
                continue; // contract holds
            }
            String candidate = findLikelyReplacement(source, row.expectedStatement());
            violations.add(row.ordinal() + ": " + row.packRelativeFile() + " no longer contains the "
                    + "channel order EnvSpecularRatioReadback's formatter assumes.\n"
                    + "    expected (from the formatter's own comment): " + row.expectedStatement() + "\n"
                    + "    " + (candidate != null
                            ? "found instead, same variables different order/content: " + candidate
                            : "no line containing all of this ordinal's channel names was found either"
                                    + " -- the variables themselves may have been renamed, not just reordered")
                    + "\n    fix: update the matching formatter case in EnvSpecularRatioReadback.java"
                    + " (and its comment) to the ACTUAL current write, then update this row to match.");
        }

        if (!violations.isEmpty()) {
            fail("Cross-repo channel contract violated for " + violations.size() + " of " + ROWS.size()
                    + " instrument(s) -- a formatter printing mislabelled channels produces confident,"
                    + " plausible, WRONG numbers with no error anywhere else:\n\n"
                    + String.join("\n\n", violations));
        }
    }

    /** Whitespace-insensitive containment check -- collapses every whitespace run to nothing so a
     * pure reformat of the same expression cannot fail this test, while a channel reorder (different
     * token sequence) still does. */
    private static String normalize(String s) {
        return s.replaceAll("\\s+", "");
    }

    /** Best-effort diagnostic for the failure message: find a line in {@code source} that still
     * mentions every non-numeric token the expected statement's {@code vec4(...)} argument list
     * names, regardless of order -- i.e. "the same variables, but rearranged or otherwise changed",
     * which is exactly the shape a channel-reorder bug takes. Returns {@code null} if no such line
     * exists (the variables themselves were likely renamed, which this cannot localize further). */
    private static String findLikelyReplacement(String source, String expectedStatement) {
        int open = expectedStatement.indexOf('(');
        int close = expectedStatement.lastIndexOf(')');
        if (open < 0 || close < 0 || close <= open) {
            return null;
        }
        String[] tokens = expectedStatement.substring(open + 1, close).split(",");
        List<String> anchors = new ArrayList<>();
        for (String token : tokens) {
            String t = token.trim();
            // Skip pure numeric/ternary-punctuation tokens (e.g. "1.0", "0.0 : 1.0") -- they carry no
            // identifying signal and would match almost any line.
            if (t.matches("[-0-9.: ?]+")) {
                continue;
            }
            anchors.add(t);
        }
        if (anchors.isEmpty()) {
            return null;
        }
        for (String line : source.split("\n")) {
            if (!line.contains("vec4(")) {
                continue;
            }
            boolean allPresent = true;
            for (String anchor : anchors) {
                if (!line.contains(anchor)) {
                    allPresent = false;
                    break;
                }
            }
            if (allPresent) {
                return line.trim();
            }
        }
        return null;
    }
}
