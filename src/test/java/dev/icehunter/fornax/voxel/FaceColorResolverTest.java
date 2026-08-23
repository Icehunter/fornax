package dev.icehunter.fornax.voxel;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FaceColorResolverTest {
    @Test
    @Disabled("needs a live Minecraft client instance (Minecraft.getInstance().getModelManager()) "
            + "for real model/sprite resolution -- verified live in this task's Step 6 instead")
    void stoneHasANonZeroColorOnEveryFace() {
        for (Direction dir : Direction.values()) {
            int color = FaceColorResolver.resolve(Blocks.STONE.defaultBlockState(), dir);
            assertNotEquals(0, color, "stone should resolve a real color on face " + dir);
        }
    }

    @Test
    @Disabled("needs a live Minecraft client instance (Minecraft.getInstance().getModelManager()) "
            + "for real model/sprite resolution -- cutout/cross milestone; verify live in-game per "
            + "this task's own validation requirements")
    void oakLeavesResolveARealCutoutRect() {
        float[] rect = FaceColorResolver.resolveCutoutRect(Blocks.OAK_LEAVES.defaultBlockState());
        assertNotEquals(null, rect);
    }

    @Test
    @Disabled("needs a live Minecraft client instance (Minecraft.getInstance().getModelManager()) "
            + "for real model/sprite resolution -- cutout/cross milestone; verify live in-game per "
            + "this task's own validation requirements")
    void shortGrassResolvesRealCrossGeometry() {
        FaceColorResolver.CrossGeometry cross =
                FaceColorResolver.resolveCrossGeometry(Blocks.SHORT_GRASS.defaultBlockState());
        assertNotEquals(null, cross);
    }

    @Test
    @Disabled("needs a live Minecraft client instance (Minecraft.getInstance().getModelManager()) "
            + "for real model/sprite resolution -- cutout/cross milestone; verify live in-game per "
            + "this task's own validation requirements")
    void stoneHasNoCrossGeometry() {
        // A real cube block bakes no direction-less (unculled) quads, so resolveCrossGeometry must
        // return null rather than fabricating geometry that doesn't exist.
        FaceColorResolver.CrossGeometry cross =
                FaceColorResolver.resolveCrossGeometry(Blocks.STONE.defaultBlockState());
        assertEquals(null, cross);
    }
}
