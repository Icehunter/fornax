package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins native call-site placement; the companion behavioral test models the separate queues. */
class StorageTextureInitializationContractTest {
    private String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/" + name + ".java"));
    }

    @Test void prepareCompletesStorageInitializationBeforeAnyComputeOrFrameBindings() throws Exception {
        String graph = source("GraphRunner");
        int prepare = graph.indexOf("public static void prepare(");
        int sizing = graph.indexOf("r.ensureSize(width, height, outputWidth, outputHeight);", prepare);
        int complete = graph.indexOf("r.completeStorageTextureInitialization();", sizing);
        assertTrue(complete > sizing, "storage initialization must complete after registry sizing");
        assertTrue(complete < graph.indexOf("VoxelWaterReflBuffer.ensureAllocated", sizing));
        assertTrue(complete < graph.indexOf("ensureRunnersBuilt();", sizing));
        assertTrue(complete < graph.indexOf("flushPendingRuntimeValues();", sizing));
        assertTrue(complete < graph.indexOf("refreshGeometryInputViews();", sizing));
        assertTrue(complete < graph.indexOf("runPreOpaqueLightingCompute(matrices", sizing));
    }

    @Test void successfulAllocationMarksStorageAndRawComputeRejectsPendingInitialization() throws Exception {
        String registry = source("TargetRegistry");
        int allocation = registry.indexOf("private void reconcile(");
        int installed = registry.indexOf("TargetInstance old = targets.put(name, next);", allocation);
        int marked = registry.indexOf("storageTextureInitialization.allocated(storage);", installed);
        assertTrue(marked > installed, "only installed allocations may mark initialization pending");
        assertTrue(marked < registry.indexOf("if (old != null)", installed));
        String runner = source("ComputePassRunner");
        int run = runner.indexOf("public long run(");
        int guard = runner.indexOf("registry.requireStorageTextureInitializationComplete(spec.name());", run);
        assertTrue(guard > run && guard < runner.indexOf("if ((globals == null", run));
        assertTrue(guard < runner.indexOf("updateAndBindDescriptorSet(registry", run));
        assertTrue(guard < runner.indexOf("VK13.vkQueueSubmit", run));
    }
}
