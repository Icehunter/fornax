package dev.icehunter.fornax.rt.vulkan;

import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs a kernel on the real device that evaluates GLSL {@code %} and the sign-folded wrap the
 * engine's shaders use, for every {@code a} in [-64, 64) and several divisors, and compares both
 * against {@link Math#floorMod}. The folded form must agree everywhere: every voxel-window lookup
 * depends on it. Plain {@code %} is only reported, since what a driver returns for a negative left
 * operand differs between drivers (docs/ARCHITECTURE.md §12). Skips without a Vulkan device.
 */
class SignedModuloProbeTest {

    private static final int[] DIVISORS = {3, 9, 16, 33};
    private static final int RANGE = 128; // a = i % RANGE - RANGE / 2
    private static final String KERNEL = """
            #version 450
            layout(local_size_x = 64) in;
            layout(std430, set = 0, binding = 0) writeonly buffer Out { int words[]; };
            int fold(int a, int d) { return a >= 0 ? a % d : d - 1 - ((-1 - a) % d); }
            void main() {
                int i = int(gl_GlobalInvocationID.x);
                if (i >= 128 * 4) return;
                int a = (i % 128) - 64;
                int d = ivec4(3, 9, 16, 33)[i / 128];
                words[i * 2] = a % d;
                words[i * 2 + 1] = fold(a, d);
            }
            """;

    @Test
    void theSignFoldedWrapMatchesFloorModOnTheRealDevice() {
        try (HeadlessVulkan vk = HeadlessVulkan.tryCreate()) {
            assumeTrue(vk != null, "no Vulkan device");
            int entries = RANGE * DIVISORS.length;
            ByteBuffer spirv = ComputeShaderCompiler.compileToSpirv(KERNEL, "signed_modulo_probe.comp");
            ByteBuffer results;
            try {
                int[] types = {VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
                HeadlessVulkan.Buffer out = vk.hostBuffer(null, entries * 2L * 4, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                long module = vk.shaderModule(spirv);
                long setLayout = vk.descriptorSetLayout(types);
                long layout = vk.pipelineLayout(setLayout, 0);
                long pipeline = vk.computePipeline(module, layout);
                long set = vk.descriptorSet(setLayout, types, new Object[] {out});
                VkCommandBuffer cmd = vk.begin();
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, new long[] {set}, null);
                VK10.vkCmdDispatch(cmd, entries / 64, 1, 1);
                vk.shaderToHost(cmd);
                vk.submitAndWait(cmd);
                results = out.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            } finally {
                MemoryUtil.memFree(spirv);
            }

            int plainMismatches = 0, plainLooksUnsigned = 0, plainLooksTruncated = 0;
            List<String> foldFailures = new ArrayList<>();
            for (int i = 0; i < entries; i++) {
                int a = (i % RANGE) - RANGE / 2;
                int d = DIVISORS[i / RANGE];
                int plain = results.getInt(i * 8);
                int folded = results.getInt(i * 8 + 4);
                int expected = Math.floorMod(a, d);
                if (folded != expected) foldFailures.add(a + " % " + d + " -> " + folded + " (want " + expected + ")");
                if (plain != expected) {
                    plainMismatches++;
                    if (plain == (int) ((a & 0xFFFFFFFFL) % d)) plainLooksUnsigned++;
                    if (plain == a % d) plainLooksTruncated++;
                }
            }
            System.out.println("[SignedModuloProbe] " + vk.deviceName + " queue family " + vk.queueFamily
                    + ": plain % disagrees with floorMod on " + plainMismatches + " of " + entries
                    + " (" + plainLooksUnsigned + " as unsigned modulo, " + plainLooksTruncated + " as truncated remainder)");
            assertEquals(List.of(), foldFailures, "the sign-folded wrap must match Math.floorMod on every device");
        }
    }
}
