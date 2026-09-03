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

/**
 * A consolidate pass's output is never a {@code [targets.*]} entry: {@link TargetKind} has no
 * array kind. Most of these guard the same silent-failure class {@code
 * GraphValidatorParticlesTest} does: a shape mismatch across layers is undefined behaviour at the
 * Vulkan copy, not a validation error, and a gated pass with no declared target would defeat
 * {@link GraphValidator#checkGateConsistency} silently.
 */
class GraphValidatorConsolidateTest {
    private static TargetSpec renderTarget(String name) {
        return new TargetSpec(name, "rgba16f", 1.0, false, null, TargetBasis.RENDER);
    }

    private static Map<String, TargetSpec> targets(TargetSpec... specs) {
        Map<String, TargetSpec> map = new LinkedHashMap<>();
        for (TargetSpec s : specs) {
            map.put(s.name(), s);
        }
        return map;
    }

    private static PassSpec consolidatePass(List<String> inputs, List<String> outputs) {
        return new PassSpec("gbuf_consolidate", PassType.CONSOLIDATE, null, null,
                null, inputs, outputs, null, null, List.of(), null, null, null);
    }

    private static PassSpec consolidatePassGated(List<String> inputs, List<String> outputs, String enabledIf) {
        return new PassSpec("gbuf_consolidate", PassType.CONSOLIDATE, null, null,
                null, inputs, outputs, null, enabledIf, List.of(), null, null, null);
    }

    private static void validate(GraphSpec graph) {
        GraphValidator.validate(graph, Map.of(), 1920, 1080);
    }

    @Test
    void consolidatePassCopyingTwoSameShapedTargetsIntoANewArrayNameValidates() {
        GraphSpec graph = new GraphSpec(targets(renderTarget("gAlbedo"), renderTarget("gMaterial")),
                List.of(consolidatePass(List.of("gAlbedo", "gMaterial"), List.of("consolidated"))));
        assertDoesNotThrow(() -> validate(graph));
    }

    @Test
    void consolidatePassOutputIsAValidInputForALaterFullscreenPass() {
        PassSpec fullscreen = new PassSpec("resolve", PassType.FULLSCREEN, null, null,
                "shaders/post/resolve.fsh", List.of("consolidated"), List.of("builtin.output"),
                null, null, List.of(), null, null, null);
        GraphSpec graph = new GraphSpec(targets(renderTarget("gAlbedo"), renderTarget("gMaterial")),
                List.of(consolidatePass(List.of("gAlbedo", "gMaterial"), List.of("consolidated")), fullscreen));
        assertDoesNotThrow(() -> validate(graph));
    }

    @Test
    void consolidatePassWithAShaderIsRejected() {
        PassSpec pass = new PassSpec("gbuf_consolidate", PassType.CONSOLIDATE, null, null,
                "shaders/post/should_not_exist.fsh", List.of("gAlbedo"), List.of("consolidated"),
                null, null, List.of(), null, null, null);
        GraphSpec graph = new GraphSpec(targets(renderTarget("gAlbedo")), List.of(pass));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void consolidatePassWithEnabledIfIsRejected() {
        GraphSpec graph = new GraphSpec(targets(renderTarget("gAlbedo")),
                List.of(consolidatePassGated(List.of("gAlbedo"), List.of("consolidated"), "SSR_QUALITY == 2")));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void consolidatePassWithNoInputsIsRejected() {
        GraphSpec graph = new GraphSpec(Map.of(), List.of(consolidatePass(List.of(), List.of("consolidated"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void consolidatePassWithTwoOutputsIsRejected() {
        GraphSpec graph = new GraphSpec(targets(renderTarget("gAlbedo")),
                List.of(consolidatePass(List.of("gAlbedo"), List.of("a", "b"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void consolidatePassReadingANonAllowlistedBuiltinIsRejected() {
        // builtin.gNormal is RGBA16_SNORM, not RGBA8 like the three allowlisted G-buffer color
        // builtins: an array texture has exactly one format shared by every layer.
        GraphSpec graph = new GraphSpec(Map.of(),
                List.of(consolidatePass(List.of("builtin.gNormal"), List.of("consolidated"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void consolidatePassCopyingAllowlistedGBufferBuiltinsValidates() {
        GraphSpec graph = new GraphSpec(Map.of(), List.of(consolidatePass(
                List.of("builtin.gAlbedo", "builtin.gMaterial", "builtin.gAo"), List.of("consolidated"))));
        assertDoesNotThrow(() -> validate(graph));
    }

    @Test
    void consolidatePassMixingAnAllowlistedBuiltinWithADeclaredTargetIsRejected() {
        // Sized two structurally different ways (a TargetSpec formula vs. render resolution
        // directly): ConsolidateRunner never mixes them within one pass.
        GraphSpec graph = new GraphSpec(targets(renderTarget("gCustom")), List.of(consolidatePass(
                List.of("builtin.gAlbedo", "gCustom"), List.of("consolidated"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void consolidatePassReadingAHistorySuffixIsRejected() {
        GraphSpec graph = new GraphSpec(targets(new TargetSpec("gAlbedo", "rgba16f", 1.0, true, null, TargetBasis.RENDER)),
                List.of(consolidatePass(List.of("gAlbedo.history"), List.of("consolidated"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void consolidatePassInputsWithDifferentFormatsAreRejected() {
        GraphSpec graph = new GraphSpec(targets(renderTarget("gAlbedo"),
                        new TargetSpec("gNormal", "rgba8", 1.0, false, null, TargetBasis.RENDER)),
                List.of(consolidatePass(List.of("gAlbedo", "gNormal"), List.of("consolidated"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void consolidatePassInputsWithDifferentScalesAreRejected() {
        GraphSpec graph = new GraphSpec(targets(renderTarget("gAlbedo"),
                        new TargetSpec("gAlbedoHalf", "rgba16f", 0.5, false, null, TargetBasis.RENDER)),
                List.of(consolidatePass(List.of("gAlbedo", "gAlbedoHalf"), List.of("consolidated"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void consolidatePassOutputCollidingWithADeclaredTargetIsRejected() {
        GraphSpec graph = new GraphSpec(targets(renderTarget("gAlbedo"), renderTarget("alreadyDeclared")),
                List.of(consolidatePass(List.of("gAlbedo"), List.of("alreadyDeclared"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }

    @Test
    void consolidatePassInputMustBeATextureNotABuffer() {
        GraphSpec graph = new GraphSpec(targets(TargetSpec.buffer("someBuffer", null)),
                List.of(consolidatePass(List.of("someBuffer"), List.of("consolidated"))));
        assertThrows(FornaxPackError.class, () -> validate(graph));
    }
}
