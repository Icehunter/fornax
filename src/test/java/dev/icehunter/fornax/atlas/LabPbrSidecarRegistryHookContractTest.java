package dev.icehunter.fornax.atlas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class LabPbrSidecarRegistryHookContractTest {
    @Test
    void blockAtlasReloadRefreshesTheResourceWideRegistry() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/vanilla/TextureAtlasMaterialHookMixin.java"));

        assertTrue(source.contains("LabPbrSidecarRegistry.refreshActive(resourceManager)"),
                "the active sidecar snapshot must advance with the existing block-atlas reload seam");
    }
}
