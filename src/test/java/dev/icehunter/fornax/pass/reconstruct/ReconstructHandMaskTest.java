package dev.icehunter.fornax.pass.reconstruct;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the responsive-pixel (first-person) mask predicate against real reversed-Z numbers, reading
 * {@code HAND_DEPTH_EPSILON} and {@code FIRST_PERSON_PROXIMITY_DEPTH} from the shipped shader
 * source so the test cannot drift from what runs. The predicate is the AND of two conditions in
 * reversed-Z depth units (depth = near/viewZ, near plane 0.05, "nearer" = larger — the section-12
 * law): {@code sceneDepth - gbufferDepth > epsilon} (vanilla wrote first-person depth into the
 * scene AFTER the engine copyback; terrain-only G-buffer beneath) AND {@code sceneDepth >
 * proximity} (inside the ~2.5m first-person volume).
 *
 * <p>The proximity bound exists because the delta alone also fires on WATER: the
 * translucent water surface writes scene depth nearer than the seafloor's G-buffer depth — the
 * identical delta signature as the hand. Hand/held items (translucent included) always satisfy
 * proximity; water beyond arm's reach never does. Known accepted edge: standing chest-deep, water
 * pixels inside 2.5m mask and render fully-current — a tiny region, visually fine.
 *
 * <p>Also pins the THREE-TIER history weight built on the same signals: opaque pixels (no delta)
 * ramp to the full blend factor; translucent-overlay pixels (delta at any distance) cap at {@code
 * TRANSLUCENT_OVERLAY_HISTORY_CAP} so animated surface textures (scrolling water waves, moving
 * over the seafloor's static motion vectors) survive accumulation instead of averaging to flat
 * color -- under TAAU only, since at ratio 1.0 the native-res clamp already displaces history and
 * the pass must match the retired blend's full-factor treatment of translucents; first-person
 * pixels (delta AND proximity) get zero history with age reset at every ratio.
 */
class ReconstructHandMaskTest {
    private static final String SHADER_RESOURCE = "/assets/fornax/shaders/post/reconstruct.fsh";
    private static final double NEAR_PLANE = 0.05;

    @Test
    void handAgainstWorldTerrainIsMasked() {
        // Arm/held item at half a meter; the terrain it covers is 3 blocks away.
        assertTrue(masked(reversedZ(0.5), reversedZ(3.0)),
                "first-person geometry at 0.5m over terrain at 3m must trip the mask");
    }

    @Test
    void heldItemAtArmsLengthOverNearbyWallIsStillMasked() {
        // Worst realistic case: held (possibly translucent) item at 1m, wall right behind at 2.5m.
        assertTrue(masked(reversedZ(1.0), reversedZ(2.5)),
                "held items (translucent included) must stay masked even against close terrain");
    }

    @Test
    void clearedFarBackgroundNeverTripsTheMask() {
        // Outside the hand silhouette the scene depth is the far clear (0.0, reversed-Z), which
        // sits at or below every terrain depth -- the delta can never exceed a positive epsilon.
        assertFalse(masked(0.0, reversedZ(1.5)), "cleared scene depth vs near terrain must not mask");
        assertFalse(masked(0.0, reversedZ(200.0)), "cleared scene depth vs far terrain must not mask");
    }

    @Test
    void waterSurfaceBeyondArmsReachIsNotMasked() {
        // The case the proximity bound exists for: a water surface whose
        // seafloor is far beneath it has the same POSITIVE delta signature as the hand. The
        // nearest such case (4m surface, 8m seafloor -- reversed-Z compresses distant deltas
        // below the epsilon on its own) is exactly where only proximity saves it.
        double surface = reversedZ(4.0);
        double seafloor = reversedZ(8.0);
        assertTrue(surface - seafloor > parseEpsilon(),
                "sanity: near-ish water's delta really does mimic the hand (why proximity is needed)");
        assertFalse(masked(surface, seafloor),
                "water beyond the first-person volume must keep temporal accumulation");

        // Farther water (10m over a 15m seafloor) fails both conditions -- doubly safe.
        assertFalse(masked(reversedZ(10.0), reversedZ(15.0)),
                "distant water must keep temporal accumulation");
    }

    @Test
    void nearbyShallowWaterEdgeIsMaskedAndAccepted() {
        // Documented accepted edge: standing chest-deep, water inside the 2.5m volume masks and
        // renders fully-current -- a tiny region, visually fine, strictly better than the
        // pre-proximity behavior of masking EVERY water pixel.
        assertTrue(masked(reversedZ(1.5), reversedZ(4.0)),
                "chest-deep near water inside the first-person volume masks (accepted edge)");
    }

    @Test
    void epsilonSitsBetweenFirstPersonDeltaAndTerrainNoise() {
        double epsilon = parseEpsilon();
        // Lower bound: must exceed continuous-surface deltas (walking-scale reprojection noise,
        // ~0.0004 -- see ReconstructValidityMathTest) by a wide margin.
        assertTrue(epsilon > 0.002, "epsilon must be far above continuous-surface depth noise, got " + epsilon);
        // Upper bound: must stay below the smallest realistic first-person delta -- vanilla's
        // hand/held-item geometry sits within ~1.5m of the camera, and the nearest terrain that
        // can show behind it without colliding is ~2m.
        double smallestFirstPersonDelta = reversedZ(1.5) - reversedZ(2.0);
        assertTrue(epsilon < smallestFirstPersonDelta,
                "epsilon (" + epsilon + ") must undercut the smallest first-person delta (" + smallestFirstPersonDelta + ")");
    }

    @Test
    void midDistanceWaterGetsCappedWeightUnderTaauOnly() {
        // Tier 2: water at 4m over an 8m seafloor -- translucent-overlay delta, outside the
        // first-person volume. Under TAAU (ratio below 1.0) the saturated weight must be exactly
        // the cap: animation survives at >= half strength, edges still smooth. Neither full
        // temporal nor masked.
        double weight = historyWeight(reversedZ(4.0), reversedZ(8.0), SATURATED_AGE, false);

        assertEquals(parseOverlayCap(), weight, 1e-9,
                "mid-distance water must accumulate at the capped weight under TAAU");
        assertTrue(weight < BLEND_FACTOR, "capped weight must be below the opaque steady state");
        assertTrue(weight > 0.0, "capped weight must not be the first-person zero");
    }

    @Test
    void midDistanceWaterKeepsFullWeightAtRatioOne() {
        // At ratio 1.0 (TAA) the tier-2 cap disengages: the pass must blend translucents at the
        // full factor, exactly like the retired native-res blend it is equivalent to.
        double weight = historyWeight(reversedZ(4.0), reversedZ(8.0), SATURATED_AGE, true);

        assertEquals(BLEND_FACTOR, weight, 1e-9,
                "at ratio 1.0 translucent overlays keep the full temporal weight");
    }

    @Test
    void opaqueTerrainKeepsFullTemporalWeight() {
        // Tier 1: no delta (scene depth == gbuffer depth after the copyback) -- full blend factor
        // at saturated age, exactly as before the tiers existed.
        double weight = historyWeight(reversedZ(6.0), reversedZ(6.0), SATURATED_AGE, false);

        assertEquals(BLEND_FACTOR, weight, 1e-9, "opaque surfaces keep the full temporal weight");
    }

    @Test
    void firstPersonRegionStillGetsZeroHistory() {
        // Tier 3: delta AND proximity -- weight zero regardless of accumulated age.
        double weightTaau = historyWeight(reversedZ(0.5), reversedZ(3.0), SATURATED_AGE, false);
        double weightTaa = historyWeight(reversedZ(0.5), reversedZ(3.0), SATURATED_AGE, true);

        assertEquals(0.0, weightTaau, 1e-9, "first-person pixels blend fully current under TAAU");
        assertEquals(0.0, weightTaa, 1e-9, "first-person masking is ratio-independent");
    }

    /** The shader's mask predicate, mirrored exactly: delta AND proximity. */
    private static boolean masked(double sceneDepth, double gbufferDepth) {
        return sceneDepth - gbufferDepth > parseEpsilon() && sceneDepth > parseProximity();
    }

    private static final double BLEND_FACTOR = 0.9;
    private static final double SATURATED_AGE = 32.0;

    /**
     * The shader's three-tier weight math, mirrored exactly: validF zeroes age for first-person
     * pixels, the overlay cap replaces the blend factor for translucent overlays under TAAU only
     * (the {@code translucentOverlay * (1.0 - u_RatioIsOne)} gate), and the 1/n ramp saturates
     * against whichever cap applies.
     */
    private static double historyWeight(double sceneDepth, double gbufferDepth, double age, boolean ratioIsOne) {
        boolean overlay = sceneDepth - gbufferDepth > parseEpsilon();
        boolean firstPerson = overlay && sceneDepth > parseProximity();
        double effectiveAge = firstPerson ? 0.0 : age;
        double cap = (overlay && !ratioIsOne) ? parseOverlayCap() : BLEND_FACTOR;
        return Math.min(1.0 - 1.0 / (effectiveAge + 1.0), cap);
    }

    private static double reversedZ(double viewDistance) {
        return NEAR_PLANE / viewDistance;
    }

    private static double parseEpsilon() {
        return parseDefine("HAND_DEPTH_EPSILON");
    }

    private static double parseProximity() {
        return parseDefine("FIRST_PERSON_PROXIMITY_DEPTH");
    }

    private static double parseOverlayCap() {
        return parseDefine("TRANSLUCENT_OVERLAY_HISTORY_CAP");
    }

    private static double parseDefine(String name) {
        String source = readShaderSource();
        Matcher m = Pattern.compile("#define\\s+" + name + "\\s+([0-9.]+)").matcher(source);
        assertTrue(m.find(), "reconstruct.fsh must define " + name);
        return Double.parseDouble(m.group(1));
    }

    private static String readShaderSource() {
        try (InputStream in = ReconstructHandMaskTest.class.getResourceAsStream(SHADER_RESOURCE)) {
            assertNotNull(in, "shader resource missing from classpath: " + SHADER_RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("failed reading " + SHADER_RESOURCE, e);
        }
    }
}
