package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A behavioural test cannot tell "returned null from cache" apart from "recomputed and got null
 * again" without capturing log output or counting device calls, and this headless suite has
 * neither. {@code buildMirror} and {@code buildShadow} short-circuit before ever reaching {@code
 * RenderSystem.getDevice()}, specifically so they can run headless (see {@link
 * PlayerMirrorVariantOfTest}'s comment), which leaves nothing to intercept. A source-text check is
 * what is available, the same approach {@code GBufferFormatLockTest} already uses.
 *
 * <p>Checks that {@code mirrorVariantOf} and {@code shadowVariantOf} test a failed-attempts set
 * before calling their build method, add to that set on a null result, and that both sets are
 * cleared in {@link DeferredGeometryPipelines#invalidate()}. Without this, a compile-failing
 * program re-runs {@code precompilePipeline} and logs an error, with no rate limit, on every draw
 * for the rest of the session.
 */
class NegativeVariantCachingContractTest {

    @Test
    void mirrorAndShadowVariantsCacheFailuresAndClearThemOnInvalidate() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pipeline/DeferredGeometryPipelines.java"));

        // The cache key is (base, slot), not base alone; see
        // DeferredGeometryPipelines.MirrorCacheKey's comment for why keying by base alone would be
        // wrong, not only differently written.
        assertTrue(source.contains("MIRROR_FAILED.contains(key)"),
                "mirrorVariantOf must check MIRROR_FAILED before calling buildMirror");
        assertTrue(source.contains("MIRROR_FAILED.add(key)"),
                "mirrorVariantOf must record a null buildMirror result in MIRROR_FAILED");
        assertTrue(source.contains("MIRROR_FAILED.clear()"),
                "invalidate() must drop MIRROR_FAILED, or a failure from a superseded pack survives"
                        + " into the next one");

        assertTrue(source.contains("failed.contains(base)"),
                "shadowVariantOf must check its FAILED set (SHADOW_FAILED or SHADOW_WORLD_SPACE_FAILED"
                        + " depending on worldSpaceInput) before calling buildShadow");
        assertTrue(source.contains("failed.add(base)"),
                "shadowVariantOf must record a null buildShadow result in its FAILED set");
        assertTrue(source.contains("SHADOW_FAILED.clear()") && source.contains("SHADOW_WORLD_SPACE_FAILED.clear()"),
                "invalidate() must drop both shadow FAILED sets");
    }

    /**
     * The same log-once pattern, a different failure: {@code PlayerMirrorCaster.reportedFailure}
     * is not cleared anywhere in that class, so a failure logged under one pack silently
     * suppresses the warning for every pack loaded after it, for the rest of the session.
     * Reloading the engine with a new pack must still be able to log the warning again.
     */
    @Test
    void invalidateResetsThePlayerMirrorCasterFailureLatch() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pipeline/DeferredGeometryPipelines.java"));

        // One instance exists per slot, so invalidate() resets all of them through a
        // registry-wide call rather than a single static latch.
        assertTrue(source.contains("PlayerMirrorCaster.resetAllForNewPack()"),
                "invalidate() must call PlayerMirrorCaster.resetAllForNewPack(), or a failure logged"
                        + " under one pack silently silences every pack loaded after it");
    }
}
