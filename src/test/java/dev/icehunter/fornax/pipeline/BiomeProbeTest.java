package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.pack.BiomesSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BiomeProbeTest {
    @Test
    void unmappedBiomeRetainsItsActualClimate() {
        var values = BiomeProbe.values(BiomesSpec.empty(), "example:cave", -0.4f, -0.6f, 0.8f);
        assertEquals(0f, values.id());
        assertEquals(-0.4f, values.baseTemperature());
        assertEquals(-0.6f, values.localTemperature());
        assertEquals(0.8f, values.downfall());
    }

    @Test
    void mappedBiomeUsesTheExplicitIdentityWithoutRenumbering() {
        var mapping = new BiomesSpec(Map.of("example:cave", 37));
        assertEquals(37f, BiomeProbe.values(mapping, "example:cave", 0f, 0f, 0f).id());
        assertEquals(0f, BiomeProbe.values(mapping, null, 0f, 0f, 0f).id());
    }

    @Test
    void noWorldOrCameraReturnsZeroWithoutRetainedPriorValues() {
        var mapping = new BiomesSpec(Map.of("example:cave", 37));
        BiomeProbe.values(mapping, "example:cave", 2f, 1f, 0.5f);
        assertSame(BiomeProbe.ZERO, BiomeProbe.read(null, null, mapping));
    }
}
