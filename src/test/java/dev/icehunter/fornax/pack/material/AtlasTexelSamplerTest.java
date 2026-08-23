package dev.icehunter.fornax.pack.material;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * javap-verified against the real jar (minecraft-merged-5fc717cda5-26.2.jar):
 * {@code NativeImage.setPixel(int,int,int)} bytecode invokes {@code ARGB.toABGR} on its third
 * argument before delegating to the private {@code setPixelABGR}, and {@code getPixel(int,int)}
 * invokes {@code ARGB.fromABGR} on the private {@code getPixelABGR}'s result -- both public methods
 * are symmetric ARGB in/out; the raw ABGR storage format never crosses the public API. So this test
 * passes packed {@code 0xAARRGGBB} values straight to {@code setPixel}, with no manual conversion.
 */
class AtlasTexelSamplerTest {
    @Test
    void averagesAUniformlyColoredRegion() {
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, 4, 4, false)) {
            int solidRed = 0xFFFF0000; // ARGB: opaque red
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    image.setPixel(x, y, solidRed);
                }
            }
            int avg = AtlasTexelSampler.averageColor(image, 0f, 0f, 1f, 1f);
            assertEquals(solidRed, avg);
        }
    }

    @Test
    void averagesAMixedRegionToTheirMean() {
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, 2, 1, false)) {
            image.setPixel(0, 0, 0xFF000000); // opaque black
            image.setPixel(1, 0, 0xFFFFFFFF); // opaque white
            int avg = AtlasTexelSampler.averageColor(image, 0f, 0f, 1f, 1f);
            int r = (avg >> 16) & 0xFF;
            assertTrue(r >= 120 && r <= 135, "expected roughly mid-gray red channel, got " + r);
        }
    }

    @Test
    void samplesOnlyTheGivenFractionalSubRectangle() {
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, 4, 1, false)) {
            image.setPixel(0, 0, 0xFF00FF00); // green, outside the sampled range
            image.setPixel(1, 0, 0xFF0000FF); // blue, inside
            image.setPixel(2, 0, 0xFF0000FF); // blue, inside
            image.setPixel(3, 0, 0xFF00FF00); // green, outside
            int avg = AtlasTexelSampler.averageColor(image, 0.25f, 0f, 0.75f, 1f);
            assertEquals(0xFF0000FF, avg, "sub-rectangle [0.25, 0.75) should sample only the blue pixels");
        }
    }

    @Test
    void opaqueFractionCountsTexelsAtOrAboveTheThreshold() {
        // 4x4: left half fully opaque, right half fully transparent -> 0.5 coverage.
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, 4, 4, false)) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    image.setPixel(x, y, x < 2 ? 0xFF00FF00 : 0x0000FF00);
                }
            }
            assertEquals(0.5f, AtlasTexelSampler.opaqueFraction(image, 0f, 0f, 1f, 1f, 0.5f), 1e-4f);
            // Sampling only the opaque half must read fully covered.
            assertEquals(1.0f, AtlasTexelSampler.opaqueFraction(image, 0f, 0f, 0.5f, 1f, 0.5f), 1e-4f);
        }
    }

    @Test
    void opaqueFractionIgnoresAlphaBelowTheThreshold() {
        // Uniform alpha 0.25: average alpha would be 0.25, but NOTHING blocks light at a 0.5 cutout.
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, 2, 2, false)) {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    image.setPixel(x, y, 0x4000FF00);
                }
            }
            assertEquals(0.0f, AtlasTexelSampler.opaqueFraction(image, 0f, 0f, 1f, 1f, 0.5f), 1e-4f);
        }
    }

    @Test
    void opaqueFractionDoesNotSilentlyReturnZeroForAnInvertedRect() {
        // u0 > u1 (or v0 > v1) must not make the pixel loop empty and read as "nothing here" 0.0 --
        // it must collapse to its start edge and still measure real coverage there.
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, 4, 4, false)) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    image.setPixel(x, y, 0xFF00FF00); // fully opaque everywhere
                }
            }
            assertEquals(1.0f, AtlasTexelSampler.opaqueFraction(image, 0.75f, 0f, 0.25f, 1f, 0.5f), 1e-4f,
                    "inverted u-rect must collapse to its start edge, not read as an empty region");
            assertEquals(1.0f, AtlasTexelSampler.opaqueFraction(image, 0f, 0.75f, 1f, 0.25f, 0.5f), 1e-4f,
                    "inverted v-rect must collapse to its start edge, not read as an empty region");
        }
    }
}
