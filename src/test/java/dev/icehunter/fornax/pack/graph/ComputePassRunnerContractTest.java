package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The real {@code vkWaitForFences}/descriptor-bind call sites need a live GPU device to exercise
 * directly, the same constraint that makes {@code BuiltinResolutionContractTest} a source-level
 * test. Pins that all three fence waits in this class route their result through
 * {@code fenceWaitSucceeded} rather than discarding it, and that the storage-buffer bind refuses a
 * null lookup by name instead of dereferencing it.
 */
class ComputePassRunnerContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/pack/graph/ComputePassRunner.java");

    @Test
    void everyVkWaitForFencesCallRoutesThroughFenceWaitSucceeded() throws IOException {
        String source = Files.readString(SOURCE);
        long waitCalls = source.lines().filter(l -> l.contains("VK13.vkWaitForFences(")).count();
        long checkedCalls = source.lines().filter(l -> l.contains("VK13.vkWaitForFences(")
                && (l.contains("fenceWaitSucceeded(") || l.contains("waitResult ="))).count();
        assertTrue(waitCalls == 3, "expected the three known call sites (ring-slot recycle, "
                + "synchronous wait, ring teardown); a new one must also route through fenceWaitSucceeded");
        assertTrue(checkedCalls == 3,
                "every vkWaitForFences call must feed fenceWaitSucceeded, not discard the result");
    }

    @Test
    void ringSlotRecycleSkipsResetAndReturnsOnAnUndrainedSlot() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public void run(TargetRegistry registry");
        assertTrue(methodStart >= 0, "run(...) must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int waitIndex = method.indexOf("ring-slot recycle in");
        assertTrue(waitIndex >= 0, "ring-slot recycle wait must still be present");
        String recycleBlock = method.substring(waitIndex, method.indexOf("VulkanCommandPool pool", waitIndex));
        assertTrue(recycleBlock.contains("return;"),
                "an undrained slot must skip this frame's dispatch rather than recycle a live command pool");
    }

    @Test
    void storageBufferBindRefusesANullLookupByName() throws IOException {
        String source = Files.readString(SOURCE);
        int branchStart = source.indexOf("VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER) {\n");
        assertTrue(branchStart >= 0, "the STORAGE_BUFFER bind branch must still exist");
        String branch = source.substring(branchStart, source.indexOf("write.descriptorType(VK13.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)", branchStart));

        assertTrue(branch.contains("if (buf == null) {"),
                "a released pack-sized buffer must be refused by name, not dereferenced as an NPE");
        assertTrue(branch.contains("spec.name()") && branch.contains("is not allocated"),
                "the failure must name the compute pass and the target, matching ParticlePassRunner's convention");
    }
}
