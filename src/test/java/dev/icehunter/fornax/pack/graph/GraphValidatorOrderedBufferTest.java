package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import dev.icehunter.fornax.pack.option.OptionScanner;
import dev.icehunter.fornax.pack.option.PackOption;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Passes consume the earlier version of a scratch buffer, not every later overwrite. */
class GraphValidatorOrderedBufferTest {
    private static final String BUFFERS = """
            [targets.requests]
            kind = "buffer"
            stride_bytes = 32
            count = 16
            [targets.hits]
            kind = "buffer"
            stride_bytes = 36
            count = 16
            [targets.state]
            kind = "buffer"
            stride_bytes = 32
            count = 16
            """;

    private static String compute(String name, String inputs, String outputs, String gate) {
        return """
                [[pass]]
                name = "%s"
                type = "compute"
                shader = "compute/step.comp"
                inputs = [%s]
                outputs = [%s]
                dispatch = [1, 1, 1]
                %s
                """.formatted(name, inputs, outputs, gate);
    }

    private static String trace(String name) {
        return """
                [[pass]]
                name = "%s"
                type = "ray_query"
                inputs = ["requests"]
                outputs = ["hits"]
                [pass.ray_query]
                kind = "closest_hit"
                rays = 16
                """.formatted(name);
    }

    private static GraphSpec load(String toml) {
        return PackTomlLoader.loadGraph(new StringReader(toml), "graph.toml");
    }

    private static void validate(String passes) {
        GraphValidator.validate(load(BUFFERS + passes), Map.of(), 16, 16);
    }

    private static Map<String, PackOption> options() {
        return OptionScanner.scan(Map.of("compute/options.comp", """
                #define QUALITY 1 //[0 1 2] compile "Quality"
                """));
    }

    @Test
    void twoNativeTracesWithComputeAdvancementReuseActiveScratch() {
        assertDoesNotThrow(() -> validate(
                compute("seed", "", "\"requests\", \"state\"", "")
                + trace("trace_first")
                + compute("advance", "\"requests\", \"hits\", \"state\"",
                        "\"requests\", \"state\"", "")
                + trace("trace_second")
                + compute("finish", "\"requests\", \"hits\", \"state\"",
                        "\"requests\", \"hits\", \"state\"", "")));
    }

    @Test
    void orderedPingPongConsumesOnlyThePreviousWriter() {
        assertDoesNotThrow(() -> validate(
                compute("seed", "", "\"state\"", "")
                + compute("first", "\"state\"", "\"requests\"", "")
                + compute("second", "\"requests\"", "\"state\"", "")
                + compute("third", "\"state\"", "\"requests\"", "")));
    }

    @Test
    void aBufferCannotInitializeItselfByDeclaringAnInputAndOutput() throws Exception {
        GraphSpec graph = load(Files.readString(Path.of(
                "src/test/resources/packs/uninitialized_buffer_update/graph.toml")));
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(graph, Map.of(), 16, 16));
        assertTrue(error.getMessage().contains("state"), error.getMessage());
    }

    @Test
    void aLaterWriterDoesNotInitializeAnEarlierInPlaceUpdate() {
        assertThrows(FornaxPackError.class, () -> validate(
                compute("uninitialized", "\"state\"", "\"state\"", "")
                + compute("too_late", "", "\"state\"", "")));
    }

    @Test
    void anImageStillCannotBeSampledAndStoredByTheSamePassAfterAnEarlierWriter() {
        String targets = """
                [targets.image]
                format = "rgba16f"
                scale = 1.0
                storage = true
                """;
        FornaxPackError error = assertThrows(FornaxPackError.class, () -> GraphValidator.validate(
                load(targets + compute("seed", "", "\"image\"", "")
                        + compute("feedback", "\"image\"", "\"image\"", "")),
                Map.of(), 16, 16));
        assertTrue(error.reason().contains("same frame"), error.getMessage());
    }

    @Test
    void futureOnlyProducerEdgesStillRejectATrueCycle() {
        FornaxPackError error = assertThrows(FornaxPackError.class, () -> validate(
                compute("first", "\"state\"", "\"requests\"", "")
                + compute("second", "\"requests\"", "\"state\"", "")));
        assertTrue(error.reason().contains("cycle"), error.getMessage());
    }

    @Test
    void aDisabledSeedCannotHideAFutureOnlyProducerCycle() {
        FornaxPackError error = assertThrows(FornaxPackError.class, () -> GraphValidator.validate(load(BUFFERS
                + compute("optional_seed", "", "\"state\"", "enabled_if = \"QUALITY == 1\"")
                + compute("first", "\"state\"", "\"requests\"", "")
                + compute("second", "\"requests\"", "\"state\"", "")), options(), 16, 16));
        assertTrue(error.reason().contains("cycle"), error.getMessage());
    }

    @Test
    void matchingCompileGatesAllowAnInitializedUpdate() {
        String gate = "enabled_if = \"QUALITY > 0\"";
        assertDoesNotThrow(() -> GraphValidator.validate(load(BUFFERS
                + compute("seed", "", "\"state\"", gate)
                + compute("advance", "\"state\"", "\"state\"", gate)), options(), 16, 16));
    }

    @Test
    void aGatedWriterCannotInitializeAnUngatedUpdate() {
        assertThrows(FornaxPackError.class, () -> GraphValidator.validate(load(BUFFERS
                + compute("seed", "", "\"state\"", "enabled_if = \"QUALITY == 1\"")
                + compute("advance", "\"state\"", "\"state\"", "")), options(), 16, 16));
    }

    @Test
    void anEarlierUnconditionalWriterCoversADisabledNearestWriter() {
        assertDoesNotThrow(() -> GraphValidator.validate(load(BUFFERS
                + compute("seed", "", "\"state\"", "")
                + compute("optional_refresh", "", "\"state\"", "enabled_if = \"QUALITY == 1\"")
                + compute("advance", "\"state\"", "\"state\"", "")), options(), 16, 16));
    }

    @Test
    void complementaryCompileWritersCoverEveryEnabledUpdate() {
        assertDoesNotThrow(() -> GraphValidator.validate(load(BUFFERS
                + compute("seed_low", "", "\"state\"", "enabled_if = \"QUALITY == 0\"")
                + compute("seed_high", "", "\"state\"", "enabled_if = \"QUALITY > 0\"")
                + compute("advance", "\"state\"", "\"state\"", "")), options(), 16, 16));
    }

    @Test
    void aPreOpaqueUpdateCannotUseAnEarlierDeclaredFinishPhaseWriter() {
        assertThrows(FornaxPackError.class, () -> validate(
                compute("seed", "", "\"state\"", "")
                + compute("light_inject", "\"state\"", "\"state\"", "")));
    }

    @Test
    void aRuntimeGatedWriterDoesNotProveAnUngatedUpdateInitialized() {
        assertThrows(FornaxPackError.class, () -> validate(
                compute("seed", "", "\"state\"", "runtime_enabled_if = \"raining\"")
                + compute("advance", "\"state\"", "\"state\"", "")));
    }
}
