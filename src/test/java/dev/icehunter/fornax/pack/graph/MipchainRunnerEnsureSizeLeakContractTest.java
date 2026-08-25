package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code ensureSize()} needs a live GPU device to exercise a mid-sequence failure directly, the
 * same constraint that makes {@code BuiltinResolutionContractTest} a source-level test. Pins that
 * the texture, full view, and whichever per-level views already succeeded are all freed if the
 * per-level view loop fails partway through.
 */
class MipchainRunnerEnsureSizeLeakContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/pack/graph/MipchainRunner.java");

    @Test
    void ensureSizeFreesPartiallyCreatedLevelViewsOnFailure() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public void ensureSize(");
        assertTrue(methodStart >= 0, "ensureSize must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("} catch (RuntimeException e) {"),
                "a mid-sequence texture/view creation failure must be caught");
        assertTrue(method.contains("for (GpuTextureView v : newLevelViews) {"),
                "the catch must free whichever per-level views already succeeded");
    }
}
