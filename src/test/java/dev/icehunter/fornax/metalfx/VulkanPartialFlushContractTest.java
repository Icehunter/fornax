package dev.icehunter.fornax.metalfx;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import com.mojang.blaze3d.vulkan.VulkanTransientMemory;
import java.lang.reflect.Modifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encoder mixin and interop callers need a live Vulkan client to execute. These source
 * contracts pin the pending-batch, retirement and event-order boundaries; they do not establish
 * GPU lifetime correctness, driver scheduling, frame time or visual quality.
 */
class VulkanPartialFlushContractTest {
    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax");
    private static final String MIXIN = "mixin/vulkan/VulkanCommandEncoderPartialFlushMixin.java";
    private static final String FLUSH = "((VulkanPartialFlush) encoder).fornax$flushPending();";

    @Test
    void theMixinShadowsMatchTheClientApiDescriptors() throws Exception {
        Class<?> mixin = Class.forName(
                "dev.icehunter.fornax.mixin.vulkan.VulkanCommandEncoderPartialFlushMixin", false,
                getClass().getClassLoader());
        for (var shadow : mixin.getDeclaredFields()) {
            var target = VulkanCommandEncoder.class.getDeclaredField(shadow.getName());
            assertEquals(target.getType(), shadow.getType(), shadow.getName());
            assertTrue(Modifier.isPrivate(target.getModifiers()), shadow.getName());
        }
        assertEquals(void.class, VulkanCommandEncoder.class.getDeclaredMethod("endCommandBuffer").getReturnType());
        assertTrue(Modifier.isPrivate(VulkanCommandEncoder.class.getDeclaredMethod("endCommandBuffer").getModifiers()));
        assertEquals(VulkanQueue.class, VulkanDevice.class.getMethod("graphicsQueue").getReturnType());
        assertEquals(VulkanQueue.Submission.class, VulkanQueue.class.getMethod("beginSubmit").getReturnType());
        assertEquals(void.class, VulkanQueue.Submission.class.getMethod("close").getReturnType());
        assertEquals(void.class, VulkanTransientMemory.class.getMethod("endSubmit").getReturnType());
        assertEquals(void.class, VulkanTransientMemory.class.getMethod("beginSubmit").getReturnType());
    }

    @Test
    void dispatchPreservesThePendingCommandsAndSemaphoresOnTheGraphicsQueue() throws IOException {
        String body = flushBody();
        assertOrdered(body, "endCommandBuffer();", "transientMemory.endSubmit();",
                "submissionBuilder.close();", "submissionBuilder = device.graphicsQueue().beginSubmit();");
        assertEquals(1, occurrences(body, "submissionBuilder ="),
                "only replace the pending batch after dispatch; rebuilding it would lose semaphores");
        assertEquals(1, occurrences(body, "submissionBuilder.close();"),
                "dispatch the existing complete batch exactly once");
    }

    @Test
    void partialFlushCannotAdvanceCompletionOrRetirePoolsAndResources() throws IOException {
        String source = read(MIXIN);
        for (String field : Set.of("currentSubmitIndex", "completedSubmitIndex", "submitSemaphore",
                "commandPools", "destroyQueue", "checkpointStorage")) {
            assertFalse(source.contains(field), "partial flush must not access retirement state: " + field);
        }
        Set<String> calls = Pattern.compile("([\\w$]+)\\s*\\(").matcher(flushBody()).results()
                .map(match -> match.group(1)).collect(Collectors.toSet());
        assertEquals(Set.of("endCommandBuffer", "endSubmit", "close", "graphicsQueue", "beginSubmit"), calls,
                "no full submit, fence completion, host wait, pool reset or indirect retirement helper");
    }

    @Test
    void transientRecordingEndsBeforeDispatchAndRestartsOnTheFreshBatch() throws IOException {
        String body = flushBody();
        assertOrdered(body, "transientMemory.endSubmit();", "submissionBuilder.close();",
                "submissionBuilder = device.graphicsQueue().beginSubmit();", "transientMemory.beginSubmit();");
        assertEquals(1, occurrences(body, "transientMemory.endSubmit();"));
        assertEquals(1, occurrences(body, "transientMemory.beginSubmit();"),
                "later uploads must receive fresh transient handles");
    }

    @Test
    void theMixinIsRegisteredAndOnlyAddsTheExplicitInteropOperation() throws IOException {
        var config = JsonParser.parseString(Files.readString(Path.of("src/main/resources/fornax.mixins.json")))
                .getAsJsonObject();
        assertTrue(config.getAsJsonArray("client").asList().stream()
                .anyMatch(value -> value.getAsString().equals("vulkan.VulkanCommandEncoderPartialFlushMixin")),
                "an unregistered mixin silently removes the partial-submit API");
        String source = read(MIXIN);
        assertTrue(source.contains("@Mixin(VulkanCommandEncoder.class)"));
        assertTrue(source.contains("implements VulkanPartialFlush"));
        assertFalse(Pattern.compile("@(Inject|Redirect|Overwrite|WrapOperation)\\b").matcher(source).find(),
                "ordinary encoder submissions must retain their original behavior");
        assertTrue(read("pipeline/VulkanPartialFlush.java").contains("void fornax$flushPending();"));
    }

    @Test
    void upscaleAndFrameGenerationFlushEachEventHandoffWithoutAFullSubmit() throws IOException {
        String upscale = method(read("metalfx/MetalFxUpscalePass.java"), "private static void run(");
        assertEquals(2, occurrences(upscale, FLUSH), "upscale has copy-in and copy-back event handoffs");
        assertFalse(upscale.contains("encoder.submit();"), "a full submit host-waits an earlier frame batch");
        assertOrdered(upscale, "VulkanMetalInterop.recordIntoStream(encoder, copyIn);",
                "encoder.signalSemaphore(timeline.vkSemaphore, v,", FLUSH,
                "Objc.selector(\"encodeWaitForEvent:value:\")");
        String copyBack = upscale.substring(upscale.indexOf("encoder.waitSemaphore("));
        assertOrdered(copyBack, "encoder.waitSemaphore(", "VulkanMetalInterop.recordIntoStream(encoder, copyBack);",
                "encoder.signalSemaphore(", FLUSH, "lastVulkanSignal = v + 2;");

        String framegen = read("metalfx/FrameGenPass.java");
        for (String signature : new String[] {"private static void run(", "public static boolean copyGeneratedInto("}) {
            String body = method(framegen, signature);
            assertEquals(1, occurrences(body, FLUSH), signature + " needs its own event dispatch");
            assertFalse(body.contains("encoder.submit();"));
            assertOrdered(body, "VulkanMetalInterop.recordIntoStream(encoder,", "encoder.signalSemaphore(",
                    FLUSH, "lastVulkanSignal =");
        }
    }

    @Test
    void hardSyncFenceWaitsAndThePresenterKeepFullSubmission() throws IOException {
        String upscale = method(read("metalfx/MetalFxUpscalePass.java"), "private static void run(");
        assertTrue(upscale.contains("if (HARD_SYNC) {\n            VulkanMetalInterop.recordAndFlush(encoder, copyIn);"));
        assertTrue(upscale.contains("if (HARD_SYNC) {\n            VulkanMetalInterop.recordAndFlush(encoder, copyBack);"));
        String sync = method(read("metalfx/VulkanMetalInterop.java"), "public static void recordAndFlush(");
        assertOrdered(sync, "encoder.createFence()", "encoder.submit();", "fence.awaitCompletion(");
        assertFalse(sync.contains("fornax$flushPending"), "ordinary fences signal only at a full-submit epoch");
        String present = method(read("pass/FrameGenPresenter.java"), "public static void presentGeneratedIfReady(");
        assertTrue(present.contains("encoder.submit();"), "present keeps its completion and retirement boundary");
        assertFalse(present.contains("fornax$flushPending"));
    }

    private static String flushBody() throws IOException {
        return method(read(MIXIN), "public void fornax$flushPending(");
    }

    private static String read(String path) throws IOException {
        Path file = SOURCE.resolve(path);
        assertTrue(Files.isRegularFile(file), "missing production interop operation: " + path);
        return Files.readString(file).replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "");
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing production scope: " + signature);
        int open = source.indexOf('{', start);
        int depth = 1;
        for (int i = open + 1; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(open + 1, i);
        }
        throw new AssertionError("unclosed production scope: " + signature);
    }

    private static int occurrences(String source, String token) {
        return source.split(Pattern.quote(token), -1).length - 1;
    }

    private static void assertOrdered(String source, String... tokens) {
        int previous = -1;
        for (String token : tokens) {
            int next = source.indexOf(token, previous + 1);
            assertTrue(next > previous, "missing or misordered interop operation: " + token);
            previous = next;
        }
    }
}
