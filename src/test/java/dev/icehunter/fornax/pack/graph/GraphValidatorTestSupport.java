package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import dev.icehunter.fornax.pack.option.OptionScanner;
import dev.icehunter.fornax.pack.option.PackOption;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small {@link GraphSpec} fixture builders for {@link GeometryInputValidationTest}, built from TOML
 * text through the real {@link PackTomlLoader} -- the same construction shape every other {@code
 * GraphValidator} test in this package already uses (see {@code GraphValidatorTest}), which keeps
 * pass/target declaration order exactly as written (the TOML parser preserves file order into
 * {@code GraphSpec.passes()}'s {@code List}, never a {@code Map.of}-style unordered collection).
 */
final class GraphValidatorTestSupport {
    private GraphValidatorTestSupport() {}

    static void validateTerrainWithHistoryInputWrittenByPass() {
        String toml = """
                [targets.simulation]
                format = "rg16f"
                scale = 1.0
                history = true

                [[pass]]
                name = "terrain_opaque"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain"
                inputs = ["simulation.history"]
                outputs = []

                [[pass]]
                name = "simulation_update"
                type = "fullscreen"
                shader = "shaders/post/simulation.fsh"
                inputs = ["simulation.history"]
                outputs = ["simulation"]
                """;
        GraphSpec graph = PackTomlLoader.loadGraph(new java.io.StringReader(toml), "graph.toml");
        GraphValidator.validate(graph, java.util.Map.of(), 1920, 1080);
    }

    /** A single {@code terrain_opaque} geometry pass declaring exactly {@code inputs} in order. */
    static void validateTerrainWithInputs(String... inputs) {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
                [[pass]]
                name = "terrain_opaque"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain"
                inputs = [%s]
                """.formatted(quoteList(inputs))), "graph.toml");
        GraphValidator.validate(g, Map.of(), 1920, 1080);
    }

    /**
     * {@code terrain_opaque} (declared FIRST, deliberately) reads target {@code ssr}, which a
     * fullscreen pass declared AFTER it writes -- pins that the finality rule's only signal is
     * "written by some pass in the graph", not file-order position relative to the geometry pass.
     */
    static void validateTerrainWithSsrInputWrittenByPass() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
                [targets.ssr]
                format = "rgba16f"
                scale = 1.0

                [[pass]]
                name = "terrain_opaque"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain"
                inputs = ["ssr"]

                [[pass]]
                name = "ssr_trace"
                type = "fullscreen"
                shader = "shaders/post/ssr_trace.fsh"
                inputs = ["builtin.depth"]
                outputs = ["ssr"]
                """), "graph.toml");
        GraphValidator.validate(g, Map.of(), 1920, 1080);
    }

    /** {@code ghostTarget} is declared but no pass in the graph ever writes it -- never final. */
    static void validateTerrainWithNeverWrittenTargetInput() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
                [targets.ghostTarget]
                format = "rgba8"
                scale = 1.0

                [[pass]]
                name = "terrain_opaque"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain"
                inputs = ["ghostTarget"]
                """), "graph.toml");
        GraphValidator.validate(g, Map.of(), 1920, 1080);
    }

    /** {@code ssrRaw} is gated on {@code SSR_QUALITY == 2}; the geometry pass is ungated -- the
     * generic {@code checkGateConsistency} rule must refuse this exactly like it already does for a
     * fullscreen pass's inputs. */
    static void validateTerrainWithDisabledTargetInput() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
                [targets.ssrRaw]
                format = "rgba16f"
                scale = 0.5
                enabled_if = "SSR_QUALITY == 2"

                [[pass]]
                name = "terrain_opaque"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain"
                inputs = ["ssrRaw"]
                """), "graph.toml");
        GraphValidator.validate(g, ssrOptions(), 1920, 1080);
    }

    /** A fullscreen pass declaring {@code builtin.depth_opaque} as an input -- runs before the
     * finish-opaque capture, so it would read last frame's stale copy; must be refused. */
    static void validateFullscreenPassWithDepthOpaqueInput() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
                [[pass]]
                name = "resolve"
                type = "fullscreen"
                shader = "shaders/post/resolve.fsh"
                inputs = ["builtin.depth_opaque"]
                outputs = ["builtin.output"]
                """), "graph.toml");
        GraphValidator.validate(g, Map.of(), 1920, 1080);
    }

    /** Nine declared geometry inputs -- one more than {@code GeometryInputs.RESERVED} (8) -- with no
     * per-input finality problem, so the failure can only be the overflow rule itself. */
    static void validateTerrainWithTooManyInputs() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
                [[pass]]
                name = "terrain_opaque"
                type = "geometry"
                slot = "terrain"
                program = "shaders/terrain"
                inputs = ["builtin.depth_opaque", "builtin.noise", "builtin.gAlbedo", "builtin.gNormal", "builtin.gMaterial", "builtin.gAo", "builtin.waterNormal", "builtin.waterDepth", "builtin.celestials"]
                """), "graph.toml");
        GraphValidator.validate(g, Map.of(), 1920, 1080);
    }

    private static String quoteList(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(values[i]).append('"');
        }
        return sb.toString();
    }

    private static Map<String, PackOption> ssrOptions() {
        Map<String, String> shaderSrc = new LinkedHashMap<>();
        shaderSrc.put("shaders/post/opts.fsh", """
                #define SSR_QUALITY 1 //[0 1 2] compile "Reflections" {0="Off" 1="Fancy" 2="Fast"}
                """);
        return OptionScanner.scan(shaderSrc);
    }
}
