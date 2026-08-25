package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code load()} needs a live GPU device to exercise a mid-sequence failure directly, the same
 * constraint that makes {@code BuiltinResolutionContractTest} a source-level test. Pins that the
 * texture is freed if anything after its creation (the upload/mip loop, or the view) fails, rather
 * than being registered into {@code textures}/{@code views} only after the whole sequence
 * succeeds with no reference kept to close it on failure.
 */
class PackTextureRegistryLoadLeakContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/pack/graph/PackTextureRegistry.java");

    @Test
    void loadFreesTheTextureIfAnythingAfterItFails() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("private void load(");
        assertTrue(methodStart >= 0, "load must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("catch (IOException | RuntimeException e) {"),
                "a mid-sequence failure (decode, upload, or a transient GPU failure) must be caught");
        assertTrue(method.contains("if (texture != null) texture.close();"),
                "the catch must free the texture if it was created but never registered");
    }
}
