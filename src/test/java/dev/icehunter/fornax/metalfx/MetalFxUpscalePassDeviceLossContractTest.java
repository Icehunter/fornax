package dev.icehunter.fornax.metalfx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code runIfEnabled()} needs a live Vulkan device lost mid-frame to exercise this directly, the
 * same constraint that makes {@code BuiltinResolutionContractTest} a source-level test. Pins that a
 * {@code GpuDeviceLossException} is caught ahead of the broad {@code Throwable} catch and rethrown
 * rather than swallowed into the ordinary "fall back to TAAU" path: once the device is actually
 * lost, falling back just hands the next unrelated submit() the same dead device.
 */
class MetalFxUpscalePassDeviceLossContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/metalfx/MetalFxUpscalePass.java");

    @Test
    void deviceLossIsCaughtAheadOfTheBroadCatchAndRethrown() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public static boolean runIfEnabled(");
        assertTrue(methodStart >= 0, "runIfEnabled must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int deviceLossCatch = method.indexOf("catch (com.mojang.blaze3d.GpuDeviceLossException e) {");
        int broadCatch = method.indexOf("catch (Throwable t) {");
        assertTrue(deviceLossCatch >= 0, "GpuDeviceLossException must be caught explicitly");
        assertTrue(broadCatch >= 0, "the broad Throwable fallback must still exist");
        assertTrue(deviceLossCatch < broadCatch,
                "GpuDeviceLossException must be caught ahead of the broad Throwable catch");

        String deviceLossBlock = method.substring(deviceLossCatch, broadCatch);
        assertTrue(deviceLossBlock.contains("throw e;"),
                "a lost device must be rethrown, not swallowed into the failed=true fallback");
        assertTrue(!deviceLossBlock.contains("failed = true;"),
                "a lost device must not be treated as a soft, continuable failure");
    }
}
