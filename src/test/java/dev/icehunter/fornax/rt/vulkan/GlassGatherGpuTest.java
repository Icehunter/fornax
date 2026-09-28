package dev.icehunter.fornax.rt.vulkan;

import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Executes a saved real gather, BRDF, binning and emission sampler on measured GPU storage.
 * Receiver geometry is an analytic four-block plane; transmission is represented by supplied
 * photon records. This diagnoses reconstruction, not transport correctness or client FPS. */
class GlassGatherGpuTest {
    private static final int SIDE = 256;
    private static final Path PACK = Path.of("../plague").toAbsolutePath().normalize();
    private static final Path OUTPUT = PACK.resolve("tools/out/glass-runtime/gather-probe");
    private record Result(float[] rgb, double mean, double maximum, double p99, double darkFraction,
                          double candidates, double brdfs, double largestInverseCosine, double millis) {
        String summary() {
            return String.format(Locale.ROOT,
                    "mean=%.7f max=%.5f p99=%.5f dark=%.4f candidates/pixel=%.2f brdfs/pixel=%.2f max1/cos=%.1f instrumentedGpuMs=%.4f",
                    mean, maximum, p99, darkFraction, candidates, brdfs, largestInverseCosine, millis);
        }
    }

    @Test void sparsePhotonDisksAndMaterialVarianceAreMeasuredByTheActualGather() throws IOException {
        assumeTrue(Files.isDirectory(PACK.resolve("shaders")), "optional pack absent");
        Path baseline = OUTPUT.resolve("point-gather-baseline.comp");
        assumeTrue(Files.isRegularFile(baseline), "local before-change gather snapshot absent");
        Files.createDirectories(OUTPUT);
        StringBuilder report = new StringBuilder();
        try (HeadlessVulkan vk = HeadlessVulkan.tryCreate()) {
            assumeTrue(vk != null, "no headless Vulkan device");
            report.append("device: ").append(vk.deviceName).append('\n');
            try (Probe probe = new Probe(vk, Files.readString(baseline))) {
                Result most = null, least = null;
                for (int sources : new int[]{32, 128, 512, 4096}) {
                    int quota = 32768 / sources;
                    Result result = probe.run(quota, 0, 0, 9, 0, 0);
                    report.append("one local face; eligibleSources=").append(sources).append(" quota=").append(quota)
                            .append(' ').append(result.summary()).append('\n');
                    saveImage("quota-" + quota + "-diffuse", result.rgb);
                    if (sources == 32) most = result;
                    if (sources == 4096) least = result;
                }
                assertTrue(least.darkFraction > most.darkFraction,
                        "same source face must expose more unlit receiver area as the global quota falls");
                Result single = probe.run(1, 2, 0, 9, 0, 0);
                report.append("single photon: ").append(single.summary()).append('\n');
                // The radius .25 disk covers pi*.25^2/16 of this four-by-four block receiver.
                assertTrue(single.darkFraction > .98 && single.darkFraction < 1,
                        "baseline must reproduce the photographed isolated quarter-block kernel");
                saveImage("single-kernel", single.rgb);
                for (int count : new int[]{512, 4096, 32768}) {
                    for (int[] material : new int[][]{{0, 9}, {230, 9}, {230, 230}}) {
                        Result a = probe.run(count, 1, material[0], material[1], 1, 0);
                        Result b = probe.run(count, 1, material[0], material[1], 1, .01f);
                        double squared = 0, maximum = 0;
                        for (int i = 0; i < a.rgb.length; i++) {
                            double delta = Math.abs(a.rgb[i] - b.rgb[i]);
                            squared += delta * delta; maximum = Math.max(maximum, delta);
                        }
                        String name = "dense-" + count + "-smooth-" + material[0] + "-f0-" + material[1];
                        report.append(name).append(' ').append(a.summary())
                                .append(String.format(Locale.ROOT, " phaseShiftRms=%.5f phaseShiftMax=%.5f%n",
                                        Math.sqrt(squared / a.rgb.length), maximum));
                        saveImage(name, a.rgb);
                    }
                }
                Result grazing = probe.run(512, 3, 0, 9, 1, 0);
                report.append("controlled grazing .001 geometric cosine: ").append(grazing.summary()).append('\n');
                assertTrue(grazing.largestInverseCosine > 900, "normal-map shading retains the geometric cosine division");
                saveImage("grazing-normal-map", grazing.rgb);
                Path room = PACK.resolve("tools/out/glass-runtime/saved-room.json");
                if (Files.isRegularFile(room)) {
                    JsonObject measured = JsonParser.parseString(Files.readString(room)).getAsJsonObject()
                            .getAsJsonObject("texture_statistics");
                    for (String name : new String[]{"stone", "bricks", "iron_block", "diamond_block", "raw_gold_block"}) {
                        if (measured == null || !measured.has(name + "_n") || !measured.has(name + "_s")) continue;
                        probe.measuredAlbedo = samples(measured, name);
                        probe.measuredNormal = samples(measured, name + "_n");
                        probe.measuredMaterial = samples(measured, name + "_s");
                        Result result = probe.run(512, 1, 0, 0, 1, 0);
                        report.append("measured sixteen texels ").append(name).append(' ').append(result.summary()).append('\n');
                        saveImage("measured-" + name, result.rgb);
                    }
                }
            }
        } finally {
            report.append("Limits: synthetic planar photon records; current sampler; saved point bin and complete gather/BRDF.\n")
                    .append("Materials are controlled labPBR bytes, not a client Gbuffer dump. Normal slope uses saved bumpStrength1.45.\n")
                    .append("Images show x/(1+x) then gamma1/2.2, not the pack post stack. Timings include probe counters, exclude client graph, transport and presentation.\n");
            Files.writeString(OUTPUT.resolve("results.txt"), report);
        }
    }

    private static final class Probe implements AutoCloseable {
        private final HeadlessVulkan vk;
        private final HeadlessVulkan.Buffer globals, photons, heads, links, cache, dummy;
        private final HeadlessVulkan.Image depth, normal, albedo, material, local, sun, stats;
        private final long gatherLayout, gatherSet, gatherPipeline, emitLayout, emitSet, emitPipeline;
        private final long binLayout, binSet, binPipeline, queryPool;
        private final float timestampPeriod;
        private final ByteBuffer gatherSpirv, emitSpirv, binSpirv;
        private int[][] measuredAlbedo, measuredNormal, measuredMaterial;

        Probe(HeadlessVulkan vk, String source) throws IOException {
            this.vk = vk;
            globals = vk.hostBuffer(null, 176, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            photons = vk.hostBuffer(null, 65536 * 64L, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            heads = vk.hostBuffer(null, 65536 * 4L, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            links = vk.hostBuffer(null, 65536 * 4L, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            cache = vk.hostBuffer(null, 64, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            dummy = vk.hostBuffer(null, 16, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            depth = inputImage(vk); normal = inputImage(vk); albedo = inputImage(vk); material = inputImage(vk);
            local = vk.storageImage(SIDE, SIDE, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
            sun = vk.storageImage(SIDE, SIDE, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
            stats = inputImage(vk);
            var initialize = vk.begin();
            for (var image : new HeadlessVulkan.Image[]{depth, normal, albedo, material, local, sun, stats})
                initializeImage(initialize, image);
            vk.submitAndWait(initialize);

            int[] types = new int[20]; Arrays.fill(types, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            Object[] resources = new Object[20]; Arrays.fill(resources, dummy);
            resources[0] = globals; resources[5] = photons; resources[6] = heads; resources[7] = links; resources[16] = cache;
            int[] imageBindings = {2, 3, 4, 15, 17, 18, 19};
            var images = new HeadlessVulkan.Image[]{depth, normal, albedo, material, local, sun, stats};
            for (int i = 0; i < imageBindings.length; i++) {
                types[imageBindings[i]] = VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; resources[imageBindings[i]] = images[i];
            }
            long gatherSetLayout = vk.descriptorSetLayout(types);
            gatherLayout = vk.pipelineLayout(gatherSetLayout, 0);
            gatherSet = vk.descriptorSet(gatherSetLayout, types, resources);
            gatherSpirv = ComputeShaderCompiler.compileToSpirv(adaptGather(source), "gather_probe.comp");
            gatherPipeline = vk.computePipeline(vk.shaderModule(gatherSpirv), gatherLayout);

            int[] emitTypes = {VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
            long emitSetLayout = vk.descriptorSetLayout(emitTypes);
            emitLayout = vk.pipelineLayout(emitSetLayout, 0);
            emitSet = vk.descriptorSet(emitSetLayout, emitTypes, new Object[]{globals, photons});
            emitSpirv = ComputeShaderCompiler.compileToSpirv(flatten(emissionSource()), "gather_emit_probe.comp");
            emitPipeline = vk.computePipeline(vk.shaderModule(emitSpirv), emitLayout);

            int[] binTypes = new int[4]; Arrays.fill(binTypes, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            long binSetLayout = vk.descriptorSetLayout(binTypes);
            binLayout = vk.pipelineLayout(binSetLayout, 0);
            binSet = vk.descriptorSet(binSetLayout, binTypes, new Object[]{photons, dummy, heads, links});
            String binSource = Files.readString(OUTPUT.resolve("point-bin-baseline.comp"))
                    .replace("layout(set=0,binding=1) uniform sampler2D u_Depth;", "");
            binSpirv = ComputeShaderCompiler.compileToSpirv(flatten(binSource), "gather_bin_probe.comp");
            binPipeline = vk.computePipeline(vk.shaderModule(binSpirv), binLayout);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
                VK10.vkGetPhysicalDeviceProperties(vk.physical, properties);
                timestampPeriod = properties.limits().timestampPeriod();
                VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                        .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(2);
                var result = stack.mallocLong(1);
                assertTrue(VK10.vkCreateQueryPool(vk.device, info, null, result) == VK10.VK_SUCCESS);
                queryPool = result.get(0);
            }
        }

        Result run(int count, int mode, float smoothness, float f0, int normalMap, float phase) {
            ByteBuffer data = globals.mapped().order(ByteOrder.LITTLE_ENDIAN);
            float[] matrix = new float[16];
            new Matrix4f().scaling(2, 2, 1).m32(-3.5f).get(matrix);
            for (int i = 0; i < 16; i++) data.putFloat(i * 4, matrix[i]);
            new Matrix4f().get(matrix);
            for (int i = 0; i < 16; i++) data.putFloat(64 + i * 4, matrix[i]);
            data.putFloat(156, 1); // Non-surface dimension disables irrelevant direct-sun ownership.
            data.putFloat(160, count); data.putFloat(164, mode);
            float[] d = new float[SIDE * SIDE * 4], n = d.clone(), a = d.clone(), m = d.clone();
            for (int y = 0; y < SIDE; y++) for (int x = 0; x < SIDE; x++) {
                int offset = (y * SIDE + x) * 4;
                d[offset] = .5f;
                // A controlled tiled normal field; 1.45 is the saved user bump strength.
                double nx = normalMap == 0 ? 0 : .3 * 1.45 * Math.sin((x / 16.0 + phase) * Math.PI * 2);
                double ny = normalMap == 0 ? 0 : .3 * 1.45 * Math.cos((y / 16.0 + phase) * Math.PI * 2);
                double inverse = 1 / Math.sqrt(nx * nx + ny * ny + 1);
                n[offset] = quantize((float) (nx * inverse)); n[offset + 1] = quantize((float) (ny * inverse));
                n[offset + 2] = quantize((float) inverse); n[offset + 3] = 7f / 32767f;
                a[offset] = a[offset + 1] = a[offset + 2] = .7f; a[offset + 3] = 1;
                m[offset] = smoothness / 255f; m[offset + 1] = f0 / 255f;
                if (measuredNormal != null) {
                    int sample = (x / 16 % 4) + 4 * (y / 16 % 4);
                    // Same terrain decode and saved bump strength; this tiles measured samples,
                    // not their original texture neighbourhood or its POM-displaced lookup.
                    nx = (measuredNormal[sample][0] - 128) / 127.0 * 1.45;
                    ny = (measuredNormal[sample][1] - 128) / 127.0 * 1.45;
                    double nz = Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
                    inverse = 1 / Math.sqrt(nx * nx + ny * ny + nz * nz);
                    n[offset] = quantize((float) (nx * inverse)); n[offset + 1] = quantize((float) (ny * inverse));
                    n[offset + 2] = quantize((float) (nz * inverse));
                    for (int channel = 0; channel < 3; channel++) {
                        a[offset + channel] = measuredAlbedo[sample][channel] / 255f;
                        m[offset + channel] = measuredMaterial[sample][channel] / 255f;
                    }
                }
            }
            var command = vk.begin();
            barrier(command, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
            upload(vk, command, depth, d); upload(vk, command, normal, n);
            upload(vk, command, albedo, a); upload(vk, command, material, m);
            VK10.vkCmdFillBuffer(command, heads.handle(), 0, heads.size(), 0);
            barrier(command, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
            dispatch(command, emitPipeline, emitLayout, emitSet, 256, 1);
            computeBarrier(command);
            dispatch(command, binPipeline, binLayout, binSet, 256, 1);
            computeBarrier(command);
            VK10.vkCmdResetQueryPool(command, queryPool, 0, 2);
            VK10.vkCmdWriteTimestamp(command, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool, 0);
            dispatch(command, gatherPipeline, gatherLayout, gatherSet, SIDE / 8, SIDE / 8);
            VK10.vkCmdWriteTimestamp(command, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool, 1);
            var lightRead = vk.hostBuffer(null, SIDE * SIDE * 8L, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            var statsRead = vk.hostBuffer(null, SIDE * SIDE * 16L, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            vk.copyImageToBuffer(command, local, lightRead); vk.copyImageToBuffer(command, stats, statsRead);
            vk.submitAndWait(command);
            double milliseconds;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var timestamps = stack.mallocLong(2);
                int result = VK10.vkGetQueryPoolResults(vk.device, queryPool, 0, 2, timestamps, 8,
                        VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WAIT_BIT);
                assertTrue(result == VK10.VK_SUCCESS);
                milliseconds = (timestamps.get(1) - timestamps.get(0)) * timestampPeriod * 1e-6;
            }
            float[] rgb = new float[SIDE * SIDE * 3], luminances = new float[SIDE * SIDE];
            double sum = 0, maximum = 0, dark = 0, visits = 0, brdfs = 0, inverseCosine = 0;
            ByteBuffer light = lightRead.mapped().order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer counts = statsRead.mapped().order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < SIDE * SIDE; i++) {
                float value = 0;
                for (int channel = 0; channel < 3; channel++) {
                    float c = Float.float16ToFloat(light.getShort(i * 8 + channel * 2));
                    assertTrue(Float.isFinite(c) && c >= 0, "finite nonnegative gather output");
                    rgb[i * 3 + channel] = c; value += c / 3;
                }
                luminances[i] = value; sum += value; maximum = Math.max(maximum, value); if (value == 0) dark++;
                visits += counts.getFloat(i * 16); brdfs += counts.getFloat(i * 16 + 4);
                inverseCosine = Math.max(inverseCosine, counts.getFloat(i * 16 + 8));
            }
            Arrays.sort(luminances);
            return new Result(rgb, sum / luminances.length, maximum, luminances[(int) (luminances.length * .99)],
                    dark / luminances.length, visits / luminances.length, brdfs / luminances.length, inverseCosine, milliseconds);
        }

        @Override public void close() {
            VK10.vkDestroyQueryPool(vk.device, queryPool, null);
            MemoryUtil.memFree(gatherSpirv); MemoryUtil.memFree(emitSpirv); MemoryUtil.memFree(binSpirv);
        }
    }

    private static String emissionSource() {
        return """
                #version 450
                #moj_import <fornax_runtime:glass_photons.glsl>
                #moj_import <fornax_runtime:glass_photon_sampling.glsl>
                layout(local_size_x=256) in;
                layout(std430,binding=0) readonly buffer Globals { mat4 a; mat4 b; vec4 c; vec4 d; vec4 fixture; };
                layout(std430,binding=1) buffer Photons { vec4 words[]; };
                void main() {
                    uint index=gl_GlobalInvocationID.x, base=index*4u, count=uint(fixture.x), mode=uint(fixture.y);
                    if(index>=PLAGUE_GLASS_PHOTONS) return;
                    words[base]=vec4(0);
                    if(index>=count) return;
                    vec4 q=plagueGlassEmissionSample(index,37u);
                    vec3 direction=vec3(sqrt(q.z)*cos(6.283185307179586*q.w),
                        sqrt(q.z)*sin(6.283185307179586*q.w),-sqrt(1.0-q.z));
                    vec3 origin=vec3(q.xy-.5,0);
                    vec3 hit=origin+direction*(-3.0/direction.z);
                    if(mode==0u && length(hit-origin)>12.0) return;
                    if(mode==1u || mode==3u) hit=vec3(q.xy*4.0-2.0,-3);
                    if(mode==2u) { hit=vec3(0,0,-3); direction=vec3(0,0,-1); }
                    if(mode==3u) direction=normalize(vec3(-1,0,-.001));
                    words[base]=vec4(hit,1);
                    // A unit-area Lambertian source emits pi*Le; dense records use fixed total flux.
                    words[base+1u]=vec4(vec3(3.141592653589793/float(count)),0);
                    words[base+2u]=vec4(-direction,0);
                    words[base+3u]=vec4(0,0,1,0);
                }
                """;
    }

    private static String adaptGather(String source) {
        source = source.replace("#moj_import <fornax:globals.glsl>", """
                layout(std430,set=0,binding=0) readonly buffer Globals {
                    mat4 u_InvProjModelView; mat4 u_SunViewProj; vec4 camera; vec4 u_WorldBounds; vec4 fixture;
                };
                #define u_CameraAbs camera.xyz
                #define texelFetch(T,P,L) imageLoad(T,P)
                #define textureSize(T,L) imageSize(T)
                layout(rgba32f,set=0,binding=19) uniform writeonly image2D probeStats;
                """);
        source = source.replaceAll("layout\\(set=0,binding=(2|3|4|15)\\) uniform sampler2D",
                "layout(rgba32f,set=0,binding=$1) uniform readonly image2D");
        source = source.replace("void main() {", "void main() {\n    vec4 probe=vec4(0);");
        source = source.replace("uint index=node-1u;", "probe.x+=1.0;\n                uint index=node-1u;");
        source = source.replace("PlagueBrdf response=", "probe.y+=1.0; probe.z=max(probe.z,1.0/geometricCosine);\n                PlagueBrdf response=");
        source = source.replace("imageStore(u_Local,pixel,", "imageStore(probeStats,pixel,probe);\n    imageStore(u_Local,pixel,");
        assertTrue(!source.contains("#moj_import <fornax_runtime:"), "snapshot must contain all pack dependencies");
        assertTrue(source.contains("probe.y+=1.0"), "BRDF counter must be attached to the actual evaluation");
        return source;
    }

    private static String flatten(String source) throws IOException {
        Matcher imports = Pattern.compile("#moj_import <fornax_runtime:([^>]+)>").matcher(source);
        StringBuffer result = new StringBuffer();
        while (imports.find()) imports.appendReplacement(result, Matcher.quoteReplacement(
                flatten(Files.readString(PACK.resolve("shaders/include/" + imports.group(1))))));
        imports.appendTail(result); return result.toString();
    }
    private static HeadlessVulkan.Image inputImage(HeadlessVulkan vk) {
        return vk.storageImage(SIDE, SIDE, VK10.VK_FORMAT_R32G32B32A32_SFLOAT);
    }
    private static float quantize(float value) { return Math.round(value * 32767) / 32767f; }
    private static int[][] samples(JsonObject measured, String name) {
        var rows = measured.getAsJsonObject(name).getAsJsonArray("samples");
        int[][] data = new int[16][4];
        for (int row = 0; row < 16; row++) for (int channel = 0; channel < 4; channel++)
            data[row][channel] = rows.get(row).getAsJsonArray().get(channel).getAsInt();
        return data;
    }
    private static void dispatch(VkCommandBuffer cmd, long pipeline, long layout, long set, int x, int y) {
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, new long[]{set}, null);
        VK10.vkCmdDispatch(cmd, x, y, 1);
    }
    private static void initializeImage(VkCommandBuffer cmd, HeadlessVulkan.Image image) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(image.handle()).dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
            barrier.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT | VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, barrier);
        }
    }
    private static void upload(HeadlessVulkan vk, VkCommandBuffer cmd, HeadlessVulkan.Image image, float[] values) {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) bytes.putFloat(value);
        var buffer = vk.hostBuffer(bytes.array(), bytes.capacity(), VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(image.width(), image.height(), 1);
            VK10.vkCmdCopyBufferToImage(cmd, buffer.handle(), image.handle(), VK10.VK_IMAGE_LAYOUT_GENERAL, region);
        }
    }
    private static void computeBarrier(VkCommandBuffer cmd) {
        barrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
    }
    private static void barrier(VkCommandBuffer cmd, int srcStage, int dstStage, int srcAccess, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            VK10.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, barrier, null, null);
        }
    }
    private static void saveImage(String name, float[] rgb) throws IOException {
        BufferedImage image = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIDE; y++) for (int x = 0; x < SIDE; x++) {
            int packed = 0;
            for (int channel = 0; channel < 3; channel++) {
                float value = rgb[(y * SIDE + x) * 3 + channel];
                int mapped = Math.round((float) Math.pow(value / (1 + value), 1 / 2.2) * 255);
                packed = packed << 8 | Math.min(255, mapped);
            }
            image.setRGB(x, SIDE - 1 - y, packed);
        }
        ImageIO.write(image, "png", OUTPUT.resolve(name + ".png").toFile());
    }
}
