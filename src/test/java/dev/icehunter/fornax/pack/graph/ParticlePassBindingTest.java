package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pack.ParticleSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the two numbers a pack hardcodes in GLSL and nothing at runtime would notice moving: the
 * descriptor binding index of each declared input, and the cross-queue wait stage a compute pass
 * feeding a particles pass gets.
 */
class ParticlePassBindingTest {
    private static final ParticleSpec PARTICLES = new ParticleSpec("shaders/particles/snow.vsh", 1000);

    private static PassSpec particlesPass(String name, List<String> inputs, List<String> outputs) {
        return new PassSpec(name, PassType.PARTICLES, null, null, "shaders/particles/snow.fsh",
                inputs, outputs, null, null, List.of(), null, null, PARTICLES);
    }

    private static PassSpec computePass(String name, List<String> outputs, String enabledIf) {
        return new PassSpec(name, PassType.COMPUTE, null, null, "shaders/compute/snow_update.comp",
                List.of(), outputs, null, enabledIf, List.of(64, 1, 1), null, null, null);
    }

    @Test
    void bindingOrderIsTheDeclaredInputsInOrder() {
        PassSpec spec = particlesPass("snow_draw",
                List.of("globals", "snowFlakes", "packOptions"), List.of("sceneColor"));
        assertEquals(List.of("globals", "snowFlakes", "packOptions"),
                ParticlePassRunner.bindingOrder(spec),
                "binding N = the Nth declared input -- this is the number a pack writes into"
                        + " layout(binding = N) and nothing else would notice it moving");
    }

    @Test
    void bindingOrderDoesNotAppendOutputs() {
        // The one structural difference from ComputePassRunner.combinedBindingOrder, and the reason
        // this has its own test: a particles pass writes through a COLOR ATTACHMENT, not a
        // descriptor. Appending outputs would shift every pack-authored binding index by the number
        // of outputs, with nothing at load or run time to say so -- the shader would just read the
        // wrong resource.
        PassSpec spec = particlesPass("snow_draw", List.of("snowFlakes"), List.of("sceneColor"));
        assertEquals(List.of("snowFlakes"), ParticlePassRunner.bindingOrder(spec));
        assertEquals(1, ParticlePassRunner.bindingOrder(spec).size());
    }

    @Test
    void computePassFeedingAnEnabledParticlesPassWaitsAtTheVertexStage() {
        // FRAGMENT would be too late: the flake buffer is read by the BILLBOARD VERTEX stage, which
        // runs first. A fragment-only wait races the simulation dispatch with no validation error.
        GraphSpec graph = new GraphSpec(Map.of(), List.of(
                computePass("snow_update", List.of("snowFlakes"), null),
                particlesPass("snow_draw", List.of("snowFlakes"), List.of("sceneColor"))));
        long stages = GraphRunner.computeGraphicsWaitStages(graph.passes().get(0), graph, Map.of());
        assertEquals(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT, stages);
    }

    @Test
    void computePassWithNoParticlesReaderNeedsNoHandoff() {
        GraphSpec graph = new GraphSpec(Map.of(), List.of(
                computePass("snow_update", List.of("snowFlakes"), null),
                particlesPass("snow_draw", List.of("otherBuffer"), List.of("sceneColor"))));
        assertEquals(0L, GraphRunner.computeGraphicsWaitStages(graph.passes().get(0), graph, Map.of()));
    }

    @Test
    void computeStorageImageFeedingGeometryWaitsAtFragmentStage() {
        PassSpec compute = computePass("water_step", List.of("waveState"), null);
        PassSpec terrain = new PassSpec("terrain", PassType.GEOMETRY, GeometrySlot.TERRAIN,
                "shaders/terrain", null, List.of("waveState"), List.of("sceneColor"),
                null, null, List.of(), null, null, null);
        GraphSpec graph = new GraphSpec(Map.of(), List.of(compute, terrain));
        assertEquals(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                GraphRunner.computeGraphicsWaitStages(compute, graph, Map.of()));
    }

    @Test
    void aCompileDisabledParticlesReaderNeedsNoHandoff() {
        GraphSpec graph = new GraphSpec(Map.of(), List.of(
                computePass("snow_update", List.of("snowFlakes"), null),
                new PassSpec("snow_draw", PassType.PARTICLES, null, null, "shaders/particles/snow.fsh",
                        List.of("snowFlakes"), List.of("sceneColor"), null, "SNOW_PARTICLES",
                        List.of(), null, null, PARTICLES)));
        assertEquals(0L, GraphRunner.computeGraphicsWaitStages(graph.passes().get(0), graph,
                Map.of("SNOW_PARTICLES", 0)));
        assertEquals(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT,
                GraphRunner.computeGraphicsWaitStages(graph.passes().get(0), graph,
                        Map.of("SNOW_PARTICLES", 1)));
    }

    @Test
    void computeStorageImageFeedingCopyWaitsAtTransferStage() {
        // FRAGMENT is the wrong stage for a copy: CopyRunner moves the target with
        // copyTextureToTexture, which lowers to vkCmdCopyImage/blit at TRANSFER, not fragment shader
        // invocation. This pass type used to fall through both branches and contribute 0, leaving no
        // semaphore signalled and no wait recorded -- an unsynchronized cross-queue read.
        PassSpec compute = computePass("water_step", List.of("waveState"), null);
        PassSpec copy = new PassSpec("water_copy", PassType.COPY, null, null, null,
                List.of("waveState"), List.of("waveStateStable"), null, null, List.of(), null, null, null);
        GraphSpec graph = new GraphSpec(Map.of(), List.of(compute, copy));
        assertEquals(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                GraphRunner.computeGraphicsWaitStages(compute, graph, Map.of()));
    }

    @Test
    void aParticlesPassIsNeverItsOwnHandoffSource() {
        // Only a COMPUTE submission has a cross-queue handoff to request; asking about any other
        // pass type must be 0, not an accidental match on its own outputs.
        GraphSpec graph = new GraphSpec(Map.of(), List.of(
                particlesPass("snow_draw", List.of("snowFlakes"), List.of("snowFlakes"))));
        assertEquals(0L, GraphRunner.computeGraphicsWaitStages(graph.passes().get(0), graph, Map.of()));
    }
}
