package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code reconcile()} needs a live GPU device to exercise a mid-sequence failure directly, the
 * same constraint that makes {@code BuiltinResolutionContractTest} a source-level test. Pins that
 * the primary texture/view is freed if the history pair's creation fails, rather than orphaned.
 */
class TargetRegistryReconcileLeakContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/pack/graph/TargetRegistry.java");

    @Test
    void reconcileFreesThePrimaryPairIfTheHistoryPairFails() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("private void reconcile(");
        assertTrue(methodStart >= 0, "reconcile must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("} catch (RuntimeException e) {"),
                "a mid-sequence texture/view creation failure must be caught");
        assertTrue(method.contains("if (texture != null) texture.close();"),
                "the catch must free the primary texture even if only the history pair failed");
    }
}
