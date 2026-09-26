package dev.icehunter.fornax.rt.vulkan;

import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Replays a captured {@code voxel_source_status} dispatch (see {@code ComputeCapture}) on a headless
 * device: the SPIR-V the game ran, the bytes it was bound to, the same group count. It also
 * recompiles the captured GLSL through the engine's compiler, as-is and with the pack's wrap helper
 * in the sign-folded form, and writes a status histogram per run next to the capture. The pack's
 * helper name is a fixture of the capture, not a contract. Skips without {@code FORNAX_CAPTURE_DIR}
 * in the environment or without a Vulkan device.
 */
class CapturedComputeReplayTest {

    private static final int RGBA16F = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

    @Test
    void voxelSourceStatusReplaysTheCapturedDispatch() throws IOException {
        String env = System.getenv("FORNAX_CAPTURE_DIR");
        assumeTrue(env != null && !env.isBlank(), "FORNAX_CAPTURE_DIR not set");
        Path dir = Path.of(env);
        assumeTrue(Files.isRegularFile(dir.resolve("voxel_source_status.spv")), "no voxel_source_status capture in " + dir);

        byte[] globals = Files.readAllBytes(dir.resolve("voxel_source_status-binding-0.bin"));
        byte[] state = Files.readAllBytes(dir.resolve("voxel_source_status-binding-1.bin"));
        byte[] summary = Files.readAllBytes(dir.resolve("voxel_source_status-binding-2.bin"));
        byte[] push = Files.readAllBytes(dir.resolve("voxel_source_status-push.bin"));
        byte[] capturedSpirv = Files.readAllBytes(dir.resolve("voxel_source_status.spv"));
        String source = Files.readString(dir.resolve("voxel_source_status.comp")).replace("\r\n", "\n");

        Map<String, String> variants = new LinkedHashMap<>();
        variants.put("recompiled-as-is", source);
        variants.put("sign-folded-wrap", source.replace(
                "int plagueSourceMod(int a, int d) { return ((a % d) + d) % d; }",
                "int plagueSourceMod(int a, int d) { return a >= 0 ? a % d : d - 1 - ((-1 - a) % d); }"));

        StringBuilder report = new StringBuilder();
        try (HeadlessVulkan vk = HeadlessVulkan.tryCreate()) {
            assumeTrue(vk != null, "no Vulkan device");
            report.append("device: ").append(vk.deviceName).append(" queue family ").append(vk.queueFamily).append('\n');
            report.append("captured-spirv: ").append(run(vk, capturedSpirv, globals, state, summary, push)).append('\n');
            for (var e : variants.entrySet()) {
                ByteBuffer spirv = ComputeShaderCompiler.compileToSpirv(e.getValue(), "voxel_source_status.comp");
                try {
                    byte[] bytes = new byte[spirv.remaining()];
                    spirv.duplicate().get(bytes);
                    report.append(e.getKey()).append(": ").append(run(vk, bytes, globals, state, summary, push)).append('\n');
                } finally {
                    MemoryUtil.memFree(spirv);
                }
            }
        }
        Files.writeString(dir.resolve("replay-results.txt"), report.toString());
        System.out.println(report);
    }

    /** Runs one module over the captured bindings and returns a histogram of the status codes it wrote. */
    private static String run(HeadlessVulkan vk, byte[] spirvBytes, byte[] globals, byte[] state, byte[] summary, byte[] push) {
        ByteBuffer spirv = MemoryUtil.memAlloc(spirvBytes.length);
        spirv.put(spirvBytes).flip();
        try {
            int[] types = {VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE};
            HeadlessVulkan.Buffer b0 = vk.hostBuffer(globals, globals.length, VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            HeadlessVulkan.Buffer b1 = vk.hostBuffer(state, state.length, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            HeadlessVulkan.Buffer b2 = vk.hostBuffer(summary, summary.length, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            HeadlessVulkan.Image out = vk.storageImage(256, 256, RGBA16F);
            HeadlessVulkan.Buffer readback = vk.hostBuffer(null, 256L * 256 * 8, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            long module = vk.shaderModule(spirv);
            long setLayout = vk.descriptorSetLayout(types);
            long layout = vk.pipelineLayout(setLayout, push.length);
            long pipeline = vk.computePipeline(module, layout);
            long set = vk.descriptorSet(setLayout, types, new Object[] {b0, b1, b2, out});

            VkCommandBuffer cmd = vk.begin();
            vk.toGeneral(cmd, out);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, new long[] {set}, null);
            if (push.length > 0) {
                ByteBuffer pushBytes = MemoryUtil.memAlloc(push.length);
                pushBytes.put(push).flip();
                try {
                    VK10.vkCmdPushConstants(cmd, layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushBytes);
                } finally {
                    MemoryUtil.memFree(pushBytes);
                }
            }
            VK10.vkCmdDispatch(cmd, 16, 16, 1);
            vk.copyImageToBuffer(cmd, out, readback);
            vk.submitAndWait(cmd);
            return histogram(readback.mapped(), 729);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private static String histogram(ByteBuffer image, int slots) {
        ByteBuffer b = image.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        Map<String, Integer> counts = new TreeMap<>();
        for (int slot = 0; slot < slots; slot++) {
            int x = slot % 256, y = slot / 256;
            int at = (y * 256 + x) * 8;
            String key = String.format("(%s,%s,%s,%s)", f16(b.getShort(at)), f16(b.getShort(at + 2)),
                    f16(b.getShort(at + 4)), f16(b.getShort(at + 6)));
            counts.merge(key, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (var e : counts.entrySet()) {
            if (shown++ == 6) { sb.append(" ... ").append(counts.size()).append(" distinct"); break; }
            sb.append(e.getKey()).append('=').append(e.getValue()).append(' ');
        }
        return sb.toString().trim();
    }

    private static String f16(short h) {
        int s = (h >> 15) & 1, e = (h >> 10) & 31, f = h & 1023;
        double v;
        if (e == 0) v = (f / 1024.0) * Math.pow(2, -14);
        else if (e == 31) v = f == 0 ? Double.POSITIVE_INFINITY : Double.NaN;
        else v = (1 + f / 1024.0) * Math.pow(2, e - 15);
        if (s == 1) v = -v;
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }
}
