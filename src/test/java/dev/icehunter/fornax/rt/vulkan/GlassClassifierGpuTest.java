package dev.icehunter.fornax.rt.vulkan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import dev.icehunter.fornax.voxel.GlassOpticalGpuFixtures;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Executes the live pack classifier on real GPU hardware with engine-encoded fixture geometry.
 * Measured texture values are optional local evidence. This is not the client's harvested scene,
 * descriptor graph, atlas filtering or per-position model emission. */
class GlassClassifierGpuTest {
    // Sixteen palette words, six seven-word face records, four material and four albedo words.
    private static final int WORDS = 16 + 42 + 4 + 4;
    private record Fixture(String name, byte[] geometry, float[] spec, float[] albedo,
                           boolean accepted, boolean blended, boolean affine) {
        Fixture(String name, byte[] geometry, float[] spec, float[] albedo,
                boolean accepted, boolean blended) {
            this(name, geometry, spec, albedo, accepted, blended, true);
        }
    }

    @Test void measuredMaterialsAndEngineFaceWordsReachTheLiveClassifier() throws IOException {
        Path pack = Path.of("../plague").toAbsolutePath().normalize();
        Path evidence = pack.resolve("tools/out/glass-runtime/material-evidence.json");
        assumeTrue(Files.isRegularFile(pack.resolve("shaders/include/glass_scene.glsl")), "optional pack absent");
        assumeTrue(Files.isRegularFile(evidence), "optional measured material evidence absent");
        JsonObject measured = new JsonObject();
        for (var resource : JsonParser.parseString(Files.readString(evidence)).getAsJsonObject()
                .getAsJsonArray("resource_packs")) {
            for (var item : resource.getAsJsonObject().getAsJsonObject("texture_statistics").entrySet())
                measured.add(item.getKey(), item.getValue());
        }
        List<Fixture> fixtures = new ArrayList<>();
        for (String stem : List.of("glass", "blue_stained_glass", "green_stained_glass", "red_stained_glass")) {
            String base = "assets/minecraft/textures/block/" + stem;
            float[] spec = centre(measured.getAsJsonObject(base + "_s.png"));
            float[] albedo = centre(measured.getAsJsonObject(base + ".png"));
            boolean blended = !stem.equals("glass");
            // Uniform blue/green colour is measured exactly. Clear tint is irrelevant to absorption;
            // patterned red is rejected by its measured conductor centre before colour is consumed.
            int argb = byteValue(albedo[3]) << 24 | byteValue(albedo[0]) << 16
                    | byteValue(albedo[1]) << 8 | byteValue(albedo[2]);
            fixtures.add(new Fixture(stem + "-closed", GlassOpticalGpuFixtures.cube(blended, true, argb),
                    spec, albedo, !stem.equals("red_stained_glass"), blended));
            fixtures.add(new Fixture(stem + "-unproved", GlassOpticalGpuFixtures.cube(blended, false, argb),
                    spec, albedo, false, blended));
        }
        Fixture blue = fixtures.get(2);
        byte[] opaque = blue.geometry.clone();
        ByteBuffer opaqueWords = ByteBuffer.wrap(opaque).order(ByteOrder.LITTLE_ENDIAN);
        opaqueWords.putInt(16 * 4, opaqueWords.getInt(16 * 4) | (1 << 26));
        fixtures.add(new Fixture("blue-opaque-backing", opaque, blue.spec, blue.albedo, false, true));
        byte[] mixed = blue.geometry.clone();
        ByteBuffer mixedWords = ByteBuffer.wrap(mixed).order(ByteOrder.LITTLE_ENDIAN);
        mixedWords.putInt(16 * 4, mixedWords.getInt(16 * 4) & ~(1 << 31));
        fixtures.add(new Fixture("blue-mixed-layer", mixed, blue.spec, blue.albedo, false, true));
        byte[] unmapped = blue.geometry.clone();
        ByteBuffer unmappedWords = ByteBuffer.wrap(unmapped).order(ByteOrder.LITTLE_ENDIAN);
        for (int face = 0; face < 6; face++) {
            int offset = (16 + face * 7) * 4;
            // Legacy mapping bit24 and optical mapping bit29; closure/layer facts remain intact.
            unmappedWords.putInt(offset, unmappedWords.getInt(offset) & ~((1 << 24) | (1 << 29)));
        }
        fixtures.add(new Fixture("blue-closed-without-material-mapping", unmapped, blue.spec, blue.albedo, false, true));
        byte[] pane = sampleOnlyPane(blue.geometry, true);
        fixtures.add(new Fixture("blue-four-way-pane-sample-only", pane, blue.spec, blue.albedo, true, true, false));
        Fixture clear = fixtures.get(0);
        fixtures.add(new Fixture("clear-four-way-pane-sample-only", sampleOnlyPane(clear.geometry, false),
                clear.spec, clear.albedo, true, false, false));
        byte[] paneUnproved = pane.clone();
        ByteBuffer paneUnprovedWords = ByteBuffer.wrap(paneUnproved).order(ByteOrder.LITTLE_ENDIAN);
        for (int face = 0; face < 6; face++) {
            int offset = (16 + face * 7) * 4;
            paneUnprovedWords.putInt(offset, paneUnprovedWords.getInt(offset) & ~(1 << 30));
        }
        fixtures.add(new Fixture("pane-material-cannot-certify-geometry", paneUnproved,
                blue.spec, blue.albedo, false, true, false));
        byte[] paneOpaque = pane.clone();
        ByteBuffer paneOpaqueWords = ByteBuffer.wrap(paneOpaque).order(ByteOrder.LITTLE_ENDIAN);
        paneOpaqueWords.putInt(16 * 4, paneOpaqueWords.getInt(16 * 4) | (1 << 26));
        fixtures.add(new Fixture("sample-only-pane-opaque-backing", paneOpaque,
                blue.spec, blue.albedo, false, true, false));
        byte[] paneMixed = pane.clone();
        ByteBuffer paneMixedWords = ByteBuffer.wrap(paneMixed).order(ByteOrder.LITTLE_ENDIAN);
        paneMixedWords.putInt(16 * 4, paneMixedWords.getInt(16 * 4) & ~(1 << 31));
        fixtures.add(new Fixture("sample-only-pane-mixed-layer", paneMixed,
                blue.spec, blue.albedo, false, true, false));
        // Every face is invalidated: a single malformed face may legitimately use another sample.
        addMalformedSample(fixtures, blue, pane, "u-nan", 1, Float.NaN);
        addMalformedSample(fixtures, blue, pane, "u-infinity", 1, Float.POSITIVE_INFINITY);
        addMalformedSample(fixtures, blue, pane, "u-negative", 1, -.125f);
        addMalformedSample(fixtures, blue, pane, "u-outside", 1, 1.125f);
        addMalformedSample(fixtures, blue, pane, "v-nan", 2, Float.NaN);
        addMalformedSample(fixtures, blue, pane, "v-outside", 2, 1.125f);
        addMalformedSample(fixtures, blue, pane, "area-zero", 3, 0);
        addMalformedSample(fixtures, blue, pane, "area-negative", 3, -.125f);
        addMalformedSample(fixtures, blue, pane, "area-outside", 3, 1.125f);
        addMalformedSample(fixtures, blue, pane, "area-nan", 3, Float.NaN);
        addMalformedSample(fixtures, blue, pane, "area-infinity", 3, Float.POSITIVE_INFINITY);
        for (int word = 4; word <= 6; word++)
            addMalformedSample(fixtures, blue, pane, "reserved-word-" + word, word, .125f);
        ByteBuffer input = ByteBuffer.allocate(fixtures.size() * WORDS * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (Fixture fixture : fixtures) {
            input.put(fixture.geometry);
            for (float v : fixture.spec) input.putFloat(v);
            for (float v : fixture.albedo) input.putFloat(v);
        }
        String source = kernel(pack);
        try (HeadlessVulkan vk = HeadlessVulkan.tryCreate()) {
            assumeTrue(vk != null, "no headless Vulkan device");
            ByteBuffer spirv = ComputeShaderCompiler.compileToSpirv(source, "glass_classifier_probe.comp");
            try {
                int[] types = {VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
                var data = vk.hostBuffer(input.array(), input.capacity(), VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                var output = vk.hostBuffer(null, fixtures.size() * 32L, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                long setLayout = vk.descriptorSetLayout(types), layout = vk.pipelineLayout(setLayout, 0);
                long pipeline = vk.computePipeline(vk.shaderModule(spirv), layout);
                long set = vk.descriptorSet(setLayout, types, new Object[]{data, output});
                var command = vk.begin();
                VK10.vkCmdBindPipeline(command, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                VK10.vkCmdBindDescriptorSets(command, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, new long[]{set}, null);
                VK10.vkCmdDispatch(command, fixtures.size(), 1, 1);
                vk.shaderToHost(command);
                vk.submitAndWait(command);
                ByteBuffer result = output.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                StringBuilder report = new StringBuilder("device: ").append(vk.deviceName).append('\n');
                for (int i = 0; i < fixtures.size(); i++) {
                    Fixture f = fixtures.get(i);
                    float accepted = result.getFloat(i * 32);
                    report.append(f.name).append(" glass=").append(accepted)
                            .append(" ior=").append(result.getFloat(i * 32 + 4))
                            .append(" alpha=").append(result.getFloat(i * 32 + 8)).append('\n');
                    assertEquals(f.accepted ? 1f : 0f, accepted, f.name);
                    if (!f.accepted) continue;
                    double root = Math.sqrt(f.spec[1]);
                    assertEquals((1 + root) / (1 - root), result.getFloat(i * 32 + 4), 1e-5, f.name);
                    assertEquals(Math.pow(1 - f.spec[0], 2), result.getFloat(i * 32 + 8), 1e-5, f.name);
                    for (int channel = 0; channel < 3; channel++) {
                        double transmission = f.blended ? linear(f.albedo[channel]) : 1;
                        double expected = -Math.log(Math.max(transmission, 1.0 / 65535));
                        assertEquals(expected, result.getFloat(i * 32 + 16 + channel * 4), 1e-4,
                                f.name + " absorption channel " + channel);
                    }
                    // Sample-only material evidence must never claim an affine surface mapping.
                    assertEquals(f.affine ? .3125f : 0f, result.getFloat(i * 32 + 12), 1e-6, f.name + " mapped U");
                    assertEquals(f.affine ? .5625f : 0f, result.getFloat(i * 32 + 28), 1e-6, f.name + " mapped V");
                }
                report.append("Limits: engine-generated fixture geometry and measured material centres; not the live client harvest or atlas bindings.\n");
                Files.writeString(pack.resolve("tools/out/glass-runtime/gpu-classifier-results.txt"), report);
                System.out.print(report);
            } finally {
                MemoryUtil.memFree(spirv);
            }
        }
    }

    private static float[] centre(JsonObject item) {
        float[] result = new float[4];
        for (int c = 0; c < 4; c++) result[c] = item.getAsJsonArray("centre").get(c).getAsFloat() / 255;
        return result;
    }
    private static int byteValue(float value) { return Math.round(value * 255); }
    private static double linear(float value) {
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }
    private static byte[] sampleOnlyPane(byte[] cube, boolean translucent) {
        // The base palette uses the engine encoder. This controlled fixture replaces its geometry
        // with the five actual 1/16-unit four-way pane boxes and the new face-record ABI.
        byte[] result = cube.clone();
        ByteBuffer words = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        // Partial panes do not inherit the full cube's vanilla light-transmission fact.
        words.putInt(0, (words.getInt(0) & ~(15 | (1 << 12))) | 5);
        int[][] boxes = {{7, 0, 7, 9, 16, 9}, {7, 0, 0, 9, 16, 7}, {7, 0, 9, 9, 16, 16},
                {0, 0, 7, 7, 16, 9}, {9, 0, 7, 16, 16, 9}};
        for (int i = 0; i < boxes.length; i++) {
            int packed = 0;
            for (int axis = 0; axis < 6; axis++) packed |= boxes[i][axis] << (axis * 5);
            words.putInt((7 + i) * 4, packed);
        }
        for (int face = 0; face < 6; face++) {
            int offset = (16 + face * 7) * 4;
            // Bits24/29 are absent: this proves material availability, not an affine map.
            int header = (words.getInt(offset) & 0x00ffffff) | (1 << 25) | (1 << 30);
            if (translucent) header |= 1 << 31;
            words.putInt(offset, header);
            // The cap's smaller rectangle is a synthetic conductor patch. Correct selection
            // uses the broad material sample and retains its measured dielectric F0.
            words.putFloat(offset + 4, face < 2 ? .28125f : .3125f);
            words.putFloat(offset + 8, .5625f);
            words.putFloat(offset + 12, face < 2 ? 14f / 256 : 7f / 16);
            for (int word = 4; word <= 6; word++) words.putInt(offset + word * 4, 0);
        }
        return result;
    }
    private static void addMalformedSample(List<Fixture> fixtures, Fixture material, byte[] pane,
                                           String name, int word, float value) {
        byte[] geometry = pane.clone();
        ByteBuffer words = ByteBuffer.wrap(geometry).order(ByteOrder.LITTLE_ENDIAN);
        for (int face = 0; face < 6; face++) words.putFloat((16 + face * 7 + word) * 4, value);
        fixtures.add(new Fixture("sample-only-pane-" + name, geometry, material.spec, material.albedo,
                false, true, false));
    }
    private static String flatten(Path pack, String name) throws IOException {
        String source = Files.readString(pack.resolve("shaders/include/" + name));
        var matcher = Pattern.compile("(?m)^#moj_import <fornax_runtime:([^>]+)>\\s*$").matcher(source);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(out,
                java.util.regex.Matcher.quoteReplacement(flatten(pack, matcher.group(1))));
        matcher.appendTail(out);
        return out.toString();
    }
    private static String kernel(Path pack) throws IOException {
        return """
                #version 450
                layout(local_size_x=1) in;
                layout(std430,set=0,binding=0) readonly buffer FixtureWords { uint fixtureWords[]; };
                layout(std430,set=0,binding=1) writeonly buffer Results { vec4 results[]; };
                const ivec4 u_VoxelWindow=ivec4(0,0,0,1);
                const vec3 u_CameraAbs=vec3(0.0);
                uint fixtureBase;
                uint plagueGlassOccupancyWord(int word) { return 0u; }
                uint plagueGlassPayloadWord(int word) { return 0u; }
                uint plagueGlassPaletteWord(int word) { return fixtureWords[fixtureBase+uint(word)]; }
                uint plagueGlassSummaryWord(int word) { return 0u; }
                uint plagueGlassFaceWord(int word) { return fixtureWords[fixtureBase+16u+uint(word)]; }
                vec4 fixtureVector(uint offset) { return uintBitsToFloat(uvec4(fixtureWords[fixtureBase+offset],
                    fixtureWords[fixtureBase+offset+1u],fixtureWords[fixtureBase+offset+2u],fixtureWords[fixtureBase+offset+3u])); }
                vec4 plagueGlassAlbedo(vec2 uv) { return fixtureVector(62u); }
                vec4 plagueGlassMaterial(vec2 uv) {
                    vec4 material=fixtureVector(58u);
                    if(uv.x<.3) material.g=1.0; // Controlled cap patch, not an active texture claim.
                    return material;
                }
                bool plagueGlassBuffersValid() { return fixtureWords.length()>=int(fixtureBase+66u); }
                """ + flatten(pack, "glass_scene.glsl") + """
                void main() {
                    uint index=gl_GlobalInvocationID.x;
                    fixtureBase=index*66u;
                    PlagueGlassMedium medium=plagueGlassMedium(0);
                    vec2 uv; vec3 tint;
                    plagueGlassUv(0,vec3(.5,0.0,.5),vec3(0.0,-1.0,0.0),uv,tint);
                    results[index*2u]=vec4(medium.glass?1.0:0.0,medium.ior,medium.roughness,uv.x);
                    results[index*2u+1u]=vec4(medium.absorption,uv.y);
                }
                """;
    }
}
