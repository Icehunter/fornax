package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import dev.icehunter.fornax.pack.ParticleSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import dev.icehunter.fornax.pack.TargetSpec;
import dev.icehunter.fornax.voxel.BrickGridUpload;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two load-time rules that make a pack-owned buffer target either work or fail loudly, never
 * fail quietly: who is allowed to size it, and who is allowed to bind it.
 *
 * <p>Both guard the same failure shape. A buffer that nothing sizes, or a buffer named by a pass
 * with no code path to bind it, throws out of runner build -- which
 * {@code GraphRunner.ensureRunnersBuilt()} catches into a retry loop, discarding EVERY runner built
 * in that attempt and trying again next frame, forever. The visible symptom is not an error but a
 * pack whose entire post chain silently never runs.
 */
class GraphValidatorBufferTargetTest {
    private static final String FILE = "graph.toml";

    private static TargetSpec texture(String name) {
        return new TargetSpec(name, "rgba16f", 1.0, false, null, TargetBasis.RENDER);
    }

    private static GraphSpec graph(Map<String, TargetSpec> targets, PassSpec... passes) {
        return new GraphSpec(targets, List.of(passes));
    }

    private static Map<String, TargetSpec> targets(TargetSpec... specs) {
        Map<String, TargetSpec> map = new LinkedHashMap<>();
        for (TargetSpec s : specs) {
            map.put(s.name(), s);
        }
        return map;
    }

    /** Any non-geometry pass type; the geometry case builds its own spec, since it is the one type
     * that names a {@code program} rather than a {@code shader}. */
    private static PassSpec pass(String name, PassType type, List<String> inputs, List<String> outputs) {
        return new PassSpec(name, type, null, null, "shaders/x", inputs, outputs, null, null,
                type == PassType.COMPUTE ? List.of(1, 1, 1) : List.of(), null, null,
                type == PassType.PARTICLES ? new ParticleSpec("shaders/particles/x.vsh", 100) : null);
    }

    // --- Ownership: exactly one of the engine and the pack sizes each buffer -------------------

    @Test
    void aPackBufferWithNoDeclaredSizeIsRejected() {
        // The gap this whole syntax closes. Before it, this graph loaded cleanly and nothing --
        // TargetPlan, TargetRegistry, any engine call site -- ever allocated snowField, so the
        // compute pass below threw once per frame inside a swallowed retry loop.
        FornaxPackError error = assertThrows(FornaxPackError.class, () -> GraphValidator.validate(
                graph(targets(TargetSpec.buffer("snowField", null), texture("out")),
                        pass("sim", PassType.COMPUTE, List.of(), List.of("snowField"))),
                Map.of(), 1920, 1080));
        assertTrue(error.getMessage().contains("snowField"), error.getMessage());
    }

    @Test
    void anEngineOwnedBufferWithNoDeclaredSizeIsAccepted() {
        // Every shipped pack declares its voxel*/analyticLightList buffers exactly this way, purely
        // so the name is referenceable -- the rule above must not break them.
        assertDoesNotThrow(() -> GraphValidator.validate(
                graph(targets(TargetSpec.buffer(BrickGridUpload.OCCUPANCY_TARGET, null), texture("out")),
                        pass("rt", PassType.COMPUTE, List.of(BrickGridUpload.OCCUPANCY_TARGET), List.of("out"))),
                Map.of(), 1920, 1080));
    }

    @Test
    void anEngineOwnedBufferWithADeclaredSizeIsRejected() {
        // The other direction, and it matters just as much: the engine's own ensureBufferSize call
        // site overwrites whatever the pack wrote, so the declared number would be a lie in
        // graph.toml that nothing ever contradicts.
        assertThrows(FornaxPackError.class, () -> GraphValidator.validate(
                graph(targets(TargetSpec.buffer(BrickGridUpload.OCCUPANCY_TARGET, null, new BufferSize(4, 16)),
                                texture("out")),
                        pass("rt", PassType.COMPUTE, List.of(BrickGridUpload.OCCUPANCY_TARGET), List.of("out"))),
                Map.of(), 1920, 1080));
    }

    @Test
    void everyEngineOwnedBufferNameIsListed() {
        // The set is the ONLY thing distinguishing "the engine will size this" from "nothing will".
        // A name dropped from it turns a shipped pack's declaration into a load error; a name added
        // to it by mistake turns a pack's own buffer into one nothing allocates. Pinned against the
        // owning classes' own constants so a rename cannot silently split them.
        assertEquals(java.util.Set.of(
                        BrickGridUpload.INDEX_GRID_TARGET, BrickGridUpload.OCCUPANCY_TARGET,
                        BrickGridUpload.PAYLOAD_TARGET, BrickGridUpload.FACE_SEAL_TARGET,
                        BrickGridUpload.PALETTE_TARGET, BrickGridUpload.LIGHT_VOLUME_TARGET,
                        BrickGridUpload.BRICK_SUMMARY_TARGET,
                        VoxelWaterReflBuffer.TARGET, AnalyticLightListBuffer.TARGET,
                        PrecipClipmapBuffer.TARGET, SurfaceFluidClipmapBuffer.TARGET,
                        WaterActorBuffer.TARGET),
                GraphValidator.ENGINE_BUFFERS);
    }

    // --- Bindability: only the pass types with a code path for a buffer may name one ------------

    @Test
    void computeMayReadAndWriteAPackBuffer() {
        assertDoesNotThrow(() -> GraphValidator.validate(
                graph(targets(TargetSpec.buffer("snowField", null, new BufferSize(4, 16)),
                                TargetSpec.buffer("snowPrev", null, new BufferSize(4, 16))),
                        pass("sim", PassType.COMPUTE, List.of("snowPrev"), List.of("snowField"))),
                Map.of(), 1920, 1080));
    }

    @Test
    void particlesAndFullscreenMayReadAPackBuffer() {
        assertDoesNotThrow(() -> GraphValidator.validate(
                graph(targets(TargetSpec.buffer("flakes", null, new BufferSize(32, 4096)),
                                texture("sceneColor")),
                        pass("sim", PassType.COMPUTE, List.of(), List.of("flakes")),
                        pass("draw", PassType.PARTICLES, List.of("flakes"), List.of("sceneColor")),
                        pass("composite", PassType.FULLSCREEN, List.of("flakes"), List.of("builtin.output"))),
                Map.of(), 1920, 1080));
    }

    @Test
    void aFullscreenPassMayNotWriteAPackBuffer() {
        // Blaze3D's fragment pipeline has no storage-buffer uniform type at all, so there is no code
        // path that could ever satisfy this. Left to runner build it becomes requireTarget().format()
        // on a target with no TargetInstance -- a throw that aborts every OTHER runner too.
        assertThrows(FornaxPackError.class, () -> GraphValidator.validate(
                graph(targets(TargetSpec.buffer("snowField", null, new BufferSize(4, 16))),
                        pass("bad", PassType.FULLSCREEN, List.of(), List.of("snowField"))),
                Map.of(), 1920, 1080));
    }

    @Test
    void aMipchainPassMayNotTargetAPackBuffer() {
        PassSpec mip = new PassSpec("bad", PassType.MIPCHAIN, null, null, "shaders/x",
                List.of(), List.of(), "snowField", null, List.of(), null, null, null);
        assertThrows(FornaxPackError.class, () -> GraphValidator.validate(
                graph(targets(TargetSpec.buffer("snowField", null, new BufferSize(4, 16))), mip),
                Map.of(), 1920, 1080));
    }

    @Test
    void aGeometryPassMayNotReadAPackBuffer() {
        // A geometry pass's inputs resolve into Sodium's shared terrain bind group as plain samplers
        // (GraphInputResolver.resolveView has only textures to hand back), so this would bind a
        // texture slot to nothing.
        //
        // The graph deliberately includes a compute pass that WRITES snowField. Without it,
        // checkGeometryInputFinality rejects the graph first ("never written this frame") and this
        // test passes no matter what the buffer-bindability rule says -- mutation-verified: with the
        // rule disabled entirely, the writer-less version of this test still passed.
        PassSpec geom = new PassSpec("terrain", PassType.GEOMETRY, null, "shaders/blocks/terrain", null,
                List.of("snowField"), List.of(), null, null, List.of(), null, null, null);
        assertThrows(FornaxPackError.class, () -> GraphValidator.validate(
                graph(targets(TargetSpec.buffer("snowField", null, new BufferSize(4, 16))),
                        pass("sim", PassType.COMPUTE, List.of(), List.of("snowField")), geom),
                Map.of(), 1920, 1080));
    }

    @Test
    void aCopyPassMayNotReadAPackBuffer() {
        // The remaining read-side type. A copy pass has no descriptor set at all -- CopyRunner
        // resolves both ends as textures -- so a buffer here resolves to nothing.
        assertThrows(FornaxPackError.class, () -> GraphValidator.validate(
                graph(targets(TargetSpec.buffer("snowField", null, new BufferSize(4, 16)), texture("out")),
                        pass("sim", PassType.COMPUTE, List.of(), List.of("snowField")),
                        pass("blit", PassType.COPY, List.of("snowField"), List.of("out"))),
                Map.of(), 1920, 1080));
    }

    // --- VRAM accounting -------------------------------------------------------------------------

    @Test
    void aPackBufferIsAccountedForInTheVramReport() {
        // Real, permanently-held VRAM the pack asked for. Omitting it understates every pack that
        // owns a buffer, with nothing in the log to say so -- the same reason the engine-injected
        // sceneHistory pair is accounted for explicitly.
        GraphSpec bare = graph(targets(texture("out")),
                pass("p", PassType.FULLSCREEN, List.of(), List.of("out")));
        GraphSpec withBuffer = graph(targets(texture("out"),
                        TargetSpec.buffer("snowField", null, new BufferSize(4, 65536))),
                pass("p", PassType.FULLSCREEN, List.of(), List.of("out")),
                pass("sim", PassType.COMPUTE, List.of(), List.of("snowField")));

        VramReport before = GraphValidator.validate(bare, Map.of(), 1920, 1080);
        VramReport after = GraphValidator.validate(withBuffer, Map.of(), 1920, 1080);

        assertEquals(before.totalBytes() + 262144L, after.totalBytes());
        assertTrue(after.lines().stream().anyMatch(l -> l.contains("snowField")),
                "the buffer needs its own report row, not just a bump in the total");
    }

    // --- End to end through the loader, in the shape a pack actually writes ----------------------

    @Test
    void theWholeDeclarationRoundTripsFromToml() {
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader("""
                [targets.snowAccumulation]
                kind = "buffer"
                stride_bytes = 4
                count = 262144

                [targets.sceneColor]
                format = "rgba16f"
                scale = 1.0

                [[pass]]
                name = "snow_accumulate"
                type = "compute"
                shader = "shaders/compute/snow_accumulate.comp"
                dispatch = [1024, 1, 1]
                inputs = ["globals", "packOptions"]
                outputs = ["snowAccumulation"]
                """), FILE);

        assertDoesNotThrow(() -> GraphValidator.validate(graph, Map.of(), 1920, 1080));
        assertEquals(1048576L,
                TargetPlan.compute(graph, Map.of(), 1920, 1080).bufferEntries().get(0).sizeBytes());
    }
}
