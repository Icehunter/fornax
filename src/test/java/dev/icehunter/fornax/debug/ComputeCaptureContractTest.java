package dev.icehunter.fornax.debug;

import com.mojang.blaze3d.buffers.GpuBuffer;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Raw dispatch execution requires a client; source contracts pin queue/binding ordering, while
 * byte/range/status tests exercise the capture's pure boundary operations. Neither proves GPU use. */
class ComputeCaptureContractTest {
    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax");

    @Test
    void graphicsStreamCaptureBracketsItsOwnDispatchWithoutSubmittingTheFrame() throws Exception {
        String source = Files.readString(SOURCE.resolve("pack/graph/ComputePassRunner.java"));
        int start = source.indexOf("private long runInGraphicsStream(");
        String body = source.substring(start, source.indexOf("private long publishGraphicsInputs()", start));
        assertTrue(body.contains("ComputeCapture.begin("), "graphics dispatch must claim its selected capture");
        assertTrue(body.contains("options, globals, capture)"));
        int before = body.indexOf("capture.beforeDispatch(");
        int dispatch = body.indexOf("VK13.vkCmdDispatch(");
        int after = body.indexOf("capture.afterDispatch(");
        assertTrue(before >= 0 && before < dispatch && dispatch < after);
        assertTrue(body.contains("encoder.createFence()"));
        assertFalse(body.contains("encoder.submit()"), "capture must preserve the normal submission schedule");
        String selection = Files.readString(SOURCE.resolve("debug/FullscreenCapture.java"));
        int frameStart = selection.indexOf("static void beginFrame(Path game,");
        String frame = selection.substring(frameStart, selection.indexOf("public static boolean isSelected", frameStart));
        assertTrue(frame.indexOf("pollGraphicsCaptures()") >= 0);
        assertTrue(frame.indexOf("pollGraphicsCaptures()") < frame.indexOf("if (inFlight != null"));
    }

    @Test
    void graphicsCaptureRetainsStagingUntilCompletionAndRetiresExactlyOnce() throws Exception {
        Class<?> type = assertDoesNotThrow(() -> Class.forName("dev.icehunter.fornax.debug.ComputeCapture$GraphicsRetirement"));
        int[] retired = {0}, closed = {0}, waited = {0};
        boolean[] completed = {false};
        var fence = new com.mojang.blaze3d.buffers.GpuFence() {
            public boolean awaitCompletion(long timeout) { waited[0]++; return completed[0]; }
            public void close() { closed[0]++; }
        };
        Object pending = type.getConstructor(com.mojang.blaze3d.buffers.GpuFence.class, Runnable.class)
                .newInstance(fence, (Runnable) () -> retired[0]++);
        Method poll = type.getMethod("retire", long.class);
        assertEquals(false, poll.invoke(pending, 0L));
        assertEquals(false, poll.invoke(pending, ComputeCapture.FENCE_TIMEOUT_NANOS));
        assertEquals(0, retired[0]);
        assertEquals(0, closed[0]);
        completed[0] = true;
        assertEquals(true, poll.invoke(pending, 0L));
        assertEquals(true, poll.invoke(pending, 0L));
        assertEquals(1, retired[0]);
        assertEquals(1, closed[0]);
        assertEquals(3, waited[0]);
    }

    @Test
    void throwingGraphicsFenceRetainsStagingUntilALaterConfirmedCompletion() {
        int[] retired = {0}, closed = {0}, waits = {0};
        var fence = new com.mojang.blaze3d.buffers.GpuFence() {
            public boolean awaitCompletion(long timeout) {
                if (waits[0]++ == 0) throw new IllegalStateException("uncertain graphics submission");
                return true;
            }
            public void close() { closed[0]++; }
        };
        var pending = new ComputeCapture.GraphicsRetirement(fence, () -> retired[0]++);
        assertFalse(pending.retire(0L));
        assertEquals(0, retired[0]);
        assertEquals(0, closed[0]);
        assertTrue(pending.retire(0L));
        assertTrue(pending.retire(0L));
        assertEquals(1, retired[0]);
        assertEquals(1, closed[0]);
        assertEquals(2, waits[0]);
    }

    @Test
    void explicitBindingFilterPreservesExactHistoryNamesAndCannotClaimReplay(@TempDir Path game) throws Exception {
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/fornax-capture.json"),
                "{\"pass\":\"selected\",\"bindings\":{\"selected\":[\"history.history\",\"output\"]}}");
        FullscreenCapture.request();
        FullscreenCapture.beginFrame(game, Map.of());
        var capture = FullscreenCapture.beginCompute("selected", "selected.comp");
        try {
            Method includes = assertDoesNotThrow(() -> capture.getClass().getDeclaredMethod("includesBinding", String.class));
            includes.setAccessible(true);
            assertEquals(true, includes.invoke(capture, "history.history"));
            assertEquals(true, includes.invoke(capture, "output"));
            assertEquals(false, includes.invoke(capture, "history"));
            assertEquals(false, includes.invoke(capture, "builtin.blockAtlas"));
            capture.computeDispatched();
        } finally {
            capture.computeRetired(true);
            FullscreenCapture.endFrame();
        }
        assertEquals(false, capture.computeData().get("replayComplete"));
    }

    @Test
    void partialCopyFailureStillOrdersCopiesBeforeLaterWrites() throws Exception {
        String source = Files.readString(SOURCE.resolve("debug/ComputeCapture.java"));
        int before = source.indexOf("public void beforeDispatch(");
        int after = source.indexOf("public void afterDispatch(");
        String input = source.substring(before, after);
        String output = source.substring(after, source.indexOf("public boolean awaitFence(", after));
        assertTrue(input.contains("guard(() -> recordCopies(cmd, false));"), "failure guard ends before the copy-to-kernel barrier");
        assertTrue(output.contains("guard(() -> recordCopies(cmd, true));"), "failure guard ends before the copy-to-writer barrier");
        assertTrue(output.indexOf("VK13.VK_ACCESS_TRANSFER_READ_BIT | VK13.VK_ACCESS_TRANSFER_WRITE_BIT")
                > output.indexOf("guard(() -> recordCopies(cmd, true));"));
        assertTrue(output.contains("VK13.VK_ACCESS_MEMORY_WRITE_BIT"));
    }

    @Test
    void filtersRejectMisspelledBindingsBeforeCaptureAllocation(@TempDir Path game) throws Exception {
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/fornax-capture.json"),
                "{\"pass\":\"selected\",\"bindings\":{\"selected\":[\"typo\"]}}");
        FullscreenCapture.request();
        FullscreenCapture.beginFrame(game, Map.of());
        var capture = FullscreenCapture.beginCompute("selected", "selected.comp");
        try {
            assertThrows(IllegalArgumentException.class, () -> capture.validateBindings(List.of("actual")));
        } finally {
            capture.computeDispatched();
            capture.computeRetired(false);
            FullscreenCapture.endFrame();
        }
        String source = Files.readString(SOURCE.resolve("debug/ComputeCapture.java"));
        int bufferStart = source.indexOf("public void buffer(");
        int imageStart = source.indexOf("public void image(");
        assertTrue(source.indexOf("if (excluded(entry, ref)) return;", bufferStart)
                < source.indexOf("bufferCopyBytes(offset, range, bufferBytes)", bufferStart));
        assertTrue(source.indexOf("if (excluded(entry, ref)) return;", imageStart)
                < source.indexOf("capture.reserveBytes(", imageStart));
    }

    @Test
    void captureCopiesOnlyTheRemainingBytesWithoutChangingTheSourceCursor() throws Exception {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{9, 1, 2, 3, 8});
        source.position(1).limit(4);
        byte[] copied = (byte[]) method("copyBytes", ByteBuffer.class).invoke(null, source);
        source.put(1, (byte) 7);
        assertArrayEquals(new byte[]{1, 2, 3}, copied);
        assertEquals(1, source.position());
        assertEquals(4, source.limit());
    }

    @Test
    void onlyDispatchedFenceCompletedAndFullyCapturedBindingsAreReplayComplete() throws Exception {
        Method complete = method("replayComplete", boolean.class, boolean.class, List.class);
        List<Map<String, Object>> captured = List.of(Map.of("status", "captured"));
        assertEquals(true, complete.invoke(null, true, true, captured));
        assertEquals(false, complete.invoke(null, false, true, captured));
        assertEquals(false, complete.invoke(null, true, false, captured));
        for (String status : List.of("pending", "unavailable", "failed")) {
            assertEquals(false, complete.invoke(null, true, true, List.of(Map.of("status", status))));
        }
        assertEquals(false, complete.invoke(null, true, true, List.of()));
    }

    @Test
    void exactBufferSlicesUseTheBoundOffsetAndRejectOutOfRangeCopies() throws Exception {
        Method bytes = method("bufferCopyBytes", long.class, long.class, long.class);
        assertEquals(832L, bytes.invoke(null, 256L, 832L, 4096L));
        assertEquals(3840L, bytes.invoke(null, 256L, -1L, 4096L));
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> bytes.invoke(null, 4090L, 16L, 4096L));
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> bytes.invoke(null, -1L, 16L, 4096L));
    }

    @Test
    void readbackUsageIsOptInAndDoesNotChangeNonUniformBuffers() throws Exception {
        Class<?> type = assertDoesNotThrow(() -> Class.forName("dev.icehunter.fornax.debug.CaptureBufferUsage"));
        Method usage = type.getMethod("forCapture", int.class, boolean.class);
        int uniform = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE;
        assertEquals(uniform, usage.invoke(null, uniform, false));
        assertEquals(uniform | GpuBuffer.USAGE_COPY_SRC, usage.invoke(null, uniform, true));
        assertEquals(GpuBuffer.USAGE_VERTEX, usage.invoke(null, GpuBuffer.USAGE_VERTEX, true));
    }

    @Test
    void captureBracketsOnlySelectedDispatchAndUsesTheActualDescriptorSlice() throws Exception {
        String source = Files.readString(SOURCE.resolve("pack/graph/ComputePassRunner.java"));
        assertTrue(source.contains("FullscreenCapture.isSelected(spec.name())"));
        assertTrue(source.contains("capture.beforeDispatch(cmd, push, groupsX, groupsY, groupsZ, dispatchKernel)"));
        int before = source.indexOf("capture.beforeDispatch(cmd,");
        int dispatch = source.indexOf("VK13.vkCmdDispatch(cmd,");
        int after = source.indexOf("capture.afterDispatch(cmd)");
        assertTrue(before >= 0 && before < dispatch && dispatch < after);
        assertTrue(source.contains("capture.buffer(i, name, type, bufferInfo.get(0).buffer(),"));
        assertTrue(source.contains("bufferInfo.get(0).offset(), bufferInfo.get(0).range()"));
        assertTrue(source.contains("slot.capture.retireAfterFence()"), "timed-out readbacks retire only after the slot fence");
        String mixins = Files.readString(Path.of("src/main/resources/fornax.mixins.json"));
        assertTrue(mixins.contains("vulkan.VulkanCaptureBufferUsageMixin"));
        assertTrue(mixins.contains("vulkan.VulkanCaptureSamplerStateMixin"));
    }

    @Test
    void unavailableComputeBindingsMakeTheWholeOneShotCaptureIncomplete(@TempDir Path game) throws Exception {
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/fornax-capture.json"), "{\"pass\":\"selected\"}");
        FullscreenCapture.request();
        FullscreenCapture.beginFrame(game, Map.of());
        var capture = FullscreenCapture.beginCompute("selected", "selected.comp");
        assertFalse(FullscreenCapture.isSelected("selected"), "claiming the pass consumes its one-frame request");
        capture.computeData().put("bindings", List.of(Map.of("status", "unavailable")));
        capture.computeDispatched();
        capture.computeRetired(false);
        FullscreenCapture.endFrame();
        try (var dirs = Files.list(game.resolve("fornax-captures"))) {
            var manifest = JsonParser.parseString(Files.readString(dirs.findFirst().orElseThrow().resolve("manifest.json"))).getAsJsonObject();
            assertEquals("incomplete", manifest.get("status").getAsString());
            assertFalse(manifest.get("replayComplete").getAsBoolean());
        }
        FullscreenCapture.beginFrame(game, Map.of());
        assertFalse(FullscreenCapture.isSelected("selected"), "capture cannot repeat without another request");
    }

    @Test
    void localSizeComesFromTheActualCompiledModule() throws Exception {
        ByteBuffer spirv = dev.icehunter.fornax.pass.compute.ComputeShaderCompiler.compileToSpirv(
                "#version 450\nlayout(local_size_x=4, local_size_y=2) in; void main() {}", "capture-local-size");
        try {
            byte[] bytes = new byte[spirv.remaining()];
            spirv.duplicate().get(bytes);
            assertEquals(List.of(4, 2, 1), method("localSize", byte[].class).invoke(null, (Object) bytes));
        } finally { org.lwjgl.system.MemoryUtil.memFree(spirv); }
    }

    @Test
    void nativeSamplerSnapshotIncludesThirdAxisAndLodWithoutRetainingMutableMemory() throws Exception {
        Class<?> type = assertDoesNotThrow(() -> Class.forName("dev.icehunter.fornax.debug.CaptureSamplerState"));
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var info = org.lwjgl.vulkan.VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .addressModeU(0).addressModeV(1).addressModeW(2).mipmapMode(1).minLod(2f).maxLod(6f);
            var state = (Map<?, ?>) type.getMethod("snapshot", org.lwjgl.vulkan.VkSamplerCreateInfo.class).invoke(null, info);
            info.addressModeW(0).maxLod(1000f);
            assertEquals(2, state.get("addressModeW"));
            assertEquals(1, state.get("mipmapMode"));
            assertEquals(2f, state.get("minLod"));
            assertEquals(6f, state.get("maxLod"));
        }
    }

    private static Method method(String name, Class<?>... params) throws Exception {
        Class<?> type = assertDoesNotThrow(() -> Class.forName("dev.icehunter.fornax.debug.ComputeCapture"));
        Method method = type.getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method;
    }
}
