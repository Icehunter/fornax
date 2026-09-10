package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.PackTomlLoader;
import dev.icehunter.fornax.pack.FornaxPackError;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoxelSourceWindowValidationTest {
    static void validate(String type, String inputs, String state, String output) {
        var graph = PackTomlLoader.loadGraph(new StringReader("""
                [targets.voxelSourceWindow]
                kind = "buffer"
                [targets.voxelSectionState]
                %s
                [targets.result]
                format = "rgba16f"
                storage = true
                [[pass]]
                name = "source"
                type = "%s"
                shader = "shaders/source.comp"
                inputs = [%s]
                outputs = ["%s"]
                dispatch = [1, 1, 1]
                """.formatted(state, type, inputs, output)), "graph.toml");
        GraphValidator.validate(graph, Map.of(), 16, 16);
    }
    @Test void sourceWindowIsEngineSizedAndWorksWithoutTheDiagnosticSummary() {
        assertDoesNotThrow(() -> validate("compute", "\"voxelSourceWindow\",\"voxelSectionState\"", "kind = \"buffer\"", "result"));
    }
    @Test void sourceWindowRejectsMissingOwnershipTextureOwnershipGraphicsReadersAndPackWriters() {
        String inputs = "\"voxelSourceWindow\",\"voxelSectionState\"";
        assertThrows(FornaxPackError.class, () -> validate("compute", "\"voxelSourceWindow\"", "kind = \"buffer\"", "result"));
        assertThrows(FornaxPackError.class, () -> validate("compute", inputs, "format = \"rgba16f\"", "result"));
        assertThrows(FornaxPackError.class, () -> validate("fullscreen", inputs, "kind = \"buffer\"", "result"));
        assertThrows(FornaxPackError.class, () -> validate("compute", "", "kind = \"buffer\"", "voxelSourceWindow"));
    }
}
