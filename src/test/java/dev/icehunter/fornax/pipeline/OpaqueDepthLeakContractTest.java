package dev.icehunter.fornax.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code ensureSize()} needs a live GPU device to exercise a mid-sequence failure directly, the
 * same constraint that makes {@code BuiltinResolutionContractTest} a source-level test. Pins that
 * the texture/view are freed if creating the view or clearing the texture fails, rather than
 * orphaned.
 */
class OpaqueDepthLeakContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/pipeline/OpaqueDepth.java");

    @Test
    void ensureSizeFreesPartiallyCreatedHandlesOnFailure() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public void ensureSize(");
        assertTrue(methodStart >= 0, "ensureSize must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("} catch (RuntimeException e) {"),
                "a mid-sequence texture/view/clear failure must be caught");
        assertTrue(method.contains("if (nextView != null) nextView.close();"),
                "the catch must free every handle successfully created so far");
    }
}
