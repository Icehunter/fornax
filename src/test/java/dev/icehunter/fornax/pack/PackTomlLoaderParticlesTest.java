package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackTomlLoaderParticlesTest {
    private static final String FILE = "graph.toml";

    private static GraphSpec load(String toml) {
        return PackTomlLoader.loadGraph(new StringReader(toml), FILE);
    }

    @Test
    void parsesAParticlesPassWithBothStagesAndAnInstanceCount() {
        GraphSpec graph = load("""
                [[pass]]
                name = "snow_draw"
                type = "particles"
                vertex_shader = "shaders/particles/snow.vsh"
                shader = "shaders/particles/snow.fsh"
                instances = 50000
                inputs = ["globals", "snowFlakes"]
                outputs = ["sceneColor"]
                """);
        PassSpec pass = graph.passes().get(0);
        assertEquals(PassType.PARTICLES, pass.type());
        ParticleSpec particles = pass.particles();
        assertNotNull(particles);
        assertEquals("shaders/particles/snow.vsh", particles.vertexShader());
        assertEquals(50000, particles.instances());
        // 'shader' keeps its fullscreen meaning: the FRAGMENT stage. A loader that quietly moved it
        // would compile the fragment source as a vertex shader and fail far from the cause.
        assertEquals("shaders/particles/snow.fsh", pass.shader());
    }

    @Test
    void everyOtherPassTypeCarriesNoParticleSpec() {
        GraphSpec graph = load("""
                [[pass]]
                name = "resolve"
                type = "fullscreen"
                shader = "shaders/post/resolve.fsh"
                outputs = ["builtin.output"]
                """);
        assertNull(graph.passes().get(0).particles());
    }

    @Test
    void particlesPassWithoutVertexShaderIsRejected() {
        assertThrows(FornaxPackError.class, () -> load("""
                [[pass]]
                name = "snow_draw"
                type = "particles"
                shader = "shaders/particles/snow.fsh"
                instances = 1000
                outputs = ["sceneColor"]
                """));
    }

    @Test
    void particlesPassWithoutInstancesIsRejected() {
        assertThrows(FornaxPackError.class, () -> load("""
                [[pass]]
                name = "snow_draw"
                type = "particles"
                vertex_shader = "shaders/particles/snow.vsh"
                shader = "shaders/particles/snow.fsh"
                outputs = ["sceneColor"]
                """));
    }

    @Test
    void nonPositiveInstanceCountIsRejected() {
        // vkCmdDraw with instanceCount 0 is legal but draws nothing, and a negative literal would
        // reach the API as a huge unsigned count -- neither is what the author meant.
        assertThrows(FornaxPackError.class, () -> load("""
                [[pass]]
                name = "snow_draw"
                type = "particles"
                vertex_shader = "shaders/particles/snow.vsh"
                shader = "shaders/particles/snow.fsh"
                instances = 0
                outputs = ["sceneColor"]
                """));
    }

    @Test
    void vertexShaderOnANonParticlesPassIsRejected() {
        // Silent-failure guard: nothing else in the pipeline reads vertex_shader off a fullscreen
        // pass, so ignoring it here would leave an author convinced they had supplied a vertex stage.
        assertThrows(FornaxPackError.class, () -> load("""
                [[pass]]
                name = "resolve"
                type = "fullscreen"
                vertex_shader = "shaders/post/resolve.vsh"
                shader = "shaders/post/resolve.fsh"
                outputs = ["builtin.output"]
                """));
    }

    @Test
    void instancesOnANonParticlesPassIsRejected() {
        assertThrows(FornaxPackError.class, () -> load("""
                [[pass]]
                name = "probe"
                type = "compute"
                shader = "shaders/compute/probe.comp"
                dispatch = [1, 1, 1]
                instances = 100
                outputs = ["probeAtlas"]
                """));
    }
}
