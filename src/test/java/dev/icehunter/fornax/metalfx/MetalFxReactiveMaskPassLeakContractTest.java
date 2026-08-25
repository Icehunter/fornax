package dev.icehunter.fornax.metalfx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code ensureSize()} needs a live GPU device to exercise a mid-sequence failure directly, the
 * same constraint that makes {@code BuiltinResolutionContractTest} a source-level test. Pins that
 * the texture is freed if creating its view fails, rather than orphaned.
 */
class MetalFxReactiveMaskPassLeakContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/metalfx/MetalFxReactiveMaskPass.java");

    @Test
    void ensureSizeFreesTheTextureIfTheViewFails() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("private static void ensureSize(");
        assertTrue(methodStart >= 0, "ensureSize must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("} catch (RuntimeException e) {"),
                "a failed view creation must be caught");
        assertTrue(method.contains("if (nextTexture != null) nextTexture.close();"),
                "the catch must free the texture already created before the view failed");
    }
}
