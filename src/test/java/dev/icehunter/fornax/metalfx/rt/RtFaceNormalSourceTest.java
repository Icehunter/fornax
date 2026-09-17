package dev.icehunter.fornax.metalfx.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level, deliberately: the kernels these assertions cover need a live Metal device to
 * execute, and CI runs on Linux where every device-gated test in this package is skipped. Without
 * this, the shared face-to-normal decode would have no coverage at all on the machine that runs the
 * suite on every push. Same pattern and same reason as the other contract tests here.
 */
class RtFaceNormalSourceTest {

    private static final Path SHADERS = Path.of("src/main/resources/assets/fornax/shaders_engine");

    private static String read(String name) throws IOException {
        return Files.readString(SHADERS.resolve(name));
    }

    /**
     * The face-to-axis mapping exists once. Two copies drift, and a drifted copy is invisible: both
     * kernels would still compile, still return unit vectors, and disagree about which way a wall
     * faces only in the frames a player looks at.
     */
    @Test
    void theFaceToAxisMappingIsWrittenInExactlyOneFile() throws IOException {
        assertTrue(read("rt_face_normal.metal").contains("inline float3 rt_face_normal(uint packed)"),
                "rt_face_normal.metal must hold the decode");
        for (String kernel : new String[]{"rt_ray_query.metal", "rt_debug.metal", "rt_trace.metal"}) {
            assertFalse(read(kernel).contains("inline float3 rt_face_normal"),
                    kernel + " must use the shared decode, not carry its own copy");
        }
    }

    /** All six of Minecraft's directions are mapped, so no face falls through to the zero vector. */
    @Test
    void everyOneOfTheSixFacesIsMappedToAnAxis() throws IOException {
        String source = read("rt_face_normal.metal");
        for (int face = 0; face < 6; face++) {
            assertTrue(source.contains("case " + face + "u:"),
                    "face " + face + " has no case in rt_face_normal");
        }
        assertTrue(source.contains("default: return float3(0.0)"),
                "an unmapped face must return the zero vector rather than a guess, since a caller "
                        + "normalizing a guess cannot tell it from a real face");
    }

    /**
     * The decode is prepended to a kernel rather than included, so it is parsed before that kernel's
     * own header lines. It must therefore carry its own include and using-directive; without them
     * every consumer fails to compile on 'dot' and 'float3', which is how this was first caught.
     */
    @Test
    void theSharedDecodeCarriesItsOwnIncludeBecauseItIsPrependedNotIncluded() throws IOException {
        String source = read("rt_face_normal.metal");
        assertTrue(source.contains("#include <metal_stdlib>"), "prelude needs its own include");
        assertTrue(source.contains("using namespace metal;"), "prelude needs its own using-directive");
    }

    /** Both consumers are registered for the prelude; a kernel left out fails to compile. */
    @Test
    void everyKernelUsingTheDecodeIsGivenItAsAPrelude() throws IOException {
        String loader = Files.readString(
                Path.of("src/main/java/dev/icehunter/fornax/metalfx/rt/MetalRtShaders.java"));
        assertTrue(loader.contains("RAY_QUERY_RESOURCE") && loader.contains("DEBUG_RESOURCE"),
                "MetalRtShaders.prelude must name both kernels that call rt_face_normal");
        for (String kernel : new String[]{"rt_ray_query.metal", "rt_debug.metal"}) {
            assertTrue(read(kernel).contains("rt_face_normal("),
                    kernel + " is registered for the prelude, so it must actually call the decode");
        }
    }

    /**
     * The debug view's normal mode paints the decoded normal, not the flat placeholder it shipped
     * with. Pinned because the placeholder was a plausible-looking constant colour, and a revert to
     * it would look like a working feature on screen.
     */
    @Test
    void theNormalDebugModeNoLongerPaintsTheFlatPlaceholder() throws IOException {
        String source = read("rt_debug.metal");
        int modeNormal = source.indexOf("case MODE_NORMAL");
        assertTrue(modeNormal > 0, "rt_debug must still have a MODE_NORMAL arm");
        String arm = source.substring(modeNormal, source.indexOf("case MODE_INSTANCE_ID"));
        assertTrue(arm.contains("rt_face_normal("),
                "MODE_NORMAL must decode the hit face rather than paint a constant");
        assertEquals(1, countOccurrences(arm, "float3(1.0, 0.0, 1.0)"),
                "magenta may appear once, as the unknown-face answer, and not as the whole arm");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
