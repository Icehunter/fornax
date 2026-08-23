package dev.icehunter.fornax.atlas;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockAtlasGhostLayoutTest {
    private static final int CANVAS = 16384;

    @Test
    void stripGeometryQuartersTheCanvas() {
        assertEquals(4096, BlockAtlasGhostLayout.stripHeight(CANVAS));
        assertEquals(12288, BlockAtlasGhostLayout.pageZeroStitchHeight(CANVAS));
    }

    @Test
    void ghostCellsTileTheStripLeftToRight() {
        assertEquals(0, BlockAtlasGhostLayout.ghostX(1, CANVAS, 0));
        assertEquals(4096, BlockAtlasGhostLayout.ghostX(2, CANVAS, 0));
        assertEquals(8192, BlockAtlasGhostLayout.ghostX(3, CANVAS, 0));
        assertEquals(12288, BlockAtlasGhostLayout.ghostY(CANVAS, 0));
        // Page texels shift right by the ghost divisor into the cell.
        assertEquals(4096 + 25, BlockAtlasGhostLayout.ghostX(2, CANVAS, 100));
        assertEquals(12288 + 1023, BlockAtlasGhostLayout.ghostY(CANVAS, 4092));
        // The fourth cell belongs to animated ghosts; its inputs are already ghost-scale.
        assertEquals(12288, BlockAtlasGhostLayout.animatedCellX(CANVAS, 0));
        assertEquals(12288 + 100, BlockAtlasGhostLayout.animatedCellX(CANVAS, 100));
        assertEquals(12288 + 7, BlockAtlasGhostLayout.animatedCellY(CANVAS, 7));
    }

    @Test
    void pageIndexOutsideTheStripIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BlockAtlasGhostLayout.ghostX(0, CANVAS, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BlockAtlasGhostLayout.ghostX(BlockAtlasGhostLayout.MAX_OVERFLOW_PAGES + 1, CANVAS, 0));
    }

    /**
     * The design's load-bearing equation (see {@link BlockAtlasGhostLayout}'s class doc): the
     * shader-side recovery {@code pageUV = (ghostUV - (0.25 * (page - 1), 0.75)) * 4} must invert
     * the Java-side ghost placement exactly for 4-aligned page coordinates, and within one ghost
     * texel otherwise.
     */
    @Test
    void shaderAffineRecoversPageUvFromGhostUv() {
        int padding = 8;
        for (int page = 1; page <= BlockAtlasGhostLayout.MAX_OVERFLOW_PAGES; page++) {
            // 4-aligned placement: exact recovery.
            int pageX = 2048;
            int pageY = 512;
            float ghostU0 = BlockAtlasGhostLayout.ghostU0(
                    BlockAtlasGhostLayout.ghostX(page, CANVAS, pageX), padding, CANVAS);
            float recoveredU = (ghostU0 - 0.25f * (page - 1)) * 4.0f;
            assertEquals((pageX + padding) / (float) CANVAS, recoveredU, 1.0e-6f,
                    "page " + page + " U recovery must be exact for 4-aligned placement");

            float ghostV0 = BlockAtlasGhostLayout.ghostV0(
                    BlockAtlasGhostLayout.ghostY(CANVAS, pageY), padding, CANVAS);
            float recoveredV = (ghostV0 - 0.75f) * 4.0f;
            assertEquals((pageY + padding) / (float) CANVAS, recoveredV, 1.0e-6f,
                    "page " + page + " V recovery must be exact for 4-aligned placement");
        }

        // Unaligned placement: the integer blit origin rounds down, so recovery lands within one
        // ghost texel (4 page texels) -- the accepted softness, no worse.
        int unalignedX = 2049;
        float ghostU0 = BlockAtlasGhostLayout.ghostU0(
                BlockAtlasGhostLayout.ghostX(1, CANVAS, unalignedX), padding, CANVAS);
        float recoveredTexels = ghostU0 * 4.0f * CANVAS - padding;
        assertTrue(Math.abs(recoveredTexels - unalignedX) <= 4.0f,
                "unaligned recovery must stay within one ghost texel, was off by "
                        + Math.abs(recoveredTexels - unalignedX));
    }

    @Test
    void ghostUvRectIsTheExactQuarterOfThePageRect() {
        int padding = 8;
        int spriteWidth = 512;
        int ghostX = BlockAtlasGhostLayout.ghostX(1, CANVAS, 0);
        float u0 = BlockAtlasGhostLayout.ghostU0(ghostX, padding, CANVAS);
        float u1 = BlockAtlasGhostLayout.ghostU1(ghostX, padding, spriteWidth, CANVAS);
        // A page-0 sprite of the same geometry spans spriteWidth / CANVAS; the ghost spans a
        // quarter of that, exactly.
        assertEquals(spriteWidth / 4.0f / CANVAS, u1 - u0, 1.0e-7f);
    }

    /**
     * Byte-layout check against the decompiled vanilla writer (std140: mat4 ProjectionMatrix at 0,
     * mat4 SpriteMatrix at 64, UPadding at 128, VPadding at 132, MipMapLevel at 136): the ghost
     * writer must keep that exact layout with the ghost origin in the translate lanes, the
     * quarter-scaled padded box on the scale diagonal, and the mip pointer two levels deeper,
     * clamped to the staging chain's top.
     */
    @Test
    void ghostUboMatchesVanillaLayoutWithGhostDeltas() {
        int uboSize = 256;
        int maxMip = 4;
        int ghostX = 4096 + 25;
        int ghostY = 12288 + 100;
        int spriteW = 512;
        int spriteH = 256;
        int padding = 8;
        ByteBuffer buffer = ByteBuffer.allocateDirect(uboSize * (maxMip + 1)).order(ByteOrder.nativeOrder());

        BlockAtlasGhostLayout.writeGhostSpriteUbo(buffer, 0, maxMip, CANVAS, CANVAS, uboSize,
                ghostX, ghostY, spriteW, spriteH, padding);

        for (int mip = 0; mip <= maxMip; mip++) {
            int base = mip * uboSize;
            // ortho2D(0, w, 0, h): m00 = 2 / w (column-major float offset 0).
            assertEquals(2.0f / (CANVAS >> mip), buffer.getFloat(base), 1.0e-9f, "mip " + mip + " ortho m00");
            // SpriteMatrix translate lands in column 3 rows 0/1 (offsets 112/116 within the mat4
            // at 64), scale on the diagonal (offsets 64 and 84).
            assertEquals((float) (ghostX >> mip), buffer.getFloat(base + 64 + 48), "mip " + mip + " translate x");
            assertEquals((float) (ghostY >> mip), buffer.getFloat(base + 64 + 52), "mip " + mip + " translate y");
            assertEquals((float) Math.max(1, (spriteW + 2 * padding) >> (mip + 2)),
                    buffer.getFloat(base + 64), "mip " + mip + " scale x");
            assertEquals((float) Math.max(1, (spriteH + 2 * padding) >> (mip + 2)),
                    buffer.getFloat(base + 64 + 20), "mip " + mip + " scale y");
            assertEquals((float) padding / spriteW, buffer.getFloat(base + 128), "mip " + mip + " UPadding");
            assertEquals((float) padding / spriteH, buffer.getFloat(base + 132), "mip " + mip + " VPadding");
            assertEquals(Math.min(mip + 2, maxMip), buffer.getInt(base + 136), "mip " + mip + " MipMapLevel");
        }
    }
}
