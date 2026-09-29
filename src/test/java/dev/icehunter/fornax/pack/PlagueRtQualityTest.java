package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.config.AaMethod;
import dev.icehunter.fornax.pack.graph.EnabledIfExpr;
import dev.icehunter.fornax.pack.graph.EngineDefines;
import dev.icehunter.fornax.pack.graph.TargetPlan;
import dev.icehunter.fornax.pack.option.OptionType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real pack integration: graph budgets, allocation and UI staging must agree. */
class PlagueRtQualityTest {
    private static PackModel pack;
    private static Map<String, Integer> defaults;
    private static final int[] GRIDS = {256, 512, 768, 1024};
    private static final int[] SAMPLES = {1, 2, 4};

    @BeforeAll static void load() {
        Path root = Path.of("../plague").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(root.resolve("pack.toml")), "sibling Plague absent");
        pack = PackDiscovery.loadFrom(root, 1920, 1080);
        defaults = new LinkedHashMap<>();
        for (var option : pack.options().values()) if (option.type() == OptionType.COMPILE)
            defaults.put(option.name(), Integer.parseInt(option.defaultValue()));
        defaults.putAll(EngineDefines.forMethod(AaMethod.OFF, true));
    }

    private static GraphSpec resolve(int giGrid, int giSamples, int localGrid, int localSamples) {
        var values = new LinkedHashMap<>(defaults);
        values.put("PLAGUE_GI_GRID", giGrid);
        values.put("PLAGUE_GI_SAMPLES", giSamples);
        values.put("PLAGUE_LOCAL_GRID", localGrid);
        values.put("PLAGUE_LOCAL_SAMPLES", localSamples);
        return GraphNumericExpressions.resolve(pack.graph(), pack.options(), values);
    }

    private static PassSpec pass(GraphSpec graph, String name) {
        return graph.passes().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    private static void extent(GraphSpec graph, String name, int width, int height) {
        var size = graph.targets().get(name).fixedSize();
        assertNotNull(size, name);
        assertEquals(width, size.width(), name + " width");
        assertEquals(height, size.height(), name + " height");
    }

    @Test void all144GridAndSampleCombinationsAllocateAndDispatchMatchingIndependentBatches() {
        int combinations = 0;
        for (int gi : GRIDS) for (int gs : SAMPLES) for (int local : GRIDS) for (int ls : SAMPLES) {
            GraphSpec graph = resolve(gi, gs, local, ls);
            int giRays = gi * gi * gs, localRays = local * local * ls;
            for (String name : List.of("giRayRequests", "giRayHits", "giShadowRequests", "giShadowHits",
                    "giLampRequests", "giLampHits", "giLampShade"))
                assertEquals(giRays, graph.targets().get(name).bufferSize().count(), name);
            for (String name : List.of("giLightRequests", "giLightHits"))
                assertEquals(localRays, graph.targets().get(name).bufferSize().count(), name);
            for (String name : List.of("gi_seed_rays", "gi_shadow_seed", "gi_lamp_seed"))
                assertEquals(List.of(giRays / 256, 1, 1), pass(graph, name).dispatch(), name);
            assertEquals(List.of(localRays / 256, 1, 1), pass(graph, "gi_light_seed").dispatch());
            for (String name : List.of("gi_trace", "gi_shadow_trace", "gi_lamp_trace"))
                assertEquals(giRays, pass(graph, name).rayQuery().rayCount(), name);
            assertEquals(localRays, pass(graph, "gi_light_trace").rayQuery().rayCount());
            extent(graph, "giSurfaceRaw", gi, gi);
            extent(graph, "giLocalSurfaceRaw", local, local);
            extent(graph, "giLightVisRaw", local, local);
            extent(graph, "giLightWeight", local, local * ls);
            for (String name : List.of("giBounce", "giBounceRaw", "giBounceDirRaw", "giMomentsRaw",
                    "giBounceMid", "giBounceDirMid", "giBounceDir", "giBounceStep2", "giBounceDirStep2",
                    "giBounceStep4", "giBounceDirStep4")) extent(graph, name, gi, gi);
            for (String name : List.of("giCacheLight", "giCacheDir", "giCacheMoments", "giCacheSurface"))
                extent(graph, name, gi, gi * 4);
            extent(graph, "giCacheTransform", 5, 1);
            assertEquals(List.of(gi / 16, gi / 16, 1), pass(graph, "gi_cache_update").dispatch(),
                    "cache update must visit every GI cell exactly once at grid " + gi);
            assertTrue(pass(graph, "gi_light_resolve").inputs().contains("giLocalSurfaceRaw.history"));
            assertFalse(pass(graph, "gi_light_resolve").inputs().contains("giSurfaceRaw.history"));
            combinations++;
        }
        assertEquals(144, combinations);
    }

    private static boolean enabled(String expression, Map<String, Integer> values) {
        return expression == null || EnabledIfExpr.parse(expression).evaluate(values);
    }

    @Test void glassContinuationBudgetsUseEngineGatesAndRetainOneQueryAfterEveryBoundary() {
        for (int grid : GRIDS) for (int samples : SAMPLES) for (int interfaces : new int[]{2, 4, 8, 16}) {
            GraphSpec graph = resolve(grid, samples, 512, 1);
            for (int glass : new int[]{0, 1}) {
                var values = new LinkedHashMap<>(defaults);
                values.put("PLAGUE_GI", 1);
                values.put("PLAGUE_GLASS_TRANSPORT", glass);
                values.put("PLAGUE_GI_GLASS_INTERFACES", interfaces);
                List<PassSpec> active = graph.passes().stream()
                        .filter(p -> enabled(p.enabledIf(), values)).toList();
                List<String> names = active.stream().map(PassSpec::name).toList();
                assertEquals(glass != 0, names.contains("gi_glass_begin"));
                assertEquals(glass != 0, names.contains("gi_glass_finalize"));
                int previous = names.indexOf("gi_trace");
                assertTrue(previous >= 0);
                for (int stage = 1; stage <= 16; stage++) {
                    boolean expected = glass != 0 && stage <= interfaces;
                    int step = names.indexOf("gi_glass_step_" + stage);
                    int trace = names.indexOf("gi_glass_trace_" + stage);
                    assertEquals(expected, step >= 0);
                    assertEquals(expected, trace >= 0);
                    if (expected) {
                        assertTrue(step > previous && trace == step + 1);
                        assertEquals(grid * grid * samples, active.get(trace).rayQuery().rayCount());
                        previous = trace;
                    }
                }
                if (glass != 0) {
                    assertTrue(names.indexOf("gi_glass_finalize") > previous);
                    assertTrue(names.indexOf("gi_shadow_seed") > names.indexOf("gi_glass_finalize"));
                }
                var plan = TargetPlan.compute(graph, values, 1920, 1080);
                long bytes = plan.bufferEntries().stream().filter(t -> t.name().startsWith("giGlass"))
                        .mapToLong(TargetPlan.BufferEntry::sizeBytes).sum();
                // State32 + terminal request32 + terminal hit36 bytes, independent of stages.
                assertEquals(glass == 0 ? 0L : 100L * grid * grid * samples, bytes);
            }
        }
    }

    @Test void giAndLocalOffStatesIndependentlyRemoveTheirRequestsAndHistories() {
        GraphSpec graph = resolve(768, 2, 256, 4);
        for (int gi : new int[]{0, 1}) for (int local : new int[]{0, 1}) for (int voxel : new int[]{0, 1}) {
            var values = new LinkedHashMap<>(defaults);
            values.put("PLAGUE_GI", gi);
            values.put("PLAGUE_LOCAL_SHADOWS", local);
            values.put("PLAGUE_LOCAL_LIGHTING", voxel);
            var plan = TargetPlan.compute(graph, values, 1920, 1080);
            var buffers = plan.bufferEntries().stream().map(TargetPlan.BufferEntry::name).toList();
            assertEquals(gi != 0, buffers.contains("giRayRequests"));
            assertEquals(gi != 0, plan.find("giSurfaceRaw").isPresent());
            assertEquals(local != 0, buffers.contains("giLightRequests"));
            assertEquals(local != 0, plan.find("giLocalSurfaceRaw").isPresent());
            assertEquals(local != 0, plan.find("giLightWeight").isPresent());
            assertEquals(local != 0 || voxel != 0, plan.find("giLightVisRaw").isPresent());
            assertEquals(gi != 0, enabled(pass(graph, "gi_cache_update").enabledIf(), values));
            assertEquals(local != 0, enabled(pass(graph, "gi_light_trace").enabledIf(), values));
        }
    }

    @Test void sunGlassAndReflectionOffStatesRemainIndependentOfWorkBudgets() {
        GraphSpec graph = resolve(1024, 4, 1024, 4);
        for (int sun : new int[]{0, 1}) for (int glass : new int[]{0, 1}) for (int reflection : new int[]{0, 1, 2}) {
            var values = new LinkedHashMap<>(defaults);
            values.put("RT_SHADOWS", sun);
            values.put("PLAGUE_GLASS_TRANSPORT", glass);
            values.put("SSR_QUALITY", reflection);
            var plan = TargetPlan.compute(graph, values, 1920, 1080);
            var buffers = plan.bufferEntries().stream().map(TargetPlan.BufferEntry::name).toList();
            assertEquals(sun != 0, buffers.contains("sunShadowRequests"));
            assertEquals(sun != 0, enabled(pass(graph, "sun_shadow_trace").enabledIf(), values));
            assertEquals(glass != 0, buffers.contains("glassPhotons"));
            assertEquals(glass != 0, enabled(pass(graph, "glass_photon_trace").enabledIf(), values));
            assertEquals(reflection != 0, plan.find("mirrorHdr").isPresent());
            long traces = graph.passes().stream().filter(p -> "shaders/post/ssr_trace.fsh".equals(p.shader()))
                    .filter(p -> enabled(p.enabledIf(), values)).count();
            assertEquals(reflection == 0 ? 0L : 1L, traces);
        }
    }

    @Test void qualityMetasMatchExistingDefaultsAndOnlyStageTheirOwnWorkControls() {
        Map<String, Set<String>> controls = new LinkedHashMap<>();
        controls.put("GI_QUALITY", Set.of("PLAGUE_GI_GRID", "PLAGUE_GI_SAMPLES", "PLAGUE_GI_GLASS_INTERFACES"));
        controls.put("LOCAL_RT_QUALITY", Set.of("PLAGUE_LOCAL_GRID", "PLAGUE_LOCAL_SAMPLES"));
        controls.put("SUN_RT_QUALITY", Set.of("SHADOW_RESOLUTION", "SHADOW_SAMPLES", "u_ShadowDistance", "u_RtShadowDistance"));
        controls.put("REFLECTION_QUALITY", Set.of("SSR_QUALITY", "u_SsrTraceQuality", "u_SsrMaxDistance"));
        controls.put("GLASS_QUALITY", Set.of("PLAGUE_GLASS_SAMPLES"));
        for (var entry : controls.entrySet()) {
            var meta = pack.screens().metas().get(entry.getKey());
            assertNotNull(meta, entry.getKey());
            assertEquals(entry.getKey().equals("GLASS_QUALITY") ? "High" : "Medium",
                    MetaMatch.matchingTier(meta, Map.of(), pack.options()), entry.getKey());
            for (String tier : meta.values()) {
                var staged = MetaMatch.stagingPlan(meta, tier, pack.options());
                assertEquals(entry.getValue(), staged.keySet(), "quality must not reset material or enablement choices");
                assertEquals(tier, MetaMatch.matchingTier(meta, staged, pack.options()));
                for (var value : staged.entrySet()) {
                    var option = pack.options().get(value.getKey());
                    if (!option.allowedValues().isEmpty()) assertTrue(option.allowedValues().contains(value.getValue()));
                    if (option.range() != null) {
                        double number = Double.parseDouble(value.getValue());
                        assertTrue(number >= option.range().min() && number <= option.range().max());
                        assertEquals(Math.rint((number-option.range().min())/option.range().step()),
                                (number-option.range().min())/option.range().step(), 1e-7);
                    }
                }
            }
        }
        assertEquals(512, defaults.get("PLAGUE_GI_GRID"));
        assertEquals(1, defaults.get("PLAGUE_GI_SAMPLES"));
        assertEquals(512, defaults.get("PLAGUE_LOCAL_GRID"));
        assertEquals(1, defaults.get("PLAGUE_LOCAL_SAMPLES"));
    }

    @Test void reportDeclaredGiAndLocalAllocationIncludingPingPongHistories() {
        var giMeta = pack.screens().metas().get("GI_QUALITY");
        var localMeta = pack.screens().metas().get("LOCAL_RT_QUALITY");
        long previous = 0;
        for (String tier : giMeta.values()) {
            var values = new LinkedHashMap<>(defaults);
            for (var meta : List.of(giMeta, localMeta))
                MetaMatch.stagingPlan(meta, tier, pack.options()).forEach((k, v) -> values.put(k, Integer.parseInt(v)));
            values.put("PLAGUE_GI", 1);
            values.put("PLAGUE_LOCAL_SHADOWS", 1);
            var graph = GraphNumericExpressions.resolve(pack.graph(), pack.options(), values);
            var plan = TargetPlan.compute(graph, values, 1920, 1080);
            long bytes = plan.bufferEntries().stream().filter(t -> t.name().startsWith("gi"))
                    .mapToLong(TargetPlan.BufferEntry::sizeBytes).sum();
            for (var texture : plan.entries()) if (texture.name().startsWith("gi"))
                bytes += (long) texture.width() * texture.height() * texture.format().bytesPerPixel()
                        * (texture.history() ? 2 : 1);
            assertTrue(bytes > previous, "larger named work budgets must allocate more");
            previous = bytes;
            System.out.printf("RT_QUALITY_ALLOCATION %s %.2f MiB (GI/local declared targets; excludes RT mirrors, scene/voxel geometry and atlases)%n",
                    tier, bytes / (1024.0 * 1024.0));
        }
    }
}
