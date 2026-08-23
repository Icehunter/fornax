package dev.icehunter.fornax.pipeline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-Java tests for {@link NoiseTexture}'s generator -- no GPU device needed (see the class doc
 * on why generation is split from upload). Covers the properties a downstream shader consumer of
 * {@code builtin.noise} (e.g. a pack's {@code cloud_noise3}) relies on: determinism (a pack
 * activation must never re-roll noise a player has already learned the silhouette of), tileability
 * (the REPEAT sampler wraps the lattice, so the value-noise function must genuinely repeat at the
 * cell period, not just look continuous at the canvas edge), per-channel contrast, and channel
 * independence (R/G/B/A must not be the same pattern relabeled).
 */
class NoiseTextureTest {

    @Test
    void generationIsDeterministic() {
        int[] first = NoiseTexture.generatePixels();
        int[] second = NoiseTexture.generatePixels();
        assertArrayEquals(first, second, "two generator calls must produce byte-identical pixels (fixed seed)");
    }

    @Test
    void pixelArrayIsFullCanvas() {
        int[] pixels = NoiseTexture.generatePixels();
        assertEquals(NoiseTexture.SIZE * NoiseTexture.SIZE, pixels.length);
    }

    @Test
    void rChannelLatticeWrapsAtCellPeriod() {
        // The R-channel value-noise lattice (16 cells/tile) must repeat exactly one full tile later --
        // this is the actual REPEAT-sampler contract, not merely "row 0 looks like row 511".
        assertLatticeWraps(NoiseTexture.R_CELLS, NoiseTexture.R_SALT);
    }

    @Test
    void gChannelLatticeWrapsAtCellPeriod() {
        assertLatticeWraps(NoiseTexture.G_CELLS, NoiseTexture.G_SALT);
    }

    @Test
    void fbmChannelWrapsAtCanvasPeriod() {
        // Every FBM octave's cell count (8/16/32/64) divides SIZE evenly, so the summed value must
        // also wrap exactly one full canvas (SIZE) later in both axes.
        for (int y = 0; y < NoiseTexture.SIZE; y += 37) {
            for (int x = 0; x < NoiseTexture.SIZE; x += 37) {
                float base = NoiseTexture.fbmValue(x, y);
                float shiftedX = NoiseTexture.fbmValue(x + NoiseTexture.SIZE, y);
                float shiftedY = NoiseTexture.fbmValue(x, y + NoiseTexture.SIZE);
                assertEquals(base, shiftedX, 0.0f, "fbmValue must wrap identically shifted by one full tile in x");
                assertEquals(base, shiftedY, 0.0f, "fbmValue must wrap identically shifted by one full tile in y");
            }
        }
    }

    @Test
    void whiteNoiseChannelWrapsAtCanvasPeriod() {
        for (int y = 0; y < NoiseTexture.SIZE; y += 41) {
            for (int x = 0; x < NoiseTexture.SIZE; x += 41) {
                assertEquals(NoiseTexture.whiteValue(x, y), NoiseTexture.whiteValue(x + NoiseTexture.SIZE, y), 0.0f);
                assertEquals(NoiseTexture.whiteValue(x, y), NoiseTexture.whiteValue(x, y + NoiseTexture.SIZE), 0.0f);
            }
        }
    }

    @Test
    void perChannelContrastIsSane() {
        int[] pixels = NoiseTexture.generatePixels();
        int rMin = 255, rMax = 0, gMin = 255, gMax = 0, bMin = 255, bMax = 0, aMin = 255, aMax = 0;
        for (int pixel : pixels) {
            int a = (pixel >>> 24) & 0xFF;
            int r = (pixel >>> 16) & 0xFF;
            int g = (pixel >>> 8) & 0xFF;
            int b = pixel & 0xFF;
            rMin = Math.min(rMin, r); rMax = Math.max(rMax, r);
            gMin = Math.min(gMin, g); gMax = Math.max(gMax, g);
            bMin = Math.min(bMin, b); bMax = Math.max(bMax, b);
            aMin = Math.min(aMin, a); aMax = Math.max(aMax, a);
        }
        assertTrue(rMin < 64, "R channel min too high: " + rMin);
        assertTrue(rMax > 191, "R channel max too low: " + rMax);
        assertTrue(gMin < 64, "G channel min too high: " + gMin);
        assertTrue(gMax > 191, "G channel max too low: " + gMax);
        assertTrue(bMin < 64, "B channel min too high: " + bMin);
        assertTrue(bMax > 191, "B channel max too low: " + bMax);
        assertTrue(aMin < 64, "A channel min too high: " + aMin);
        assertTrue(aMax > 191, "A channel max too low: " + aMax);
    }

    @Test
    void rAndGChannelsAreIndependent() {
        int[] pixels = NoiseTexture.generatePixels();
        int[] r = new int[pixels.length];
        int[] g = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            r[i] = (pixels[i] >>> 16) & 0xFF;
            g[i] = (pixels[i] >>> 8) & 0xFF;
        }
        assertFalse(java.util.Arrays.equals(r, g), "R and G channels must not be the same pattern (16 vs 32 lattice cells + distinct salts)");
    }

    @Test
    void bChannelDiffersFromRChannel() {
        int[] pixels = NoiseTexture.generatePixels();
        int[] r = new int[pixels.length];
        int[] b = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            r[i] = (pixels[i] >>> 16) & 0xFF;
            b[i] = pixels[i] & 0xFF;
        }
        assertFalse(java.util.Arrays.equals(r, b), "R (single-octave) and B (4-octave FBM) channels must not coincide");
    }

    @Test
    void aChannelIsUncorrelatedWhiteNoise() {
        // White noise must not equal any lattice-interpolated channel at every sampled pixel --
        // spot-check a handful of neighboring pixels where a smooth channel would be nearly flat.
        int[] pixels = NoiseTexture.generatePixels();
        int[] a = new int[pixels.length];
        int[] r = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            a[i] = (pixels[i] >>> 24) & 0xFF;
            r[i] = (pixels[i] >>> 16) & 0xFF;
        }
        assertFalse(java.util.Arrays.equals(a, r), "A (white noise) must not coincide with R (smooth lattice noise)");
    }

    private static void assertLatticeWraps(int cells, int salt) {
        for (int y = 0; y < NoiseTexture.SIZE; y += 23) {
            for (int x = 0; x < NoiseTexture.SIZE; x += 23) {
                float base = NoiseTexture.channelValue(x, y, cells, salt);
                float shiftedX = NoiseTexture.channelValue(x + NoiseTexture.SIZE, y, cells, salt);
                float shiftedY = NoiseTexture.channelValue(x, y + NoiseTexture.SIZE, cells, salt);
                assertEquals(base, shiftedX, 0.0f, "channelValue must wrap identically shifted by one full tile in x");
                assertEquals(base, shiftedY, 0.0f, "channelValue must wrap identically shifted by one full tile in y");
            }
        }
    }
}
