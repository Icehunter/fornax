package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sprite-grid helper packs are told to use for {@code builtin.spriteBounds} and
 * {@code builtin.spriteHeightRange}.
 *
 * <p>The grid's resolution moves with the pack, so a shader that derives the cell index from a
 * constant reads the wrong cell and parallax switches off with no error. The helper has to take the
 * size from the texture, and has to keep this name, because {@code docs/PACK-FORMAT.md} tells pack
 * authors to call it.
 *
 * <p>Reads the shader source rather than running it: there is no GLSL interpreter here, and the
 * claim is about the text a pack imports.
 */
class SpriteGridHelperContractTest {

    private static final Path INCLUDE = Path.of(
            "src/main/resources/assets/fornax/shaders/include/block_atlas.glsl");

    private static String source() throws Exception {
        return Files.readString(INCLUDE);
    }

    @Test
    void engineIncludeShipsTheSpriteGridHelper() throws Exception {
        assertTrue(source().contains("vec4 fornax_spriteGridCell(sampler2D grid, vec2 uv)"),
                "docs/PACK-FORMAT.md tells pack authors to call fornax_spriteGridCell; it must exist"
                        + " with this exact signature in " + INCLUDE);
    }

    @Test
    void theHelperTakesItsResolutionFromTheTextureRatherThanAConstant() throws Exception {
        String body = source();
        int start = body.indexOf("vec4 fornax_spriteGridCell");
        String helper = body.substring(start, body.indexOf('}', start));

        assertTrue(helper.contains("textureSize(grid, 0)"),
                "the helper must read the grid size from the texture");
        // 512 and 1024 have both been the default; either appearing here means someone reintroduced
        // a baked-in resolution.
        assertFalse(helper.contains("512") || helper.contains("1024") || helper.contains("4096"),
                "the helper must not name any grid resolution");
    }

    @Test
    void theHelperClampsSoTheFarEdgeOfASpriteDoesNotFetchOutOfRange() throws Exception {
        String body = source();
        int start = body.indexOf("vec4 fornax_spriteGridCell");
        String helper = body.substring(start, body.indexOf('}', start));

        // uv reaches exactly 1.0 on a sprite's far edge, where uv * size is one past the last cell
        // and texelFetch is undefined rather than merely wrong.
        assertTrue(helper.contains("clamp("), "the cell index must be clamped into the grid");
        assertTrue(helper.contains("size - ivec2(1)"), "the upper clamp must be the last cell");
    }
}
