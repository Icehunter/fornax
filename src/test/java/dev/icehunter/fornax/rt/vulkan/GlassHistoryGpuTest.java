package dev.icehunter.fornax.rt.vulkan;

import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Executes the complete optional pack history shader on a GPU. Integer texelFetch inputs are
 * adapted to storage-image loads; no filtering is involved. This tests shader arithmetic, real
 * history formats and ping-pong reuse, not client descriptors, graph scheduling or appearance. */
class GlassHistoryGpuTest {
    private static final int SIDE = 16;
    private static final float NEAR = .05f;
    // Native TAA phases from CameraJitter; radiance multipliers have an exact four-frame mean of3.
    private static final float[][] PHASES = {{-.25f, -.25f}, {.25f, -.25f}, {-.25f, .25f}, {.25f, .25f}};
    private static final float[] NOISE = {1, 3, 2, 6};
    private static final int[] HISTORY_FORMATS = {VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
            VK10.VK_FORMAT_R16G16B16A16_SFLOAT, VK10.VK_FORMAT_R32G32B32A32_SFLOAT,
            VK10.VK_FORMAT_R32G32B32A32_SFLOAT, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
            VK10.VK_FORMAT_R8G8B8A8_UNORM};
    private record Result(float minAge, float maxAge, float maximumError, float contrast,
                          float depthResetAge, float materialResetAge, float translationResetAge) { }

    @Test void savedScreenHistoryFailuresAndAnyActiveHistoryPassMeetTheirDeclaredContract() throws IOException {
        Path pack = Path.of("../plague").toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(pack.resolve("shaders")), "optional pack absent");
        Path before = pack.resolve("tools/out/glass-runtime/temporal-regression-before");
        StringBuilder report = new StringBuilder();
        try (HeadlessVulkan vk = HeadlessVulkan.tryCreate()) {
            assumeTrue(vk != null, "no headless Vulkan device");
            report.append("device: ").append(vk.deviceName).append('\n');
            if (Files.isRegularFile(before.resolve("glass_caustic_accum.comp"))) {
                Result oldMaterial = sequence(vk, kernel(pack, before), true, false, false);
                Result oldGrid = sequence(vk, kernel(pack, before), false, false, false);
                report.append("saved-before varying material: ").append(oldMaterial).append('\n');
                report.append("saved-before constant material: ").append(oldGrid).append('\n');
                writeReport(pack, report);
                assertTrue(oldMaterial.minAge < 4, "saved strict material equality must reproduce history resets");
                assertTrue(oldGrid.maximumError > 1, "saved jittered point must reproduce spatial history diffusion");
            } else {
                report.append("SKIP saved-before comparison: local evidence absent\n");
            }
            Path originBefore = pack.resolve("tools/out/glass-runtime/temporal-origin-assumption");
            if (Files.isRegularFile(originBefore.resolve("glass_caustic_accum.comp"))) {
                Result origin = sequence(vk, kernel(pack, originBefore), false, false, true);
                report.append("saved origin-zero with translated projection: ").append(origin).append('\n');
                writeReport(pack, report);
                assertTrue(origin.maximumError > 1, "saved origin-zero ray must reproduce view-bob history diffusion");
            } else {
                report.append("SKIP saved origin-zero comparison: local evidence absent\n");
            }
            String graph = Files.readString(pack.resolve("graph.toml"));
            boolean screenHistoryActive = Pattern.compile("shader\\s*=\\s*\"shaders/compute/glass_caustic_accum\\.comp\"")
                    .matcher(graph).find();
            if (!screenHistoryActive) {
                assertTrue(Pattern.compile("shader\\s*=\\s*\"shaders/compute/glass_photon_cache\\.comp\"")
                        .matcher(graph).find(), "removing RGB history requires the source-space transport pass");
                report.append("INACTIVE:live screen RGB history is absent from the graph; saved failures only.\n")
                        .append("Live world-photon ownership is exercised separately by GlassPhotonCacheGpuTest.\n");
                writeReport(pack, report);
                return;
            }
            for (boolean translatedProjection : new boolean[]{false, true})
            for (boolean varying : new boolean[]{false, true}) {
                Result current = sequence(vk, kernel(pack, null), varying, true, translatedProjection);
                report.append("live varyingMaterial=").append(varying)
                        .append(" translatedProjection=").append(translatedProjection).append(": ").append(current).append('\n');
                writeReport(pack, report);
                assertEquals(4, current.minAge, 1e-3, "every interior pixel retains four phases");
                assertEquals(4, current.maxAge, 1e-3, "age does not count spatial donors as extra frames");
                // Four binary16 ULPs at maximum expected value24 allow storage and mix roundoff.
                assertTrue(current.maximumError <= .0625f, "stationary checker must preserve its per-pixel mean: " + current);
                assertTrue(current.contrast >= 20.9f, "mean3 times checker contrast7 must remain21");
                assertEquals(1, current.depthResetAge, 1e-3, "changed receiver plane discards history");
                assertEquals(1, current.materialResetAge, 1e-3, "disjoint material footprint discards history");
                assertEquals(1, current.translationResetAge, 1e-3, "translated eye keeps the explicit RGB-history reset");
            }
        }
    }

    private static void writeReport(Path pack, StringBuilder report) throws IOException {
        Path path = pack.resolve("tools/out/glass-runtime/gpu-history-results.txt");
        Files.createDirectories(path.getParent());
        Files.writeString(path, report + "Limits: integer sampler adapter; no client graph, POM raster or FPS measurement.\n");
    }

    private static Result sequence(HeadlessVulkan vk, String source, boolean varying, boolean resets, boolean translatedProjection) {
        ByteBuffer spirv = ComputeShaderCompiler.compileToSpirv(source, "glass_history_probe.comp");
        try {
            int[] types = new int[20];
            Arrays.fill(types, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            types[0] = types[1] = VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            var globals = vk.hostBuffer(null, 320, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            var unused = vk.hostBuffer(null, 16, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            Object[] resources = new Object[20];
            resources[0] = globals; resources[1] = unused;
            List<HeadlessVulkan.Image> images = new ArrayList<>();
            for (int binding : new int[]{2, 3, 4, 5, 10, 11}) {
                int format = binding == 4 || binding == 5
                        ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
                var image = vk.storageImage(SIDE, SIDE, format);
                resources[binding] = image; images.add(image);
            }
            HeadlessVulkan.Image[][] history = new HeadlessVulkan.Image[2][6];
            for (int bank = 0; bank < 2; bank++) for (int lane = 0; lane < 6; lane++) {
                history[bank][lane] = vk.storageImage(lane == 3 ? 1 : SIDE, lane == 3 ? 1 : SIDE, HISTORY_FORMATS[lane]);
                images.add(history[bank][lane]);
            }
            var initialize = vk.begin();
            for (var image : images) {
                initialLayout(initialize, image);
                upload(vk, initialize, image, new float[image.width() * image.height() * 4]);
            }
            transferToShader(initialize);
            vk.submitAndWait(initialize);
            long setLayout = vk.descriptorSetLayout(types), layout = vk.pipelineLayout(setLayout, 0);
            long pipeline = vk.computePipeline(vk.shaderModule(spirv), layout);
            float minAge = Float.POSITIVE_INFINITY, maxAge = 0, error = 0, contrast = 0;
            float depthReset = 0, materialReset = 0, translationReset = 0;
            for (int frame = 0; frame < (resets ? 7 : 4); frame++) {
                int inputBank = frame & 1, outputBank = 1 - inputBank;
                for (int lane = 0; lane < 6; lane++) {
                    // Shader bindings6..9 are light/surface/clock, then12..13 material metadata.
                    resources[lane < 4 ? 6 + lane : 8 + lane] = history[inputBank][lane];
                    resources[14 + lane] = history[outputBank][lane];
                }
                writeGlobals(globals.mapped(), frame, translatedProjection);
                var command = vk.begin();
                shaderToTransfer(command);
                for (int binding : new int[]{2, 3, 4, 5, 10, 11})
                    upload(vk, command, (HeadlessVulkan.Image) resources[binding], input(binding, frame, varying));
                transferToShader(command);
                long set = vk.descriptorSet(setLayout, types, resources);
                VK10.vkCmdBindPipeline(command, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                VK10.vkCmdBindDescriptorSets(command, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, new long[]{set}, null);
                VK10.vkCmdDispatch(command, SIDE / 8, SIDE / 8, 1);
                var localRead = vk.hostBuffer(null, SIDE * SIDE * 8L, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                var sunRead = vk.hostBuffer(null, SIDE * SIDE * 8L, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                vk.copyImageToBuffer(command, history[outputBank][0], localRead);
                vk.copyImageToBuffer(command, history[outputBank][1], sunRead);
                shaderToShader(command);
                vk.submitAndWait(command);
                ByteBuffer local = localRead.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer sun = sunRead.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                if (frame == 3) {
                    float low = 0, high = 0; int count = 0;
                    // Three-pixel inset excludes boundary footprints from the contrast statistic.
                    for (int y = 3; y < SIDE - 3; y++) for (int x = 3; x < SIDE - 3; x++) {
                        int offset = (y * SIDE + x) * 8;
                        float age = Float.float16ToFloat(sun.getShort(offset + 6));
                        float radiance = Float.float16ToFloat(local.getShort(offset));
                        minAge = Math.min(minAge, age); maxAge = Math.max(maxAge, age);
                        float base = checker(x, y);
                        error = Math.max(error, Math.abs(radiance - 3 * base));
                        // Sun uses twice the local RGB, so both history outputs are checked.
                        error = Math.max(error, Math.abs(Float.float16ToFloat(sun.getShort(offset)) - 6 * base) * .5f);
                        assertEquals(1, Float.float16ToFloat(local.getShort(offset + 6)), 0,
                                "direct-light ownership mask remains the current raw value");
                        if (base == 1) low += radiance; else high += radiance;
                        count++;
                    }
                    contrast = (high - low) / (count / 2f);
                } else if (frame > 3) {
                    float age = Float.float16ToFloat(sun.getShort(((SIDE / 2) * SIDE + SIDE / 2) * 8 + 6));
                    if (frame == 4) depthReset = age;
                    if (frame == 5) materialReset = age;
                    if (frame == 6) translationReset = age;
                }
            }
            return new Result(minAge, maxAge, error, contrast, depthReset, materialReset, translationReset);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private static float checker(int x, int y) { return ((x + y) & 1) == 0 ? 1 : 8; }
    private static float unorm(float value) { return Math.round(value * 255) / 255f; }

    private static float[] input(int binding, int frame, boolean varying) {
        float[] data = new float[SIDE * SIDE * 4];
        float[] phase = PHASES[frame % 4];
        for (int y = 0; y < SIDE; y++) for (int x = 0; x < SIDE; x++) {
            int offset = (y * SIDE + x) * 4;
            float px = (x + .5f - phase[0]) / SIDE, py = (y + .5f - phase[1]) / SIDE;
            if (binding == 2) data[offset] = NEAR / (frame >= 4 ? 3 : 4);
            if (binding == 3) {
                float nx = varying ? (px - .5f) * .2f : 0;
                data[offset] = nx; data[offset + 2] = (float) Math.sqrt(1 - nx * nx);
                data[offset + 3] = 7f / 32767f; // Geometric-normal ABI's exact positive-Z code.
            }
            if (binding == 4 || binding == 5) {
                float radiance = checker(x, y) * NOISE[frame % 4] * (binding == 5 ? 2 : 1);
                data[offset] = radiance; data[offset + 1] = radiance * .5f;
                data[offset + 2] = radiance * .25f; data[offset + 3] = 1;
            }
            if (binding == 10) {
                data[offset] = unorm(frame >= 5 ? .95f : varying ? .2f + .5f * px : .4f);
                data[offset + 1] = unorm(frame >= 5 ? .95f : varying ? .2f + .5f * py : .4f);
                data[offset + 2] = unorm(frame >= 5 ? .95f : .4f); data[offset + 3] = 1;
            }
            if (binding == 11) {
                data[offset] = unorm(frame >= 5 ? .95f : varying ? .2f + .5f * px : .4f);
                data[offset + 1] = 9f / 255f;
            }
        }
        return data;
    }

    private static void writeGlobals(ByteBuffer destination, int frame, boolean translatedProjection) {
        ByteBuffer out = destination.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        float[] phase = PHASES[frame % 4], previous = PHASES[Math.floorMod(frame - 1, 4)];
        Matrix4f p = new Matrix4f().zero().m00(1).m11(1).m23(-1).m32(NEAR);
        // A5mm projection-local translation models view bob without cameraDelta.
        // This is a synthetic counterexample, not an estimate of a client's bob amplitude.
        if (translatedProjection) p.translate(.005f, 0, 0);
        Matrix4f jittered = new Matrix4f(p).m20(-2 * phase[0] / SIDE).m21(-2 * phase[1] / SIDE);
        Matrix4f previousP = new Matrix4f(p).m20(-2 * previous[0] / SIDE).m21(-2 * previous[1] / SIDE);
        for (Matrix4f matrix : new Matrix4f[]{jittered.invert(new Matrix4f()), p.invert(new Matrix4f()), previousP, new Matrix4f()})
            for (float value : matrix.get(new float[16])) out.putFloat(value);
        out.putFloat(frame == 6 ? .125f : 0).putFloat(0).putFloat(0).putFloat(0); // Camera delta.
        for (int i = 0; i < 8; i++) out.putFloat(0); // Stable day/game clocks.
        out.putFloat(2 * phase[0] / SIDE).putFloat(2 * phase[1] / SIDE);
        out.putFloat(2 * previous[0] / SIDE).putFloat(2 * previous[1] / SIDE);
    }

    private static int pixelBytes(int format) {
        return format == VK10.VK_FORMAT_R32G32B32A32_SFLOAT ? 16
                : format == VK10.VK_FORMAT_R16G16B16A16_SFLOAT ? 8 : 4;
    }

    private static void upload(HeadlessVulkan vk, VkCommandBuffer cmd, HeadlessVulkan.Image image, float[] values) {
        ByteBuffer bytes = ByteBuffer.allocate(image.width() * image.height() * pixelBytes(image.format())).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            if (image.format() == VK10.VK_FORMAT_R32G32B32A32_SFLOAT) bytes.putFloat(value);
            else if (image.format() == VK10.VK_FORMAT_R16G16B16A16_SFLOAT) bytes.putShort(Float.floatToFloat16(value));
            else bytes.put((byte) Math.round(value * 255));
        }
        var buffer = vk.hostBuffer(bytes.array(), bytes.capacity(), VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(image.width(), image.height(), 1);
            VK10.vkCmdCopyBufferToImage(cmd, buffer.handle(), image.handle(), VK10.VK_IMAGE_LAYOUT_GENERAL, region);
        }
    }

    private static void initialLayout(VkCommandBuffer cmd, HeadlessVulkan.Image image) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(image.handle()).dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, null, null, barrier);
        }
    }

    private static void barrier(VkCommandBuffer cmd, int srcStage, int dstStage, int srcAccess, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            VK10.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, barrier, null, null);
        }
    }
    private static void transferToShader(VkCommandBuffer cmd) {
        barrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
    }
    private static void shaderToTransfer(VkCommandBuffer cmd) {
        barrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
    }
    private static void shaderToShader(VkCommandBuffer cmd) {
        barrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static String flatten(Path pack, Path before, Path file) throws IOException {
        String source = Files.readString(file);
        Matcher imports = Pattern.compile("#moj_import <fornax_runtime:([^>]+)>").matcher(source);
        StringBuffer out = new StringBuffer();
        while (imports.find()) {
            Path include = before != null && Files.isRegularFile(before.resolve(imports.group(1)))
                    ? before.resolve(imports.group(1)) : pack.resolve("shaders/include/" + imports.group(1));
            imports.appendReplacement(out, Matcher.quoteReplacement(flatten(pack, before, include)));
        }
        imports.appendTail(out);
        return out.toString();
    }

    private static String kernel(Path pack, Path before) throws IOException {
        Path file = before == null ? pack.resolve("shaders/compute/glass_caustic_accum.comp") : before.resolve("glass_caustic_accum.comp");
        String source = flatten(pack, before, file).replace("#moj_import <fornax:globals.glsl>", """
                layout(std430,set=0,binding=0) readonly buffer Globals {
                    mat4 u_InvProjModelView; mat4 u_InvProjModelViewNoJitter;
                    mat4 u_PrevProjectionMatrix; mat4 u_PrevModelViewMatrix;
                    vec4 u_CameraDelta; vec4 u_WorldClock; vec4 u_SkyState;
                    vec2 u_JitterOffset; vec2 u_PrevJitterOffset;
                };
                #define texelFetch(T,P,L) imageLoad(T,P)
                #define textureSize(T,L) imageSize(T)
                """);
        source = source.replaceAll("layout\\(set=0,binding=(2|3|10|11)\\) uniform sampler2D",
                "layout(rgba32f,set=0,binding=$1) uniform readonly image2D");
        assertTrue(!source.contains("#moj_import"), "all shader dependencies must be resolved");
        return source;
    }
}
