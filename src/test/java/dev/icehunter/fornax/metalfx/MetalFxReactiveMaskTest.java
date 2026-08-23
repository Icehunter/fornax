package dev.icehunter.fornax.metalfx;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetalFxReactiveMaskTest {
    private static final String MASK =
            "/assets/fornax/shaders/post/metalfx_reactive_mask.fsh";
    private static final String RECONSTRUCT =
            "/assets/fornax/shaders/post/reconstruct.fsh";

    @Test
    void maskKeepsReconstructDepthThresholdsInLockstep() {
        assertEquals(define(RECONSTRUCT, "HAND_DEPTH_EPSILON"),
                define(MASK, "HAND_DEPTH_EPSILON"), 0.0);
        assertEquals(define(RECONSTRUCT, "FIRST_PERSON_PROXIMITY_DEPTH"),
                define(MASK, "FIRST_PERSON_PROXIMITY_DEPTH"), 0.0);
    }

    @Test
    void maskMakesFirstPersonFullyReactiveAndOtherOverlaysHalfReactive() {
        double epsilon = define(MASK, "HAND_DEPTH_EPSILON");
        double proximity = define(MASK, "FIRST_PERSON_PROXIMITY_DEPTH");
        double overlayStrength = define(MASK, "TRANSLUCENT_REACTIVE_STRENGTH");

        assertEquals(1.0, strength(0.10, 0.02, epsilon, proximity, overlayStrength), 1e-9);
        assertEquals(0.5, strength(0.0125, 0.00625, epsilon, proximity, overlayStrength), 1e-9);
        assertEquals(0.0, strength(0.01, 0.01, epsilon, proximity, overlayStrength), 1e-9);
    }

    private static double strength(double sceneDepth, double gbufferDepth,
            double epsilon, double proximity, double overlayStrength) {
        boolean overlay = sceneDepth - gbufferDepth >= epsilon;
        boolean firstPerson = overlay && sceneDepth >= proximity;
        return firstPerson ? 1.0 : overlay ? overlayStrength : 0.0;
    }

    private static double define(String resource, String name) {
        Matcher matcher = Pattern.compile("#define\\s+" + name + "\\s+([0-9.]+)")
                .matcher(read(resource));
        assertTrue(matcher.find(), resource + " must define " + name);
        return Double.parseDouble(matcher.group(1));
    }

    private static String read(String resource) {
        try (InputStream in = MetalFxReactiveMaskTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "shader resource missing: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("failed reading " + resource, e);
        }
    }
}
