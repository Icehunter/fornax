package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ShaderImports} guards the failure class where a pack shader's {@code #moj_import} names an
 * include id blaze3d cannot serve: vanilla's import resolution splices the error message into the
 * composed GLSL instead of failing (no cross-namespace fallback), so without this check the first
 * symptom is a broken pipeline compile mid-frame.
 */
class ShaderImportsTest {
    @Test
    void packLocalImportResolvingAgainstServedIncludePasses() {
        assertDoesNotThrow(() -> ShaderImports.validate(Map.of(
                "shaders/blocks/terrain.vsh", "#moj_import <fornax_runtime:chunk_vertex.glsl>\nvoid main() {}",
                "shaders/include/chunk_vertex.glsl", "vec3 _vert_position;")));
    }

    @Test
    void packLocalImportWithoutServedIncludeThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class, () -> ShaderImports.validate(Map.of(
                "shaders/blocks/terrain.vsh", "#moj_import <fornax_runtime:chunk_vertex.glsl>\nvoid main() {}")));
        assertTrue(e.getMessage().contains("chunk_vertex.glsl"));
    }

    @Test
    void engineImportOfANonEngineIncludeThrows() {
        // The engine jar ships only globals.glsl and block_atlas.glsl -- a pack still importing
        // <fornax:chunk_vertex.glsl> (the pre-pack-migration spelling) must be rejected at load,
        // not degrade to spliced-error GLSL.
        FornaxPackError e = assertThrows(FornaxPackError.class, () -> ShaderImports.validate(Map.of(
                "shaders/blocks/terrain.vsh", "#moj_import <fornax:chunk_vertex.glsl>\nvoid main() {}")));
        assertTrue(e.getMessage().contains("fornax_runtime"));
    }

    @Test
    void engineAndExternalImportsPass() {
        assertDoesNotThrow(() -> ShaderImports.validate(Map.of(
                "shaders/post/resolve.fsh", "#moj_import <fornax:globals.glsl>\n#moj_import <sodium:fog.glsl>\nvoid main() {}",
                "shaders/blocks/terrain.fsh", "#moj_import <fornax:block_atlas.glsl>\nvoid main() {}")));
    }

    @Test
    void unknownImportNamespaceThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class, () -> ShaderImports.validate(Map.of(
                "shaders/post/resolve.fsh", "#moj_import <someoneelse:util.glsl>\nvoid main() {}")));
        assertTrue(e.getMessage().contains("someoneelse"));
    }
}
