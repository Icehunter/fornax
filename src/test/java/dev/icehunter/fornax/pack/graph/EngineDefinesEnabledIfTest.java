package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import dev.icehunter.fornax.pack.TargetSpec;
import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link EngineDefines}' {@code FX_TAA}/{@code FX_UPSCALE}/{@code FX_METHOD_*}/{@code FX_COMPUTE}
 * keys are NEVER pack-declared: they exist only as a runtime overlay {@code GraphRunner.rebuild}
 * always applies to {@code compileValues}, never as a pack-declared {@link PackOption} (scanned from
 * a {@code //[...] compile} annotation in pack shader source). {@code
 * GraphValidator.checkEnabledIf} carves these names out via {@link EngineDefines#KEYS}, mirroring
 * the existing {@code sunShadowMap}/{@code sceneHistory}/{@code packOptions} engine-owned-name
 * precedent, so a pack's {@code graph.toml} can reference {@code FX_COMPUTE} in an {@code
 * enabled_if} (exactly what {@code voxelWaterRefl}'s tier-4 gate does) without {@code
 * GraphValidator.validate} refusing the whole pack over an unrecognized option name. This test
 * proves it with the pack's REAL gate string (byte-identical target/pass gates, so {@code
 * checkGateConsistency}'s short-circuit is also exercised, not just {@code checkEnabledIf}).
 */
class EngineDefinesEnabledIfTest {
    private static final String GATE = "SSR_WATER_MODE > 3 && SSR_QUALITY != 0 && FX_COMPUTE";

    private static PackOption enumOption(String name, List<String> values) {
        return new PackOption(name, OptionType.COMPILE, null, values, false, false, values.get(0), name, Map.of());
    }

    @Test
    void engineFactAloneInEnabledIfNoLongerThrowsUnknownOption() {
        // The narrowest reproduction: a target gated on FX_COMPUTE alone, no pack option declared at
        // all -- this is exactly what threw before the fix (checkEnabledIf's "unknown option").
        TargetSpec target = new TargetSpec("probe", "rgba16f", 1.0, false, "FX_COMPUTE");
        GraphSpec graph = new GraphSpec(Map.of("probe", target), List.of());
        assertDoesNotThrow(() -> GraphValidator.validate(graph, Map.of(), 1920, 1080));
    }

    @Test
    void voxelWaterReflGateValidatesWithFxComputeAndDeclaredEnumOptions() {
        Map<String, PackOption> options = new LinkedHashMap<>();
        options.put("SSR_WATER_MODE", enumOption("SSR_WATER_MODE", List.of("0", "1", "2", "3", "4")));
        options.put("SSR_QUALITY", enumOption("SSR_QUALITY", List.of("0", "1", "2")));
        // FX_COMPUTE is deliberately absent from options -- it is never pack-declared (see class doc).

        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("voxelOccupancy", TargetSpec.buffer("voxelOccupancy", null));
        targets.put("voxelPayload", TargetSpec.buffer("voxelPayload", null));
        targets.put("voxelPalette", TargetSpec.buffer("voxelPalette", null));
        targets.put("voxelWaterRefl", new TargetSpec("voxelWaterRefl", null, 0.0, false, GATE,
                TargetBasis.RENDER, TargetKind.BUFFER));

        PassSpec computePass = new PassSpec("voxel_water_refl", PassType.COMPUTE, null, null,
                "shaders/compute/voxel_water_refl.comp",
                List.of("builtin.waterNormal", "builtin.waterDepth", "voxelOccupancy", "voxelPayload", "voxelPalette"),
                List.of("voxelWaterRefl"), null, GATE, List.of(1, 1, 1), null, null, null);
        PassSpec debugPass = new PassSpec("voxel_water_debug", PassType.FULLSCREEN, null, "voxel_water_debug",
                null, List.of("builtin.waterNormal", "voxelWaterRefl"), List.of("builtin.output"),
                null, GATE, List.of(), null, "translucent", null);

        GraphSpec graph = new GraphSpec(targets, List.of(computePass, debugPass));
        assertDoesNotThrow(() -> GraphValidator.validate(graph, options, 1920, 1080));
    }

    @Test
    void unknownNonEngineOptionStillThrows() {
        // The fix must not blanket-disable the unknown-option check -- only the seven EngineDefines
        // keys are exempt.
        TargetSpec target = new TargetSpec("probe", "rgba16f", 1.0, false, "NOT_A_REAL_OPTION");
        GraphSpec graph = new GraphSpec(Map.of("probe", target), List.of());
        assertThrows(FornaxPackError.class, () -> GraphValidator.validate(graph, Map.of(), 1920, 1080));
    }
}
