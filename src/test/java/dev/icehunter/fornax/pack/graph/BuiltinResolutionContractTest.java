package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code GraphValidator.BUILTINS} to the names {@code GraphInputResolver} can actually resolve.
 *
 * <p>A builtin accepted at load but unresolvable at runtime fails in the worst possible way: the pack
 * loads clean, {@code FullscreenPassRunner} disables the pass that referenced it for the rest of its
 * lifetime, and the frame silently loses whatever that pass produced. {@code builtin.lightmap} sat in
 * exactly that state from the start -- valid to declare, impossible to use -- and blanked an entire
 * deferred output the first time a pack referenced it.
 *
 * <p>Deliberately source-level rather than reflective: the resolver's cases are a {@code switch} over
 * string literals with no runtime enumeration, and invoking it would need a live GPU device. Reading
 * the source is what actually answers "is there a case for this name".
 */
class BuiltinResolutionContractTest {

    private static final Path RESOLVER =
            Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphInputResolver.java");

    private static final Path VALIDATOR =
            Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphValidator.java");

    private static final Path COMPUTE_PASS_RUNNER =
            Path.of("src/main/java/dev/icehunter/fornax/pack/graph/ComputePassRunner.java");

    private static final Path PARTICLE_PASS_RUNNER =
            Path.of("src/main/java/dev/icehunter/fornax/pack/graph/ParticlePassRunner.java");

    private static final Path ORCHESTRATION_MIXIN = Path.of(
            "src/main/java/dev/icehunter/fornax/mixin/sodium/SodiumWorldRendererOrchestrationMixin.java");

    @Test
    void everyValidatedBuiltinHasAResolverCase() throws Exception {
        String resolver = Files.readString(RESOLVER);

        Set<String> unresolvable = new TreeSet<>();
        for (String builtin : GraphValidator.BUILTINS) {
            // Some builtins are contributed as constants (OpaqueDepth.NAME and friends) and appear in
            // the resolver as the symbol rather than the literal, so accept either spelling.
            boolean asLiteral = resolver.contains('"' + builtin + '"');
            boolean asConstant = resolver.contains(constantSpellingOf(builtin));
            if (!asLiteral && !asConstant) {
                unresolvable.add(builtin);
            }
        }

        assertTrue(unresolvable.isEmpty(),
                "these builtins validate at load but have no GraphInputResolver case, so any pack"
                        + " referencing one loads clean and then silently loses that pass: " + unresolvable);
    }

    /** Guards the guard: an emptied or moved BUILTINS set would make the check above vacuously pass. */
    @Test
    void builtinsSetIsNonTrivial() {
        assertTrue(GraphValidator.BUILTINS.size() >= 10,
                "BUILTINS shrank unexpectedly (" + GraphValidator.BUILTINS.size() + ") -- if these names"
                        + " moved elsewhere, move this contract check with them: "
                        + List.copyOf(new TreeSet<>(GraphValidator.BUILTINS)));
    }







    /** Brace-matches a method body starting at {@code signature}'s first occurrence, so a check can
     * assert on ONE method's text rather than the whole file (which may mention the same string in
     * an unrelated method). */
    private static String extractMethod(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "method signature not found in source: " + signature);
        int braceStart = source.indexOf('{', start);
        assertTrue(braceStart >= 0, "no method body found after signature: " + signature);
        int depth = 0;
        int i = braceStart;
        for (; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
        }
        return source.substring(start, i + 1);
    }

    /** The resolver spells a few builtins as constants; map those back to the symbol it uses. */
    private static String constantSpellingOf(String builtin) {
        return switch (builtin) {
            case "builtin.depth_opaque" -> "OpaqueDepth.NAME";
            case "builtin.waterNormal" -> "WaterSurfaceManager.NORMAL_NAME";
            case "builtin.waterDepth" -> "WaterSurfaceManager.DEPTH_NAME";
            case "builtin.mirrorNormal" -> "PlayerMirrorTargets.NORMAL_NAME";
            case "builtin.mirrorAlbedo" -> "PlayerMirrorTargets.ALBEDO_NAME";
            case "builtin.mirrorMaterial" -> "PlayerMirrorTargets.MATERIAL_NAME";
            case "builtin.mirrorDepth" -> "PlayerMirrorTargets.DEPTH_NAME";
            case "builtin.mirrorXNormal" -> "PlayerMirrorTargets.X_NORMAL_NAME";
            case "builtin.mirrorXAlbedo" -> "PlayerMirrorTargets.X_ALBEDO_NAME";
            case "builtin.mirrorXMaterial" -> "PlayerMirrorTargets.X_MATERIAL_NAME";
            case "builtin.mirrorXDepth" -> "PlayerMirrorTargets.X_DEPTH_NAME";
            case "builtin.mirrorZNormal" -> "PlayerMirrorTargets.Z_NORMAL_NAME";
            case "builtin.mirrorZAlbedo" -> "PlayerMirrorTargets.Z_ALBEDO_NAME";
            case "builtin.mirrorZMaterial" -> "PlayerMirrorTargets.Z_MATERIAL_NAME";
            case "builtin.mirrorZDepth" -> "PlayerMirrorTargets.Z_DEPTH_NAME";
            // A name with no constant spelling must appear as a literal; this sentinel never matches.
            default -> " no-constant-spelling";
        };
    }
}
