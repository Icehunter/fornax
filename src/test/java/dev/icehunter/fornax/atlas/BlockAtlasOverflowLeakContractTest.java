package dev.icehunter.fornax.atlas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code build()} needs a live GPU device to exercise a mid-sequence failure directly, the same
 * constraint that makes {@code BuiltinResolutionContractTest} a source-level test. Pins that the
 * array-texture allocation is freed if the sprite-compositing loop throws partway through, rather
 * than orphaned when {@code rebuild()}'s own catch has no reference to it.
 */
class BlockAtlasOverflowLeakContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/atlas/BlockAtlasOverflow.java");

    @Test
    void buildFreesTheAlbedoAllocationIfCompositingFailsPartway() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("private static Published build(");
        assertTrue(methodStart >= 0, "build must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("} catch (RuntimeException e) {"),
                "a mid-loop sprite-compositing failure must be caught");
        assertTrue(method.contains("albedo.close();"),
                "the catch must free the already-created array-texture allocation");
    }
}
