package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code runPreOpaqueLightingCompute} needs a live compute backend and real {@code ComputePassRunner}
 * instances to exercise directly, the same constraint that makes {@code BuiltinResolutionContractTest}
 * a source-level test. Pins that the signalled producer's wait-stage mask is derived from {@link
 * GraphRunner#computeGraphicsWaitStages} rather than a hardcoded constant, so it stays correct if a
 * pack ever points a non-fragment-stage pass at one of these lighting buffers.
 */
class PreOpaqueLightingComputeWaitStageContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java");

    @Test
    void eachRawProducerWaitStageIsDerivedNotHardcoded() throws IOException {
        String source = Files.readString(SOURCE).replace("\r\n", "\n");
        int methodStart = source.indexOf("private static void runPreOpaqueLightingCompute(");
        assertTrue(methodStart >= 0, "runPreOpaqueLightingCompute must still exist");
        int methodEnd = source.indexOf("\n    }\n", methodStart);
        assertTrue(methodEnd > methodStart, "pre-opaque lighting method must have a closing brace");
        String method = source.substring(methodStart, methodEnd);

        assertFalse(method.contains("VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT"),
                "the wait stage must not hardcode FRAGMENT -- a pack pointing a COPY or PARTICLES"
                        + " pass at a lighting buffer needs a different stage, and a hardcoded"
                        + " constant would silently under-synchronize that case");
        assertTrue(method.contains("graphicsWaitStagesFor(p, pack.graph())"),
                "the signalled mask must come from the shared, tested computeGraphicsWaitStages"
                        + " path for each raw producer, including a producer before a graphics-stream"
                        + " consumer within the pre-opaque phase");
    }
}
