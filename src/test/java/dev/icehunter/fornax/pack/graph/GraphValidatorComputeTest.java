package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import dev.icehunter.fornax.pack.TargetSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphValidatorComputeTest {
    private static TargetSpec target(String name) {
        return new TargetSpec(name, "rgba16f", 1.0, false, null, TargetBasis.RENDER);
    }

    @Test
    void computePassWithAnOutputValidates() {
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("probeAtlas", target("probeAtlas"));
        PassSpec pass = new PassSpec("voxel_probe_update", PassType.COMPUTE, null, null,
                "shaders/compute/voxel_probe_update.comp", List.of(), List.of("probeAtlas"),
                null, null, List.of(8, 8, 1), null, null, null);
        GraphSpec graph = new GraphSpec(targets, List.of(pass));
        assertDoesNotThrow(() -> GraphValidator.validate(graph, Map.of(), 1920, 1080));
    }

    @Test
    void computePassWithNoOutputsIsRejected() {
        PassSpec pass = new PassSpec("pointless", PassType.COMPUTE, null, null,
                "shaders/compute/pointless.comp", List.of(), List.of(),
                null, null, List.of(8, 8, 1), null, null, null);
        GraphSpec graph = new GraphSpec(Map.of(), List.of(pass));
        assertThrows(FornaxPackError.class, () -> GraphValidator.validate(graph, Map.of(), 1920, 1080));
    }

    @Test
    void computePassWithPackOptionsInputValidatesWithNoDeclaredTarget() {
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("shadowMask", target("shadowMask"));
        PassSpec pass = new PassSpec("rt_shadow", PassType.COMPUTE, null, null,
                "shaders/compute/rt_shadow.comp", List.of("packOptions"), List.of("shadowMask"),
                null, null, List.of(8, 8, 1), null, null, null);
        GraphSpec graph = new GraphSpec(targets, List.of(pass));
        assertDoesNotThrow(() -> GraphValidator.validate(graph, Map.of(), 1920, 1080));
    }

    @Test
    void nonComputePassWithPackOptionsInputIsRejected() {
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("shadowMask", target("shadowMask"));
        PassSpec pass = new PassSpec("shadow_composite", PassType.FULLSCREEN, null, "shadow_composite",
                null, List.of("packOptions"), List.of("shadowMask"),
                null, null, List.of(), null, null, null);
        GraphSpec graph = new GraphSpec(targets, List.of(pass));
        assertThrows(FornaxPackError.class, () -> GraphValidator.validate(graph, Map.of(), 1920, 1080));
    }

    // --- The reserved 'globals' input on a compute pass -----------------------------------------
    // A compute pass's only other per-frame channel is the PassParams push constant, whose two free
    // scalars GraphRunner.computeParams fills in BY PASS NAME -- so a pack-authored pass name the
    // engine does not recognize receives zeros in every one of them, every frame, with nothing
    // anywhere reporting it. Binding u_Globals is what gives such a pass a clock (wind clock, frame
    // counter), a weather anchor and the true sun direction. These tests pin the name/type pair that
    // makes that possible; nothing else in the system would notice it being refused again.

    @Test
    void computePassWithGlobalsInputValidatesWithNoDeclaredTarget() {
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("snowField", target("snowField"));
        PassSpec pass = new PassSpec("snow_accumulate", PassType.COMPUTE, null, null,
                "shaders/compute/snow_accumulate.comp", List.of("globals"), List.of("snowField"),
                null, null, List.of(8, 8, 1), null, null, null);
        GraphSpec graph = new GraphSpec(targets, List.of(pass));
        assertDoesNotThrow(() -> GraphValidator.validate(graph, Map.of(), 1920, 1080));
    }

    @Test
    void fullscreenPassWithGlobalsInputIsStillRejected() {
        // Unchanged, and deliberately so: FullscreenPassRunner.build binds u_Globals into every
        // fullscreen pass's bind group unconditionally, so naming it here would bind nothing while
        // silently shifting the pass's other sampler indices by one.
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("shadowMask", target("shadowMask"));
        PassSpec pass = new PassSpec("composite", PassType.FULLSCREEN, null, "composite",
                null, List.of("globals"), List.of("shadowMask"),
                null, null, List.of(), null, null, null);
        GraphSpec graph = new GraphSpec(targets, List.of(pass));
        assertThrows(FornaxPackError.class, () -> GraphValidator.validate(graph, Map.of(), 1920, 1080));
    }

    @Test
    void copyPassWithGlobalsInputIsStillRejected() {
        // The generic "every other pass type" arm -- a copy pass has no descriptor set at all, so the
        // name would resolve to nothing at runner build.
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("shadowMask", target("shadowMask"));
        PassSpec pass = new PassSpec("blit", PassType.COPY, null, null, null,
                List.of("globals"), List.of("shadowMask"),
                null, null, List.of(), null, null, null);
        GraphSpec graph = new GraphSpec(targets, List.of(pass));
        assertThrows(FornaxPackError.class, () -> GraphValidator.validate(graph, Map.of(), 1920, 1080));
    }

    @Test
    void passWithSunShadowMapInputValidatesWithNoDeclaredTarget() {
        // sunShadowMap is engine-owned (ShadowMapManager), never a pack-declared target -- like
        // sceneHistory and packOptions, it must be recognized by name here or a pack's resolve
        // pass sampling it would fail load-time validation.
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("shadowMask", target("shadowMask"));
        PassSpec pass = new PassSpec("shadow_resolve", PassType.FULLSCREEN, null, "shadow_resolve",
                null, List.of("sunShadowMap"), List.of("shadowMask"),
                null, null, List.of(), null, null, null);
        GraphSpec graph = new GraphSpec(targets, List.of(pass));
        assertDoesNotThrow(() -> GraphValidator.validate(graph, Map.of(), 1920, 1080));
    }

    @Test
    void sunShadowMapHistoryInputIsRejected() {
        // Unlike sceneHistory, sunShadowMap has no history slot -- it's a single current-frame
        // depth target the engine overwrites every frame -- so ".history" must be rejected.
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        targets.put("shadowMask", target("shadowMask"));
        PassSpec pass = new PassSpec("shadow_resolve", PassType.FULLSCREEN, null, "shadow_resolve",
                null, List.of("sunShadowMap.history"), List.of("shadowMask"),
                null, null, List.of(), null, null, null);
        GraphSpec graph = new GraphSpec(targets, List.of(pass));
        assertThrows(FornaxPackError.class, () -> GraphValidator.validate(graph, Map.of(), 1920, 1080));
    }
}
