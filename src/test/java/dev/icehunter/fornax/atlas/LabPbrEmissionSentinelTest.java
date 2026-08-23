package dev.icehunter.fornax.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LabPBR 1.3 emission alpha: 0..254 are literal and 255 is unprovided per texel. */
class LabPbrEmissionSentinelTest {
    @Test
    void all255IsUnprovidedAndByteIdentical() {
        int[] texels = {argb(255, 0x112233), argb(255, 0x445566)};
        int[] before = texels.clone();

        assertFalse(LabPbrEmissionSentinel.resolve(texels));
        assertArrayEquals(before, texels);
    }

    @Test
    void constant254IsAuthoredAndByteIdentical() {
        int[] texels = {argb(254, 0x112233), argb(254, 0x445566)};
        int[] before = texels.clone();

        assertTrue(LabPbrEmissionSentinel.resolve(texels));
        assertArrayEquals(before, texels);
    }

    @Test
    void constantZeroIsAuthoredAndByteIdentical() {
        int[] texels = {argb(0, 0x112233), argb(0, 0x445566)};
        int[] before = texels.clone();

        assertTrue(LabPbrEmissionSentinel.resolve(texels));
        assertArrayEquals(before, texels);
    }

    @Test
    void mixedAuthoredAndUnprovidedTexelsRemainByteIdentical() {
        int[] texels = {argb(254, 0x112233), argb(255, 0x445566),
                argb(0, 0x778899), argb(127, 0xAABBCC)};
        int[] before = texels.clone();

        assertTrue(LabPbrEmissionSentinel.resolve(texels));
        assertArrayEquals(before, texels);
    }

    @Test
    void emptyInputIsUnprovided() {
        assertFalse(LabPbrEmissionSentinel.resolve(new int[0]));
    }

    private static int argb(int alpha, int rgb) {
        return (alpha << 24) | rgb;
    }
}
