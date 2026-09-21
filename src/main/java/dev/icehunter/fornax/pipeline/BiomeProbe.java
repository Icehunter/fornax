package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.pack.BiomesSpec;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/** The biome where the camera is. Read every frame, whatever the pack draws or turns on. */
public final class BiomeProbe {
    public record Values(float id, float baseTemperature, float localTemperature, float downfall) {}

    public static final Values ZERO = new Values(0f, 0f, 0f, 0f);

    private BiomeProbe() {}

    public static Values read() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.gameRenderer == null) return ZERO;
        var camera = client.gameRenderer.mainCamera();
        if (camera == null) return ZERO;
        return read(client.level, camera.blockPosition(), mapping());
    }

    /** Keeps nothing between frames. With no world or no camera, every number is 0. */
    public static Values read(@Nullable ClientLevel level, @Nullable BlockPos pos, BiomesSpec mapping) {
        if (level == null || pos == null) return ZERO;
        Holder<Biome> holder = level.getBiome(pos);
        Biome biome = holder.value();
        return values(mapping, key(holder), biome.getBaseTemperature(),
                biome.getTemperature(pos, level.getSeaLevel()), biome.climateSettings.downfall());
    }

    /** Name to ID, and nothing else. A name with no ID still keeps its heat and rain. */
    public static Values values(BiomesSpec mapping, @Nullable String key,
                                float baseTemperature, float localTemperature, float downfall) {
        return new Values(mapping.id(key), baseTemperature, localTemperature, downfall);
    }

    public static int id(Holder<Biome> biome) { return mapping().id(key(biome)); }

    private static String key(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
    }

    private static BiomesSpec mapping() {
        var pack = GraphRunner.currentPack();
        return pack == null ? BiomesSpec.empty() : pack.biomes();
    }
}
