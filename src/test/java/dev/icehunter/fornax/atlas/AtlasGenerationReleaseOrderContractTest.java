package dev.icehunter.fornax.atlas;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the release-before-allocate ordering the resource-pack-switch device-loss fix depends on:
 * the previous GPU generation is freed, and freed AFTER a GPU-idle wait, before the next generation
 * is ever requested from the driver. Old and new WERE briefly double-resident by design (see {@code
 * LabPbrAtlasPair}'s and {@code BlockAtlasOverflow}'s own class docs), live-caught contributing to a
 * native out-of-memory crash across three back-to-back resource-pack switches.
 *
 * <p>Deliberately source-level: {@code TextureAtlasReleaseGenerationMixin} is a mixin injected into
 * vanilla's {@code TextureAtlas} and cannot be instantiated without a live client, matching this
 * repo's established pattern for GPU-adjacent mixin logic (see {@code
 * DefaultChunkRendererFaceCullingMixinContractTest}).
 */
class AtlasGenerationReleaseOrderContractTest {
    private static final Path RELEASE_MIXIN = Path.of(
            "src/main/java/dev/icehunter/fornax/mixin/vanilla/TextureAtlasReleaseGenerationMixin.java");
    private static final Path BLOCK_ATLAS_OVERFLOW =
            Path.of("src/main/java/dev/icehunter/fornax/atlas/BlockAtlasOverflow.java");

    @Test
    void releaseMixinWaitsForGpuIdleBeforeClosingAnything() throws IOException {
        String source = Files.readString(RELEASE_MIXIN);
        int methodStart = source.indexOf("private void fornax$releasePreviousGeneration(");
        assertTrue(methodStart >= 0, "fornax$releasePreviousGeneration must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int waitIndex = method.indexOf("VulkanComputeBackend.waitForGpuIdleBeforeDestroy()");
        int labPbrCloseIndex = method.indexOf("LabPbrAtlasPair.replace(");
        int overflowCloseIndex = method.indexOf("BlockAtlasOverflow.releaseCurrent()");
        int boundsCloseIndex = method.indexOf("SpriteBoundsTexture.destroy()");

        assertTrue(waitIndex >= 0, "the release hook must wait for GPU idle before closing anything");
        assertTrue(labPbrCloseIndex > waitIndex,
                "the LabPBR sidecar pair must be freed after the GPU-idle wait, not before");
        assertTrue(overflowCloseIndex > waitIndex,
                "the block-atlas overflow layers must be freed after the GPU-idle wait, not before");
        assertTrue(boundsCloseIndex > waitIndex,
                "the sprite-bounds grid must be freed after the GPU-idle wait, not before");
    }

    @Test
    void releaseMixinReleasesTheOldGenerationBeforeVanillaAllocatesTheNewOne() throws IOException {
        String source = Files.readString(RELEASE_MIXIN);
        assertTrue(source.contains("@Inject(method = \"upload\", at = @At(\"HEAD\"))"),
                "the release must run at upload's HEAD, strictly before vanilla's own createTexture"
                        + " for this generation (which itself releases before it allocates)");
    }

    @Test
    void blockAtlasOverflowRebuildClosesThePreviousAllocationBeforeBuildingTheNextOne()
            throws IOException {
        String source = Files.readString(BLOCK_ATLAS_OVERFLOW);
        int methodStart = source.indexOf("public static void rebuild(");
        assertTrue(methodStart >= 0, "rebuild() must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int releaseIndex = method.indexOf("releaseCurrent();");
        int buildIndex = method.indexOf("build(layout)");

        assertTrue(releaseIndex >= 0, "rebuild() must release the previous allocation itself, as a"
                + " second line of defense independent of the release-before-allocate mixin");
        assertTrue(buildIndex > releaseIndex,
                "the previous allocation must be released BEFORE the next one is built -- old and"
                        + " new were briefly double-resident here before this ordering, live-caught"
                        + " contributing to a native out-of-memory crash during a resource-pack"
                        + " switch");
    }

    @Test
    void releaseCurrentInvalidatesTheTerrainProgramCacheWhenItActuallyReleasesAPagedGeneration()
            throws IOException {
        // Without this, a releaseCurrent() with no matching rebuild() afterward (upload() throwing,
        // or a fatal rethrow from this class's own allocation failure) leaves cached terrain
        // programs compiled for the OLD nonzero FORNAX_ATLAS_OVERFLOW_PAGES value while
        // overflowPageCount() has already dropped to 0.
        String source = Files.readString(BLOCK_ATLAS_OVERFLOW);
        int methodStart = source.indexOf("public static void releaseCurrent(");
        assertTrue(methodStart >= 0, "releaseCurrent() must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int closeIndex = method.indexOf("previous.albedo.close();");
        int resetIndex = method.indexOf("lastPublishedPageCount = 0;");
        int clearIndex = method.indexOf("ShaderChunkRendererAccessor.fornax$getPrograms().clear();");

        assertTrue(closeIndex >= 0, "the allocation must still be closed");
        assertTrue(resetIndex >= 0,
                "releaseCurrent() must reset lastPublishedPageCount itself, not leave that only to"
                        + " rebuild()");
        assertTrue(clearIndex >= 0,
                "releaseCurrent() must clear the terrain program cache itself for the same reason");
        assertTrue(resetIndex > closeIndex,
                "the reset must happen after the actual release, describing the generation that is"
                        + " now actually published");
    }
}
