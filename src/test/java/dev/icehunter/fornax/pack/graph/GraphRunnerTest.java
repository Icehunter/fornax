package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import dev.icehunter.fornax.pack.TargetSpec;
import dev.icehunter.fornax.pipeline.SkyProbe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphRunnerTest {
    private static TargetSpec storageTexture(String name, boolean history) {
        return new TargetSpec(name, "rgba16f", 1.0, history, null,
                dev.icehunter.fornax.pack.graph.TargetBasis.RENDER,
                dev.icehunter.fornax.pack.graph.TargetKind.TEXTURE,
                dev.icehunter.fornax.pack.graph.TargetFilter.NEAREST,
                null, new TextureSize(512, 512), true);
    }

    @Test
    void storageWriteWithNoGraphicsUserNeedsNoGraphicsCompletion() {
        PassSpec stepA = new PassSpec("water_step_a", PassType.COMPUTE, null, null,
                "shaders/compute/water_step_a.comp", List.of("waterWaveB.history"),
                List.of("waterWaveA"), null, null, List.of(16, 16, 1), null, null, null);
        GraphSpec graph = new GraphSpec(Map.of(
                "waterWaveA", storageTexture("waterWaveA", false),
                "waterWaveB", storageTexture("waterWaveB", true)), List.of(stepA));

        assertFalse(GraphRunner.computeStorageWriteNeedsGraphicsCompletion(stepA, graph, Map.of()));
    }

    @Test
    void historyPingPongWriteWaitsWhenPriorGraphicsCanStillReadPhysicalImage() {
        PassSpec stepB = new PassSpec("water_step_b", PassType.COMPUTE, null, null,
                "shaders/compute/water_step_b.comp", List.of("waterWaveA"),
                List.of("waterWaveB"), null, null, List.of(16, 16, 1), null, null, null);
        PassSpec terrain = new PassSpec("terrain", PassType.GEOMETRY,
                dev.icehunter.fornax.pack.GeometrySlot.DEFAULT, "terrain", null,
                List.of("waterWaveB.history"), List.of(), null, null, List.of(), null, null, null);
        GraphSpec graph = new GraphSpec(Map.of(
                "waterWaveA", storageTexture("waterWaveA", false),
                "waterWaveB", storageTexture("waterWaveB", true)), List.of(stepB, terrain));

        assertTrue(GraphRunner.computeStorageWriteNeedsGraphicsCompletion(stepB, graph, Map.of()));
    }

    @Test
    void disabledGraphicsReaderDoesNotRequireGraphicsCompletion() {
        PassSpec writer = new PassSpec("writer", PassType.COMPUTE, null, null,
                "shaders/compute/writer.comp", List.of(), List.of("field"), null, null,
                List.of(1, 1, 1), null, null, null);
        PassSpec reader = new PassSpec("reader", PassType.FULLSCREEN, null, null,
                "shaders/post/reader.fsh", List.of("field"), List.of("builtin.output"),
                null, "ADVANCED_EFFECTS", List.of(), null, null, null);
        GraphSpec graph = new GraphSpec(Map.of("field", storageTexture("field", false)),
                List.of(writer, reader));

        assertFalse(GraphRunner.computeStorageWriteNeedsGraphicsCompletion(
                writer, graph, Map.of("ADVANCED_EFFECTS", 0)));
        assertTrue(GraphRunner.computeStorageWriteNeedsGraphicsCompletion(
                writer, graph, Map.of("ADVANCED_EFFECTS", 1)));
    }

    @Test
    void preOpaqueLightingComputeIncludesOnlyIndependentLightingProducers() {
        assertTrue(GraphRunner.isPreOpaqueLightingComputePass(computePass("light_inject")));
        assertTrue(GraphRunner.isPreOpaqueLightingComputePass(computePass("light_propagate")));
        assertTrue(GraphRunner.isPreOpaqueLightingComputePass(computePass("light_list_reset")));
        assertTrue(GraphRunner.isPreOpaqueLightingComputePass(computePass("light_list_build")));
        assertFalse(GraphRunner.isPreOpaqueLightingComputePass(computePass("voxel_water_refl")));
        assertFalse(GraphRunner.isPreOpaqueLightingComputePass(computePass("unrelated_compute")));
        assertFalse(GraphRunner.isPreOpaqueLightingComputePass(fullscreenPass("light_list_build")));
    }

    private static PassSpec computePass(String name) {
        return new PassSpec(name, PassType.COMPUTE, null, null, "shaders/compute/" + name + ".comp",
                List.of(), List.of(), null, null, List.of(1, 1, 1), List.of(8, 8), null, null);
    }

    private static PassSpec fullscreenPass(String name) {
        return new PassSpec(name, PassType.FULLSCREEN, null, null, "shaders/post/" + name + ".fsh",
                List.of(), List.of(), null, null, List.of(), null, null, null);
    }

    @Test
    void falseWhenNoCopyPassWritesSceneDepth() {
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("resolve", PassType.FULLSCREEN, null, null, "shaders/post/resolve.fsh",
                        List.of("builtin.depth"), List.of("builtin.output"), null, null, List.of(), null, null, null)));
        assertFalse(GraphRunner.computePackDeclaresDepthCopyback(g));
    }

    @Test
    void trueWhenCopyPassWritesSceneDepth() {
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("depth_copyback", PassType.COPY, null, null, null,
                        List.of("builtin.depth"), List.of("builtin.sceneDepth"), null, null, List.of(), null, null, null)));
        assertTrue(GraphRunner.computePackDeclaresDepthCopyback(g));
    }

    @Test
    void falseWhenCopyPassWritesSomethingElse() {
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("taa_copy_in", PassType.COPY, null, null, null,
                        List.of("builtin.output"), List.of("taaRaw"), null, null, List.of(), null, null, null)));
        assertFalse(GraphRunner.computePackDeclaresDepthCopyback(g));
    }

    // --- Part A5: anyEnabledComputePassReadsVoxelGrid -------------------------------------------

    @Test
    void trueWhenEnabledComputePassReadsVoxelOccupancy() {
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("rt_shadow", PassType.COMPUTE, null, null, "shaders/compute/rt_shadow.comp",
                        List.of("voxelOccupancy", "packOptions"), List.of("rtDirect"), null, null,
                        List.of(1, 1, 1), List.of(8, 8), null, null)));
        assertTrue(GraphRunner.anyEnabledComputePassReadsVoxelGrid(g, Map.of()));
    }

    @Test
    void falseWhenNoPassReadsVoxelOccupancy() {
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("some_other_compute", PassType.COMPUTE, null, null, "shaders/compute/other.comp",
                        List.of("depth"), List.of("out"), null, null, List.of(1, 1, 1), List.of(8, 8), null, null),
                new PassSpec("resolve", PassType.FULLSCREEN, null, null, "shaders/post/resolve.fsh",
                        List.of("builtin.depth"), List.of("builtin.output"), null, null, List.of(), null, null, null)));
        assertFalse(GraphRunner.anyEnabledComputePassReadsVoxelGrid(g, Map.of()));
    }

    // A pack's sun-shadow DDA can run as a FULLSCREEN fragment pass instead of COMPUTE, texelFetching
    // voxelOccupancy directly. anyEnabledComputePassReadsVoxelGrid recognizes both pass types for this
    // reason: COMPUTE-only would leave VoxelDebugRaymarchPass.onFrame treating the grid as unneeded
    // while the fragment pass's texelFetch reads an unallocated buffer (FullscreenPassRunner.run
    // throws). These three tests lock in that behavior.
    @Test
    void trueWhenEnabledFullscreenPassReadsVoxelOccupancy() {
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("celestial_shadow", PassType.FULLSCREEN, null, null, "shaders/post/celestial_shadow.fsh",
                        List.of("builtin.gNormal", "builtin.depth", "voxelOccupancy"), List.of("celestialVisVoxel"),
                        null, null, List.of(), null, null, null)));
        assertTrue(GraphRunner.anyEnabledComputePassReadsVoxelGrid(g, Map.of()));
    }

    @Test
    void falseWhenVoxelOccupancyReadingFullscreenPassDisabledByEnabledIf() {
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("celestial_shadow", PassType.FULLSCREEN, null, null, "shaders/post/celestial_shadow.fsh",
                        List.of("builtin.gNormal", "builtin.depth", "voxelOccupancy"), List.of("celestialVisVoxel"),
                        null, "SUN_SHADOW_VOXEL_PROTO", List.of(), null, null, null)));
        assertFalse(GraphRunner.anyEnabledComputePassReadsVoxelGrid(g, Map.of("SUN_SHADOW_VOXEL_PROTO", 0)));
        assertTrue(GraphRunner.anyEnabledComputePassReadsVoxelGrid(g, Map.of("SUN_SHADOW_VOXEL_PROTO", 1)));
    }

    @Test
    void falseWhenOnlyANonComputeNonFullscreenPassReadsVoxelOccupancy() {
        // COPY/MIPCHAIN/GEOMETRY pass types are excluded even if one declared voxelOccupancy as an
        // input (see anyEnabledComputePassReadsVoxelGrid's doc). No real pack pass of those types
        // does, and admitting them would broaden this gate's meaning.
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("weird_copy", PassType.COPY, null, null, null,
                        List.of("voxelOccupancy"), List.of("builtin.output"), null, null, List.of(), null, null, null)));
        assertFalse(GraphRunner.anyEnabledComputePassReadsVoxelGrid(g, Map.of()));
    }

    @Test
    void falseWhenVoxelGridReadingComputePassDisabledByEnabledIf() {
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("rt_shadow", PassType.COMPUTE, null, null, "shaders/compute/rt_shadow.comp",
                        List.of("voxelOccupancy", "packOptions"), List.of("rtDirect"), null, "RT_SHADOWS_ENABLE",
                        List.of(1, 1, 1), List.of(8, 8), null, null)));
        assertFalse(GraphRunner.anyEnabledComputePassReadsVoxelGrid(g, Map.of("RT_SHADOWS_ENABLE", 0)));
        assertTrue(GraphRunner.anyEnabledComputePassReadsVoxelGrid(g, Map.of("RT_SHADOWS_ENABLE", 1)));
    }

    // --- anyEnabledPassReadsPrecipClipmap --------------------------------------------------------
    //
    // The gate that decides whether the engine pays to fill the per-column precipitation field. Its
    // failure modes are asymmetric and both bad, which is why it is tested rather than eyeballed: too
    // narrow and the consuming pass reads an unallocated buffer, which throws in
    // ComputePassRunner.descriptorTypeFor and aborts EVERY runner in that build attempt; too broad
    // and every pack pays 1024 biome queries a frame for a field nothing reads.

    @Test
    void trueWhenAnEnabledComputePassReadsThePrecipClipmap() {
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("snow_accumulate", PassType.COMPUTE, null, null,
                        "shaders/compute/snow_accumulate.comp",
                        List.of("voxelOccupancy", "globals", PrecipClipmapBuffer.TARGET), List.of("snowField"),
                        null, null, List.of(16, 16, 1), null, null, null)));
        assertTrue(GraphRunner.anyEnabledPassReadsPrecipClipmap(g, Map.of()));
    }

    @Test
    void falseWhenNoPassReadsThePrecipClipmap() {
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("snow_accumulate", PassType.COMPUTE, null, null,
                        "shaders/compute/snow_accumulate.comp",
                        List.of("voxelOccupancy", "globals"), List.of("snowField"),
                        null, null, List.of(16, 16, 1), null, null, null)));
        assertFalse(GraphRunner.anyEnabledPassReadsPrecipClipmap(g, Map.of()));
    }

    @Test
    void falseWhenThePrecipClipmapReaderIsDisabledByEnabledIf() {
        // The whole point of gating on compile values rather than on the pass merely existing: with
        // the pack's snow option off, the pass is never built and the fill must not run either.
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("snow_accumulate", PassType.COMPUTE, null, null,
                        "shaders/compute/snow_accumulate.comp",
                        List.of(PrecipClipmapBuffer.TARGET), List.of("snowField"),
                        null, "PLAGUE_SNOW", List.of(16, 16, 1), null, null, null)));
        assertFalse(GraphRunner.anyEnabledPassReadsPrecipClipmap(g, Map.of("PLAGUE_SNOW", 0)));
        assertTrue(GraphRunner.anyEnabledPassReadsPrecipClipmap(g, Map.of("PLAGUE_SNOW", 1)));
    }

    @Test
    void trueWhenAFullscreenPassReadsThePrecipClipmap() {
        // A fullscreen pass binds a buffer input as a real texel-buffer descriptor, so it needs the
        // target allocated for exactly the same reason a compute pass does. A future consumer
        // (dry ground in a desert, say) must not have to be a compute pass to switch the fill on.
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("gbuffer_resolve", PassType.FULLSCREEN, null, null,
                        "shaders/post/gbuffer_resolve.fsh",
                        List.of("builtin.depth", PrecipClipmapBuffer.TARGET), List.of("builtin.output"),
                        null, null, List.of(), null, null, null)));
        assertTrue(GraphRunner.anyEnabledPassReadsPrecipClipmap(g, Map.of()));
    }

    @Test
    void falseWhenOnlyACopyPassNamesThePrecipClipmap() {
        // Same deliberate restriction the voxel-grid gate carries, and stated here so the two cannot
        // drift: COPY/MIPCHAIN/GEOMETRY have no code path that binds a buffer target at all.
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("weird_copy", PassType.COPY, null, null, null,
                        List.of(PrecipClipmapBuffer.TARGET), List.of("builtin.output"),
                        null, null, List.of(), null, null, null)));
        assertFalse(GraphRunner.anyEnabledPassReadsPrecipClipmap(g, Map.of()));
    }

    @Test
    void thePrecipClipmapGateIsIndependentOfTheVoxelGridGate() {
        // Snow accumulation happens to read both, but they are separate facts about a graph and a
        // future consumer of one must not be forced to declare the other.
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("dryness", PassType.COMPUTE, null, null, "shaders/compute/dryness.comp",
                        List.of(PrecipClipmapBuffer.TARGET), List.of("drynessField"),
                        null, null, List.of(16, 16, 1), null, null, null)));
        assertTrue(GraphRunner.anyEnabledPassReadsPrecipClipmap(g, Map.of()));
        assertFalse(GraphRunner.anyEnabledComputePassReadsVoxelGrid(g, Map.of()));
    }

    // --- anyEnabledPassReadsPrecipCoarseClipmap -----------------------------------------------

    @Test
    void coarsePrecipitationGateAdmitsOnlyEnabledComputeReaders() {
        GraphSpec compute = new GraphSpec(Map.of(), List.of(
                new PassSpec("coarse_weather_reader", PassType.COMPUTE, null, null, "shaders/read",
                        List.of(PrecipCoarseClipmapBuffer.TARGET), List.of("builtin.output"),
                        null, null, List.of(1, 1, 1), null, null, null)));
        assertTrue(GraphRunner.anyEnabledComputePassReadsPrecipCoarseClipmap(compute, Map.of()));

        for (PassType type : List.of(PassType.FULLSCREEN, PassType.PARTICLES)) {
            GraphSpec graphics = new GraphSpec(Map.of(), List.of(
                    new PassSpec("coarse_weather_reader", type, null, null, "shaders/read",
                            List.of(PrecipCoarseClipmapBuffer.TARGET), List.of("builtin.output"),
                            null, null, List.of(1, 1, 1), null, null, null)));
            assertFalse(GraphRunner.anyEnabledComputePassReadsPrecipCoarseClipmap(graphics, Map.of()), type.name());
        }
    }

    @Test
    void coarsePrecipitationGateRejectsAbsentCompileDisabledAndNonBindableReaders() {
        GraphSpec absent = new GraphSpec(Map.of(), List.of(
                new PassSpec("reader", PassType.COMPUTE, null, null, "shaders/read", List.of(),
                        List.of("builtin.output"), null, null, List.of(1, 1, 1), null, null, null)));
        GraphSpec disabled = new GraphSpec(Map.of(), List.of(
                new PassSpec("reader", PassType.FULLSCREEN, null, null, "shaders/read",
                        List.of(PrecipCoarseClipmapBuffer.TARGET), List.of("builtin.output"), null,
                        "CLOUD_WEATHER", List.of(), null, null, null)));
        GraphSpec copy = new GraphSpec(Map.of(), List.of(
                new PassSpec("reader", PassType.COPY, null, null, null,
                        List.of(PrecipCoarseClipmapBuffer.TARGET), List.of("builtin.output"), null,
                        null, List.of(), null, null, null)));

        assertFalse(GraphRunner.anyEnabledComputePassReadsPrecipCoarseClipmap(absent, Map.of()));
        assertFalse(GraphRunner.anyEnabledComputePassReadsPrecipCoarseClipmap(disabled, Map.of("CLOUD_WEATHER", 0)));
        assertFalse(GraphRunner.anyEnabledComputePassReadsPrecipCoarseClipmap(copy, Map.of()));
    }

    @Test
    void coarsePrecipitationGateIsExactAndIndependentFromFinePrecipitation() {
        GraphSpec fineOnly = new GraphSpec(Map.of(), List.of(
                new PassSpec("fine", PassType.COMPUTE, null, null, "shaders/read",
                        List.of(PrecipClipmapBuffer.TARGET), List.of("builtin.output"), null, null,
                        List.of(1, 1, 1), null, null, null)));
        GraphSpec coarseOnly = new GraphSpec(Map.of(), List.of(
                new PassSpec("coarse", PassType.COMPUTE, null, null, "shaders/read",
                        List.of(PrecipCoarseClipmapBuffer.TARGET), List.of("builtin.output"), null, null,
                        List.of(1, 1, 1), null, null, null)));

        assertTrue(GraphRunner.anyEnabledPassReadsPrecipClipmap(fineOnly, Map.of()));
        assertFalse(GraphRunner.anyEnabledComputePassReadsPrecipCoarseClipmap(fineOnly, Map.of()));
        assertFalse(GraphRunner.anyEnabledPassReadsPrecipClipmap(coarseOnly, Map.of()));
        assertTrue(GraphRunner.anyEnabledComputePassReadsPrecipCoarseClipmap(coarseOnly, Map.of()));
    }

    @Test
    void aRequiredCoarseResetBlocksGraphExecutionUntilTheUploadIsReady() {
        assertTrue(GraphRunner.canExecuteGraphForCoarsePrecipitation(false, false));
        assertTrue(GraphRunner.canExecuteGraphForCoarsePrecipitation(true, true));
        assertFalse(GraphRunner.canExecuteGraphForCoarsePrecipitation(true, false));
    }

    // --- anyEnabledPassReadsSurfaceFluidClipmap ------------------------------------------------

    @Test
    void trueWhenAnEnabledComputePassReadsTheSurfaceFluidClipmap() {
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("water_step", PassType.COMPUTE, null, null,
                        "shaders/compute/water_step.comp",
                        List.of("globals", SurfaceFluidClipmapBuffer.TARGET), List.of("waveNext"),
                        null, null, List.of(16, 16, 1), null, null, null)));
        assertTrue(GraphRunner.anyEnabledPassReadsSurfaceFluidClipmap(g, Map.of()));
    }

    @Test
    void falseWhenSurfaceFluidReaderIsAbsentOrCompileDisabled() {
        GraphSpec absent = new GraphSpec(Map.of(), List.of(
                new PassSpec("water_step", PassType.COMPUTE, null, null,
                        "shaders/compute/water_step.comp", List.of("globals"), List.of("waveNext"),
                        null, null, List.of(16, 16, 1), null, null, null)));
        GraphSpec disabled = new GraphSpec(Map.of(), List.of(
                new PassSpec("water_step", PassType.COMPUTE, null, null,
                        "shaders/compute/water_step.comp", List.of(SurfaceFluidClipmapBuffer.TARGET),
                        List.of("waveNext"), null, "WATER_ENABLE", List.of(16, 16, 1), null, null, null)));
        assertFalse(GraphRunner.anyEnabledPassReadsSurfaceFluidClipmap(absent, Map.of()));
        assertFalse(GraphRunner.anyEnabledPassReadsSurfaceFluidClipmap(disabled, Map.of("WATER_ENABLE", 0)));
        assertTrue(GraphRunner.anyEnabledPassReadsSurfaceFluidClipmap(disabled, Map.of("WATER_ENABLE", 1)));
    }

    @Test
    void fullscreenAndParticlesReadersAlsoRequestSurfaceFluidUpdates() {
        // GraphValidator.checkBufferBindable legalizes COMPUTE, PARTICLES and FULLSCREEN readers of
        // a buffer target; this gate must see all three or the buffer never gets allocated.
        for (PassType type : List.of(PassType.FULLSCREEN, PassType.PARTICLES)) {
            GraphSpec graph = new GraphSpec(Map.of(), List.of(
                    new PassSpec("reader", type, null, null, "shaders/read",
                            List.of(SurfaceFluidClipmapBuffer.TARGET), List.of("builtin.output"),
                            null, null, List.of(), null, null, null)));
            assertTrue(GraphRunner.anyEnabledPassReadsSurfaceFluidClipmap(graph, Map.of()));
        }
    }

    @Test
    void onlyACopyPassCannotRequestSurfaceFluidUpdates() {
        // GraphValidator.checkBufferBindable forbids COPY from binding a buffer at all, unlike
        // FULLSCREEN/PARTICLES/COMPUTE; same restriction the voxel-grid and precip-clipmap gates carry.
        GraphSpec graph = new GraphSpec(Map.of(), List.of(
                new PassSpec("reader", PassType.COPY, null, null, "shaders/read",
                        List.of(SurfaceFluidClipmapBuffer.TARGET), List.of("builtin.output"),
                        null, null, List.of(), null, null, null)));
        assertFalse(GraphRunner.anyEnabledPassReadsSurfaceFluidClipmap(graph, Map.of()));
    }

    // --- Demotion crash: shouldMarkSourcesReady's generation guard -------------------------------
    //
    // Pins the ORDERING invariant, not just a symptom: a rebuild()'s RuntimeShaderPack.reload future
    // must never be allowed to flip sourcesReady once a NEWER rebuild() has already superseded it
    // (its own closeCurrent() bumped the generation counter again before the stale future landed).
    // Getting this comparison backwards, or dropping it, lets ensureRunnersBuilt() build a pass
    // pipeline against a stale shader-text snapshot; see GraphRunner.rebuild's own doc comment for
    // the resulting crash.

    @Test
    void currentGenerationCompletionMarksSourcesReady() {
        assertTrue(GraphRunner.shouldMarkSourcesReady(2, 2));
    }

    @Test
    void supersededGenerationCompletionDoesNotMarkSourcesReady() {
        // A rebuild whose own future lands AFTER a later rebuild has already started (closeCurrent()
        // bumped the counter past the first rebuild's captured generation) must be ignored.
        assertFalse(GraphRunner.shouldMarkSourcesReady(1, 2));
    }

    @Test
    void firstEverRebuildCompletionMarksSourcesReady() {
        assertTrue(GraphRunner.shouldMarkSourcesReady(1, 1));
    }

    // --- FX_COMPUTE gate self-heal: graphReferencesEngineCompute -------------------------------
    //
    // rebuild()'s first call runs from FornaxMod's boot-time loadConfiguredPack(), before any
    // GpuDevice exists, so EngineDefines' FX_COMPUTE overlay bakes to 0 regardless of real hardware.
    // Nothing re-derives compileValues once a real computeBackend shows up unless the player dirties
    // a COMPILE option, so a pack gating on FX_COMPUTE (e.g. WORLD_REFLECTIONS + SSR_WATER_MODE > 3)
    // stayed permanently wrong: the voxel arm never activated while its `!(... && FX_COMPUTE)`
    // sibling stayed permanently true. ensureRunnersBuilt()'s self-heal replays the pack's last
    // rebuild() once a real computeBackend is known, gated on this pure graph-scan so a pack that
    // never references FX_COMPUTE never pays the extra rebuild.

    @Test
    void graphReferencesEngineComputeTrueWhenTargetGatesOnIt() {
        GraphSpec g = new GraphSpec(
                Map.of("voxelWaterRefl", new TargetSpec("voxelWaterRefl", null, 0.0, false,
                        "WORLD_REFLECTIONS != 0 && SSR_WATER_MODE > 3 && SSR_QUALITY != 0 && FX_COMPUTE",
                        TargetBasis.RENDER, TargetKind.BUFFER)),
                List.of());
        assertTrue(GraphRunner.graphReferencesEngineCompute(g));
    }

    @Test
    void graphReferencesEngineComputeTrueWhenPassGatesOnIt() {
        GraphSpec g = new GraphSpec(Map.of(), List.of(
                new PassSpec("voxel_water_refl", PassType.COMPUTE, null, null, "shaders/compute/voxel_water_refl.comp",
                        List.of("builtin.waterNormal"), List.of("voxelWaterRefl"), null,
                        "WORLD_REFLECTIONS != 0 && SSR_WATER_MODE > 3 && SSR_QUALITY != 0 && FX_COMPUTE",
                        List.of(1, 1, 1), List.of(8, 8), null, null)));
        assertTrue(GraphRunner.graphReferencesEngineCompute(g));
    }

    @Test
    void graphReferencesEngineComputeFalseWhenNoEnabledIfMentionsIt() {
        GraphSpec g = new GraphSpec(
                Map.of("ssao", new TargetSpec("ssao", "r8", 1.0, false, "SSAO_ENABLE")),
                List.of(new PassSpec("resolve", PassType.FULLSCREEN, null, null, "shaders/post/resolve.fsh",
                        List.of("builtin.depth"), List.of("builtin.output"), null, "SSAO_ENABLE",
                        List.of(), null, null, null)));
        assertFalse(GraphRunner.graphReferencesEngineCompute(g));
    }

    @Test
    void graphReferencesEngineComputeFalseOnEmptyGraph() {
        assertFalse(GraphRunner.graphReferencesEngineCompute(new GraphSpec(Map.of(), List.of())));
    }

    @Test
    void analyticLightListDispatchCoversOnlyCameraScanCube() {
        // 64 blocks / 2 blocks per Standard cell = 32 cells each side, plus the centre = 65.
        // ceil(65 / local_size_4) = 17 groups, or 314,432 invocations instead of 8,000,000 at d=25.
        assertEquals(17, GraphRunner.analyticLightListGroups(64.0f));
    }

    @Test
    void analyticLightListDispatchRoundsFractionalRadiusOutward() {
        assertEquals(2, GraphRunner.analyticLightListGroups(2.1f));
    }

    @Test
    void analyticScanRadiusIsUnclampedWhenTheWindowIsLargerThanTheDefaultScan() {
        // diameter 25 (radius 12 sections/192 blocks, comfortably below RADIUS_CEILING's own 16
        // sections, so a real window this size is achievable) is bigger than the 64-block default
        // scan radius, so it passes through untouched. Any diameter giving a window radius > 64
        // blocks would do; this value doesn't need to track RADIUS_CEILING's own live max.
        assertEquals(64.0f, GraphRunner.clampedAnalyticScanRadiusBlocks(25), 1e-6f);
    }

    @Test
    void analyticScanRadiusClampsToASmallWindow() {
        // u_LightReach set to 1 chunk (diameter 3, radius 1 section/16 blocks): the 64-block default
        // would scan past the streamed window into wrapped/unrelated toroidal data. Clamped to the
        // window's own real radius, 16 blocks.
        assertEquals(16.0f, GraphRunner.clampedAnalyticScanRadiusBlocks(3), 1e-6f);
    }

    @Test
    void analyticScanRadiusIsUnclampedWhenTheWindowNeverActivated() {
        // diameter <= 1 (sentinel/never-activated window): isLightListBuildPass's own dispatch
        // degrades to a harmless small scan via analyticLightListGroups, not a wrap hazard; see this
        // method's own doc for why leaving it unclamped here is safe.
        assertEquals(64.0f, GraphRunner.clampedAnalyticScanRadiusBlocks(1), 1e-6f);
        assertEquals(64.0f, GraphRunner.clampedAnalyticScanRadiusBlocks(0), 1e-6f);
    }

    @Test
    void analyticLightListDispatchRejectsInvalidRadius() {
        assertEquals(0, GraphRunner.analyticLightListGroups(0.0f));
        assertEquals(0, GraphRunner.analyticLightListGroups(Float.NaN));
    }

    // --- isEmitterLightPass's sun-direction write -----------------------------------------------
    //
    // light_inject.comp's GI_SUN_BOUNCE daylight gate (clamp(sunDir.y, 0, 1)) must not be fed
    // SunDirection.computeSunDirection(): that shadow-caster helper falls back to the MOON
    // direction once the sun sets, so at night it puts a positive y through the daylight gate and
    // injects full sunlight into every sky-exposed block. The gate instead reads the TRUE sun
    // direction (u_SkyCelestial.xyz, populated per globals.glsl as "xyz = TRUE sun direction
    // (moon = -xyz)"), which stays negative once the sun is below the horizon.
    //
    // SkyFrameState's sun direction is populated only for packs that cancel vanilla's sky, so it
    // cannot be this source for every other pack without gating sun bounce off at all hours.
    // SkyProbe.read() dereferences Minecraft.getInstance(), so applyEmitterSunDirection takes the
    // resolved vector as arguments; these tests drive it through SkyProbe's pure sun-direction
    // functions, the same conversion computeParams uses at runtime, without needing a live client.

    @Test
    void emitterLightPassReceivesNegativeSunYWhenSunBelowHorizon() {
        // sunAngleRadians = PI -> sunY = cos(PI) = -1 (straight down / midnight), the exact shape of
        // the reported bug: the sun is below the horizon, so a moon-fallback vector would read
        // positive y here instead.
        float angle = (float) Math.PI;
        PassParams result = GraphRunner.applyEmitterSunDirection(PassParams.of(1920, 1080),
                SkyProbe.sunDirX(angle), SkyProbe.sunDirY(angle), 0.0f);
        assertTrue(result.sunDirY() < 0.0f,
                "emitter-light pass must receive a sun direction with negative y when the sun is below the horizon");
    }

    @Test
    void emitterLightPassReceivesPositiveSunYWhenSunAboveHorizon() {
        // sunAngleRadians = 0 -> sunY = cos(0) = 1 (solar noon).
        PassParams result = GraphRunner.applyEmitterSunDirection(PassParams.of(1920, 1080),
                SkyProbe.sunDirX(0.0f), SkyProbe.sunDirY(0.0f), 0.0f);
        assertTrue(result.sunDirY() > 0.0f);
    }
}
