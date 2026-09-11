package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.graph.EntityOccluderBuffer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityOccluderStrideContractTest {
    private static final String SHADER = "shaders/post/entity_shadow.fsh";

    private static PassSpec readerPass(String shader, List<String> inputs) {
        return new PassSpec("entity_shadow", PassType.FULLSCREEN, null, null, shader, inputs,
                List.of("someOutput"), null, null, List.of(), null, null, null);
    }

    private static String mirrorDeclarations(int header, int max, int record) {
        return "const int ENTITY_OCCLUDER_HEADER_WORDS = " + header + ";\n"
                + "const int ENTITY_OCCLUDER_MAX = " + max + ";\n"
                + "const int ENTITY_OCCLUDER_RECORD_WORDS = " + record + ";\n";
    }

    @Test
    void acceptsAPassWhoseShaderMirrorsTheCurrentAbi() {
        GraphSpec graph = new GraphSpec(Map.of(),
                List.of(readerPass(SHADER, List.of(EntityOccluderBuffer.TARGET))));
        Map<String, String> sources = Map.of(SHADER, mirrorDeclarations(
                EntityOccluderBuffer.HEADER_FLOATS, EntityOccluderBuffer.MAX_OCCLUDERS,
                EntityOccluderBuffer.FLOATS_PER_OCCLUDER));

        assertDoesNotThrow(() -> EntityOccluderStrideContract.validate(graph, sources));
    }

    @Test
    void rejectsAStaleMirroredValueByName() {
        int stale = EntityOccluderBuffer.MAX_OCCLUDERS + 1;
        GraphSpec graph = new GraphSpec(Map.of(),
                List.of(readerPass(SHADER, List.of(EntityOccluderBuffer.TARGET))));
        Map<String, String> sources = Map.of(SHADER, mirrorDeclarations(
                EntityOccluderBuffer.HEADER_FLOATS, stale, EntityOccluderBuffer.FLOATS_PER_OCCLUDER));

        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> EntityOccluderStrideContract.validate(graph, sources));
        assertTrue(error.getMessage().contains(String.valueOf(stale)),
                "error names the stale value the shader declared: " + error.getMessage());
        assertTrue(error.getMessage().contains(SHADER), "error names the offending file: " + error.getMessage());
    }

    @Test
    void rejectsAReaderThatNeverDeclaresTheAbiAtAll() {
        GraphSpec graph = new GraphSpec(Map.of(),
                List.of(readerPass(SHADER, List.of(EntityOccluderBuffer.TARGET))));
        Map<String, String> sources = Map.of(SHADER, "void main() { fragColor = vec4(0.0); }\n");

        FornaxPackError error = assertThrows(FornaxPackError.class,
                () -> EntityOccluderStrideContract.validate(graph, sources));
        assertTrue(error.getMessage().contains("ENTITY_OCCLUDER_HEADER_WORDS"),
                "error names the missing declaration: " + error.getMessage());
    }

    @Test
    void ignoresAPassThatDoesNotReadTheEntityOccluderSet() {
        GraphSpec graph = new GraphSpec(Map.of(), List.of(readerPass(SHADER, List.of())));
        // A stale copy of these values in a file no reading pass touches must never fail the
        // load. Nothing this contract checks reaches that file.
        Map<String, String> sources = Map.of(SHADER, mirrorDeclarations(999, 999, 999));

        assertDoesNotThrow(() -> EntityOccluderStrideContract.validate(graph, sources));
    }

    @Test
    void findsTheMirrorThroughATransitivelyImportedPackInclude() {
        // This matches the real case: a pass's fragment shader imports a shared include, and the
        // copied values live inside that include, not in the fragment shader's own text.
        String fragment = "#moj_import <fornax_runtime:entity_occluders.glsl>\nvoid main() {}\n";
        String include = mirrorDeclarations(EntityOccluderBuffer.HEADER_FLOATS,
                EntityOccluderBuffer.MAX_OCCLUDERS, EntityOccluderBuffer.FLOATS_PER_OCCLUDER);
        GraphSpec graph = new GraphSpec(Map.of(),
                List.of(readerPass(SHADER, List.of(EntityOccluderBuffer.TARGET))));
        Map<String, String> sources = Map.of(
                SHADER, fragment,
                "shaders/include/entity_occluders.glsl", include);

        assertDoesNotThrow(() -> EntityOccluderStrideContract.validate(graph, sources));
    }
}
