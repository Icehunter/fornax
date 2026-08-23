package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the one-way door in {@link GraphRunner#wantsSunAndDebugParams}: a fullscreen pass that reads
 * {@code u_SunDirection} must be listed there, or it silently renders as though the sun were at full
 * zenith.
 *
 * <p><b>Why this needs a test at all.</b> Omitting a pass is not a loud failure.
 * {@code PassParams.reset()} defaults {@code trueSunHeight} to <b>1.0</b>, so an unlisted pass reads
 * a perfectly valid "sun overhead" rather than a zero, a NaN, or anything a shader could defend
 * against. Live-caught 2026-08-03: Plague's clouds rendered SUNSET-ORANGE at midnight beneath a
 * correct night sky, because {@code sunVisibility} pinned to 1.0 (full day) while {@code noonFactor}
 * -- sourced from the GLOBAL {@code u_SkyState.y}, and therefore right -- read 0.0, and
 * {@code mix(sunset, noon, 0.0)} selects pure sunset colour at full strength. The sky looked correct
 * throughout, because {@code gbuffer_resolve} was on the list and the clouds pass was not. Two passes
 * disagreeing about what time it is, with no error anywhere.
 *
 * <p>Costs a test run to catch; cost a launch to find.
 */
class GraphRunnerSunParamsTest {

    /**
     * The {@code u_PassParams} block, which is the only place {@code u_SunDirection} can be declared
     * -- it rides in that per-pass block, not in {@code u_Globals}.
     *
     * <p><b>Declaring it is not evidence of wanting it.</b> std140 is positional, so a pass needing
     * only {@code u_Param2} must still declare every field ahead of it, sun included; the bloom blur
     * chain and the SSAO passes all declare the full prefix and never touch the sun. The test
     * therefore strips this block out and asks whether {@code u_SunDirection} survives ANYWHERE else
     * in the file -- i.e. whether the shader actually READS it. First cut of this test keyed on the
     * declaration and reported nine false positives.
     */
    private static final Pattern PASS_PARAMS_BLOCK =
            Pattern.compile("uniform\\s+u_PassParams\\s*\\{[^}]*}", Pattern.DOTALL);

    @Test
    void everyPassShaderReadingSunDirectionIsWiredForIt() throws IOException {
        Path plague = locatePlague();
        assumeTrue(plague != null, "Plague pack not present next to this checkout -- skipping");

        Path graph = plague.resolve("graph.toml");
        assumeTrue(Files.isRegularFile(graph), "graph.toml absent -- skipping");
        String graphText = Files.readString(graph);

        Set<String> unwired = new TreeSet<>();
        for (PassDecl pass : declaredFullscreenPasses(graphText)) {
            Path shader = plague.resolve(pass.shader);
            if (!Files.isRegularFile(shader)) {
                continue;
            }
            if (!readsSunDirection(Files.readString(shader))) {
                continue;
            }
            if (!GraphRunner.wantsSunAndDebugParams(pass.name)) {
                unwired.add(pass.name + " (" + pass.shader + ")");
            }
        }

        assertTrue(unwired.isEmpty(),
                "These passes READ u_SunDirection but GraphRunner.wantsSunAndDebugParams does not "
                        + "match them, so they will read trueSunHeight = 1.0 (sun at full zenith) at "
                        + "every hour, including midnight: " + unwired);
    }

    /**
     * The converse is deliberately NOT asserted. Several listed passes want only part of the block --
     * {@code ssr_water_fill} and {@code direct_light_analytic} are matched for {@code u_Param2}/
     * {@code u_Param3} and never read the sun at all -- so "listed but does not declare it" is a
     * legitimate state, not a defect. Filling a pass's params costs nothing; starving one silently
     * changes what it renders. Only the harmful direction is a failure.
     */
    @Test
    void theCloudMarchSpecificallyIsWired() {
        assertTrue(GraphRunner.wantsSunAndDebugParams("clouds_march"),
                "clouds_march reads u_SunDirection.w as the sun-up dot product, the input its "
                        + "entire day/night colour crossfade is keyed on");
        assertTrue(GraphRunner.wantsSunAndDebugParams("clouds_march_full"),
                "the CLOUD_QUALITY==2 arm is a separate pass name and needs the same wiring -- "
                        + "matching it is the whole reason that entry is a prefix match");
    }

    @Test
    void waterVolumeMarchReceivesTheActiveCelestialDirection() {
        assertTrue(GraphRunner.wantsSunAndDebugParams("water_volume_march"));
    }

    @Test
    void waterVolumeHistoryReceivesTheLiveDebugIdSoDiagnosticsCannotPoisonHistory() {
        assertTrue(GraphRunner.wantsSunAndDebugParams("water_volume_scatter_history"));
    }

    private record PassDecl(String name, String shader, String type) {
    }

    /**
     * Walks {@code [[pass]]} tables, keeping those with a {@code shader = } line. Deliberately a
     * scan rather than a TOML parse: this test must fail when a NEW pass is added without wiring,
     * and the cheapest way to guarantee that is to read the same file the author edits.
     */
    private static java.util.List<PassDecl> declaredFullscreenPasses(String graphText) {
        java.util.List<PassDecl> out = new java.util.ArrayList<>();
        for (String block : graphText.split("\\[\\[pass]]")) {
            String name = firstMatch(block, "(?m)^\\s*name\\s*=\\s*\"([^\"]+)\"");
            String shader = firstMatch(block, "(?m)^\\s*shader\\s*=\\s*\"([^\"]+)\"");
            String type = firstMatch(block, "(?m)^\\s*type\\s*=\\s*\"([^\"]+)\"");
            if (name != null && shader != null) {
                out.add(new PassDecl(name, shader, type == null ? "" : type));
            }
        }
        return out;
    }

    private static String firstMatch(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The SAME one-way door, on the other field of the same block.
     *
     * <p>{@code u_Param2} carries the terrain render distance to every pass that fogs, and
     * {@link PassParams#reset()} leaves it at <b>0.0</b> -- a valid float no shader can tell from a
     * real one. Plague's fog anchors its border curve on this lane in {@code resolve} and
     * {@code water_composite}; unwired, the curve's denominator is whatever the shader's fallback
     * happens to be, and the veil either never closes or closes at the camera. Neither raises an
     * error.
     *
     * <p>The sun half of this file was written first and this half was NOT added with it -- the
     * omission was called out as still-open when Plague's fog landed. It is the same shape of bug in
     * the same struct.
     */
    @Test
    void everyPassShaderReadingParam2IsWiredForIt() throws IOException {
        Path plague = locatePlague();
        assumeTrue(plague != null, "Plague pack not present next to this checkout -- skipping");
        Path graph = plague.resolve("graph.toml");
        assumeTrue(Files.isRegularFile(graph), "graph.toml absent -- skipping");

        Set<String> unwired = new TreeSet<>();
        for (PassDecl pass : declaredFullscreenPasses(Files.readString(graph))) {
            Path shader = plague.resolve(pass.shader);
            if (!Files.isRegularFile(shader) || !readsParam2(Files.readString(shader))) {
                continue;
            }
            // Mipchain passes never reach GraphRunner.computeParams: MipchainRunner builds its own
            // params buffer and puts a seed/reduce FLAG in this slot. Exempted by TYPE rather than
            // by name so a second mipchain pass needs no edit here.
            if ("mipchain".equals(pass.type) || GraphRunner.suppliesParam2(pass.name)) {
                continue;
            }
            unwired.add(pass.name + " (" + pass.shader + ")");
        }

        assertTrue(unwired.isEmpty(),
                "These passes READ u_Param2 but no branch of GraphRunner.computeParams fills it for "
                        + "them, so they will read 0.0 -- silently, with no error: " + unwired);
    }

    /**
     * The two Plague fog sites specifically, because they are the reason this half exists and
     * because a prefix-match refactor of the predicate above could drop either without the sweep
     * noticing (the sweep only fails for a pass that READS the field, and a pack edit could remove
     * the read at the same time as the wiring).
     */
    @Test
    void theFogSitesSpecificallyAreWiredForRenderDistance() {
        assertTrue(GraphRunner.suppliesParam2("resolve"),
                "gbuffer_resolve.fsh anchors its border fog on the terrain render distance");
        assertTrue(GraphRunner.suppliesParam2("water_composite"),
                "water_composite.fsh must use the SAME anchor as the resolve, or distant water "
                        + "dissolves at a different screen distance from the land beside it");
    }

    /** True when the shader mentions u_Param2 somewhere other than the forced std140 declaration. */
    private static boolean readsParam2(String shaderSource) {
        return stripComments(PASS_PARAMS_BLOCK.matcher(shaderSource).replaceAll(""))
                .contains("u_Param2");
    }

    /** True when the shader mentions the sun somewhere other than the forced std140 declaration. */
    private static boolean readsSunDirection(String shaderSource) {
        return stripComments(PASS_PARAMS_BLOCK.matcher(shaderSource).replaceAll(""))
                .contains("u_SunDirection");
    }

    /**
     * A comment is not a read. Both predicates above exist to find REAL uses, and a pack comment
     * that merely names the field -- e.g. clouds_composite.fsh documenting WHY it avoids u_Param2,
     * which this test itself is the reason for -- must not count as one. First tripped by exactly
     * that comment: the honest documentation of a wiring decision failed the test that enforces
     * the wiring.
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /** Mirrors {@code PlaguePackLoadsTest.locatePlague()} -- same two candidates, same skip. */
    private static Path locatePlague() {
        Path repo = Path.of("").toAbsolutePath();
        for (Path candidate : Stream.of(
                repo.resolve("run/shaderpacks/Plague"),
                repo.getParent() == null ? null : repo.getParent().resolve("plague")).toList()) {
            if (candidate != null && Files.isRegularFile(candidate.resolve("pack.toml"))) {
                return candidate;
            }
        }
        return null;
    }
}
