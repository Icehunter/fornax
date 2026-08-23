package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the responsive-history rule for animated deferred entities. Entity geometry has no
 * previous-frame pose, so its motion attachment contains camera motion only. Reusing history for
 * waving banner cloth or a moving iron golem therefore produces dark internal wisps and flashes.
 * The existing gAo alpha surface-class lane identifies those pixels without changing any material
 * channel: block entity = 0, particle = 0.25, cutout terrain = 0.5, animated entity = 0.75,
 * solid terrain = 1.
 */
class TemporalEntityHistoryMaskTest {
    private static final String SHADER = "/assets/fornax/shaders/post/temporal_accumulate.fsh";
    private static final Path RUNNER = Path.of(
            "src/main/java/dev/icehunter/fornax/pack/graph/TemporalPassRunner.java");

    @Test
    void temporalRunnerBindsTheExistingSurfaceClassAttachment() throws IOException {
        String source = Files.readString(RUNNER);

        assertTrue(source.contains(".withSampler(\"u_SurfaceClass\")"),
                "temporal bind-group must declare the surface-class sampler");
        assertTrue(source.contains("resolveView(\"builtin.gAo\""),
                "temporal runner must resolve the live gAo attachment");
        assertTrue(source.contains("bindTexture(\"u_SurfaceClass\""),
                "temporal pass must bind gAo to the shader's surface-class sampler");
    }

    @Test
    void onlyDrawnEntityPixelsRejectHistory() {
        assertTrue(responsive(0.4, 0.75), "drawn entity pixels must use the current frame");
        assertFalse(responsive(0.0, 0.0), "cleared sky shares class zero but must retain sky TAA");
        assertFalse(responsive(0.4, 0.0), "static block entities keep temporal accumulation");
        assertFalse(responsive(0.4, 0.25), "particles keep their existing temporal treatment");
        assertFalse(responsive(0.4, 0.5), "cutout terrain keeps temporal accumulation");
        assertFalse(responsive(0.4, 1.0), "solid terrain keeps temporal accumulation");
    }

    @Test
    void shaderResetsBothHistoryWeightAndAgeForResponsiveEntities() {
        String shader = shader();

        assertTrue(shader.contains("float validF = valid && !responsiveEntity ? 1.0 : 0.0;"),
                "responsive entities must force history validity to zero before weight and age");
        assertTrue(shader.contains("float ageIn = hist.a * CONFIDENCE_FRAMES * validF;"),
                "the same validity must reset accumulated age, not merely hide history for one frame");
        assertTrue(shader.contains("float ageOut = responsiveEntity ? 0.0"),
                "responsive pixels must store zero age so vacated pixels cannot reuse their color next frame");
    }

    private static boolean responsive(double depth, double surfaceClass) {
        return depth > define("SKY_DEPTH_EPSILON")
                && surfaceClass > define("ENTITY_SURFACE_CLASS_MIN")
                && surfaceClass < define("ENTITY_SURFACE_CLASS_MAX");
    }

    private static double define(String name) {
        Matcher matcher = Pattern.compile("#define\\s+" + name + "\\s+([0-9.]+)").matcher(shader());
        assertTrue(matcher.find(), "temporal shader must define " + name);
        return Double.parseDouble(matcher.group(1));
    }

    private static String shader() {
        try (InputStream in = TemporalEntityHistoryMaskTest.class.getResourceAsStream(SHADER)) {
            assertNotNull(in, "shader resource missing from classpath: " + SHADER);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("failed reading " + SHADER, e);
        }
    }
}
