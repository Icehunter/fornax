package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Level-zero and unavoidable-resize contracts for LabPBR sidecars. */
class LabPbrSidecarBlitterResamplingTest {
    @Test
    void exactSizeMaterialTransportPreservesEveryAuthoredByte() {
        try (NativeImage source = new NativeImage(NativeImage.Format.RGBA, 2, 2, false);
             NativeImage destination = new NativeImage(NativeImage.Format.RGBA, 2, 2, false)) {
            source.setPixel(0, 0, argb(0, 1, 64, 65));
            source.setPixel(1, 0, argb(127, 128, 229, 230));
            source.setPixel(0, 1, argb(254, 232, 233, 234));
            source.setPixel(1, 1, argb(255, 255, 238, 10));

            LabPbrSidecarBlitter.transport(
                    source, source.getHeight(), destination, LabPbrSidecarBlitter.Filter.MATERIAL);

            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    assertEquals(source.getPixel(x, y), destination.getPixel(x, y));
                }
            }
        }
    }

    @Test
    void normalReductionRenormalizesDirectionAndAveragesAoAndHeight() {
        try (NativeImage source = new NativeImage(NativeImage.Format.RGBA, 2, 2, false);
             NativeImage reduced = new NativeImage(NativeImage.Format.RGBA, 1, 1, false)) {
            source.setPixel(0, 0, argb(10, 204, 128, 20));
            source.setPixel(1, 0, argb(20, 128, 128, 40));
            source.setPixel(0, 1, argb(30, 204, 128, 60));
            source.setPixel(1, 1, argb(40, 128, 128, 80));

            LabPbrSidecarBlitter.transport(
                    source, source.getHeight(), reduced, LabPbrSidecarBlitter.Filter.NORMAL);

            int texel = reduced.getPixel(0, 0);
            assertEquals(168, red(texel));
            assertEquals(128, green(texel));
            assertEquals(50, blue(texel));
            assertEquals(25, alpha(texel));
        }
    }

    @Test
    void allBlackFlatNormalSentinelSurvivesLevelZeroDownscale() {
        try (NativeImage source = new NativeImage(NativeImage.Format.RGBA, 2, 2, false);
             NativeImage reduced = new NativeImage(NativeImage.Format.RGBA, 1, 1, false)) {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    source.setPixel(x, y, 0);
                }
            }

            LabPbrSidecarBlitter.transport(
                    source, source.getHeight(), reduced, LabPbrSidecarBlitter.Filter.NORMAL);

            assertEquals(0, reduced.getPixel(0, 0),
                    "the authored all-black flat-normal sentinel must remain categorical");
        }
    }

    @Test
    void allBlackFlatNormalSentinelSurvivesMipReduction() {
        assertEquals(0, LabPbrSidecarBlitter.reduceNormal(0, 0, 0, 0),
                "normal mip generation must not reinterpret the flat sentinel as (-1,-1,0)");
    }

    @Test
    void materialReductionPreservesClassesAndEmissionAbsence() {
        try (NativeImage source = new NativeImage(NativeImage.Format.RGBA, 2, 2, false);
             NativeImage reduced = new NativeImage(NativeImage.Format.RGBA, 1, 1, false)) {
            source.setPixel(0, 0, argb(200, 10, 229, 64));
            source.setPixel(1, 0, argb(255, 100, 230, 65));
            source.setPixel(0, 1, argb(255, 110, 231, 127));
            source.setPixel(1, 1, argb(255, 120, 232, 128));

            LabPbrSidecarBlitter.transport(
                    source, source.getHeight(), reduced, LabPbrSidecarBlitter.Filter.MATERIAL);

            int texel = reduced.getPixel(0, 0);
            assertEquals(110, red(texel));
            assertEquals(232, green(texel));
            assertTrue(blue(texel) >= 65);
            assertEquals(107, blue(texel));
            assertEquals(50, alpha(texel), "255 is absence and contributes zero over the footprint");
        }
    }

    @Test
    void normalUpscalingUsesNearestAuthoredTexels() {
        try (NativeImage source = new NativeImage(NativeImage.Format.RGBA, 2, 1, false);
             NativeImage enlarged = new NativeImage(NativeImage.Format.RGBA, 4, 1, false)) {
            source.setPixel(0, 0, argb(20, 204, 128, 40));
            source.setPixel(1, 0, argb(80, 128, 128, 100));

            LabPbrSidecarBlitter.transport(
                    source, source.getHeight(), enlarged, LabPbrSidecarBlitter.Filter.NORMAL);

            assertEquals(source.getPixel(0, 0), enlarged.getPixel(0, 0));
            assertEquals(source.getPixel(0, 0), enlarged.getPixel(1, 0));
            assertEquals(source.getPixel(1, 0), enlarged.getPixel(2, 0));
            assertEquals(source.getPixel(1, 0), enlarged.getPixel(3, 0));
        }
    }

    @Test
    void upscalingReplicatesEveryAuthoredByteWithoutSemanticReduction() {
        for (LabPbrSidecarBlitter.Filter filter : new LabPbrSidecarBlitter.Filter[] {
                LabPbrSidecarBlitter.Filter.NORMAL, LabPbrSidecarBlitter.Filter.MATERIAL}) {
            for (int authored : new int[] {
                    argb(0, 0, 0, 0),
                    argb(255, 255, 0, 254),
                    argb(254, 1, 238, 128)}) {
                try (NativeImage source = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
                     NativeImage enlarged = new NativeImage(NativeImage.Format.RGBA, 8, 8, false)) {
                    source.setPixel(0, 0, authored);

                    LabPbrSidecarBlitter.transport(source, 1, enlarged, filter);

                    for (int y = 0; y < enlarged.getHeight(); y++) {
                        for (int x = 0; x < enlarged.getWidth(); x++) {
                            assertEquals(authored, enlarged.getPixel(x, y),
                                    filter + " changed source bytes at " + x + "," + y);
                        }
                    }
                }
            }
        }
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }

    private static int red(int argb) {
        return (argb >>> 16) & 0xFF;
    }

    private static int green(int argb) {
        return (argb >>> 8) & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }
}
