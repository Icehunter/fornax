package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.graph.GraphValidator;
import dev.icehunter.fornax.pack.graph.TargetPlan;
import dev.icehunter.fornax.pack.option.OptionScanner;
import dev.icehunter.fornax.pack.option.PackOption;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphNumericExpressionsTest {
    private static final String OPTIONS = """
            #define GRID 512 //[256 512 768 1024] compile "Grid"
            #define SAMPLES 1 //[1 2 4] compile "Samples"
            #define LOCAL_GRID 512 //[256 512 768 1024] compile "Local Grid"
            """;
    private static final String GRAPH = """
            [targets.grid]
            format = "rgba16f"
            width = "GRID"
            height = "GRID"
            [targets.cache]
            format = "rgba16f"
            width = "GRID"
            height = "GRID * 4"
            [targets.local]
            format = "rgba16f"
            width = "LOCAL_GRID"
            height = "LOCAL_GRID"
            [targets.requests]
            kind = "buffer"
            stride_bytes = 32
            count = "GRID * GRID * SAMPLES"
            [targets.hits]
            kind = "buffer"
            stride_bytes = 36
            count = "GRID * GRID * SAMPLES"
            [[pass]]
            name = "seed"
            type = "compute"
            shader = "shaders/seed.comp"
            outputs = ["requests"]
            dispatch = ["GRID * GRID * SAMPLES / 256", 1, 1]
            [[pass]]
            name = "trace"
            type = "ray_query"
            inputs = ["requests"]
            outputs = ["hits"]
            [pass.ray_query]
            kind = "closest_hit"
            rays = "GRID * GRID * SAMPLES"
            """;

    private static GraphSpec load(String text) { return PackTomlLoader.loadGraph(new StringReader(text), "graph.toml"); }
    private static Map<String, PackOption> options(String text) {
        var sources = new LinkedHashMap<String, String>(); sources.put("shaders/options.glsl", text);
        return OptionScanner.scan(sources);
    }
    private static Map<String, Integer> selected(int grid, int samples) {
        var values = new LinkedHashMap<String, Integer>(); values.put("GRID", grid); values.put("SAMPLES", samples);
        return values;
    }
    private static GraphSpec resolved(String graph) {
        return GraphNumericExpressions.resolve(load(graph), options(OPTIONS), Map.of());
    }

    @Test void fixedDimensionsCanReferenceCompileOptionsBeforeTheirValuesAreResolved() {
        assertDoesNotThrow(() -> load(GRAPH));
    }

    @Test void defaultsResolveEveryDestinationAndPreserveDeclarationOrderAndFormulas() {
        GraphSpec graph = resolved(GRAPH);
        assertEquals(List.of("grid", "cache", "local", "requests", "hits"), new ArrayList<>(graph.targets().keySet()));
        assertEquals(512, graph.targets().get("grid").fixedSize().width());
        assertEquals(2048, graph.targets().get("cache").fixedSize().height()); // Four history surfaces per grid cell.
        assertEquals(262144, graph.targets().get("requests").bufferSize().count()); // 512 squared, one sample.
        assertEquals(262144, graph.passes().get(1).rayQuery().rayCount());
        assertEquals(List.of(1024, 1, 1), graph.passes().get(0).dispatch()); // 262144 threads / 256 per group.
        assertEquals("GRID * GRID * SAMPLES", graph.numericExpressions().get("targets.requests.count"));
        assertDoesNotThrow(() -> GraphValidator.validate(graph, options(OPTIONS), 1920, 1080));
    }

    @Test void selectedValuesResolveBeforePlanningAndAnAlreadyResolvedGraphCanChangeAgain() {
        GraphSpec original = resolved(GRAPH);
        GraphSpec high = GraphNumericExpressions.resolveValidated(original, options(OPTIONS), selected(1024, 4));
        assertEquals(4194304, high.passes().get(1).rayQuery().rayCount()); // 1024 squared times four samples.
        assertEquals(16384, high.passes().get(0).dispatch().get(0));
        assertEquals(134217728L, high.targets().get("requests").bufferSize().sizeBytes());
        assertDoesNotThrow(() -> TargetPlan.compute(high, selected(1024, 4), 1919, 1079));
        GraphSpec low = GraphNumericExpressions.resolveValidated(high, options(OPTIONS), selected(256, 2));
        assertEquals(131072, low.passes().get(1).rayQuery().rayCount());
        assertEquals(256, low.targets().get("grid").fixedSize().width());
        assertEquals(512, low.targets().get("local").fixedSize().width()); // Independent budget unchanged.
        assertEquals(512, original.targets().get("grid").fixedSize().width()); // No shared mutable graph.
    }

    @Test void allIndependentGridAndSampleCombinationsAreValidated() {
        var variants = new ArrayList<GraphSpec>();
        GraphNumericExpressions.forEachVariant(load(GRAPH), options(OPTIONS), variants::add);
        assertEquals(48, variants.size()); // Four grids, three sample counts, four independent local grids.
        for (GraphSpec graph : variants) {
            assertTrue(graph.numericExpressions().isEmpty());
            assertEquals(graph.targets().get("requests").bufferSize().count(), graph.passes().get(1).rayQuery().rayCount());
            assertEquals(graph.passes().get(0).dispatch().get(0) * 256, graph.passes().get(1).rayQuery().rayCount());
        }
    }

    @Test void parenthesesAndOperatorPrecedenceUseExactIntegerArithmetic() {
        GraphSpec graph = resolved(GRAPH.replace("height = \"GRID * 4\"", "height = \"(GRID + 256) * 4 / 2\""));
        assertEquals(1536, graph.targets().get("cache").fixedSize().height());
    }

    @Test void malformedExpressionsFailDuringParsingWithTheirDestination() {
        for (String value : List.of("", "GRID - 1", "ceil(GRID)", "GRID ** 2", "GRID /", "(GRID", "1.5", "9223372036854775808")) {
            FornaxPackError error = assertThrows(FornaxPackError.class, () -> load(GRAPH.replace("width = \"GRID\"", "width = \"" + value + "\"")));
            assertTrue(error.getMessage().contains("targets.grid.width"), error.getMessage());
        }
    }

    @Test void unknownRuntimeAndInvalidSelectedOptionsFailLoudly() {
        FornaxPackError unknown = assertThrows(FornaxPackError.class,
                () -> GraphNumericExpressions.resolve(load(GRAPH), options(OPTIONS.replace("GRID 512", "OTHER 512")), Map.of()));
        assertTrue(unknown.getMessage().contains("unknown sizing option"));
        FornaxPackError runtime = assertThrows(FornaxPackError.class,
                () -> GraphNumericExpressions.resolve(load(GRAPH), options(OPTIONS.replace("compile \"Grid\"", "runtime \"Grid\"")), Map.of()));
        assertTrue(runtime.getMessage().contains("compile-time"));
        assertThrows(FornaxPackError.class,
                () -> GraphNumericExpressions.resolve(load(GRAPH), options(OPTIONS), selected(333, 1)));
    }

    @Test void zeroInexactDivisionAndOverflowAreRejectedInsteadOfRoundedOrWrapped() {
        for (String value : List.of("0", "GRID / 3", "GRID / 0", "2147483648", "9223372036854775807 * GRID")) {
            FornaxPackError error = assertThrows(FornaxPackError.class,
                    () -> resolved(GRAPH.replace("width = \"GRID\"", "width = \"" + value + "\"")));
            assertTrue(error.getMessage().contains("targets.grid.width"), error.getMessage());
        }
    }

    @Test void aBadNondefaultDomainValueFailsDiscoveryValidation() {
        var opts = options(OPTIONS.replace("[256 512 768 1024] compile \"Grid\"", "[512 513] compile \"Grid\""));
        GraphSpec graph = GraphNumericExpressions.resolve(load(GRAPH), opts, Map.of());
        FornaxPackError error = assertThrows(FornaxPackError.class, () -> GraphValidator.validate(graph, opts, 1920, 1080));
        assertTrue(error.getMessage().contains("pass.seed.dispatch.0"), error.getMessage());
    }

    @Test void aBufferThatOnlyFitsTheDefaultSampleCountFailsBeforeAnyTierIsApplied() {
        String smaller = GRAPH.replace("count = \"GRID * GRID * SAMPLES\"", "count = \"GRID * GRID\"");
        assertThrows(FornaxPackError.class, () -> GraphValidator.validate(load(smaller), options(OPTIONS), 1920, 1080));
    }

    @Test void resolvedCountsKeepTheExistingRayAndAllocationCeilings() {
        assertThrows(FornaxPackError.class, () -> resolved(GRAPH.replace("count = \"GRID * GRID * SAMPLES\"", "count = \"1073741824\"")));
        assertThrows(FornaxPackError.class, () -> resolved(GRAPH.replace("rays = \"GRID * GRID * SAMPLES\"", "rays = \"16777217\"")));
    }

    @Test void literalAndRenderSizedGraphsKeepTheirPreviousRepresentation() {
        GraphSpec literal = load("""
                [targets.image]
                format = "rgba16f"
                width = 512
                height = 512
                [targets.data]
                kind = "buffer"
                stride_bytes = 32
                count = "render"
                """);
        assertTrue(literal.numericExpressions().isEmpty());
        assertTrue(literal.targets().get("data").bufferSize().perRenderPixel());
        assertSame(literal, GraphNumericExpressions.resolve(literal, Map.of(), Map.of()));
    }

    @Test void discoveryReturnsDefaultResolvedGraphWhileRetainingReloadProvenance(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pack.toml"), """
                [pack]
                name = "numeric budgets"
                version = "1"
                authors = ["fixture"]
                license = "MIT"
                format = 1
                """);
        Files.writeString(root.resolve("graph.toml"), GRAPH);
        Files.writeString(root.resolve("screens.toml"), "");
        Files.createDirectories(root.resolve("shaders"));
        Files.writeString(root.resolve("shaders/options.glsl"), OPTIONS);
        PackModel model = PackDiscovery.loadFrom(root, 1920, 1080);
        assertEquals(512, model.graph().targets().get("grid").fixedSize().width());
        assertEquals(262144, model.graph().passes().get(1).rayQuery().rayCount());
        assertTrue(!model.graph().numericExpressions().isEmpty());
    }

    @Test void brokenRuntimeSizingFixtureIsRefused() throws Exception {
        Path root = Path.of("src/test/resources/packs/runtime_in_numeric_size");
        FornaxPackError error = assertThrows(FornaxPackError.class, () -> PackDiscovery.loadFrom(root, 1920, 1080));
        assertTrue(error.getMessage().contains("compile-time"), error.getMessage());
    }

    /** Rebuild owns GPU state; this source contract checks refusal precedes resource destruction. */
    @Test void selectedBudgetValidationPrecedesLiveGraphTeardown() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java"));
        int begin = source.indexOf("public static CompletableFuture<Void> rebuild(");
        int resolve = source.indexOf("GraphNumericExpressions.resolveValidated(", begin);
        int close = source.indexOf("closeCurrent();", begin);
        assertTrue(resolve > begin && resolve < close);
        assertTrue(source.substring(begin, close).contains("PackModel pack = unresolvedPack.withGraph("));
    }
}
