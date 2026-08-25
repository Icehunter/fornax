package dev.icehunter.fornax.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code ensureCreated()} needs a live GPU device to exercise a {@code createTextureView} failure
 * directly, the same constraint that makes {@code BuiltinResolutionContractTest} a source-level
 * test. Pins that {@code texture}/{@code view} assign together or not at all: a failure after
 * {@code texture} is set but before {@code view} is left the {@code texture != null} guard
 * skipping every later retry, so {@link NoiseTexture#getView()} silently returned {@code null} for
 * the rest of the process.
 */
class NoiseTextureTeardownContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/pipeline/NoiseTexture.java");

    @Test
    void textureFieldIsNotAssignedUntilTheViewAlsoSucceeds() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("private static void ensureCreated()");
        assertTrue(methodStart >= 0, "ensureCreated must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int textureAssign = method.indexOf("texture = newTexture;");
        int viewCreate = method.indexOf("device.createTextureView(newTexture)");
        assertTrue(textureAssign >= 0, "must still assign the texture field");
        assertTrue(viewCreate >= 0, "must still create the view");
        assertTrue(textureAssign > viewCreate,
                "texture must be assigned AFTER createTextureView succeeds, not before; otherwise"
                        + " ensureCreated()'s `if (texture != null) return;` guard skips every retry"
                        + " once texture is set, even though view never got created");
    }

    @Test
    void aFailedViewCreationClosesTheAlreadyCreatedTexture() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("private static void ensureCreated()");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int catchStart = method.indexOf("} catch (RuntimeException e) {");
        assertTrue(catchStart >= 0, "a failed createTextureView must be caught, not left to escape raw");
        String catchBlock = method.substring(catchStart, method.indexOf('}', catchStart + 1) + 1);
        assertTrue(catchBlock.contains("newTexture.close();"),
                "the already-created texture must be freed, not leaked, when the view fails");
    }
}
