package dev.icehunter.fornax.pipeline;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;

import java.util.function.Supplier;

/**
 * Holds one saved answer per build thread: does it rain, snow, or nothing here. The block mixin
 * and the fluid mixin ask this about the same spot, one right after the other, on the same
 * thread, for any block sitting in water. One saved answer is enough, since nothing else on that
 * thread asks in between.
 */
public final class BiomePrecipitationCache {
    private static final ThreadLocal<long[]> LAST_POS = ThreadLocal.withInitial(() -> new long[]{Long.MIN_VALUE});
    private static final ThreadLocal<Biome.Precipitation[]> LAST_RESULT =
            ThreadLocal.withInitial(() -> new Biome.Precipitation[1]);

    private BiomePrecipitationCache() {}

    /**
     * Rain, snow, or none at {@code pos}. Returns the saved answer if the last call on this
     * thread asked about this same spot. Otherwise runs {@code lookup}, saves the answer, and
     * returns it.
     */
    public static Biome.Precipitation at(BlockPos pos, Supplier<Biome.Precipitation> lookup) {
        long packed = pos.asLong();
        long[] lastPos = LAST_POS.get();
        if (lastPos[0] == packed) {
            return LAST_RESULT.get()[0];
        }
        Biome.Precipitation result = lookup.get();
        lastPos[0] = packed;
        LAST_RESULT.get()[0] = result;
        return result;
    }
}
