package dev.icehunter.fornax.atlas;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The atlas builder needs a live GPU. These checks pin its CPU metadata wiring only. */
class MaterialSourceIndexContractTest {
    @Test
    void unchangedGpuAtlasRebindsTheSourceEvidenceToTheNewSpriteGeneration() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/atlas/MaterialMapAtlasReloadListener.java"));
        assertTrue(source.contains("existing.rebindSourceIndex(sprites)"),
                "GPU fingerprint reuse must not retain the old sprite identity bindings");
    }

    @Test
    void retainedSourcePublicationRunsAfterSuccessfulUploadAndBeforeThePendingReturn() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/vanilla/TextureAtlasMaterialHookMixin.java"));
        int uploaded = source.indexOf("AtlasGenerationSchedule.onAtlasUploaded(this.location, preparations)");
        int pending = source.indexOf("AtlasGenerationSchedule.hasPending(this.location)");
        assertTrue(source.contains("@At(\"RETURN\")"));
        assertTrue(uploaded >= 0 && uploaded < pending,
                "an unchanged block upload must publish source identities before its pending early return");
    }

    @Test
    void diskCacheHitsStillReadUnfilteredSourceEvidence() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/atlas/MaterialMapAtlasReloadListener.java"));
        assertTrue(source.contains("MaterialSourceIndex.readStatic"),
                "filtered disk-cache texels cannot certify tiny authored source texels");
        assertTrue(source.contains("MaterialSourceIndex.summarize(source)"),
                "fresh builds must summarize source bytes before the resample");
    }
}
