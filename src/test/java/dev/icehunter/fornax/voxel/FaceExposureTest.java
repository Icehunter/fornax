package dev.icehunter.fornax.voxel;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaceExposureTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void faceBetweenStoneAndAirIsExposed() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        assertTrue(FaceExposure.isExposed(stone, air, Direction.UP));
    }

    @Test
    void faceBetweenTwoStoneBlocksIsNotExposed() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertFalse(FaceExposure.isExposed(stone, stone, Direction.NORTH));
    }

    @Test
    void faceBetweenStoneAndGlassIsExposed() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        assertTrue(FaceExposure.isExposed(stone, glass, Direction.EAST));
    }

    @Test
    void faceBetweenStoneAndTintedGlassIsNotExposed() {
        // Tinted glass fully blocks light (getLightDampening() == 15) despite looking like glass --
        // confirmed vanilla behavior; the exposure test must respect this, not just "is it glass".
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState tintedGlass = Blocks.TINTED_GLASS.defaultBlockState();
        assertFalse(FaceExposure.isExposed(stone, tintedGlass, Direction.WEST));
    }

    @Test
    void faceBetweenStoneAndLeavesIsExposed() {
        // Leaves dampen light by only 1 (not fully sealed) -- must count as exposed.
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        assertTrue(FaceExposure.isExposed(stone, leaves, Direction.DOWN));
    }
}
