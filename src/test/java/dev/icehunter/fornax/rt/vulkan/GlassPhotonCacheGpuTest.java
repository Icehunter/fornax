package dev.icehunter.fornax.rt.vulkan;

import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Executes the optional pack's complete photon-cache maintenance pass with real GPU storage.
 * Controlled section/source uploads test invalidation and compaction. No client upload ordering,
 * renderer geometry or optical path correctness is inferred from these fixture records. */
class GlassPhotonCacheGpuTest {
    // VoxelSourceWindow ABI2 and pack cache header/stride contracts.
    private static final int CAPACITY = 4096;
    private static final int MAX_SLOTS = 33 * 33 * 33;
    private static final int SOURCE_BASE = 16 + MAX_SLOTS * 2;
    private static final int SOURCE_STRIDE = 25;
    private static final int SOURCE_WORDS = SOURCE_BASE + CAPACITY * SOURCE_STRIDE;
    private static final int CACHE_WORDS = 16 + CAPACITY + MAX_SLOTS * 8 + CAPACITY * SOURCE_STRIDE;
    private static final int DIMENSION = 3;
    private static final int SECTION_WORDS = DIMENSION * DIMENSION * DIMENSION * 8;
    private static final String GLOBALS = """
            layout(std430,set=0,binding=0) readonly buffer Globals {
                ivec4 u_VoxelWindow;
                vec4 fixtureCamera;
                vec4 u_FrameState;
                vec4 u_WorldBounds;
                mat4 u_SunViewProj;
                vec4 u_ShadowMapParams;
            };
            #define u_CameraAbs fixtureCamera.xyz
            """;

    @Test void cameraMotionLeavesWorldPhotonsOwnedWhileSceneAndSourceChangesInvalidateThem() throws IOException {
        Path pack = Path.of("../plague").toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(pack.resolve("shaders")), "optional pack absent");
        Path file = pack.resolve("shaders/compute/glass_photon_cache.comp");
        assertTrue(Files.isRegularFile(file), "cache pass must exist in the linked pack");
        String source = flatten(pack, file).replace("#moj_import <fornax:globals.glsl>", GLOBALS)
                .replaceAll("layout\\(set=0,binding=3\\) uniform sampler2D u_Depth;", "");
        assertTrue(!source.contains("#moj_import"), "fixture resolves every shader dependency");
        try (HeadlessVulkan vk = HeadlessVulkan.tryCreate()) {
            assumeTrue(vk != null, "no headless Vulkan device");
            ByteBuffer spirv = ComputeShaderCompiler.compileToSpirv(source, "glass_photon_cache_probe.comp");
            try {
                Runner runner = new Runner(vk, spirv);
                StringBuilder report = new StringBuilder("device: ").append(vk.deviceName).append('\n');
                runner.resetSources(2);
                runner.source(0, 2, 65, 3, 1, .5f, .25f);
                runner.source(1, -2, 66, 4, .2f, .4f, .8f);
                runner.run();
                assertTrue(runner.header(0) != 0, "initialized ABI receives a cache signature");
                assertEquals(1, runner.header(1), "zero allocation requires a full rebuild");
                assertEquals(2, runner.header(2));
                assertEquals(72, runner.header(5), "cache origin is the camera section's integer block centre");
                assertIndices(runner, Set.of(0, 1));
                report.append("initial rebuild:2 sources\n");

                runner.run();
                assertEquals(0, runner.header(1), "unchanged input retains transport");
                runner.opticalValid(false);
                runner.run();
                assertEquals(0, runner.header(0), "invalid optical buffer lengths cannot certify cached paths");
                runner.opticalValid(true);
                runner.run();
                assertEquals(1, runner.header(1), "restoring optical buffers refills invalidated paths");
                runner.run();
                assertEquals(0, runner.header(1));
                for (float camera : new float[]{.125f, 1.125f}) {
                    runner.globals.putFloat(16, camera).putFloat(20, 65.75f).putFloat(24, .375f);
                    runner.globals.putFloat(32, camera + 128);
                    runner.run();
                    assertEquals(0, runner.header(1), "camera motion must not dirty world paths");
                    assertEquals(8, runner.header(4), "motion within the section keeps the stored world origin");
                }
                for (float camera : new float[]{16.125f, -16.125f}) {
                    runner.globals.putFloat(16, camera);
                    runner.run();
                    assertEquals(1, runner.header(1), "crossing a stable coverage tile rebuilds transport");
                    assertEquals((int)Math.floor(camera / 16) * 16 + 8, runner.header(4));
                    runner.run();
                    assertEquals(0, runner.header(1));
                }
                report.append("fractional/block camera moves:0 rebuilds; section crossings:rebuild then warm\n");

                // Content word5 changes lightmaps only; geometry word4 describes optical boundaries.
                runner.sections.putInt(5 * 4, 100);
                runner.run();
                assertEquals(0, runner.header(1), "lightmap-only revision keeps optical paths");
                for (int word : new int[]{0, 3, 4, 6}) {
                    runner.sections.putInt(word * 4, runner.sections.getInt(word * 4) + 1);
                    runner.run();
                    assertEquals(1, runner.header(1), "owner/generation/geometry/publication change must rebuild word" + word);
                    runner.run();
                    assertEquals(0, runner.header(1), "the changed token becomes the new baseline");
                }
                report.append("section owner/generation/geometry/validity:4 immediate rebuilds; content-only:0\n");

                runner.swapSources(0, 1);
                runner.run();
                assertEquals(1, runner.header(1), "record reordering may conservatively rebuild");
                assertIndices(runner, Set.of(0, 1));
                runner.run();
                assertEquals(0, runner.header(1));
                for (int word : new int[]{11, 15, 19, 24, 5}) {
                    int offset = (SOURCE_BASE + word) * 4;
                    runner.sources.putInt(offset, runner.sources.getInt(offset) + 1);
                    runner.run();
                    assertEquals(1, runner.header(1), "source RGB/shape/revision change must rebuild word" + word);
                }
                report.append("source reorder/RGB/box/revision:all invalidate\n");

                // Atlas replacement need not change already-decoded geometry or source RGB.
                for (int header : new int[]{3, 4, 5}) {
                    runner.sources.putInt(header * 4, runner.sources.getInt(header * 4) + 1);
                    runner.run();
                    assertEquals(1, runner.header(1), "storage/atlas generation must invalidate header" + header);
                }
                runner.sources.putInt(2 * 4, 1);
                runner.run();
                assertEquals(1, runner.header(1), "deleting a source must remove its old photons");
                assertEquals(1, runner.header(2));
                runner.sources.putInt(2 * 4, 2);
                runner.run();
                assertEquals(1, runner.header(1), "adding a source must rebuild the allocation");
                assertEquals(2, runner.header(2));

                runner.sources.putInt(0, 99);
                runner.run();
                assertEquals(0, runner.header(0), "invalid source ABI cannot retain a valid cache");
                assertEquals(0, runner.header(2));
                runner.sources.putInt(0, 2);
                runner.sources.putInt(4, CAPACITY - 1);
                runner.run();
                assertEquals(0, runner.header(0), "mismatched source capacity is invalid");
                runner.sources.putInt(4, CAPACITY);
                runner.sources.putInt(3 * 4, 0);
                runner.run();
                assertEquals(0, runner.header(0), "uninitialized storage generation is invalid");
                runner.sources.putInt(3 * 4, 1);
                runner.run();
                assertNotEquals(0, runner.header(0));
                assertEquals(1, runner.header(1), "invalid-to-valid transition must refill every path");
                report.append("source removal/addition/atlas replacement/invalid ABI:immediate invalidation\n");

                runner.resetSources(CAPACITY);
                for (int i = 0; i < CAPACITY; i++) runner.source(i, i % 16, 64 + (i / 16) % 16, i / 256, 1, .5f, .25f);
                runner.run();
                assertEquals(CAPACITY, runner.header(2), "inventory entries after256 must be retained");
                Set<Integer> all = new HashSet<>();
                for (int i = 0; i < CAPACITY; i++) all.add(i);
                assertIndices(runner, all);
                runner.run();
                assertEquals(0, runner.header(1));
                runner.sources.putInt((SOURCE_BASE + 4095 * SOURCE_STRIDE + 11) * 4, Float.floatToRawIntBits(Float.NaN));
                runner.run();
                assertEquals(CAPACITY - 1, runner.header(2), "nonfinite radiance cannot enter active sources");
                report.append("all4096 sources compacted exactly once; nonfinite radiance excluded\n");
                Path output = pack.resolve("tools/out/glass-runtime/gpu-photon-cache-results.txt");
                Files.createDirectories(output.getParent());
                Files.writeString(output, report + "Limits:cache pass only; no scene transport, client scheduling or FPS claim.\n");
            } finally {
                MemoryUtil.memFree(spirv);
            }
        }
    }

    private static void assertIndices(Runner runner, Set<Integer> expected) {
        Set<Integer> actual = new HashSet<>();
        for (int i = 0; i < runner.header(2); i++) actual.add(runner.cache.getInt((16 + i) * 4));
        assertEquals(expected, actual, "compaction must preserve every valid row without duplicates");
    }

    @Test void actualPhotonWritesSurviveCameraMotionAndReorderingButRefillAfterSceneEdits() throws IOException {
        Path pack = Path.of("../plague").toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(pack.resolve("shaders")), "optional pack absent");
        String cacheSource = flatten(pack, pack.resolve("shaders/compute/glass_photon_cache.comp"))
                .replace("#moj_import <fornax:globals.glsl>", GLOBALS)
                .replaceAll("layout\\(set=0,binding=3\\) uniform sampler2D u_Depth;", "");
        String traceSource = traceKernel(pack);
        int photons = defaultPhotonBudget(pack), localBudget = photons / 2;
        int twoSourcePaths = (localBudget / 2) * 2;
        try (HeadlessVulkan vk = HeadlessVulkan.tryCreate()) {
            assumeTrue(vk != null, "no headless Vulkan device");
            ByteBuffer cacheSpirv = ComputeShaderCompiler.compileToSpirv(cacheSource, "glass_cache_for_trace_probe.comp");
            ByteBuffer traceSpirv = ComputeShaderCompiler.compileToSpirv(traceSource, "glass_cached_trace_probe.comp");
            try {
                Runner cache = new Runner(vk, cacheSpirv);
                TraceRunner trace = new TraceRunner(vk, traceSpirv, cache, photons);
                cache.resetSources(2);
                cache.source(0, 2, 65, 3, 1, .5f, .25f);
                cache.source(1, -2, 66, 4, .2f, .4f, .8f);
                cache.run(); trace.run();
                assertEquals(twoSourcePaths, trace.calls(), "cold cache traces every path in the source quota");
                List<float[]> baseline = trace.localPhotons();
                assertEquals(twoSourcePaths, baseline.size());
                assertFlux(baseline, new double[]{1.2, .9, 1.05}, new double[]{.25, .5, .75});
                StringBuilder report = new StringBuilder("\ntrace device: ").append(vk.deviceName)
                        .append("\ncold paths:").append(trace.calls()).append('\n');

                for (float camera : new float[]{.125f, 1.125f, 15.75f}) {
                    cache.globals.putFloat(16, camera).putFloat(20, 64.75f).putFloat(24, .5f);
                    cache.globals.putFloat(32, camera * 100);
                    cache.run(); trace.run();
                    assertEquals(0, trace.calls(), "warm camera motion executes no local transport calls");
                    assertPhotonsEqual(baseline, trace.localPhotons(), 0);
                }
                cache.globals.putFloat(16, 16.125f);
                cache.run(); trace.run();
                    assertEquals(twoSourcePaths, trace.calls(), "coverage tile crossing rebuilds");
                assertPhotonsEqual(baseline, trace.localPhotons(), 1f / 4096);
                report.append("same-tile camera moves:0 path calls, bit-exact map; tile crossing:equal world map\n");

                cache.swapSources(0, 1);
                cache.run(); trace.run();
                assertEquals(twoSourcePaths, trace.calls());
                assertPhotonsEqual(baseline, trace.localPhotons(), 1f / 4096);
                report.append("source permutation:identical world positions/directions/RGB\n");

                cache.source(2, 4, 67, -3, .6f, .3f, .1f);
                cache.sources.putInt(8, 3);
                cache.run(); trace.run();
                assertEquals((localBudget / 3) * 3, trace.calls(), "all paths in each actual source quota execute");
                assertFlux(trace.localPhotons(), new double[]{1.8, 1.2, 1.15}, new double[]{.25, .5, .75});
                cache.sources.putInt(8, 2);
                cache.run(); trace.run();
                assertPhotonsEqual(baseline, trace.localPhotons(), 1f / 4096);
                report.append("source add/remove:correct integrated RGB; restoring inventory restores exact sample set\n");

                // Transport adapter mode2 means the edited scene has no glass-crossing receiver.
                trace.statistics.putInt(8, 2);
                cache.sections.putInt(16, cache.sections.getInt(16) + 1);
                cache.run(); trace.run();
                assertEquals(twoSourcePaths, trace.calls());
                assertEquals(0, trace.localPhotons().size(), "deleted optical path must remove old photons immediately");
                trace.statistics.putInt(8, 1);
                cache.sections.putInt(16, cache.sections.getInt(16) + 1);
                cache.run(); trace.run();
                assertFlux(trace.localPhotons(), new double[]{1.2, .9, 1.05}, new double[]{.5, .25, .75});
                report.append("geometry removal clears map; changed transport tint refills the new RGB\n");

                cache.opticalValid(false); trace.statistics.putInt(4, 0);
                cache.run(); trace.run();
                assertEquals(0, trace.calls());
                assertEquals(0, trace.localPhotons().size(), "invalid geometry buffers clear cached paths");
                cache.opticalValid(true); trace.statistics.putInt(4, 1);
                cache.run(); trace.run();
                assertEquals(twoSourcePaths, trace.calls(), "restored geometry buffers force a refill");
                assertFlux(trace.localPhotons(), new double[]{1.2, .9, 1.05}, new double[]{.5, .25, .75});
                report.append("invalid optical buffers:clear; recovery:full refill\n")
                        .append("Work measurement:actual adapter transport call counts, not GPU time or FPS.\n")
                        .append("Limits:real cache/trace shaders; synthetic tinted hit adapter replaces optical traversal and solar shadow map.\n");
                Files.writeString(pack.resolve("tools/out/glass-runtime/gpu-photon-trace-results.txt"), report);
            } finally {
                MemoryUtil.memFree(cacheSpirv); MemoryUtil.memFree(traceSpirv);
            }
        }
    }

    /** The trace fixture compiles the shared option defaults, so allocate and dispatch that same budget. */
    private static int defaultPhotonBudget(Path pack) throws IOException {
        String layout = Files.readString(pack.resolve("shaders/include/glass_photons.glsl"));
        assertTrue(Pattern.compile("PLAGUE_GLASS_PHOTONS\\s*=\\s*uint\\(PLAGUE_GLASS_SAMPLES\\)")
                .matcher(layout).find(), "layout must derive its active photon prefix from the scanned option");
        String options = Files.readString(pack.resolve("shaders/include/glass_options.glsl"));
        var option = dev.icehunter.fornax.pack.option.OptionScanner.scan(
                java.util.Map.of("shaders/include/glass_options.glsl", options)).get("PLAGUE_GLASS_SAMPLES");
        assertTrue(option != null, "shared photon budget option must be declared");
        int photons = Integer.parseInt(option.defaultValue());
        assertTrue(photons > 0 && photons % 2 == 0, "source and sky quotas require a positive even budget");
        return photons;
    }

    private static void assertFlux(List<float[]> photons, double[] radiance, double[] transmission) {
        double[] sum = new double[3];
        for (float[] photon : photons) for (int c = 0; c < 3; c++) sum[c] += photon[3 + c];
        for (int c = 0; c < 3; c++) assertEquals(Math.PI * radiance[c] * transmission[c], sum[c], 1e-5,
                "Lambertian pi*A*Le times controlled RGB transport channel" + c);
    }

    private static void assertPhotonsEqual(List<float[]> expected, List<float[]> actual, float positionTolerance) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) for (int c = 0; c < 12; c++)
            assertEquals(expected.get(i)[c], actual.get(i)[c], c < 3 ? positionTolerance : 0,
                    "world photon" + i + " component" + c);
    }

    private static final class TraceRunner {
        final HeadlessVulkan vk;
        final Runner cache;
        final ByteBuffer output, beamOutput, statistics;
        final int photons;
        final long layout, pipeline, set;

        TraceRunner(HeadlessVulkan vk, ByteBuffer spirv, Runner cache, int photons) {
            this.vk = vk; this.cache = cache; this.photons = photons;
            int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            var photonBuffer = vk.hostBuffer(null, photons * 64L, usage);
            var beamBuffer = vk.hostBuffer(null, photons * 208L, usage);
            var stats = vk.hostBuffer(null, 16, usage);
            var dummy = vk.hostBuffer(null, 16, usage);
            output = photonBuffer.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            beamOutput = beamBuffer.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            statistics = stats.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            statistics.putInt(4, 1);
            int[] types = new int[16]; Arrays.fill(types, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            Object[] resources = new Object[16]; Arrays.fill(resources, dummy);
            resources[0] = cache.globalBuffer; resources[11] = cache.sourceBuffer;
            resources[12] = cache.cacheBuffer; resources[13] = photonBuffer;
            resources[14] = beamBuffer; resources[15] = stats;
            long setLayout = vk.descriptorSetLayout(types);
            layout = vk.pipelineLayout(setLayout, 0);
            pipeline = vk.computePipeline(vk.shaderModule(spirv), layout);
            set = vk.descriptorSet(setLayout, types, resources);
        }

        int calls() { return statistics.getInt(0); }

        void assertReceiverOrOpticalReferencePlane() {
            for(int i=0;i<photons/2;i++) {
                if(output.getFloat(i*64+12)<=0)continue;
                // Finite records live at the last optical plane; residual records retain
                // their terminal receiver. Both are exact planes in this slab fixture.
                boolean finite=beamOutput.getFloat(i*208+5*16+12)>=0;
                assertEquals(finite?759:760,output.getFloat(i*64)+cache.header(4),1f/4096,
                        finite?"finite cell references the glass exit":"point residual stays on the receiver near face");
            }
        }

        void run() {
            statistics.putInt(0, 0);
            var command = vk.begin();
            VK10.vkCmdBindPipeline(command, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(command, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, new long[]{set}, null);
            VK10.vkCmdDispatch(command, (photons + 63) / 64, 1, 1);
            vk.shaderToHost(command);
            vk.submitAndWait(command);
        }

        List<float[]> localPhotons() {
            List<float[]> result = new ArrayList<>();
            for (int i = 0; i < photons / 2; i++) {
                int at = i * 64;
                if (output.getFloat(at + 12) <= 0) continue;
                float[] record = new float[12];
                for (int c = 0; c < 3; c++) {
                    record[c] = output.getFloat(at + c * 4) + cache.header(4 + c);
                    record[3 + c] = output.getFloat(at + 16 + c * 4);
                    record[6 + c] = output.getFloat(at + 32 + c * 4);
                    record[9 + c] = output.getFloat(at + 48 + c * 4);
                }
                result.add(record);
            }
            // Direction is generated from source identity and ordinal, independent of camera and row order.
            result.sort(Comparator.comparingDouble((float[] value) -> value[6])
                    .thenComparingDouble(value -> value[7]).thenComparingDouble(value -> value[8])
                    .thenComparingDouble(value -> value[0]).thenComparingDouble(value -> value[1])
                    .thenComparingDouble(value -> value[2]));
            return result;
        }
    }

    @Test void actualOpticalIntersectionsRemainIdenticalWhenTheEyeMovesDuringAForcedRebuild() throws IOException {
        Path pack = Path.of("../plague").toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(pack.resolve("shaders")), "optional pack absent");
        String cacheSource = flatten(pack, pack.resolve("shaders/compute/glass_photon_cache.comp"))
                .replace("#moj_import <fornax:globals.glsl>", GLOBALS)
                .replace("layout(set=0,binding=3) uniform sampler2D u_Depth;", "");
        String traceSource = traceKernel(pack, true);
        try (HeadlessVulkan vk = HeadlessVulkan.tryCreate()) {
            assumeTrue(vk != null, "no headless Vulkan device");
            ByteBuffer cacheSpirv = ComputeShaderCompiler.compileToSpirv(cacheSource, "glass_anchor_cache_probe.comp");
            ByteBuffer traceSpirv = ComputeShaderCompiler.compileToSpirv(traceSource, "glass_anchor_trace_probe.comp");
            try {
                Runner cache = new Runner(vk, cacheSpirv);
                // Coordinates match the scale of the saved room; world geometry is a glass slab
                // at X758 and opaque receiver at X760 across the bounded voxel window.
                cache.globals.putInt(0, 47).putInt(4, 4).putInt(8, -52);
                cache.globals.putFloat(16, 752.125f).putFloat(20, 64.75f).putFloat(24, -824.5f);
                cache.resetSources(1); cache.source(0, 756, 65, -832, 1, .5f, .25f);
                int photons = defaultPhotonBudget(pack);
                TraceRunner trace = new TraceRunner(vk, traceSpirv, cache, photons);
                cache.run(); trace.run();
                List<float[]> baseline = trace.localPhotons();
                int rebuildCalls=trace.calls();
                assertEquals(photons / 2,rebuildCalls,"every primary path is traced exactly once");
                assertTrue(baseline.size() > 1000, "real glass-crossing rays must reach the actual receiver");
                trace.assertReceiverOrOpticalReferencePlane();
                for (float delta : new float[]{.03125f, .125f, .33331299f, .875f}) {
                    cache.globals.putFloat(16, 752.125f + delta).putFloat(20, 64.75f + delta)
                            .putFloat(24, -824.5f + delta);
                    cache.sections.putInt(16, cache.sections.getInt(16) + 1);
                    cache.run(); trace.run();
                    assertEquals(rebuildCalls, trace.calls(), "force the same complete primary and beam-probe work instead of reusing cached paths");
                    assertPhotonsEqual(baseline, trace.localPhotons(), 0);
                }
                Files.writeString(pack.resolve("tools/out/glass-runtime/gpu-photon-anchor-results.txt"),
                        "device: " + vk.deviceName + "\nreal receiver photons: " + baseline.size()
                                + "\ntransport calls per full rebuild: " + rebuildCalls
                                + "\nfour fractional camera moves with forced rebuilds: bit-exact world records\n"
                                + "Limits: actual optical traversal over controlled planar voxel geometry; no harvested client geometry or sun shadow sampler.\n");
            } finally { MemoryUtil.memFree(cacheSpirv); MemoryUtil.memFree(traceSpirv); }
        }
    }

    private static String traceKernel(Path pack) throws IOException { return traceKernel(pack, false); }

    private static String traceKernel(Path pack, boolean actualScene) throws IOException {
        String source = Files.readString(pack.resolve("shaders/compute/glass_photon_trace.comp"))
                .replace("#version 450", "#version 450\n#define PLAGUE_LOCAL_SHADOWS 1")
                .replace("#moj_import <fornax:globals.glsl>", GLOBALS)
                .replace("#moj_import <fornax_runtime:shadow_options.glsl>", "")
                .replace("#moj_import <fornax_runtime:glass_compute_scene.glsl>", actualScene ? actualScene() : """
                        layout(std430,set=0,binding=15) buffer Statistics { uint fixtureStats[]; };
                        struct PlagueGlassMedium { bool glass; float ior; vec3 absorption; float roughness; };
                        struct PlagueGlassHit { vec3 position; vec3 normal; bool glass; };
                        // This adapter supplies a synthetic hit, so it cannot certify a finite optical cell.
                        uvec2 plagueGlassPathKey=uvec2(0u); vec4 plagueGlassLastPlane=vec4(0);
                        vec3 plagueGlassLastLo=vec3(0),plagueGlassLastHi=vec3(0);
                        vec3 plagueGlassLastPosition=vec3(0),plagueGlassLastFlux=vec3(0);
                        float plagueGlassLastDistance=0.0,plagueGlassPathDistance=0.0;
                        bool plagueGlassLastInAir=false;
                        bool plagueGlassLastIsSource=false;
                        PlagueGlassMedium plagueGlassLastMedium=PlagueGlassMedium(false,1.0,vec3(0),0.0);
                        bool plagueGlassBuffersValid() { return fixtureStats[1]!=0u; }
                        int plagueGlassTransport(inout vec3 origin,inout vec3 direction,inout vec3 throughput,float reach,
                                bool importance,inout uint randomState,out PlagueGlassHit receiver,
                                out bool crossed,out float firstDistance) {
                            atomicAdd(fixtureStats[0],1u);
                            crossed=true; firstDistance=1.0;
                            receiver.position=origin+direction*2.0; receiver.normal=-direction; receiver.glass=false;
                            throughput*=fixtureStats[2]==1u ? vec3(.5,.25,.75) : vec3(.25,.5,.75);
                            plagueGlassLastPosition=origin; plagueGlassLastFlux=throughput;
                            plagueGlassLastDistance=0.0; plagueGlassPathDistance=2.0;
                            return fixtureStats[2]==2u ? 0 : 1;
                        }
                        """)
                .replaceAll("layout\\(set=0,binding=10\\) uniform sampler2DShadow u_GlassSunShadow;", "");
        if(!actualScene) source=source.replace("#moj_import <fornax_runtime:glass_beam_visibility.glsl>","""
                bool plagueGlassBeamCertifyLastLeg(vec3 lo,vec3 hi,vec4 plane,vec3 exitLo,vec3 exitHi,vec3 receiver,vec3 normal) { return false; }
                """);
        source = flattenText(pack, source);
        assertTrue(!source.contains("#moj_import"), "trace fixture resolves every import");
        return source;
    }

    private static String actualScene() {
        return """
                layout(std430,set=0,binding=15) buffer Statistics { uint fixtureStats[]; };
                ivec3 fixtureCell(int slot,int index) {
                    ivec3 minimum=u_VoxelWindow.xyz-ivec3(1);
                    ivec3 residue=ivec3(slot%3,slot/9,(slot/3)%3);
                    ivec3 minimumResidue=minimum-ivec3(floor(vec3(minimum)/3.0))*3;
                    ivec3 section=minimum+((residue-minimumResidue+ivec3(3))%3);
                    return section*16+ivec3(index&15,index>>8,(index>>4)&15);
                }
                int fixtureEntry(ivec3 cell) { return cell.x==758 ? 0 : cell.x==760 ? 1 : -1; }
                uint plagueGlassOccupancyWord(int word) {
                    int slot=word/128,first=(word%128)*32; uint bits=0u;
                    for(int i=0;i<32;i++) if(fixtureEntry(fixtureCell(slot,first+i))>=0) bits|=1u<<uint(i);
                    return bits;
                }
                uint plagueGlassPayloadWord(int word) {
                    int slot=word/1024,first=(word%1024)*4; uint packed=0u;
                    for(int i=0;i<4;i++) packed|=uint(max(0,fixtureEntry(fixtureCell(slot,first+i))))<<uint(i*8);
                    return packed;
                }
                // Synthetic palette/face callbacks below describe 96 entries in every section.
                int plagueGlassPaletteCapacity() { return 96; }
                uint plagueGlassPaletteWord(int word) { int offset=word%16; return offset>=1&&offset<=6 ? 0xffffffffu : 0u; }
                uint plagueGlassSummaryWord(int word) { return 1u; }
                uint plagueGlassFaceWord(int word) {
                    int entry=(word/42)%plagueGlassPaletteCapacity(),offset=word%7;
                    if(offset==0) return entry==0 ? 0xe1ffffffu : 0u;
                    return floatBitsToUint(offset==1||offset==2?.25:offset==3||offset==6?.125:0.0);
                }
                vec4 plagueGlassAlbedo(vec2 uv) { return vec4(.2,.5,.8,.215); }
                vec4 plagueGlassMaterial(vec2 uv) { return vec4(1,.04,0,1); }
                bool plagueGlassBuffersValid() { return fixtureStats[1]!=0u; }
                #define plagueGlassTransport plagueGlassActualTransport
                #moj_import <fornax_runtime:glass_scene.glsl>
                #undef plagueGlassTransport
                int plagueGlassTransport(inout vec3 origin,inout vec3 direction,inout vec3 throughput,float reach,
                        bool importance,inout uint state,out PlagueGlassHit receiver,out bool crossed,out float first) {
                    atomicAdd(fixtureStats[0],1u);
                    return plagueGlassActualTransport(origin,direction,throughput,reach,importance,state,receiver,crossed,first);
                }
                """;
    }

    private static final class Runner {
        final HeadlessVulkan vk;
        final ByteBuffer globals, sections, sources, cache;
        final HeadlessVulkan.Buffer globalBuffer, sectionBuffer, sourceBuffer, cacheBuffer;
        final long setLayout, layout, pipeline;
        long set;
        final int[] types;
        final Object[] resources;
        final HeadlessVulkan.Buffer occupancy, invalidOccupancy;

        Runner(HeadlessVulkan vk, ByteBuffer spirv) {
            this.vk = vk;
            int storage = VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            types = new int[10]; Arrays.fill(types, storage);
            globalBuffer = vk.hostBuffer(null, 144, usage);
            sectionBuffer = vk.hostBuffer(null, SECTION_WORDS * 4L, usage);
            sourceBuffer = vk.hostBuffer(null, SOURCE_WORDS * 4L, usage);
            var depthPlaceholder = vk.hostBuffer(null, 16, usage);
            cacheBuffer = vk.hostBuffer(null, CACHE_WORDS * 4L, usage);
            int slots = DIMENSION * DIMENSION * DIMENSION;
            occupancy = vk.hostBuffer(null, slots * 128L * 4, usage);
            invalidOccupancy = vk.hostBuffer(null, 16, usage);
            var payload = vk.hostBuffer(null, slots * 1024L * 4, usage);
            var palette = vk.hostBuffer(null, slots * 1536L * 4, usage);
            var summary = vk.hostBuffer(null, slots * 4L, usage);
            var faces = vk.hostBuffer(null, slots * 96L * 42 * 4, usage);
            globals = globalBuffer.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            sections = sectionBuffer.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            sources = sourceBuffer.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            cache = cacheBuffer.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            globals.putInt(0, 0).putInt(4, 4).putInt(8, 0).putInt(12, DIMENSION);
            globals.putFloat(20, 64).putFloat(60, 1); // Camera world Y; disable solar path in local fixtures.
            for (int slot = 0; slot < SECTION_WORDS / 8; slot++) {
                int base = slot * 32;
                sections.putInt(base, slot % 3 - 1).putInt(base + 4, slot / 9 + 3)
                        .putInt(base + 8, (slot / 3) % 3 - 1).putInt(base + 12, 1)
                        .putInt(base + 16, slot + 1).putInt(base + 20, slot + 1).putInt(base + 24, 1);
            }
            setLayout = vk.descriptorSetLayout(types);
            layout = vk.pipelineLayout(setLayout, 0);
            pipeline = vk.computePipeline(vk.shaderModule(spirv), layout);
            resources = new Object[]{globalBuffer, sectionBuffer, sourceBuffer, depthPlaceholder,
                    occupancy, payload, palette, summary, faces, cacheBuffer};
            set = vk.descriptorSet(setLayout, types, resources);
        }

        void resetSources(int count) {
            MemoryUtil.memSet(sources, 0);
            sources.putInt(0, 2).putInt(4, CAPACITY).putInt(8, count).putInt(12, 1);
        }

        void source(int index, int x, int y, int z, float red, float green, float blue) {
            int base = (SOURCE_BASE + index * SOURCE_STRIDE) * 4;
            sources.putInt(base, x).putInt(base + 4, y).putInt(base + 8, z);
            sources.putInt(base + 16, 1 << 5).putInt(base + 20, 1).putInt(base + 24, 1).putInt(base + 28, 5);
            sources.putFloat(base + 44, red).putFloat(base + 60, green).putFloat(base + 76, blue);
            sources.putInt(base + 96, (16 << 15) | (16 << 20) | (16 << 25));
        }

        void swapSources(int a, int b) {
            for (int word = 0; word < SOURCE_STRIDE; word++) {
                int ai = (SOURCE_BASE + a * SOURCE_STRIDE + word) * 4;
                int bi = (SOURCE_BASE + b * SOURCE_STRIDE + word) * 4;
                int value = sources.getInt(ai);
                sources.putInt(ai, sources.getInt(bi)); sources.putInt(bi, value);
            }
        }

        int header(int index) { return cache.getInt(index * 4); }

        void opticalValid(boolean valid) {
            resources[4] = valid ? occupancy : invalidOccupancy;
            set = vk.descriptorSet(setLayout, types, resources);
        }

        void run() {
            var command = vk.begin();
            VK10.vkCmdBindPipeline(command, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(command, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, new long[]{set}, null);
            VK10.vkCmdDispatch(command, 1, 1, 1);
            vk.shaderToHost(command);
            vk.submitAndWait(command);
        }
    }

    private static String flatten(Path pack, Path file) throws IOException {
        return flattenText(pack, Files.readString(file));
    }

    private static String flattenText(Path pack, String source) throws IOException {
        Matcher imports = Pattern.compile("#moj_import <fornax_runtime:([^>]+)>").matcher(source);
        StringBuffer out = new StringBuffer();
        while (imports.find()) imports.appendReplacement(out, Matcher.quoteReplacement(
                flatten(pack, pack.resolve("shaders/include/" + imports.group(1)))));
        imports.appendTail(out);
        return out.toString();
    }
}

