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
