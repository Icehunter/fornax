package dev.icehunter.fornax.pack.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native submit and render-scope ownership cannot be invoked without a client Vulkan device. */
class DeferredComputeWaitContractTest {
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/" + name + ".java"));
    }
    @Test void successfulSubmitPublishesTheWaitOutsideTheKernelReuseBranch() throws Exception {
        String runner = source("ComputePassRunner");
        int success = runner.indexOf("slot.submitted = true;");
        int publication = runner.indexOf("graphicsWaits.submitted(spec, slot.graphicsSemaphore, graphicsWaitStageMask)");
        assertTrue(publication > success, "only successful submissions, including cache hits, may publish pending waits");
        assertTrue(runner.contains("if (graphicsWaits == null)"), "pre-opaque callers keep their immediate native wait");
    }
    @Test void everyPassIsCheckedBeforeItsRunnerAndHistorySwapIsOutsideTheDrainedScope() throws Exception {
        String graph = source("GraphRunner");
        int scope = graph.indexOf("try (ComputeGraphicsWaits graphicsWaits =");
        int before = graph.indexOf("graphicsWaits.beforePass(p);", scope);
        int dispatch = graph.indexOf("switch (p.type())", scope);
        int end = graph.indexOf("// Deferred compute waits have been consumed", dispatch);
        int swap = graph.indexOf("r.swapHistory();", end);
        assertTrue(scope >= 0 && before > scope && dispatch > before && end > dispatch && swap > end,
                "the scope must drain before captures, history swaps and geometry outside finish");
    }
    @Test void conflictPlanIsRebuiltWithThePackAndUsedByTheFrameScope() throws Exception {
        String graph = source("GraphRunner");
        assertTrue(graph.contains("computeGraphicsConflicts = ComputeGraphicsWaits.compile(pack.graph().passes())"));
        assertTrue(graph.contains("new ComputeGraphicsWaits(computeGraphicsConflicts,"));
    }

}
