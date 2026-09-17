package dev.icehunter.fornax.rt;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.ShaderImports;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level, deliberately: no GLSL executes in this suite, and the tier constants in
 * {@code ray_answer.glsl} are a second copy of {@link RayTier}'s ordinals that a compiler will never
 * compare against the first. A drifted copy compiles, samples and lights a frame with the wrong
 * fallback selected, so reading both files and comparing them here is the only check there is.
 */
class RayAnswerGlslContractTest {

    private static final Path INCLUDE =
            Path.of("src/main/resources/assets/fornax/shaders/include/ray_answer.glsl");

    private static final Pattern TIER_CONSTANT =
            Pattern.compile("const\\s+int\\s+FORNAX_RAY_TIER_([A-Z_]+)\\s*=\\s*(\\d+)\\s*;");

    private static String source() throws IOException {
        return Files.readString(INCLUDE);
    }

    @Test
    void everyTierConstantInTheIncludeEqualsTheEnumOrdinalOfTheSameName() throws IOException {
        Matcher matcher = TIER_CONSTANT.matcher(source());
        int found = 0;
        while (matcher.find()) {
            RayTier tier = RayTier.valueOf(matcher.group(1));
            assertEquals(tier.ordinal(), Integer.parseInt(matcher.group(2)),
                    "FORNAX_RAY_TIER_" + matcher.group(1) + " disagrees with RayTier." + tier.name());
            found++;
        }
        assertEquals(RayTier.values().length, found,
                "the include must declare one constant per tier; an appended tier needs its GLSL twin");
    }

    /**
     * Validity is channel A, and a reader that tests the wrong channel gets a plausible-looking
     * result rather than an error: G also reads zero on an untraced texel, so a helper testing G
     * would answer "not answered" correctly and "answered" wrongly for every tier-0 write.
     */
    @Test
    void theAnsweredHelperTestsTheAlphaChannelAndTheTierHelperReadsGreen() throws IOException {
        String source = source();
        assertTrue(source.contains("bool fornaxRayAnswered(vec4 answer)"),
                "the answered helper must keep its name and signature; packs import it by name");
        assertTrue(source.contains("return answer.a > 0.5;"),
                "validity lives in A");
        assertTrue(source.contains("int fornaxRayTier(vec4 answer)"),
                "the tier helper must keep its name and signature");
        assertTrue(source.contains("return int(answer.g + 0.5);"),
                "the tier lives in G and is rounded, since a sampler may return a value a fraction "
                        + "off the stored small integer");
    }

    /**
     * The include has no version directive of its own. blaze3d splices an import into the importing
     * file's source, so a second {@code #version} there is a compile error in every consumer.
     */
    @Test
    void theIncludeDeclaresNoVersionDirectiveOfItsOwn() throws IOException {
        assertTrue(!source().contains("#version"),
                "an engine include is spliced into a pack file that already declares its version");
    }

    /**
     * The registration step. An include the engine ships but does not list is rejected at pack load
     * with a message that reads like the pack's mistake. {@code chunk_vertex.glsl} sits in the same
     * directory and is deliberately absent from that list; it is served to packs through
     * {@code fornax_runtime} instead, so do not "fix" the list by copying the directory listing.
     */
    @Test
    void aPackMayImportRayAnswerThroughTheFornaxNamespace() {
        assertDoesNotThrow(() -> ShaderImports.validate(Map.of(
                "shaders/post/lighting.fsh",
                "#moj_import <fornax:ray_answer.glsl>\nvoid main() {}")));
    }

    @Test
    void anEngineIncludeThatDoesNotExistIsStillRejected() {
        FornaxPackError error = assertThrows(FornaxPackError.class, () -> ShaderImports.validate(Map.of(
                "shaders/post/lighting.fsh",
                "#moj_import <fornax:ray_answer_v2.glsl>\nvoid main() {}")));
        assertTrue(error.getMessage().contains("ray_answer_v2.glsl"));
    }

    /** The name in the allow-list is worth nothing if the resource is not actually in the jar. */
    @Test
    void theIncludeExistsAtThePathBlazeThreeDResolvesForTheFornaxNamespace() {
        assertTrue(Files.isRegularFile(INCLUDE),
                "blaze3d maps <fornax:ray_answer.glsl> to fornax:shaders/include/ray_answer.glsl");
    }
}
