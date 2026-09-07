package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The biome tint multiply, on its own.
 *
 * <p>Grass, leaves, vines and water are grey in the atlas and the game tints them where they stand.
 * A harvested section that skips that step gives a reflection a grey bank, which reads as a bug
 * rather than as the noise it replaces, so the multiply is worth pinning.
 */
class SectionHarvestTintTest {
    private static int applyTint(int faceColor, int tint) {
        // Drive the real resolver with one layer-zero quad and a fixed atlas colour, so the
        // colour and coverage checks do not need Minecraft's live sprite manager.
        var quad = new BakedQuad(new Vector3f(), new Vector3f(0, 1, 0),
                new Vector3f(1, 1, 0), new Vector3f(1, 0, 0), 0, 0, 0, 0,
                Direction.NORTH, new BakedQuad.MaterialInfo(null, null, null, 0, true, 0));
        BlockStateModelPart part = new BlockStateModelPart() {
            public List<BakedQuad> getQuads(Direction face) {
                return face == Direction.NORTH ? List.of(quad) : List.of();
            }
            public boolean useAmbientOcclusion() { return true; }
            public Material.Baked particleMaterial() { return null; }
            public int materialFlags() { return 0; }
        };
        return FaceColorResolver.resolve(List.of(part), Direction.NORTH, tint, q -> faceColor);
    }

    /** White, which is what an untinted block answers, has to leave the face exactly as it was. */
    @Test
    void whiteIsIdentity() throws Exception {
        assertEquals(0xFF8ABF6E, applyTint(0xFF8ABF6E, -1));
        assertEquals(0xFF8ABF6E, applyTint(0xFF8ABF6E, 0x00FFFFFF));
    }

    /** Alpha is coverage, not colour, so the tint must not touch it. */
    @Test
    void alphaSurvives() throws Exception {
        assertEquals(0x80000000, applyTint(0x80FFFFFF, 0x00000000) & 0xFF000000);
    }

    /** A grey atlas texel under a plains grass tint comes out green, not grey. */
    @Test
    void greyGrassBecomesGreen() throws Exception {
        int tinted = applyTint(0xFF808080, 0x0091BD59);
        int r = (tinted >> 16) & 0xFF;
        int g = (tinted >> 8) & 0xFF;
        int b = tinted & 0xFF;
        assertEquals(0xFF000000, tinted & 0xFF000000);
        org.junit.jupiter.api.Assertions.assertTrue(g > r && g > b,
                "a grass tint must leave green the strongest channel, got "
                        + Integer.toHexString(tinted));
    }

    /**
     * The Sodium hook has to hand the harvest a world slice.
     *
     * <p>Without one the harvest still runs and still succeeds, and every grass block, leaf and vine
     * is stored as the grey the atlas holds. Nothing fails, nothing logs; the colours are simply
     * wrong wherever they are later read. The two-argument overload exists for callers that have no
     * world to ask, so a hand back to it here would be silent. Checked as source text because the
     * call needs a live client to run.
     */
    @Test
    void theSodiumHookSuppliesAWorldSlice() throws IOException {
        String hook = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/sodium/ChunkBuilderMeshingTaskMixin.java"));
        org.junit.jupiter.api.Assertions.assertTrue(hook.contains("getWorldSlice()"),
                "the harvest call must pass Sodium's world slice, or every tinted block is stored grey");
        org.junit.jupiter.api.Assertions.assertTrue(
                hook.contains("origin.minBlockX(), origin.minBlockY(), origin.minBlockZ()"),
                "the harvest call must pass the section corner, or the tint is read at the wrong place");
    }

    /**
     * The no-world overload really does mean no tint, rather than reaching for a level of its own.
     * A harvest off the render thread has no safe level to ask.
     */
    @Test
    void theTwoArgumentOverloadTakesNoTint() throws IOException {
        String src = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/voxel/SectionHarvester.java"));
        org.junit.jupiter.api.Assertions.assertTrue(
                src.contains("return harvest(blockData, materialScalars, null, 0, 0, 0);"),
                "the two-argument harvest must delegate with a null tint source");
    }
}
