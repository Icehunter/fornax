package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.PackTomlLoader;
import dev.icehunter.fornax.pack.FornaxPackError;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoxelEmitterPoolValidationTest {
    private static void validate(String type, String inputs) {
        validate(type, inputs, "buffer", "buffer");
    }

    private static void validate(String type, String inputs, String stateKind, String summaryKind) {
        var graph = PackTomlLoader.loadGraph(new StringReader("""
                [targets.voxelEmitterPool]
                kind = "buffer"
                [targets.voxelSectionState]
                %s
                [targets.voxelSourceSummary]
                %s
                [targets.preview]
                format = "rgba16f"
                scale = 1.0
                storage = true
                [[pass]]
                name = "preview"
                type = "%s"
                shader = "shaders/preview.comp"
                inputs = [%s]
                outputs = ["preview"]
                dispatch = [1, 1, 1]
                """.formatted(targetDeclaration(stateKind), targetDeclaration(summaryKind), type, inputs)), "graph.toml");
        GraphValidator.validate(graph, Map.of(), 16, 16);
    }

    private static String targetDeclaration(String kind) {
        return kind.equals("buffer") ? "kind = \"buffer\"" : "format = \"rgba16f\"";
    }

    @Test void engineSizedPoolAdmitsAComputeConsumerWithBothOwnershipInputs() {
        assertDoesNotThrow(() -> validate("compute", "\"voxelEmitterPool\", \"voxelSectionState\", \"voxelSourceSummary\""));
    }

    @Test void ownershipInputsMustBeBuffersRatherThanTexturesWithMatchingNames() {
        String inputs = "\"voxelEmitterPool\", \"voxelSectionState\", \"voxelSourceSummary\"";
        assertThrows(FornaxPackError.class, () -> validate("compute", inputs, "texture", "buffer"));
        assertThrows(FornaxPackError.class, () -> validate("compute", inputs, "buffer", "texture"));
    }

    @Test void missingOwnershipOrAGraphicsReaderFailsAtLoad() {
        assertThrows(FornaxPackError.class, () -> validate("compute", "\"voxelEmitterPool\""));
        assertThrows(FornaxPackError.class, () -> validate("fullscreen", "\"voxelEmitterPool\", \"voxelSectionState\", \"voxelSourceSummary\""));
    }
}
