package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        int methodStart = source.indexOf("public long run(TargetRegistry registry");
        assertTrue(methodStart >= 0, "run(...) must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int waitIndex = method.indexOf("ring-slot recycle in");
        assertTrue(waitIndex >= 0, "ring-slot recycle wait must still be present");
        String recycleBlock = method.substring(waitIndex, method.indexOf("VulkanCommandPool pool", waitIndex));
        assertTrue(recycleBlock.contains("return -1L;"),
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

    @Test
    void hazardousStorageReuseWaitsOnATimelineInsteadOfIdlingGraphics() throws IOException {
        String source = Files.readString(SOURCE);
        String run = methodBody(source, "public long run(TargetRegistry registry");

        assertTrue(hasConnectedTimelineSubmitContract(run),
                "run() must connect one reuse ticket to the actual VkSubmitInfo wait, align its "
                        + "simultaneous binary signal, cancel both failed-submit paths, and retain success");
    }

    @Test
    void timelineSubmitContractRejectsDisconnectedOrIncompleteMutations() throws IOException {
        String run = methodBody(Files.readString(SOURCE), "public long run(TargetRegistry registry");

        String[] mutants = {
                replaceOnce(run,
                        ".pWaitSemaphores(stack.longs(imageReuseTimelineSemaphore))",
                        ".pWaitSemaphores(stack.longs(slot.graphicsSemaphore))"),
                replaceOnce(run,
                        ".pWaitDstStageMask(stack.ints(VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT))",
                        ".pWaitDstStageMask(stack.ints(VK13.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT))"),
                replaceOnce(run,
                        "timelineInfo.pSignalSemaphoreValues(stack.longs(0L));",
                        "timelineInfo.pSignalSemaphoreValues(stack.longs(1L));"),
                replaceFirst(run,
                        "imageReuseSequence.cancel(reuseTicket);",
                        "// cancellation removed"),
                replaceSecond(run,
                        "imageReuseSequence.cancel(reuseTicket);",
                        "// cancellation removed"),
                replaceOnce(run,
                        "pendingGraphicsRelease = reuseTicket;",
                        "pendingGraphicsRelease = null;"),
                replaceOnce(run,
                        "imageReuseSequence.beginWrite()",
                        "new CrossQueueImageReuseSequence.Ticket(0L, 1L)")
        };
        for (String mutant : mutants) {
            assertFalse(hasConnectedTimelineSubmitContract(mutant),
                    "each disconnected wait/stage/value/cancel/publication mutation must be rejected");
        }
    }

    @Test
    void hazardousRunnerDestroysItsTimelineAfterDrainingTheRing() throws IOException {
        String source = Files.readString(SOURCE);
        String close = methodBody(source, "public void close()");

        int drain = close.indexOf("destroyRingResources()");
        int timelineDestroy = close.indexOf(
                "VK13.vkDestroySemaphore(device, imageReuseTimelineSemaphore, null)");
        assertTrue(drain >= 0 && timelineDestroy > drain,
                "the reuse timeline must be destroyed only after compute ring fences are drained");
    }

    @Test
    void graphicsCompletionReleaseOnlyRecordsASignal() throws IOException {
        String source = Files.readString(SOURCE);
        String method = methodBody(source, "void recordGraphicsStorageReadsComplete(");

        int signal = method.indexOf("graphics.signalSemaphore(imageReuseTimelineSemaphore, ticket.releaseValue(),");
        int publish = method.indexOf("imageReuseSequence.publishGraphicsCompletion(ticket)");
        int clear = method.indexOf("pendingGraphicsRelease = null");
        assertTrue(signal >= 0 && publish > signal && clear > publish,
                "the release must signal this runner's timeline value before publishing and clearing its ticket");
        assertFalse(method.contains(".submit(") || method.contains("vkQueueSubmit")
                        || method.contains("vkWait") || method.contains("waitIdle"),
                "recording the release must neither submit nor host-wait a queue");
    }

    private static boolean hasConnectedTimelineSubmitContract(String run) {
        String compact = run.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "")
                .replaceAll("\\s+", "");
        String begin = "CrossQueueImageReuseSequence.TicketreuseTicket=imageReuseSequence!=null"
                + "?imageReuseSequence.beginWrite():null;";
        String waitValue = "VkTimelineSemaphoreSubmitInfotimelineInfo="
                + "VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default()"
                + ".pWaitSemaphoreValues(stack.longs(reuseTicket.waitValue()));";
        String attachedWait = "submitInfo.pNext(timelineInfo.address())"
                + ".pWaitSemaphores(stack.longs(imageReuseTimelineSemaphore))"
                + ".pWaitDstStageMask(stack.ints(VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT));";
        String alignedBinaryValue = "if(graphicsWaitStageMask!=0){"
                + "timelineInfo.pSignalSemaphoreValues(stack.longs(0L));}";
        String binarySignal = "if(graphicsWaitStageMask!=0){"
                + "submitInfo.pSignalSemaphores(stack.longs(slot.graphicsSemaphore));}";
        String thrownSubmitCancel = "catch(RuntimeException|Errore){if(reuseTicket!=null){"
                + "imageReuseSequence.cancel(reuseTicket);}throwe;}";
        String failedResultCancel = "if(result!=VK13.VK_SUCCESS){if(reuseTicket!=null){"
                + "imageReuseSequence.cancel(reuseTicket);}thrownewIllegalStateException(";
        String retainSuccess = "pendingGraphicsRelease=reuseTicket;";

        int beginIndex = compact.indexOf(begin);
        int waitValueIndex = compact.indexOf(waitValue);
        int attachedWaitIndex = compact.indexOf(attachedWait);
        int alignedValueIndex = compact.indexOf(alignedBinaryValue);
        int binarySignalIndex = compact.indexOf(binarySignal);
        int submitIndex = compact.indexOf("VK13.vkQueueSubmit(backend.computeQueue().vkQueue(),submitInfo,slot.fence)");
        int retainIndex = compact.indexOf(retainSuccess);
        return !compact.contains("graphicsQueue().waitIdle()")
                && beginIndex >= 0
                && waitValueIndex > beginIndex
                && alignedValueIndex > waitValueIndex
                && attachedWaitIndex > alignedValueIndex
                && binarySignalIndex > attachedWaitIndex
                && submitIndex > binarySignalIndex
                && compact.contains(thrownSubmitCancel)
                && compact.contains(failedResultCancel)
                && countOccurrences(compact, "imageReuseSequence.cancel(reuseTicket);") == 2
                && retainIndex > submitIndex;
    }

    private static String methodBody(String source, String signature) {
        int methodStart = source.indexOf(signature);
        assertTrue(methodStart >= 0, signature + " must still exist");
        int bodyStart = source.indexOf('{', methodStart);
        int depth = 0;
        for (int i = bodyStart; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(methodStart, i + 1);
            }
        }
        throw new AssertionError("unterminated method body for " + signature);
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static String replaceOnce(String value, String before, String after) {
        int first = value.indexOf(before);
        assertTrue(first >= 0, "mutation anchor must exist: " + before);
        assertTrue(value.indexOf(before, first + before.length()) < 0,
                "mutation anchor must be unique: " + before);
        return value.substring(0, first) + after + value.substring(first + before.length());
    }

    private static String replaceFirst(String value, String before, String after) {
        int first = value.indexOf(before);
        assertTrue(first >= 0, "mutation anchor must exist: " + before);
        return value.substring(0, first) + after + value.substring(first + before.length());
    }

    private static String replaceSecond(String value, String before, String after) {
        int first = value.indexOf(before);
        assertTrue(first >= 0, "first mutation anchor must exist: " + before);
        int second = value.indexOf(before, first + before.length());
        assertTrue(second >= 0, "second mutation anchor must exist: " + before);
        assertTrue(value.indexOf(before, second + before.length()) < 0,
                "mutation anchor must occur exactly twice: " + before);
        return value.substring(0, second) + after + value.substring(second + before.length());
    }
}
