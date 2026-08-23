package dev.icehunter.fornax.pack.layout;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class VanillaAssetOverridesTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    @Test
    void moonPhaseMapsToVanillaCelestialPath() {
        Map<String, byte[]> out = VanillaAssetOverrides.extract(
                Map.of("textures/vanilla/celestial/moon/full_moon.png", PNG),
                Map.of("CELESTIAL_TEXTURES", 1));
        assertArrayEquals(PNG, out.get("textures/environment/celestial/moon/full_moon.png"));
        assertEquals(1, out.size());
    }

    @Test
    void allEightPhasesAndSunAreRegistered() {
        String[] names = {"celestial/sun.png", "celestial/moon/full_moon.png",
                "celestial/moon/waning_gibbous.png", "celestial/moon/third_quarter.png",
                "celestial/moon/waning_crescent.png", "celestial/moon/new_moon.png",
                "celestial/moon/waxing_crescent.png", "celestial/moon/first_quarter.png",
                "celestial/moon/waxing_gibbous.png"};
        for (String name : names) {
            Map<String, byte[]> out = VanillaAssetOverrides.extract(
                    Map.of("textures/vanilla/" + name, PNG),
                    Map.of("CELESTIAL_TEXTURES", 1));
            assertArrayEquals(PNG, out.get("textures/environment/" + name), name);
        }
    }

    @Test
    void gateOffOrAbsentSuppresses() {
        assertTrue(VanillaAssetOverrides.extract(
                Map.of("textures/vanilla/celestial/moon/full_moon.png", PNG),
                Map.of("CELESTIAL_TEXTURES", 0)).isEmpty());
        assertTrue(VanillaAssetOverrides.extract(
                Map.of("textures/vanilla/celestial/moon/full_moon.png", PNG),
                Map.of()).isEmpty());
    }

    @Test
    void unknownAssetNameFailsLoudly() {
        assertThrows(dev.icehunter.fornax.pack.FornaxPackError.class, () ->
                VanillaAssetOverrides.extract(
                        Map.of("textures/vanilla/creeper.png", PNG), Map.of()));
    }
}
