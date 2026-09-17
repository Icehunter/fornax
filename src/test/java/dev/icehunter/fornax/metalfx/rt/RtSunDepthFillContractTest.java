package dev.icehunter.fornax.metalfx.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level, deliberately: the kernel needs a live Metal device and CI runs on Linux. What it
 * pins is the pairing between the kernel's constant struct and the Java writer's byte offsets,
 * which no compiler compares, and the two halves of fill mode.
 *
 * <p>Fill mode is the dangerous one. The dispatch writes into an image a higher tier has already
 * answered, so the read-and-return is the only thing standing between "the voxel tier extends the
 * mesh tier" and "the voxel tier erases it". Both outcomes render; only one is right.
 */
class RtSunDepthFillContractTest {

    private static final Path KERNEL =
            Path.of("src/main/resources/assets/fornax/shaders_engine/rt_sun_depth.metal");
    private static final Path LEGACY =
            Path.of("src/main/java/dev/icehunter/fornax/metalfx/rt/MetalRtShadowPass.java");

    private static String kernel() throws IOException {
        return Files.readString(KERNEL);
    }

    /** Reading the destination needs read access; a write-only texture cannot be filled into. */
    @Test
    void theOutputIsReadWriteBecauseFillModeHasToSeeWhatIsAlreadyThere() throws IOException {
        assertTrue(kernel().contains("texture2d<float, access::read_write> sunDepthOut [[texture(0)]]"),
                "a write-only output cannot be filled into, and the usage flags on every texture "
                        + "bound here must carry read as well as write");
    }

    @Test
    void fillModeReturnsOnAnAnsweredTexelAndOwnedModeClearsItInstead() throws IOException {
        String source = kernel();
        assertTrue(source.contains("if (sunDepthOut.read(gid).a > 0.5) return;"),
                "an answered texel belongs to a higher tier and must be left exactly as it is");
        assertTrue(source.contains("sunDepthOut.write(float4(0.0), gid);"),
                "the owned-image path still clears up front, so its early returns leave the texel "
                        + "invalid rather than leaving last frame's answer");
        // Scoped to the kernel: rtUnwarp is defined above it, and a whole-file search finds the
        // definition rather than the call this is about.
        int body = source.indexOf("kernel void rt_sun_depth(");
        assertTrue(body > 0, "rt_sun_depth.metal must declare its kernel");
        int guard = source.indexOf("if (constants.fillMode != 0u) {", body);
        int unwarp = source.indexOf("rtUnwarp(", body);
        assertTrue(guard > 0 && guard < unwarp,
                "the skip must come before any ray work, or the tier pays for rays it discards");
    }

    /** G is the tier and B is reserved: nothing reads a second depth. */
    @Test
    void ananswerWritesTheTierIntoGreenAndLeavesBlueReserved() throws IOException {
        assertTrue(kernel().contains(
                        "sunDepthOut.write(float4(hitDepth, float(constants.tier), 0.0, 1.0), gid);"),
                "value, tier and validity leave the kernel in one store");
    }

    /**
     * 192, not 184: the {@code int4} first-section member gives the struct 16-byte alignment, so
     * the tier at 176 and the fill flag at 180 sit in a tail rounded up to 192. A short allocation
     * on the Java side reads both out of whatever follows it.
     */
    @Test
    void theConstantStructAndTheJavaWriterAgreeOnOneHundredNinetyTwoBytes() throws IOException {
        String source = kernel();
        assertTrue(source.contains("uint tier;"), "the struct must declare the tier");
        assertTrue(source.contains("uint fillMode;"), "and the fill flag");
        int tier = source.indexOf("uint tier;");
        int firstSection = source.indexOf("int4 firstSection;");
        assertTrue(firstSection > 0 && tier > firstSection,
                "both words are appended after the existing members, never inserted among them");

        String legacy = Files.readString(LEGACY);
        assertTrue(legacy.contains("SUN_CONSTANTS_BYTES = 192"),
                "the allocation must cover the struct including its tail padding");
        assertTrue(legacy.contains("seg.set(ValueLayout.JAVA_INT, 176, tier)"),
                "the tier word goes at byte 176");
        assertTrue(legacy.contains("seg.set(ValueLayout.JAVA_INT, 180, fillMode ? 1 : 0)"),
                "the fill flag at byte 180");
    }
}
