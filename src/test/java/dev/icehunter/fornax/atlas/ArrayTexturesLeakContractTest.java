package dev.icehunter.fornax.atlas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code create()} needs a live GPU device to exercise a mid-sequence failure directly, the same
 * constraint that makes {@code BuiltinResolutionContractTest} a source-level test. Pins that the
 * texture is freed if the hand-built {@code ArrayView} constructor throws, rather than being
 * orphaned when its only reference was a local about to go out of scope.
 */
class ArrayTexturesLeakContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/atlas/ArrayTextures.java");

    @Test
    void createFreesTheTextureIfTheArrayViewConstructorFails() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public static Allocation create(");
        assertTrue(methodStart >= 0, "create must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("} catch (RuntimeException e) {"),
                "a failed ArrayView construction must be caught");
        assertTrue(method.contains("texture.close();"),
                "the catch must free the texture already created before the view failed");
    }
}
