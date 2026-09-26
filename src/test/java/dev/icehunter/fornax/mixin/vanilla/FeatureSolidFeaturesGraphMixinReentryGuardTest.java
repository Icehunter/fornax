package dev.icehunter.fornax.mixin.vanilla;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-text contract for the reentry guard at the top of {@code
 * fornax$runGraphAfterSolidFeatures}. It must check both {@code isShadowPhase()} and {@code
 * isPlayerMirrorPhase()} before anything else, and that check must be the first statement in the
 * method.
 *
 * <p>The crash this guards against only shows up in a live game: {@code PlayerMirrorCaster.cast()}
 * calls {@code renderAllFeatures}, which reaches a second {@code PreparedFrame.executeSolid}
 * internally and re-enters this injected hook from inside {@code cast()}. Without the {@code
 * isPlayerMirrorPhase()} half of the guard, that reentrant call still saw {@code
 * wantPlayerMirror()} as true and called {@code cast()} a second time while the first call's
 * {@code PreparedFrame} was still marked in use, throwing {@code IllegalStateException:
 * PreparedFrame already in use} and disabling the mirror caster for the rest of the session.
 * There is no headless fixture for a Sodium or vanilla {@code PreparedFrame}, or for a
 * mixin-transformed class, so a source-text check is what is available here, the same approach
 * every other contract test in this tree uses for a fact a live-GPU test cannot reach.
 */
class FeatureSolidFeaturesGraphMixinReentryGuardTest {

    @Test
    void theReentryGuardChecksBothPhasesAndIsTheFirstStatement() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/vanilla/FeatureSolidFeaturesGraphMixin.java"));

        int methodStart = source.indexOf("private void fornax$runGraphAfterSolidFeatures(");
        assertTrue(methodStart >= 0, "fornax$runGraphAfterSolidFeatures must exist");
        int bodyStart = source.indexOf('{', methodStart) + 1;

        int guardAt = source.indexOf(
                "if (DeferredGeometryPipelines.isShadowPhase() || DeferredGeometryPipelines.isPlayerMirrorPhase()) {",
                bodyStart);
        assertTrue(guardAt >= 0,
                "the method must check isShadowPhase() || isPlayerMirrorPhase() together in one guard"
                        + ": checking isShadowPhase() alone lets a mirror-phase reentry (from"
                        + " PlayerMirrorCaster.cast()'s own renderAllFeatures) fall through and call"
                        + " cast() a second time");

        // Only the method's opening brace and whitespace or comments may sit before the guard: it
        // must be the first statement in the method, not merely present somewhere inside it.
        String beforeGuard = source.substring(bodyStart, guardAt);
        String stripped = beforeGuard.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "")
                .trim();
        assertTrue(stripped.isEmpty(),
                "the phase guard must be the first statement in the method body (comments aside), so"
                        + " a reentrant call returns before running any of the shadow or mirror logic"
                        + " below it: found this before it instead: '" + stripped + "'");

        int returnAt = source.indexOf("return;", guardAt);
        int closeBrace = source.indexOf('}', returnAt);
        assertTrue(returnAt >= 0 && returnAt < closeBrace, "the guard must return, not merely test");
    }
}
