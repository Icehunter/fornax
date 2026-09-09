package dev.icehunter.fornax.voxel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.LightLayer;
import org.jetbrains.annotations.Nullable;

/** Optional world light per cell. One byte per cell, four cells to a GPU uint, in the payload's
 * (y*256 + z*16 + x) order: low 4 bits block light, high 4 sky, Minecraft's 0..15. With no world
 * data a cell stays dark. */
public final class VoxelLightmap {
    public static final String TARGET = "voxelLightmap";
    public static final int BYTES_PER_SLOT = 16 * 16 * 16;
    public static final int WORDS_PER_SLOT = BYTES_PER_SLOT / Integer.BYTES;

    @FunctionalInterface interface LightReader { byte read(int x, int y, int z); }
    private VoxelLightmap() { }

    static byte pack(int block, int sky) {
        return (byte) ((block & 15) | ((sky & 15) << 4));
    }

    static byte[] sample(LightReader reader, int originX, int originY, int originZ) {
        byte[] lightmap = new byte[BYTES_PER_SLOT];
        for (int y=0; y<16; y++) for (int z=0; z<16; z++) for (int x=0; x<16; x++)
            lightmap[(y << 8) | (z << 4) | x] = reader.read(originX+x, originY+y, originZ+z);
        return lightmap;
    }

    /** Values are copied here, never read again during the GPU upload. */
    public static byte[] capture(@Nullable BlockAndLightGetter source, int originX, int originY, int originZ) {
        if (source == null) return new byte[BYTES_PER_SLOT];
        long start = VoxelRefillTelemetry.start();
        try {
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            byte[] lightmap = sample((x,y,z) -> {
                position.set(x,y,z);
                return pack(source.getBrightness(LightLayer.BLOCK, position), source.getBrightness(LightLayer.SKY, position));
            }, originX, originY, originZ);
            VoxelRefillTelemetry.add(VoxelRefillTelemetry.Count.LIGHT_CELLS, lightmap.length);
            return lightmap;
        } finally { VoxelRefillTelemetry.finish(VoxelRefillTelemetry.Phase.LIGHT, start); }
    }
}
