package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackTomlLoaderComputeTest {
    private static final String FILE = "graph.toml";

    @Test
    void parsesAComputePassWithDispatch() {
        String toml = """
                [[pass]]
                name = "voxel_probe_update"
                type = "compute"
                shader = "shaders/compute/voxel_probe_update.comp"
                inputs = []
                outputs = []
                dispatch = [8, 8, 1]
                """;
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader(toml), FILE);
        PassSpec pass = graph.passes().get(0);
        assertEquals(PassType.COMPUTE, pass.type());
        assertEquals(List.of(8, 8, 1), pass.dispatch());
    }

    @Test
    void nonComputePassHasEmptyDispatch() {
        String toml = """
                [[pass]]
                name = "resolve"
                type = "fullscreen"
                shader = "shaders/post/resolve.fsh"
                inputs = []
                outputs = ["builtin.output"]
                """;
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader(toml), FILE);
        assertEquals(List.of(), graph.passes().get(0).dispatch());
    }

    @Test
    void computePassWithWrongDispatchArityIsRejected() {
        String toml = """
                [[pass]]
                name = "bad"
                type = "compute"
                shader = "shaders/compute/bad.comp"
                inputs = []
                outputs = []
                dispatch = [8, 8]
                """;
        assertThrows(FornaxPackError.class, () -> PackTomlLoader.loadGraph(new StringReader(toml), FILE));
    }

    @Test
    void computePassMissingDispatchIsRejected() {
        String toml = """
                [[pass]]
                name = "bad"
                type = "compute"
                shader = "shaders/compute/bad.comp"
                inputs = []
                outputs = []
                """;
        assertThrows(FornaxPackError.class, () -> PackTomlLoader.loadGraph(new StringReader(toml), FILE));
    }

    @Test
    void nonComputePassWithDispatchIsRejected() {
        String toml = """
                [[pass]]
                name = "bad"
                type = "fullscreen"
                shader = "shaders/post/bad.fsh"
                inputs = []
                outputs = ["builtin.output"]
                dispatch = [8, 8, 1]
                """;
        assertThrows(FornaxPackError.class, () -> PackTomlLoader.loadGraph(new StringReader(toml), FILE));
    }
}
