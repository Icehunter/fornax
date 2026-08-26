package dev.icehunter.fornax.pack;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@link PackReload#reload} needs a live {@code Minecraft} instance and a real shaderpacks
 * directory to exercise directly, the same constraint that makes {@code BuiltinResolutionContractTest}
 * a source-level test. Pins that the resource-reload future carries an {@code .exceptionally}
 * handler, the same way its structurally identical siblings ({@code PackSwitch.apply},
 * {@code PackEditSession.apply}) already do -- without it, a listener throwing during
 * {@code Minecraft.reloadResourcePacks()} silently skips {@code RendererReload.request()} and
 * terrain edits stay invisible with no log line, the exact incident this method's own comment
 * already names.
 */
class PackReloadContractTest {
    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax/pack/PackReload.java");

    @Test
    void resourceReloadFutureHasAnExceptionallyHandler() throws IOException {
        String source = Files.readString(SOURCE);
        int chainStart = source.indexOf("sourcesVisible.thenRunAsync(");
        assertTrue(chainStart >= 0, "the resource-reload chain must still exist");
        String chain = source.substring(chainStart, source.indexOf("});", chainStart) + "});".length());

        assertTrue(chain.contains(".exceptionally("),
                "a failed resource reload must not silently skip RendererReload.request()");
        assertTrue(chain.contains("FornaxMod.LOGGER.error("),
                "the failure must be logged, matching PackSwitch.apply and PackEditSession.apply");
    }
}
