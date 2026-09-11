package dev.icehunter.fornax.pipeline;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks the one saved answer that {@link BlockRendererMaterialIdMixin} and
 * {@link FluidRendererMaterialIdMixin} share for one block's rain/snow answer. */
class BiomePrecipitationCacheTest {
    @Test
    void secondCallForTheSamePositionSkipsTheLookup() {
        BlockPos pos = new BlockPos(101, 12, -37);
        AtomicInteger lookups = new AtomicInteger();

        Biome.Precipitation first = BiomePrecipitationCache.at(pos, () -> {
            lookups.incrementAndGet();
            return Biome.Precipitation.SNOW;
        });
        Biome.Precipitation second = BiomePrecipitationCache.at(pos, () -> {
            lookups.incrementAndGet();
            return Biome.Precipitation.RAIN;
        });

        assertEquals(Biome.Precipitation.SNOW, first);
        assertEquals(Biome.Precipitation.SNOW, second, "same position must return the cached answer");
        assertEquals(1, lookups.get(), "second call must not run its own lookup");
    }

    @Test
    void aDifferentPositionRunsItsOwnLookup() {
        BlockPos first = new BlockPos(202, 40, 8);
        BlockPos second = new BlockPos(203, 40, 8);
        AtomicInteger lookups = new AtomicInteger();

        BiomePrecipitationCache.at(first, () -> {
            lookups.incrementAndGet();
            return Biome.Precipitation.NONE;
        });
        Biome.Precipitation secondResult = BiomePrecipitationCache.at(second, () -> {
            lookups.incrementAndGet();
            return Biome.Precipitation.RAIN;
        });

        assertEquals(Biome.Precipitation.RAIN, secondResult);
        assertEquals(2, lookups.get(), "a new position must run its own lookup, not reuse the last one");
    }
}
