package dev.icehunter.fornax.pass.shadow;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code ensureSize()} needs a live GPU device to exercise a mid-sequence failure directly, the
 * same constraint that makes {@code BuiltinResolutionContractTest} a source-level test. Pins that
 * all 4 resources (shadow texture/view, dummy colour texture/view) are hoisted above the
 * {@code try} and freed on a mid-sequence failure.
 */
class ShadowMapManagerLeakContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/pass/shadow/ShadowMapManager.java");

    @Test
    void ensureSizeFreesPartiallyCreatedHandlesOnFailure() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public static void ensureSize(");
        assertTrue(methodStart >= 0, "ensureSize must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("} catch (RuntimeException e) {"),
                "a mid-sequence resource creation failure must be caught");
        assertTrue(method.contains("if (nextDummyColorTexture != null) nextDummyColorTexture.close();"),
                "the catch must free every handle successfully created so far");
    }
}
