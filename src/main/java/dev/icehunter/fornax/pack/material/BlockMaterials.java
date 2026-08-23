package dev.icehunter.fornax.pack.material;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * The live blockstate -> material ID lookup the terrain mesher reads. Rebuilt whenever the
 * active pack changes or block tags reload (see {@link MaterialResolution}). Keyed by Block (v0.1
 * categories are per-block-type); unmapped blocks return 0 (uncategorized -> pure labPBR).
 */
public final class BlockMaterials {
    private static volatile Map<Block, Integer> byBlock = Map.of();

    private BlockMaterials() {}

    public static void install(Map<Block, Integer> map) { byBlock = Map.copyOf(map); }

    public static void clear() { byBlock = Map.of(); }

    public static int idForState(BlockState state) { return byBlock.getOrDefault(state.getBlock(), 0); }
}
