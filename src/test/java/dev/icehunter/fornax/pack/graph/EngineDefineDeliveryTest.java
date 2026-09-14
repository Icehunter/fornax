package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.config.AaMethod;
import dev.icehunter.fornax.pack.BlocksSpec;
import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackModel;
import dev.icehunter.fornax.pack.ParticleSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import dev.icehunter.fornax.pack.RawShaderImports;
import dev.icehunter.fornax.pack.layout.PackOptionsLayout;
import dev.icehunter.fornax.pack.option.OptionScanner;
import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises rebuild's production source preparation and the raw-import/shaderc path used by compute
 * runners. No GPU is created. The fixture requires an engine fact without declaring it itself, so
 * missing delivery cannot silently compile as a false preprocessor expression.
 */
class EngineDefineDeliveryTest {
    private static final String COMPUTE = "shaders/compute/facts.comp";
    private static final String INCLUDE = "shaders/include/temporal_fact.glsl";
    private static final String COMPUTE_SOURCE = """
            #version 450
            #moj_import <fornax_runtime:temporal_fact.glsl>
            layout(local_size_x = 1) in;
            layout(set = 0, binding = 0, std430) buffer Result { uint value; };
            void main() { value = uint(FX_TAA); }
            """;

    @ParameterizedTest
    @CsvSource({"OFF,0", "SSAA,0", "TAA,1", "TAAU,1", "METALFX,1"})
    void computeReceivesTemporalFactsWithoutRuntimeOptions(AaMethod method, int expected) {
        Map<String, String> sources = computeSources(expected);
        Map<String, String> prepared = prepare(
                List.of(pass(PassType.COMPUTE, COMPUTE, List.of())), sources, method, Map.of());
        assertCompiles(prepared);
        assertFact(prepared.get(COMPUTE), "FX_TAA", expected);
        assertFalse(prepared.get(COMPUTE).contains("uniform u_PackOptions"));
        assertEquals(sources.get(INCLUDE), prepared.get(INCLUDE), "includes receive facts from their entrypoint");
    }

    @Test
    void computeFactsAreIndependentOfAnUnboundRuntimeOption() {
        Map<String, String> sources = computeSources(1);
        sources.put("shaders/options.glsl", "#define GAIN 1.0 //[0.0 1.0] runtime \"Gain\"\n");
        Map<String, String> prepared = prepare(
                List.of(pass(PassType.COMPUTE, COMPUTE, List.of())), sources, AaMethod.TAA, Map.of());
        assertCompiles(prepared);
        assertFalse(prepared.get(COMPUTE).contains("uniform u_PackOptions"));
    }

    @Test
    void computeKeepsPositionalRuntimeBindingAndSourceRewriteOrder() {
        Map<String, String> sources = computeSources(1);
        sources.put(COMPUTE, COMPUTE_SOURCE.replace("#moj_import", """
                #define GAIN 1.0 //[0.0 1.0] runtime "Gain"
                #define PACK_SWITCH 0 //[0 1] compile "Pack switch"
                #moj_import"""));
        Map<String, String> prepared = prepare(
                List.of(pass(PassType.COMPUTE, COMPUTE, List.of("result", "packOptions"))),
                sources, AaMethod.METALFX, Map.of("PACK_SWITCH", "1"));
        assertCompiles(prepared);
        String source = prepared.get(COMPUTE);
        assertTrue(source.contains("layout(std140, set = 0, binding = 1) uniform u_PackOptions"));
        assertTrue(source.contains("#define PACK_SWITCH 1 "));
        assertFalse(source.contains("#define GAIN"));
        assertTrue(source.indexOf("#define FX_TAA") < source.indexOf("uniform u_PackOptions"));
        assertTrue(source.indexOf("uniform u_PackOptions") < source.indexOf("#moj_import"));
    }

    @Test
    void sharedFullscreenAndComputePathsReceiveThePreambleOnlyOnce() {
        Map<String, String> sources = computeSources(1);
        Map<String, String> prepared = prepare(List.of(
                pass(PassType.FULLSCREEN, COMPUTE, List.of()),
                pass(PassType.COMPUTE, COMPUTE, List.of()),
                pass(PassType.COMPUTE, COMPUTE, List.of())), sources, AaMethod.TAA, Map.of());
        assertCompiles(prepared);
        assertFact(prepared.get(COMPUTE), "FX_TAA", 1);
        assertFact(prepared.get(COMPUTE), "FX_COMPUTE", 1);
    }

    @Test
    void fullscreenFactsRemainEngineOwnedAndFollowVersion() {
        String path = "shaders/post/facts.fsh";
        Map<String, String> prepared = prepare(
                List.of(pass(PassType.FULLSCREEN, path, List.of())),
                Map.of(path, "#version 330\nvoid main() {}\n"), AaMethod.OFF,
                Map.of("FX_TAA", "7"));
        assertFact(prepared.get(path), "FX_TAA", 0);
        assertTrue(prepared.get(path).startsWith("#version 330\n"));
    }

    @Test
    void geometryParticleAndMipShadersKeepTheirSourceWithoutRuntimeOptions() {
        String geometry = "shaders/blocks/geometry";
        String particle = "shaders/particles/facts.fsh";
        String particleVertex = "shaders/particles/facts.vsh";
        String mip = "shaders/post/mip.fsh";
        Map<String, String> sources = new LinkedHashMap<>();
        for (String path : List.of(geometry + ".vsh", geometry + ".fsh", particle, particleVertex, mip)) {
            sources.put(path, "#version 450\nvoid main() {}\n");
        }
        List<PassSpec> passes = List.of(
                new PassSpec("geometry", PassType.GEOMETRY, GeometrySlot.DEFAULT, geometry, null,
                        List.of(), List.of(), null, null, List.of(), null, null, null),
                new PassSpec("particles", PassType.PARTICLES, null, null, particle,
                        List.of(), List.of(), null, null, List.of(), null, null,
                        new ParticleSpec(particleVertex, 1)),
                pass(PassType.MIPCHAIN, mip, List.of()));
        assertEquals(sources, prepare(passes, sources, AaMethod.METALFX, Map.of()));
    }

    private static Map<String, String> computeSources(int expected) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(COMPUTE, COMPUTE_SOURCE);
        sources.put(INCLUDE, """
                #ifndef FX_TAA
                #error engine temporal fact is missing
                #endif
                #if FX_TAA != %d
                #error engine temporal fact disagrees with the selected AA method
                #endif
                """.formatted(expected));
        return sources;
    }

    private static Map<String, String> prepare(List<PassSpec> passes, Map<String, String> sources,
            AaMethod method, Map<String, String> compileValues) {
        var options = OptionScanner.scan(sources);
        var layout = PackOptionsLayout.build(List.copyOf(options.values()));
        var pack = new PackModel(Path.of("."), null, new GraphSpec(Map.of(), passes),
                null, options, BlocksSpec.empty());
        return GraphRunner.prepareShaderSources(pack, sources, compileValues, layout, method, true);
    }

    private static PassSpec pass(PassType type, String shader, List<String> inputs) {
        return new PassSpec(type.name(), type, null, null, shader, inputs, List.of(),
                null, null, List.of(1, 1, 1), null, null, null);
    }

    private static void assertCompiles(Map<String, String> prepared) {
        String source = RawShaderImports.expand(prepared.get(COMPUTE), prepared, COMPUTE);
        ByteBuffer spirv = assertDoesNotThrow(() -> ComputeShaderCompiler.compileToSpirv(source, COMPUTE));
        try {
            assertTrue(spirv.hasRemaining());
            assertTrue(source.startsWith("#version 450\n"));
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private static void assertFact(String source, String key, int value) {
        List<String> definitions = source.lines().filter(line -> line.startsWith("#define " + key + " ")).toList();
        assertEquals(List.of("#define " + key + " " + value), definitions,
                "the engine fact must appear exactly once with its authoritative value");
    }
}
