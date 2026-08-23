package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.ParticleSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import dev.icehunter.fornax.pack.TargetSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every case here guards something that fails SILENTLY or FATALLY rather than visibly: a mismatched
 * attachment format/extent is undefined behaviour under dynamic rendering (no validation error), a
 * missing shader stage throws inside runner build (which {@code ensureRunnersBuilt} swallows into a
 * retry loop that takes every other runner down with it), and a reserved input name accepted on the
 * wrong pass type shifts every subsequent {@code layout(binding = N)} the pack hardcoded.
 */
class GraphValidatorParticlesTest {
    private static final ParticleSpec PARTICLES = new ParticleSpec("shaders/particles/snow.vsh", 50_000);

    private static TargetSpec renderTarget(String name) {
        return new TargetSpec(name, "rgba16f", 1.0, false, null, TargetBasis.RENDER);
    }

    private static Map<String, TargetSpec> targets(TargetSpec... specs) {
        Map<String, TargetSpec> map = new LinkedHashMap<>();
        for (TargetSpec s : specs) {
            map.put(s.name(), s);
        }
        return map;
    }

    private static PassSpec particlesPass(List<String> inputs, List<String> outputs) {
        return new PassSpec("snow_draw", PassType.PARTICLES, null, null,
                "shaders/particles/snow.fsh", inputs, outputs,
                null, null, List.of(), null, null, PARTICLES);
    }

    private static void validate(GraphSpec graph) {
        GraphValidator.validate(graph, Map.of(), 1920, 1080);
    }

    @Test
    void particlesPassDrawingIntoAFullResRenderTargetValidates() {
        GraphSpec graph = new GraphSpec(targets(renderTarget("sceneColor")),
                List.of(particlesPass(List.of("globals"), List.of("sceneColor"))));
        assertDoesNotThrow(() -> validate(graph));
    }

    @Test
    void particlesPassWithoutAFragmentShaderIsRejected() {
        PassSpec pass = new PassSpec("snow_draw", PassType.PARTICLES, null, null,
                null, List.of(), List.of("sceneColor"),
                null, null, List.of(), null, null, PARTICLES);
        GraphSpec graph = new GraphSpec(targets(renderTarget("sceneColor")), List.of(pass));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void particlesPassWithTwoOutputsIsRejected() {
        GraphSpec graph = new GraphSpec(targets(renderTarget("sceneColor"), renderTarget("other")),
                List.of(particlesPass(List.of(), List.of("sceneColor", "other"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void particlesPassWritingBuiltinOutputIsRejected() {
        // builtin.output is the MAIN render target; under TAAU it is a different size from the
        // G-buffer depth this pass attaches, and a dynamic-rendering render area must fit inside
        // every attachment it names.
        GraphSpec graph = new GraphSpec(Map.of(), List.of(particlesPass(List.of(), List.of("builtin.output"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void particlesPassWritingAHalfResTargetIsRejected() {
        TargetSpec half = new TargetSpec("sceneColorHalf", "rgba16f", 0.5, false, null, TargetBasis.RENDER);
        GraphSpec graph = new GraphSpec(targets(half),
                List.of(particlesPass(List.of(), List.of("sceneColorHalf"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void particlesPassWritingAFixedExtentTargetIsRejected() {
        TargetSpec fixed = new TargetSpec("particleGrid", "rgba16f", 1.0, false, null,
                TargetBasis.RENDER, TargetKind.TEXTURE, TargetFilter.NEAREST, null,
                new TextureSize(512, 512));
        GraphSpec graph = new GraphSpec(targets(fixed),
                List.of(particlesPass(List.of(), List.of("particleGrid"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void particlesPassWritingAnOutputBasisTargetIsRejected() {
        TargetSpec outputBasis = new TargetSpec("native", "rgba16f", 1.0, false, null, TargetBasis.OUTPUT);
        GraphSpec graph = new GraphSpec(targets(outputBasis),
                List.of(particlesPass(List.of(), List.of("native"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void particlesPassWritingABufferTargetIsRejected() {
        GraphSpec graph = new GraphSpec(targets(TargetSpec.buffer("snowFlakes", null)),
                List.of(particlesPass(List.of(), List.of("snowFlakes"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void particlesPassSamplingItsOwnDepthAttachmentIsRejected() {
        GraphSpec graph = new GraphSpec(targets(renderTarget("sceneColor")),
                List.of(particlesPass(List.of("builtin.depth"), List.of("sceneColor"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void globalsIsAValidParticlesInputAndNothingElse() {
        GraphSpec ok = new GraphSpec(targets(renderTarget("sceneColor")),
                List.of(particlesPass(List.of("globals"), List.of("sceneColor"))));
        assertDoesNotThrow(() -> validate(ok));

        PassSpec fullscreen = new PassSpec("resolve", PassType.FULLSCREEN, null, null,
                "shaders/post/resolve.fsh", List.of("globals"), List.of("builtin.output"),
                null, null, List.of(), null, null, null);
        GraphSpec bad = new GraphSpec(Map.of(), List.of(fullscreen));
        assertThrows(FornaxPackError.class, () -> validate(bad));
    }

    @Test
    void packOptionsIsAValidParticlesInput() {
        // Widened from COMPUTE-only: a particles pass binds u_PackOptions as a positional descriptor
        // exactly the way a compute pass does, so refusing it here would make a runtime-tunable
        // particle system unauthorable.
        GraphSpec graph = new GraphSpec(targets(renderTarget("sceneColor")),
                List.of(particlesPass(List.of("packOptions"), List.of("sceneColor"))));
        assertDoesNotThrow(() -> validate(graph));
    }
}
