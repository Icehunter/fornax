package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pass.shadow.RtShadowResult;
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

    /**
     * {@link RtShadowResult#TARGET}/{@link RtShadowResult#VALID_TARGET} are validated parallel to
     * {@code BUILTINS} (see {@code ShadowMapManager.isShadowMapRef}'s own precedent), so the loop
     * above never sees them. Pin the same "accepted at load implies resolvable at runtime" contract
     * for them directly: {@code GraphValidator} must recognize both names via
     * {@code RtShadowResult.isRtShadowRef}, and {@code GraphInputResolver} must have a case for both.
     */
    @Test
    void rtShadowBuiltinsAreValidatedAndResolvable() throws Exception {
        String validator = Files.readString(VALIDATOR);
        assertTrue(validator.contains("RtShadowResult.isRtShadowRef"),
                "GraphValidator no longer recognizes RtShadowResult's builtin names (rtSunVisibility/"
                        + "rtSunValid) -- a pack referencing either would fail load with 'references no"
                        + " declared target or built-in'");

        String resolver = Files.readString(RESOLVER);
        assertTrue(RtShadowResult.isRtShadowRef("rtTerrainShadowDepth"));
        assertFalse(RtShadowResult.isLegacyRtShadowRef("rtTerrainShadowDepth"));
        assertTrue(resolver.contains("case TerrainShadowResult.TARGET -> TerrainShadowResult.view()"));
        assertTrue(resolver.contains("case TerrainShadowResult.TARGET -> TerrainShadowResult.texture()"));
        assertTrue(resolver.contains("RtShadowResult.TARGET"),
                "GraphInputResolver has no case for RtShadowResult.TARGET (rtSunVisibility) -- a pack"
                        + " referencing it would load clean and then silently disable the referencing"
                        + " pass forever");
        assertTrue(resolver.contains("RtShadowResult.VALID_TARGET"),
                "GraphInputResolver has no case for RtShadowResult.VALID_TARGET (rtSunValid) -- a pack"
                        + " referencing it would load clean and then silently disable the referencing"
                        + " pass forever");
    }

    /** Guards the guard: {@link RtShadowResult}'s names are deliberately kept OUT of {@code
     * BUILTINS} (validated on a parallel branch, like {@code ShadowMapManager}'s own names) -- if
     * either were added to the set instead, {@link #rtShadowBuiltinsAreValidatedAndResolvable} would
     * still pass but for the wrong reason, since the first test's loop would then cover them too. */
    @Test
    void rtShadowBuiltinsStayOutOfTheBuiltinsSet() {
        assertFalse(GraphValidator.BUILTINS.contains(RtShadowResult.TARGET),
                "rtSunVisibility should be validated on its own branch (like sunShadowMap), not folded"
                        + " into BUILTINS");
        assertFalse(GraphValidator.BUILTINS.contains(RtShadowResult.VALID_TARGET),
                "rtSunValid should be validated on its own branch (like sunShadowMap), not folded into"
                        + " BUILTINS");
    }

    /**
     * {@code GraphValidator.checkInputRef}'s own {@code RtShadowResult.isRtShadowRef} branch is not
     * the only place that classifies a raw-Vulkan pass's declared inputs before {@code
     * checkInputRef} ever runs: {@code ComputePassRunner.descriptorTypeFor} and {@code
     * ParticlePassRunner.descriptorTypeFor} independently decide each input's {@code
     * VkDescriptorType} up front, to size their own descriptor pools, and neither reuses {@code
     * checkInputRef}'s logic. A name accepted by the validator but unrecognized by one of these
     * throws "references target/input '...' which is neither an allocated buffer nor texture
     * target" the first time a compute or particles pass declares it -- load succeeds, the pass
     * throws building its own descriptor pool.
     */
    @Test
    void computePassRunnerClassifiesRtShadowBuiltins() throws Exception {
        String source = Files.readString(COMPUTE_PASS_RUNNER);
        assertTrue(source.contains("RtShadowResult.isRtShadowRef"),
                "ComputePassRunner.descriptorTypeFor has no RtShadowResult branch -- a compute pass"
                        + " declaring rtSunVisibility/rtSunValid would load clean and then throw"
                        + " building its own descriptor pool");
    }

    /** See {@link #computePassRunnerClassifiesRtShadowBuiltins}'s own doc; the particles runner
     * keeps an independent copy of the same classification. */
    @Test
    void particlePassRunnerClassifiesRtShadowBuiltins() throws Exception {
        String source = Files.readString(PARTICLE_PASS_RUNNER);
        assertTrue(source.contains("RtShadowResult.isRtShadowRef"),
                "ParticlePassRunner.descriptorTypeFor has no RtShadowResult branch -- a particles pass"
                        + " declaring rtSunVisibility/rtSunValid would load clean and then throw"
                        + " building its own descriptor pool");
    }

    /**
     * {@code GraphValidator.checkGeometryInputFinality} is a THIRD independent classification site:
     * it runs for every input of the graph's one allowed GEOMETRY pass and rejects any name it does
     * not recognize as "never written this frame, so it is not final-for-frame" -- a self-contradicting
     * load failure for a name the validator's own {@code checkInputRef} just accepted on the very same
     * pass. Extracted by brace-matching rather than a whole-file substring check, since the file
     * contains {@code RtShadowResult.isRtShadowRef} elsewhere ({@code checkInputRef}'s own branch)
     * and a whole-file check would pass even if this specific method never saw it.
     */
    @Test
    void geometryInputFinalityRecognizesRtShadowBuiltins() throws Exception {
        String validator = Files.readString(VALIDATOR);
        String method = extractMethod(validator, "private static void checkGeometryInputFinality");
        assertTrue(method.contains("RtShadowResult.isRtShadowRef"),
                "GraphValidator.checkGeometryInputFinality has no RtShadowResult branch -- a geometry"
                        + " pass declaring rtSunVisibility/rtSunValid would load clean past checkInputRef"
                        + " and then fail with 'is never written this frame, so it is not"
                        + " final-for-frame at translucent draw time'");
    }

    /**
     * The three contract tests above only prove every consumer RECOGNIZES the two names; none of them
     * proves anything ever ALLOCATES the targets those names resolve against. {@code
     * RtShadowResult#ensureSize} existing is not enough -- it must be called from somewhere outside
     * its own file, unconditionally, or {@code GraphInputResolver.resolveView} always resolves both
     * names to null and {@code FullscreenPassRunner} disables the referencing pass for the rest of the
     * session on the very first frame (the exact failure {@code ShadowMapManager}'s own SHADOWS-off
     * 64x64 fallback in {@code SodiumWorldRendererOrchestrationMixin} exists to prevent).
     */
    @Test
    void rtShadowResultIsActuallyAllocatedSomewhere() throws Exception {
        String orchestration = Files.readString(ORCHESTRATION_MIXIN);
        assertTrue(orchestration.contains("RtShadowResult.ensureSize("),
                "No call site for RtShadowResult.ensureSize found in " + ORCHESTRATION_MIXIN
                        + " -- rtSunVisibility/rtSunValid are validated and resolvable in source but"
                        + " both textures stay permanently unallocated, so GraphInputResolver.resolveView"
                        + " throws 'resolved to no allocated target' on the first frame a pack references"
                        + " either name, disabling that pass for the session");
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
            // A name with no constant spelling must appear as a literal; this sentinel never matches.
            default -> " no-constant-spelling";
        };
    }
}
