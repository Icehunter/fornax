package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import dev.icehunter.fornax.pack.option.OptionScanner;
import dev.icehunter.fornax.pack.option.PackOption;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the real {@code voxel_water_refl} shape -- a compute pass reading {@code
 * builtin.waterNormal}/{@code builtin.waterDepth}/{@code sunShadowMap}/the voxel SSBOs and writing
 * a {@code kind="buffer"} target -- and confirms {@code builtin.depth_opaque} stays refused as a
 * compute input. Also exercises {@code GraphValidator.checkGateConsistency}'s domain enumeration,
 * which proves implication (or refuses a genuine mismatch) across the {@code SSR_WATER_MODE x
 * SSR_QUALITY x FX_COMPUTE} domain, including {@code EngineDefines.KEYS} names like {@code
 * FX_COMPUTE} that are never a pack-declared {@link PackOption} (see {@code
 * GraphValidator.checkEnabledIf}'s carve-out).
 */
class VoxelWaterReflGraphValidatorTest {
    // WORLD_REFLECTIONS is the master compile option prefixed onto every tier-4 gate: byte-identical
    // across the voxelWaterRefl target, the voxel_water_refl pass, and the composite consumer arm.
    private static final String GATE =
            "WORLD_REFLECTIONS != 0 && SSR_WATER_MODE > 3 && SSR_QUALITY != 0 && FX_COMPUTE";

    /** {@code SSR_WATER_MODE} (5-value tier enum) + {@code SSR_QUALITY} + {@code WORLD_REFLECTIONS}
     * (master toggle), scanned from annotated {@code #define} lines like real pack shader
     * declarations. {@code FX_COMPUTE} is NOT declared here: it is an engine-injected compile fact
     * ({@link EngineDefines#KEYS}), never scanned from pack shader source (see that field's doc). */
    private static Map<String, PackOption> waterReflOptions() {
        Map<String, String> shaderSrc = new LinkedHashMap<>();
        shaderSrc.put("shaders/post/opts.fsh", """
                #define SSR_WATER_MODE 3 //[0 1 2 3 4] compile "Water Reflections" {0="Off" 1="Highlights" 2="Traced" 3="High" 4="Beyond"}
                #define SSR_QUALITY 1 //[0 1 2] compile "Reflections" {0="Off" 1="Fancy" 2="Fast"}
                #define WORLD_REFLECTIONS 0 //[0 1] compile "World Reflections" {0="Off" 1="On"}
                """);
        return OptionScanner.scan(shaderSrc);
    }

    // --- 1. Legality: the real voxel_water_refl shape validates cleanly, including a cross-gate
    // input (ssrWater, gated "SSR_WATER_MODE > 1 && SSR_QUALITY != 0" -- a STRICT SUBSET of the pass's
    // own "> 3 && ... && FX_COMPUTE" gate, not byte-identical to it) whose implication proof needs
    // FX_COMPUTE's domain enumerated. This is the shape a pack's graph.toml ships. ---

    @Test
    void realComputeShapeWithCrossGateSsrWaterInputValidatesCleanly() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
                [targets.ssrWater]
                format = "rgba16f"
                scale = 1.0
                history = true
                enabled_if = "SSR_WATER_MODE > 1 && SSR_QUALITY != 0"

                [targets.voxelOccupancy]
                kind = "buffer"

                [targets.voxelPayload]
                kind = "buffer"

                [targets.voxelPalette]
                kind = "buffer"

                [targets.voxelWaterRefl]
                kind = "buffer"
                enabled_if = "%1$s"

                [[pass]]
                name = "voxel_water_refl"
                type = "compute"
                shader = "shaders/compute/voxel_water_refl.comp"
                dispatch = [1, 1, 1]
                enabled_if = "%1$s"
                inputs = ["builtin.waterNormal", "builtin.waterDepth", "ssrWater", "sunShadowMap", "voxelOccupancy", "voxelPayload", "voxelPalette"]
                outputs = ["voxelWaterRefl"]
                """.formatted(GATE)), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(g, waterReflOptions(), 1920, 1080));
    }

    // --- 2. builtin.depth_opaque is GEOMETRY-only; a compute pass reading it must still be refused
    // (regression coverage -- checkInputRef's existing rule, exercised against this round's shape). ---

    @Test
    void depthOpaqueRejectedAsComputeInput() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
                [targets.voxelWaterRefl]
                kind = "buffer"
                enabled_if = "%1$s"

                [[pass]]
                name = "voxel_water_refl"
                type = "compute"
                shader = "shaders/compute/voxel_water_refl.comp"
                dispatch = [1, 1, 1]
                enabled_if = "%1$s"
                inputs = ["builtin.depth_opaque"]
                outputs = ["voxelWaterRefl"]
                """.formatted(GATE)), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, waterReflOptions(), 1920, 1080));
        assertEquals("pass.voxel_water_refl.inputs", e.key());
        assertTrue(e.reason().contains("geometry pass"), e.reason());
    }

    // --- 3. Gate consistency: target + writer pass + a fullscreen consumer all sharing the
    // byte-identical gate string validate cleanly (mirrors voxelWaterRefl + voxel_water_refl +
    // its debug/composite consumer arm -- the gate-consistency law this round pins in three places). ---

    @Test
    void byteIdenticalGatesAcrossTargetWriterAndConsumerValidateCleanly() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
                [targets.voxelWaterRefl]
                kind = "buffer"
                enabled_if = "%1$s"

                [[pass]]
                name = "voxel_water_refl"
                type = "compute"
                shader = "shaders/compute/voxel_water_refl.comp"
                dispatch = [1, 1, 1]
                enabled_if = "%1$s"
                inputs = ["builtin.waterNormal"]
                outputs = ["voxelWaterRefl"]

                [[pass]]
                name = "voxel_water_debug"
                type = "fullscreen"
                shader = "shaders/post/voxel_water_debug.fsh"
                enabled_if = "%1$s"
                inputs = ["builtin.waterNormal", "voxelWaterRefl"]
                outputs = ["builtin.output"]
                """.formatted(GATE)), "graph.toml");
        assertDoesNotThrow(() -> GraphValidator.validate(g, waterReflOptions(), 1920, 1080));
    }

    // --- 4. A genuine gate MISMATCH is still caught: the consumer's gate is a wider superset
    // ("> 1" instead of "> 3") than the target's -- so at mode=2 or 3 (with FX_COMPUTE=1, SSR_QUALITY
    // != 0) the consumer is enabled while voxelWaterRefl is unallocated. The counterexample assignment
    // must be named, including FX_COMPUTE's enumerated value -- proving the domain enumeration fix
    // (test 1's implication proof) does not turn a real mismatch into a false green. ---

    @Test
    void gateMismatchAgainstNarrowerTargetIsCaughtWithNamedCounterexample() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
                [targets.voxelWaterRefl]
                kind = "buffer"
                enabled_if = "SSR_WATER_MODE > 3 && SSR_QUALITY != 0 && FX_COMPUTE"

                [[pass]]
                name = "voxel_water_refl"
                type = "compute"
                shader = "shaders/compute/voxel_water_refl.comp"
                dispatch = [1, 1, 1]
                enabled_if = "SSR_WATER_MODE > 3 && SSR_QUALITY != 0 && FX_COMPUTE"
                inputs = ["builtin.waterNormal"]
                outputs = ["voxelWaterRefl"]

                [[pass]]
                name = "voxel_water_debug"
                type = "fullscreen"
                shader = "shaders/post/voxel_water_debug.fsh"
                enabled_if = "SSR_WATER_MODE > 1 && SSR_QUALITY != 0 && FX_COMPUTE"
                inputs = ["builtin.waterNormal", "voxelWaterRefl"]
                outputs = ["builtin.output"]
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, waterReflOptions(), 1920, 1080));
        assertEquals("pass.voxel_water_debug.inputs", e.key());
        assertTrue(e.reason().contains("voxelWaterRefl"), e.reason());
        // The counterexample assignment must name FX_COMPUTE=1 -- the domain enumeration's proof
        // point. Without it this would throw the generic "cannot prove" refusal instead of a real
        // counterexample, since checkGateConsistency short-circuits on byte-identical strings but
        // these two are not identical.
        assertTrue(e.reason().contains("FX_COMPUTE=1"), e.reason());
    }

    // --- 5. Domain enumeration includes tier 4: a target enabled ONLY below mode 4 ("< 4") paired
    // with a pass enabled ONLY at mode 4 ("> 3") produces a counterexample that exists SOLELY at
    // mode=4 -- if the enumerated SSR_WATER_MODE domain excluded that tier (e.g. a stale {0,1,2,3}
    // left over from allowedValues() not being re-scanned), this pair would validate cleanly by
    // omission instead of throwing. ---

    @Test
    void domainEnumerationIncludesNewTier4() {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader("""
                [targets.belowBeyond]
                format = "r8"
                scale = 1.0
                enabled_if = "SSR_WATER_MODE < 4"

                [[pass]]
                name = "only_at_beyond"
                type = "fullscreen"
                shader = "shaders/post/only_at_beyond.fsh"
                enabled_if = "SSR_WATER_MODE > 3"
                inputs = ["belowBeyond"]
                outputs = ["builtin.output"]
                """), "graph.toml");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidator.validate(g, waterReflOptions(), 1920, 1080));
        assertEquals("pass.only_at_beyond.inputs", e.key());
        assertTrue(e.reason().contains("SSR_WATER_MODE=4"), e.reason());
    }
}
