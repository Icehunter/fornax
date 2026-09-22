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
                "run() must merge current graphics and previous reuse timeline values into aligned arrays "
                        + "on the actual VkSubmitInfo, align its binary signal, cancel both failed-submit paths, "
                        + "and retain the successful reuse ticket");
    }

    @Test
    void timelineSubmitContractRejectsDisconnectedOrIncompleteMutations() throws IOException {
        String run = methodBody(Files.readString(SOURCE), "public long run(TargetRegistry registry");

        String[] mutants = {
                replaceOnce(run,
                        "graphicsInputTimelineSemaphore, graphicsInputValue,",
                        "slot.graphicsSemaphore, graphicsInputValue,"),
                replaceOnce(run,
                        "graphicsInputTimelineSemaphore, graphicsInputValue,",
                        "graphicsInputTimelineSemaphore, 0L,"),
                replaceOnce(run,
                        "imageReuseTimelineSemaphore, reuseTicket != null ? reuseTicket.waitValue() : 0",
                        "slot.graphicsSemaphore, reuseTicket != null ? reuseTicket.waitValue() : 0"),
                replaceOnce(run,
                        "imageReuseTimelineSemaphore, reuseTicket != null ? reuseTicket.waitValue() : 0",
                        "imageReuseTimelineSemaphore, 0L"),
                replaceOnce(run,
                        "if (!waits.isEmpty()) {",
                        "if (reuseTicket != null && reuseTicket.waitValue() != 0) {"),
                replaceOnce(run,
                        "LongBuffer waitSemaphores = stack.mallocLong(waits.size());",
                        "LongBuffer waitSemaphores = stack.mallocLong(1);"),
                replaceOnce(run,
                        "LongBuffer waitValues = stack.mallocLong(waits.size());",
                        "LongBuffer waitValues = stack.mallocLong(1);"),
                replaceOnce(run,
                        "IntBuffer waitStages = stack.mallocInt(waits.size());",
                        "IntBuffer waitStages = stack.mallocInt(1);"),
                replaceOnce(run,
                        "waitSemaphores.put(i, waits.get(i).semaphore());",
                        "waitSemaphores.put(i, waits.get(0).semaphore());"),
                replaceOnce(run,
                        "waitValues.put(i, waits.get(i).value());",
                        "waitValues.put(i, waits.get(0).value());"),
                replaceOnce(run,
                        "waitStages.put(i, capture != null ? VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT",
                        "waitStages.put(i, capture != null ? VK13.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT"),
                replaceOnce(run,
                        ": VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);",
                        ": VK13.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);"),
                replaceOnce(run,
                        ".pWaitSemaphoreValues(waitValues)",
                        ".pWaitSemaphoreValues(stack.longs(reuseTicket.waitValue()))"),
                replaceOnce(run,
                        ".pWaitSemaphores(waitSemaphores)",
                        ".pWaitSemaphores(stack.longs(imageReuseTimelineSemaphore))"),
                replaceOnce(run,
                        ".pWaitDstStageMask(waitStages)",
                        ".pWaitDstStageMask(stack.ints(VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT))"),
                replaceOnce(run,
                        "submitInfo.pNext(timelineInfo.address())",
                        "submitInfo.pNext(0L)"),
                replaceOnce(run,
                        "timelineInfo.pSignalSemaphoreValues(stack.longs(0L));",
                        "timelineInfo.pSignalSemaphoreValues(stack.longs(1L));"),
                replaceOnce(run,
                        "submitInfo.pSignalSemaphores(stack.longs(slot.graphicsSemaphore));",
                        "submitInfo.pSignalSemaphores(stack.longs(imageReuseTimelineSemaphore));"),
                replaceOnce(run,
                        "VK13.vkQueueSubmit(backend.computeQueue().vkQueue(), submitInfo, slot.fence)",
                        "VK13.vkQueueSubmit(backend.computeQueue().vkQueue(), VkSubmitInfo.calloc(stack), slot.fence)"),
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

    @Test
    void graphicsStreamBranchPrecedesTheComputeQueueFenceSubmitChain() throws IOException {
        String source = Files.readString(SOURCE);
        String run = methodBody(source, "public long run(TargetRegistry registry");

        int branch = run.indexOf("if (graphicsStream) {");
        int returnCall = run.indexOf("return runInGraphicsStream(", branch);
        int submit = run.indexOf("VK13.vkQueueSubmit(backend.computeQueue().vkQueue(),");
        assertTrue(branch >= 0 && returnCall > branch && submit > returnCall,
                "a graphics-stream pass must return out of run() before it ever reaches the "
                        + "compute-queue fence/submit chain");
    }

    @Test
    void runInGraphicsStreamRecordsIntoTheGraphicsEncoderWithAWidenedReleaseBarrier() throws IOException {
        String source = Files.readString(SOURCE);
        String method = methodBody(source, "private long runInGraphicsStream(TargetRegistry registry");

        assertTrue(method.contains("VulkanMetalInterop.recordIntoStream("),
                "must record into Blaze3D's graphics encoder, not submit to the compute queue");
        assertTrue(method.contains("VK13.vkCmdDispatch("));
        assertTrue(method.contains("VK13.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT")
                        && method.contains("VK13.VK_PIPELINE_STAGE_TRANSFER_BIT")
                        && method.contains("VK13.VK_ACCESS_TRANSFER_READ_BIT"),
                "the release barrier must widen past COMPUTE_SHADER: same-queue readers here "
                        + "include a fragment sampler and a RAY_QUERY transfer copy");
    }

    private static boolean hasConnectedTimelineSubmitContract(String run) {
        String compact = run.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "")
                .replaceAll("\\s+", "");
        String begin = "CrossQueueImageReuseSequence.TicketreuseTicket=imageReuseSequence!=null"
                + "?imageReuseSequence.beginWrite():null;";
        String mergedWaits = "List<GraphicsInputDependency.Wait>waits=GraphicsInputDependency.waits("
                + "graphicsInputTimelineSemaphore,graphicsInputValue,"
                + "imageReuseTimelineSemaphore,reuseTicket!=null?reuseTicket.waitValue():0);";
        String alignedWaitArrays = "if(!waits.isEmpty()){"
                + "LongBufferwaitSemaphores=stack.mallocLong(waits.size());"
                + "LongBufferwaitValues=stack.mallocLong(waits.size());"
                + "IntBufferwaitStages=stack.mallocInt(waits.size());"
                + "for(inti=0;i<waits.size();i++){"
                + "waitSemaphores.put(i,waits.get(i).semaphore());"
                + "waitValues.put(i,waits.get(i).value());"
                + "waitStages.put(i,capture!=null?VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT"
                + ":VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);}";
        String waitValue = "VkTimelineSemaphoreSubmitInfotimelineInfo="
                + "VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default()"
                + ".pWaitSemaphoreValues(waitValues);";
        String attachedWait = "submitInfo.pNext(timelineInfo.address())"
                + ".pWaitSemaphores(waitSemaphores).pWaitDstStageMask(waitStages);";
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
        // Contiguity pins the actual arrays and timeline structure inside the same nonempty-wait
        // branch. Matching isolated identifiers elsewhere would accept a disconnected submission.
        String connectedWait = mergedWaits + alignedWaitArrays + waitValue + alignedBinaryValue
                + attachedWait + "}" + binarySignal;
        int connectedWaitIndex = compact.indexOf(connectedWait);
        int submitIndex = compact.indexOf("VK13.vkQueueSubmit(backend.computeQueue().vkQueue(),submitInfo,slot.fence)");
        int thrownCancelIndex = compact.indexOf(thrownSubmitCancel);
        int failedCancelIndex = compact.indexOf(failedResultCancel);
        int retainIndex = compact.indexOf(retainSuccess);
        return !compact.contains("graphicsQueue().waitIdle()")
                && beginIndex >= 0
                && connectedWaitIndex == beginIndex + begin.length()
                && submitIndex > connectedWaitIndex + connectedWait.length()
                && thrownCancelIndex > submitIndex
                && failedCancelIndex > thrownCancelIndex
                && countOccurrences(compact, "imageReuseSequence.cancel(reuseTicket);") == 2
                && retainIndex > failedCancelIndex;

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
