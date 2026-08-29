package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A compute-pipeline build requires a live Vulkan device, so this pins the runner-construction
 * boundary in source: one optional compute pass may fail without discarding every later runner. */
class ComputeRunnerBuildFailureIsolationContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java");

    @Test
    void computePipelineFailureIsNamedAndDoesNotAbortTheRunnerBuildLoop() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("private static void ensureRunnersBuilt()");
        assertTrue(methodStart >= 0, "ensureRunnersBuilt must still exist");
        int caseStart = source.indexOf("case COMPUTE -> {", methodStart);
        int caseEnd = source.indexOf("case PARTICLES -> {", caseStart);
        assertTrue(caseStart >= 0 && caseEnd > caseStart, "the COMPUTE build case must still exist");
        String block = source.substring(caseStart, caseEnd);

        assertTrue(block.contains("try {"), "compute pipeline construction must have a local guard");
        assertTrue(block.contains("} catch (RuntimeException e) {"),
                "a failed compute pipeline must be quarantined inside its own case");
        assertTrue(block.contains("GpuFatalErrors.rethrowIfFatal(e);"),
                "device loss and other fatal GPU failures must never be quarantined");
        assertTrue(block.contains("logRunnerBuildFailureOnce(p.name(), e);"),
                "the failure must identify the exact pass");
        String catchBlock = block.substring(block.indexOf("} catch (RuntimeException e) {"));
        assertFalse(catchBlock.contains("throw e;"), "the local catch must continue to later passes");
        assertFalse(catchBlock.contains("return;"), "the local catch must continue to later passes");
    }

    @Test
    void computeBuildFailureLogGateResetsOnPackTeardown() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("runnerBuildFailureLogged.clear();"),
                "a new pack session must report its own first pipeline failure");
    }
}
