package dev.icehunter.fornax.voxel;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FaceColorResolverTest {
    /**
     * The one invariant this class must never lose: {@code resolve} must not cache its result keyed
     * only by {@code BlockState}. {@code collectParts} resolves through whatever model provider is
     * installed, including a connected-texture mod's, and the same state can legally bake different
     * quads at different world positions, depending on neighbor context. A state-keyed cache freezes
     * whichever resolution happened to be live for the first call and replays it everywhere that
     * state occurs for the rest of the session. No headless test can exercise
     * {@code Minecraft.getInstance()} to reproduce the resulting wrong-texture bug directly, so this
     * pins the shape instead: no static cache field of any kind on this class.
     */
    @Test
    void resolveNeverCachesAcrossCallsKeyedByBlockState() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/voxel/FaceColorResolver.java"));
        assertFalse(source.contains("ConcurrentHashMap"),
                "FaceColorResolver must not cache resolved quad/color data keyed by BlockState: "
                        + "the same state can bake different quads at different positions under a "
                        + "connected-texture mod, and a cache here freezes the first, possibly wrong, "
                        + "resolution for the rest of the session");
        for (java.lang.reflect.Field field : FaceColorResolver.class.getDeclaredFields()) {
            boolean isStaticMap = java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    && java.util.Map.class.isAssignableFrom(field.getType());
            assertFalse(isStaticMap,
                    "unexpected static Map field on FaceColorResolver: " + field.getName()
                            + ": a static Map, even a final reference, is a mutable cross-call cache");
        }
    }

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
