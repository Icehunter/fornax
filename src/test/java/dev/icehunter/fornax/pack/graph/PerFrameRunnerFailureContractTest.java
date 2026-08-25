package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code GraphRunner.finish()} needs a live pack and GPU device to exercise directly, the same
 * constraint that makes {@code BuiltinResolutionContractTest} a source-level test. Pins that its
 * COPY and MIPCHAIN dispatch cases catch a per-frame resolve/run failure, the same protection
 * FULLSCREEN/COMPUTE/PARTICLES/TEMPORAL already have inside their own runners: a target genuinely
 * not yet allocated (a transient mid-reload window) must skip that pass for the frame, not crash
 * the whole render frame.
 */
class PerFrameRunnerFailureContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java");

    @Test
    void copyDispatchCatchesAPerFrameResolveFailure() throws IOException {
        String source = Files.readString(SOURCE);
        int caseStart = source.indexOf("case COPY -> {");
        assertTrue(caseStart >= 0, "the COPY dispatch case must still exist");
        String block = source.substring(caseStart, source.indexOf('\n', source.indexOf("}\n", caseStart)));

        assertTrue(block.contains("CopyRunner.run(p, r);"), "must still dispatch to CopyRunner.run");
        assertTrue(block.contains("} catch (RuntimeException e) {"),
                "a per-frame resolve failure must be caught, not left to crash the whole frame");
        assertTrue(block.contains("logPassRunFailureOnce(p.name(), e);"),
                "the failure must be logged (rate-limited), not silently swallowed");
    }

    @Test
    void mipchainDispatchCatchesAPerFrameResolveFailure() throws IOException {
        String source = Files.readString(SOURCE);
        int caseStart = source.indexOf("case MIPCHAIN -> {");
        assertTrue(caseStart >= 0, "the MIPCHAIN dispatch case must still exist");
        String block = source.substring(caseStart, source.indexOf("logMissingRunnerOnce(p.name());", caseStart));

        assertTrue(block.contains("runner.run(r, mipchainTargets);"), "must still dispatch to MipchainRunner.run");
        assertTrue(block.contains("} catch (RuntimeException e) {"),
                "a per-frame resolve failure must be caught, not left to crash the whole frame");
        assertTrue(block.contains("logPassRunFailureOnce(p.name(), e);"),
                "the failure must be logged (rate-limited), not silently swallowed");
    }

    @Test
    void passRunFailureLoggedResetsOnEveryTeardown() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("passRunFailureLogged.clear();"),
                "the per-session log-once set must reset on rebuild/teardown, or a NEW pack session's"
                        + " first failure never logs again");
    }
}
