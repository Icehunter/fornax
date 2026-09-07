package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import dev.icehunter.fornax.pack.option.OptionScanner;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeReuseDeclarationTest {
    private static final String GRAPH = """
            [targets.tableA]
            format = "rgba16f"
            width = 16
            height = 16
            storage = true
            [targets.tableB]
            format = "rgba16f"
            width = 16
            height = 16
            storage = true
            [[pass]]
            name = "bakeA"
            type = "compute"
            shader = "a.comp"
            dispatch = [1, 1, 1]
            inputs = ["globals", "packOptions"]
            outputs = ["tableA"]
            reuse_when_unchanged = { runtime = ["u_TestDensity"], globals = ["u_SkyState.x", "u_FrameState.z"] }
            [[pass]]
            name = "bakeB"
            type = "compute"
            shader = "b.comp"
            dispatch = [1, 1, 1]
            inputs = ["globals", "packOptions", "tableA"]
            outputs = ["tableB"]
            reuse_when_unchanged = { runtime = ["u_TestDensity"], globals = ["u_SkyState.x"] }
            """;

    private static GraphSpec validate(String text) {
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader(text), "graph.toml");
        GraphValidator.validate(graph, OptionScanner.scan(Map.of("options.glsl",
                "#define u_TestDensity 1.0 //[0.0..2.0 step 0.1] runtime \"Density\"\n")), 64, 64);
        return graph;
    }

    @Test void reusableComputeChainLoadsWithSelectedRuntimeAndGlobalLanes() {
        assertDoesNotThrow(() -> validate(GRAPH));
    }

    @Test void explicitlySelectedPushLanesAreAccepted() {
        assertDoesNotThrow(() -> validate(GRAPH.replace("runtime = [", "push = [\"u_SunDirection.x\"], runtime = [")));
    }

    @Test void unknownPushLaneFailsAtLoad() {
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> validate(GRAPH.replace("runtime = [", "push = [\"u_Unknown\"], runtime = [")));
        assertTrue(error.getMessage().contains("u_Unknown"));
    }

    @Test void absentDeclarationKeepsOrdinaryComputePassesLegal() {
        assertDoesNotThrow(() -> validate(GRAPH.replaceAll("(?m)^reuse_when_unchanged.*\\R", "")));
    }

    @Test void unknownRuntimeKeyNamesTheMissingOption() {
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> validate(GRAPH.replace("u_TestDensity", "u_Missing")));
        assertTrue(error.getMessage().contains("u_Missing"));
    }

    @Test void unknownGlobalLaneFailsAtLoad() {
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> validate(GRAPH.replace("u_SkyState.x", "u_Unknown.x")));
        assertTrue(error.getMessage().contains("u_Unknown.x"));
    }

    @Test void historyOutputsCannotBeReusedAcrossSwaps() {
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> validate(GRAPH.replace("storage = true", "storage = true\nhistory = true")));
        assertTrue(error.getMessage().contains("history"));
    }

    @Test void mutableBuiltinInputsCannotClaimScalarOnlyDependencies() {
        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> validate(GRAPH.replace("inputs = [\"globals\", \"packOptions\"]",
                        "inputs = [\"globals\", \"packOptions\", \"builtin.depth\"]")));
        assertTrue(error.getMessage().contains("builtin.depth"));
    }

    @Test void duplicateKeysAndMissingUniformBindingsFailAtLoad() {
        assertTrue(assertThrows(FornaxPackError.class,
                () -> validate(GRAPH.replace("runtime = [\"u_TestDensity\"]",
                        "runtime = [\"u_TestDensity\", \"u_TestDensity\"]")))
                .getMessage().contains("duplicate"));
        assertTrue(assertThrows(FornaxPackError.class,
                () -> validate(GRAPH.replace("\"globals\", \"packOptions\"", "\"globals\"")))
                .getMessage().contains("packOptions"));
        assertTrue(assertThrows(FornaxPackError.class,
                () -> validate(GRAPH.replace("\"globals\", \"packOptions\"", "\"packOptions\"")))
                .getMessage().contains("globals"));
    }

    @Test void competingWritersAndInPlaceInputsCannotReuseImages() {
        String writer = """
                [[pass]]
                name = "overwrite"
                type = "copy"
                inputs = ["builtin.depth"]
                outputs = ["tableA"]
                """;
        assertTrue(assertThrows(FornaxPackError.class, () -> validate(GRAPH + writer))
                .getMessage().contains("another writer"));
        assertTrue(assertThrows(FornaxPackError.class,
                () -> validate(GRAPH.replace("inputs = [\"globals\", \"packOptions\"]",
                        "inputs = [\"globals\", \"packOptions\", \"tableA\"]")))
                .getMessage().contains("in-place"));
    }

    @Test void preOpaqueAndRuntimeGatedPassesAreOutsideTheReuseContract() {
        assertTrue(assertThrows(FornaxPackError.class, () -> validate(GRAPH.replace("bakeA", "light_inject")))
                .getMessage().contains("ordinary compute"));
        assertTrue(assertThrows(FornaxPackError.class,
                () -> validate(GRAPH.replace("name = \"bakeA\"", "name = \"bakeA\"\nruntime_enabled_if = \"dimension != 3\"")))
                .getMessage().contains("runtime_enabled_if"));
    }

    @Test void aGatedProducerCannotLeaveAnUngatedConsumerReadingAnOldResult() {
        assertTrue(assertThrows(FornaxPackError.class,
                () -> validate(GRAPH.replace("name = \"bakeA\"", "name = \"bakeA\"\nenabled_if = \"FX_COMPUTE\"")))
                .getMessage().contains("identical enabled_if"));
    }

    @Test void anOrdinaryProducerCannotSupplyAReusableInput() {
        String text = GRAPH.replaceFirst("(?m)^reuse_when_unchanged.*\\R", "");
        FornaxPackError error = assertThrows(FornaxPackError.class, () -> validate(text));
        assertTrue(error.getMessage().contains("tableA"));
    }
}
