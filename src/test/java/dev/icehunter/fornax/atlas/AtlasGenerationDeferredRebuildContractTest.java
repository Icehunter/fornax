package dev.icehunter.fornax.atlas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the v2 fix's ordering across the three mixins {@link AtlasGenerationSchedule} coordinates:
 * the HEAD hook must decide "did anything change" BEFORE releasing anything (restoring the
 * fingerprint-skip path a first version of the release mixin defeated), and both RETURN hooks must
 * defer to a scheduled rebuild rather than building in the same {@code upload} call the release
 * happened in (which would put the new generation's allocation zero real frames after the release,
 * exactly the ordering that does not actually free VRAM on this backend -- see {@code
 * TextureAtlasReleaseGenerationMixin}'s and {@link AtlasGenerationSchedule}'s own docs).
 *
 * <p>Deliberately source-level: these mixins target {@code TextureAtlas} and need a live client to
 * instantiate, the same constraint that makes {@code BuiltinResolutionContractTest} a source-level
 * test.
 */
class AtlasGenerationDeferredRebuildContractTest {
    private static final Path RELEASE_MIXIN = Path.of(
            "src/main/java/dev/icehunter/fornax/mixin/vanilla/TextureAtlasReleaseGenerationMixin.java");
    private static final Path MATERIAL_HOOK_MIXIN = Path.of(
            "src/main/java/dev/icehunter/fornax/mixin/vanilla/TextureAtlasMaterialHookMixin.java");
    private static final Path BLOCK_HOOK_MIXIN = Path.of(
            "src/main/java/dev/icehunter/fornax/mixin/vanilla/TextureAtlasBlockHookMixin.java");

    @Test
    void releaseMixinChecksBothFingerprintsBeforeReleasingAnything() throws IOException {
        String source = Files.readString(RELEASE_MIXIN);
        int methodStart = source.indexOf("private void fornax$releasePreviousGeneration(");
        assertTrue(methodStart >= 0, "fornax$releasePreviousGeneration must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int unchangedCheckIndex = method.indexOf("fornax$unchanged(");
        int waitIndex = method.indexOf("VulkanComputeBackend.waitForGpuIdleBeforeDestroy()");
        int scheduleIndex = method.indexOf("AtlasGenerationSchedule.scheduleRelease(");

        assertTrue(unchangedCheckIndex >= 0, "the fingerprint pre-check must still exist");
        assertTrue(waitIndex >= 0, "the release must still wait for GPU idle");
        assertTrue(scheduleIndex >= 0, "a real change must still schedule a deferred rebuild");
        assertTrue(unchangedCheckIndex < waitIndex,
                "the fingerprint check must run BEFORE any release so the selected rebuild scope"
                        + " can retain unchanged sidecar lanes");
        assertTrue(waitIndex < scheduleIndex,
                "release must happen before scheduling the deferred rebuild, not after");
    }

    @Test
    void unchangedCheckComparesBothLanesFingerprintsAgainstThePublishedPair() throws IOException {
        String source = Files.readString(RELEASE_MIXIN);
        int methodStart = source.indexOf("private static boolean fornax$unchanged(");
        assertTrue(methodStart >= 0, "fornax$unchanged must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("NormalMapAtlas.getInstance(atlasLocation)"),
                "must read the currently published normal atlas, not assume none exists");
        assertTrue(method.contains("MaterialMapAtlas.getInstance(atlasLocation)"),
                "must read the currently published material atlas, not assume none exists");
        assertTrue(method.contains("normalFingerprint.equals(existingNormal.fingerprint())")
                        && method.contains("materialFingerprint.equals(existingMaterial.fingerprint())"),
                "both lanes must be compared -- a pack shipping a new _n map with an unchanged _s"
                        + " one is still a real change");
    }

    @Test
    void materialHookMixinDefersToAScheduledRebuildRatherThanBuildingInTheSameCall() throws IOException {
        String source = Files.readString(MATERIAL_HOOK_MIXIN);
        int methodStart = source.indexOf("private void fornax$buildMaterialMapAtlas(");
        assertTrue(methodStart >= 0, "fornax$buildMaterialMapAtlas must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int pendingCheckIndex = method.indexOf("AtlasGenerationSchedule.hasPending(this.location)");
        int rebuildIndex = method.indexOf("LabPbrAtlasPair.rebuild(");

        assertTrue(pendingCheckIndex >= 0, "must check whether a rebuild was already scheduled");
        assertTrue(rebuildIndex >= 0, "the un-deferred build call must still exist when no"
                + " generation retirement is pending");
        assertTrue(pendingCheckIndex < rebuildIndex,
                "the pending check must gate the immediate build, not run after it");
    }

    @Test
    void blockHookMixinDefersToAScheduledRebuildRatherThanBuildingInTheSameCall() throws IOException {
        String source = Files.readString(BLOCK_HOOK_MIXIN);
        int methodStart = source.indexOf("private void fornax$captureBlockAtlas(");
        assertTrue(methodStart >= 0, "fornax$captureBlockAtlas must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int pendingCheckIndex = method.indexOf("AtlasGenerationSchedule.hasPending(this.location)");
        int rebuildIndex = method.indexOf("BlockAtlasOverflow.rebuild(");

        assertTrue(pendingCheckIndex >= 0, "must check whether a rebuild was already scheduled");
        assertTrue(rebuildIndex >= 0, "the un-deferred rebuild call must still exist when no"
                + " generation retirement is pending");
        assertTrue(pendingCheckIndex < rebuildIndex,
                "the pending check must gate the immediate rebuild, not run after it");
    }
}
