package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import dev.icehunter.fornax.pack.TargetSpec;
import dev.icehunter.fornax.pack.layout.PackOptionsLayout;
import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK13;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-JVM test of the positional inputs+outputs -> binding-kind resolution a compute pass's
 * descriptor set is built from -- no GPU device involved, so this runs in plain JUnit. */
class ComputeTargetBindingTest {
    private static final String FILE = "graph.toml";

    @Test
    void combinesInputsThenOutputsInDeclarationOrder() {
        PassSpec spec = new PassSpec("shadow", PassType.COMPUTE, null, null, "rt_shadow",
                List.of("voxelOccupancy", "depth"), List.of("rtDirect"), null, null, List.of(1, 1, 1), null, null, null);
        List<String> combined = ComputePassRunner.combinedBindingOrder(spec);
        assertEquals(List.of("voxelOccupancy", "depth", "rtDirect"), combined,
                "inputs first (in order), then outputs (in order) -- binding N = index N");
    }

    @Test
    void emptyInputsStillOrdersOutputsFirst() {
        PassSpec spec = new PassSpec("x", PassType.COMPUTE, null, null, "s",
                List.of(), List.of("a", "b"), null, null, List.of(1, 1, 1), null, null, null);
        assertEquals(List.of("a", "b"), ComputePassRunner.combinedBindingOrder(spec));
    }

    // --- Part A2/A7.1: kind = "buffer" target parsing ------------------------------------------

    @Test
    void bufferKindTargetParsesWithNullFormat() {
        String toml = """
                [targets.voxelOccupancy]
                kind = "buffer"
                """;
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader(toml), FILE);
        TargetSpec t = graph.targets().get("voxelOccupancy");
        assertEquals(TargetKind.BUFFER, t.kind());
        assertNull(t.format());
    }

    @Test
    void bufferKindTargetRejectsTextureOnlyKeys() {
        // format/scale/history/basis are texture-only fields -- a buffer-kind target sized by
        // TargetRegistry.ensureBufferSize has none of these, so declaring any of them alongside
        // kind = "buffer" must be refused as an unknown key for this target shape.
        String toml = """
                [targets.voxelOccupancy]
                kind = "buffer"
                format = "r8"
                scale = 1.0
                history = true
                basis = "output"
                """;
        assertThrows(FornaxPackError.class, () -> PackTomlLoader.loadGraph(new StringReader(toml), FILE));
    }

    // --- Part A5/A7.2: local_size parsing on [[pass]] -------------------------------------------

    @Test
    void computeLocalSizeParsesIntoPassSpec() {
        String toml = """
                [[pass]]
                name = "rt_shadow"
                type = "compute"
                shader = "shaders/compute/rt_shadow.comp"
                local_size = [8, 8]
                dispatch = [1, 1, 1]
                inputs = []
                outputs = ["rtDirect"]
                """;
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader(toml), FILE);
        assertEquals(List.of(8, 8), graph.passes().get(0).localSize());
    }

    @Test
    void localSizeRejectedOnNonComputePass() {
        String toml = """
                [[pass]]
                name = "bad"
                type = "fullscreen"
                shader = "shaders/post/bad.fsh"
                local_size = [8, 8]
                inputs = []
                outputs = ["builtin.output"]
                """;
        assertThrows(FornaxPackError.class, () -> PackTomlLoader.loadGraph(new StringReader(toml), FILE));
    }

    @Test
    void localSizeRejectedWhenNotExactlyTwoEntries() {
        String toml = """
                [[pass]]
                name = "bad"
                type = "compute"
                shader = "shaders/compute/bad.comp"
                local_size = [8]
                dispatch = [1, 1, 1]
                inputs = []
                outputs = ["rtDirect"]
                """;
        assertThrows(FornaxPackError.class, () -> PackTomlLoader.loadGraph(new StringReader(toml), FILE));
    }

    @Test
    void localSizeRejectedWhenNonPositive() {
        String toml = """
                [[pass]]
                name = "bad"
                type = "compute"
                shader = "shaders/compute/bad.comp"
                local_size = [8, 0]
                dispatch = [1, 1, 1]
                inputs = []
                outputs = ["rtDirect"]
                """;
        assertThrows(FornaxPackError.class, () -> PackTomlLoader.loadGraph(new StringReader(toml), FILE));
    }

    // --- Part A3/A7.3: GraphValidator regression -- a buffer-kind target (no format) referenced
    // as a compute pass's input must validate cleanly, never hit TargetFormat.parse(null, ...). ---

    @Test
    void graphValidatorAcceptsBufferKindTargetAsComputeInput() {
        String toml = """
                [targets.voxelOccupancy]
                kind = "buffer"

                [targets.rtDirect]
                format = "r8"
                scale = 1.0

                [[pass]]
                name = "rt_shadow"
                type = "compute"
                shader = "shaders/compute/rt_shadow.comp"
                local_size = [8, 8]
                dispatch = [1, 1, 1]
                inputs = ["voxelOccupancy"]
                outputs = ["rtDirect"]
                """;
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader(toml), FILE);
        assertDoesNotThrow(() -> GraphValidator.validate(graph, Map.of(), 1920, 1080));
    }

    // --- Part C2/C3: u_PackOptions positional binding for COMPUTE passes ------------------------
    // Mirrors GraphRunner.rebuild()'s own computation: binding =
    // ComputePassRunner.combinedBindingOrder(p).indexOf(ComputePassRunner.PACK_OPTIONS_INPUT).

    @Test
    void packOptionsBindingMatchesCombinedBindingOrderIndex() {
        PassSpec spec = new PassSpec("rt_shadow", PassType.COMPUTE, null, null, "shaders/compute/rt_shadow.comp",
                List.of("voxelOccupancy", "packOptions"), List.of("rtDirect"), null, null,
                List.of(1, 1, 1), List.of(8, 8), null, null);
        int binding = ComputePassRunner.combinedBindingOrder(spec).indexOf(ComputePassRunner.PACK_OPTIONS_INPUT);
        assertEquals(1, binding, "packOptions is the 2nd declared input -> binding 1");

        PackOptionsLayout layout = PackOptionsLayout.build(List.of(
                new PackOption("SSAO_RADIUS", OptionType.RUNTIME, null, List.of(), false, false, "0.0",
                        "SSAO_RADIUS", Map.of())));
        String glsl = layout.glslBlock(binding);
        assertTrue(glsl.contains("layout(std140, set = 0, binding = 1) uniform u_PackOptions"),
                "the compute pass's block must declare the real binding index packOptions resolves to");
    }

    @Test
    void computePassWithoutPackOptionsInputGetsNoBindingIndex() {
        // GraphRunner.rebuild() skips prepending a u_PackOptions block entirely for a compute pass
        // whose inputs don't reference the reserved "packOptions" name -- indexOf returning -1 is the
        // signal it uses to make that call.
        PassSpec spec = new PassSpec("some_other_compute", PassType.COMPUTE, null, null, "shaders/compute/other.comp",
                List.of("depth"), List.of("out"), null, null, List.of(1, 1, 1), List.of(8, 8), null, null);
        int binding = ComputePassRunner.combinedBindingOrder(spec).indexOf(ComputePassRunner.PACK_OPTIONS_INPUT);
        assertEquals(-1, binding);
    }

    // --- Task 5c: builtin.* recognition boundary for ComputePassRunner.build()'s descriptor-type
    // loop -- the exact condition `GraphValidator.BUILTINS.contains(name)` branches on. Real
    // build()/updateAndBindDescriptorSet() GPU behavior can't be unit-tested without a live Vulkan
    // device (no test in this file exercises build() against a real device -- every test here stays
    // pure-JVM), so this covers the pure, GPU-free boundary condition directly. ------------------

    @Test
    void rtShadowDeclaredBuiltinInputsAreRecognizedAsBuiltins() {
        // rt_shadow's real graph.toml declaration: inputs = ["voxelOccupancy", "builtin.depth",
        // "builtin.gNormal", "packOptions"]. build()'s new branch must recognize both builtin names.
        assertTrue(GraphValidator.BUILTINS.contains("builtin.depth"));
        assertTrue(GraphValidator.BUILTINS.contains("builtin.gNormal"));
    }

    @Test
    void bufferAndReservedInputNamesAreNeverBuiltins() {
        // A buffer target name (voxelOccupancy) and the reserved packOptions name must never be
        // accidentally treated as a builtin -- build()'s packOptions check runs first, and a buffer
        // target name must fall through to the registry.getBuffer() branch, not this one.
        assertFalse(GraphValidator.BUILTINS.contains("voxelOccupancy"));
        assertFalse(GraphValidator.BUILTINS.contains(ComputePassRunner.PACK_OPTIONS_INPUT));
    }

    // --- sunShadowMap classification boundary for ComputePassRunner.build()'s descriptor-type loop.
    // A compute pass declaring "sunShadowMap" (voxel_water_refl) needs its own name-match branch in
    // that loop: it is NOT builtin.-prefixed (GraphValidator.checkInputRef treats ShadowMapManager.TARGET
    // as a peer of BUILTINS, not a member: see that method's own `base.equals(ShadowMapManager.TARGET)`
    // branch, and ShadowMapManager's own class doc: "Deliberately NOT a TargetRegistry target") and it
    // is never a TargetRegistry entry, so without that branch neither the BUILTINS check nor the
    // registry.getBuffer/get checks below it would ever match, and the runner build would throw
    // "references target 'sunShadowMap' which is neither an allocated buffer nor texture target" every
    // frame (the whole graph never building). build() itself needs a live Vulkan device (SPIRV compile,
    // descriptor pool/layout creation) so it can't run headless -- this covers the same pure, GPU-free
    // boundary condition the Task 5c tests above do: the exact name membership build()'s branches
    // depend on. -------------------------------

    // --- The reserved 'globals' input's descriptor classification and binding index ---------------
    // descriptorTypeFor is pure over (spec, name, registry): no GPU, no allocation, so the whole
    // classification chain build() depends on is directly testable. A headless TargetRegistry has
    // nothing allocated, which is exactly the state that proves the reserved names are matched by
    // NAME before any registry lookup can be reached.

    private static TargetRegistry emptyRegistry() {
        return TargetRegistry.create(new GraphSpec(Map.of(), List.of()), Map.of());
    }

    @Test
    void declaredStorageTextureUsesStorageImageDescriptorsInCompute() {
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader("""
                [targets.waveState]
                format = "rgba16f"
                width = 512
                height = 512
                storage = true
                """), FILE);
        TargetRegistry registry = TargetRegistry.create(graph, Map.of());
        assertEquals(VK13.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                ComputePassRunner.descriptorTypeFor(
                        computePass(List.of("waveState"), List.of()), "waveState", registry));
        assertEquals(VK13.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                ComputePassRunner.descriptorTypeFor(
                        computePass(List.of("waveState.history"), List.of()), "waveState.history", registry));
    }

    @Test
    void ordinaryComputeTextureRemainsASampledImage() {
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader("""
                [targets.lookup]
                format = "rgba16f"
                scale = 1.0
                """), FILE);
        TargetRegistry registry = TargetRegistry.create(graph, Map.of());
        assertEquals(VK13.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                ComputePassRunner.descriptorTypeFor(
                        computePass(List.of("lookup"), List.of()), "lookup", registry));
    }

    private static PassSpec computePass(List<String> inputs, List<String> outputs) {
        return new PassSpec("snow_accumulate", PassType.COMPUTE, null, null,
                "shaders/compute/snow_accumulate.comp", inputs, outputs, null, null,
                List.of(1, 1, 1), null, null, null);
    }

    @Test
    void globalsClassifiesAsAUniformBufferOnAComputePass() {
        // The classification that makes a compute pass's clock possible at all. Without this branch
        // the name falls through every check to the terminal throw, which ensureRunnersBuilt swallows
        // -- discarding every other runner in that attempt, every frame, forever.
        PassSpec spec = computePass(List.of("globals"), List.of("snowField"));
        assertEquals(VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                ComputePassRunner.descriptorTypeFor(spec, ParticlePassRunner.GLOBALS_INPUT, emptyRegistry()));
    }

    @Test
    void globalsAndPackOptionsShareTheUniformBufferTypeAndAreMatchedBeforeAnyRegistryLookup() {
        PassSpec spec = computePass(List.of("globals", "packOptions"), List.of("snowField"));
        TargetRegistry registry = emptyRegistry();
        assertEquals(VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                ComputePassRunner.descriptorTypeFor(spec, ComputePassRunner.PACK_OPTIONS_INPUT, registry));
        assertEquals(VK13.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                ComputePassRunner.descriptorTypeFor(spec, ParticlePassRunner.GLOBALS_INPUT, registry));
        // Neither is a declared target in this (empty) registry, so a lookup-first ordering would
        // reach the terminal throw instead.
        assertThrows(IllegalStateException.class,
                () -> ComputePassRunner.descriptorTypeFor(spec, "notATarget", registry));
    }

    @Test
    void globalsIsNeitherABuiltinNorTheOtherReservedName() {
        // Same boundary the sunShadowMap tests below pin: 'globals' needs its OWN branch because
        // neither the BUILTINS set nor the packOptions check would ever match it.
        assertFalse(GraphValidator.BUILTINS.contains(ParticlePassRunner.GLOBALS_INPUT));
        assertFalse(ParticlePassRunner.GLOBALS_INPUT.equals(ComputePassRunner.PACK_OPTIONS_INPUT));
        assertEquals("globals", ParticlePassRunner.GLOBALS_INPUT);
    }

    @Test
    void globalsTakesAPositionalBindingIndexLikeAnyOtherInput() {
        // The number a pack hardcodes as layout(std140, binding = N) uniform u_Globals. It is the
        // input's own position in combinedBindingOrder -- so inserting an input BEFORE it moves the
        // block, and nothing at load or run time would notice.
        PassSpec spec = computePass(List.of("snowPrev", "globals", "packOptions"), List.of("snowField"));
        List<String> order = ComputePassRunner.combinedBindingOrder(spec);
        assertEquals(1, order.indexOf(ParticlePassRunner.GLOBALS_INPUT));
        assertEquals(2, order.indexOf(ComputePassRunner.PACK_OPTIONS_INPUT));
        assertEquals(3, order.indexOf("snowField"), "outputs still come after every input");
    }

    @Test
    void sunShadowMapIsNeitherABuiltinNorAReservedInputName() {
        // Confirms the root cause directly: sunShadowMap was never a member of BUILTINS (by design --
        // it's engine-owned but not builtin.-prefixed) and isn't the reserved packOptions name either,
        // so it needs its OWN classification branch in ComputePassRunner.build() (added this task,
        // mirroring GraphValidator's own peer-of-BUILTINS treatment) rather than falling into either
        // existing check.
        assertEquals("sunShadowMap", ShadowMapManager.TARGET);
        assertFalse(GraphValidator.BUILTINS.contains(ShadowMapManager.TARGET));
        assertFalse(ShadowMapManager.TARGET.equals(ComputePassRunner.PACK_OPTIONS_INPUT));
    }

    @Test
    void computePassDeclaringSunShadowMapInputIsGraphValidatorLegal() {
        // The precondition ComputePassRunner.build() must now honor at runtime: GraphRunner never
        // builds a runner for a pass GraphValidator already rejected, so a compute pass reading
        // sunShadowMap reaching build() at all proves GraphValidator already treats it as legal
        // (Task 2) -- this pins that precondition against the exact voxel_water_refl input shape
        // (Task 3) so this test and ComputePassRunner.build()'s classification branch can't drift
        // apart silently.
        String toml = """
                [targets.voxelOccupancy]
                kind = "buffer"

                [targets.voxelPayload]
                kind = "buffer"

                [targets.voxelPalette]
                kind = "buffer"

                [targets.voxelWaterRefl]
                kind = "buffer"

                [[pass]]
                name = "voxel_water_refl"
                type = "compute"
                shader = "shaders/compute/voxel_water_refl.comp"
                dispatch = [1, 1, 1]
                inputs = ["builtin.waterNormal", "builtin.waterDepth", "sunShadowMap", "voxelOccupancy", "voxelPayload", "voxelPalette"]
                outputs = ["voxelWaterRefl"]
                """;
        GraphSpec graph = PackTomlLoader.loadGraph(new StringReader(toml), FILE);
        assertDoesNotThrow(() -> GraphValidator.validate(graph, Map.of(), 1920, 1080));

        // And the exact per-binding classification ComputePassRunner.build() computes for this pass's
        // combinedBindingOrder (inputs then outputs) -- every non-buffer, non-reserved name here must
        // resolve to a real classification, matching build()'s own branch order (BUILTINS,
        // ShadowMapManager.TARGET, registry buffer, registry texture): builtin.waterNormal/
        // builtin.waterDepth hit BUILTINS, sunShadowMap hits the branch this task adds, and the three
        // voxel*  names are buffer-kind targets -- none of them may hit the terminal "neither an
        // allocated buffer nor texture target" throw.
        List<String> binding = ComputePassRunner.combinedBindingOrder(graph.passes().get(0));
        assertEquals(List.of("builtin.waterNormal", "builtin.waterDepth", "sunShadowMap",
                "voxelOccupancy", "voxelPayload", "voxelPalette", "voxelWaterRefl"), binding);
        for (String name : binding) {
            boolean classifiable = GraphValidator.BUILTINS.contains(name)
                    || name.equals(ShadowMapManager.TARGET)
                    || graph.targets().containsKey(name); // buffer-kind here, matches registry.getBuffer's role
            assertTrue(classifiable, "binding '" + name + "' must classify to a descriptor type in "
                    + "ComputePassRunner.build() instead of hitting its terminal throw");
        }
    }
}
