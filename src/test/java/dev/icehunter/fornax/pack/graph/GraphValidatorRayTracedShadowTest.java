package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import dev.icehunter.fornax.pack.option.OptionScanner;
import dev.icehunter.fornax.pack.option.PackOption;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphValidatorRayTracedShadowTest {
    private static final String TOGGLE = "#define TRACE_ENABLED 1 //[0 1] compile \"Tracing\"\n";
    private static final String RADIUS = "#define u_TraceRadius 64.0 //[0.0..128.0 step 1.0] runtime \"Radius\"\n";

    @Test
    void acceptsCompileExpressionsWithNumericRuntimeRadius() {
        assertDoesNotThrow(() -> validate(graph("TRACE_ENABLED && FX_COMPUTE"),
                options(TOGGLE + RADIUS)));
    }

    @Test
    void acceptsNumericRuntimeEnums() {
        assertDoesNotThrow(() -> validate(graph("TRACE_ENABLED"),
                options(TOGGLE + "#define u_TraceRadius 64 //[0 64 128] runtime \"Radius\"\n")));
    }

    @Test
    void declarationAbsenceDoesNotRequireAnyOptions() {
        assertDoesNotThrow(() -> validate(PackTomlLoader.loadGraph(new StringReader(""), "graph.toml"), Map.of()));
    }

    @Test
    void rejectsUnknownAndRuntimeGateNamesEvenWhenTheGateIsOff() {
        for (String expression : new String[] {"MISSING", "u_TraceRadius", "0 && MISSING", "TRACE_ENABLED &&"}) {
            FornaxPackError error = assertThrows(FornaxPackError.class,
                    () -> validate(graph(expression), options(TOGGLE + RADIUS)));
            assertEquals("ray_traced_shadows.enabled_if", error.key());
        }
    }

    @Test
    void rejectsUnknownRadiusOption() {
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> validate(graph("TRACE_ENABLED"), options(TOGGLE)));
        assertEquals("ray_traced_shadows.distance_option", error.key());
    }

    @Test
    void rejectsCompileBooleanAndNonnumericRadiusOptions() {
        for (String declaration : new String[] {
                "#define u_TraceRadius 64 //[0 64 128] compile \"Radius\"",
                "#define u_TraceRadius //[] runtime \"Radius\"",
                "#define u_TraceRadius NEAR //[NEAR FAR] runtime \"Radius\"",
                "#define u_TraceRadius NaN //[NaN 1.0] runtime \"Radius\"",
                "#define u_TraceRadius Infinity //[1.0 Infinity] runtime \"Radius\"",
                "#define u_TraceRadius BAD //[0.0..128.0 step 1.0] runtime \"Radius\""
        }) {
            FornaxPackError error = assertThrows(FornaxPackError.class,
                    () -> validate(graph("TRACE_ENABLED"), options(TOGGLE + declaration + "\n")));
            assertEquals("ray_traced_shadows.distance_option", error.key());
        }
    }

    @Test
    void brokenPackFixtureRejectsACompileRadiusAtLoad() {
        var stream = getClass().getResourceAsStream("/packs/ray_traced_shadow_compile_radius/graph.toml");
        assertNotNull(stream);
        GraphSpec graph = PackTomlLoader.loadGraph(
                new InputStreamReader(stream, StandardCharsets.UTF_8), "graph.toml");
        FornaxPackError error = assertThrows(FornaxPackError.class, () -> validate(graph,
                options(TOGGLE + "#define u_TraceRadius 64 //[0 64 128] compile \"Radius\"\n")));
        assertEquals("ray_traced_shadows.distance_option", error.key());
    }

    private static GraphSpec graph(String gate) {
        return PackTomlLoader.loadGraph(new StringReader("""
                [ray_traced_shadows]
                enabled_if = "%s"
                distance_option = "u_TraceRadius"
                """.formatted(gate)), "graph.toml");
    }

    private static Map<String, PackOption> options(String source) {
        return OptionScanner.scan(Map.of("shaders/options.glsl", source));
    }

    private static void validate(GraphSpec graph, Map<String, PackOption> options) {
        GraphValidator.validate(graph, options, 1280, 720);
    }
}
