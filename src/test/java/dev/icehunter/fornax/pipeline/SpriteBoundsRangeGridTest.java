package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.atlas.SpriteHeightRanges;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHERE a sprite's height range is filed in the grid, as opposed to what its numbers are.
 *
 * <p><b>Why this exists, stated plainly, because the gap it fills was expensive.</b> The height
 * range published for each sprite was already covered by tests, and they passed: the percentiles are
 * counted over an 8-bit histogram, the trim is exact, the degenerate cases return a zero span. The
 * pack's own shader harness went further and reproduced the remap arithmetic against the real
 * resource-pack PNGs at 55 checks, all green. None of it could see the defect, because every one of
 * those tests was handed the range directly. The range was right. It was written to the wrong cells.
 *
 * <p>The producer measured its rectangle on the PBR SIDECAR atlas, which is deliberately half the
 * block atlas's size on each axis, and the consumer converted it using the BLOCK atlas's width. Every
 * range therefore landed at half its true position and a quarter of its true area, filling one corner
 * of the grid with other sprites' data and leaving roughly nine cells in ten empty. Nothing failed.
 * The shader read a zero span, took its documented fallback to the raw labPBR height, and rendered a
 * near-flat surface -- so parallax depth, the contrast slider and the height-map debug view all went
 * quietly inert together while the log still reported a healthy count of ranges.
 *
 * <p>So these tests assert placement and nothing else. They are the tests that fail on that bug, and
 * they fail on any future re-introduction of a unit mismatch across the same boundary, because the
 * property they pin -- "a fragment inside a sprite fetches THAT sprite's range" -- is the only thing
 * the shader actually depends on.
 */
class SpriteBoundsRangeGridTest {
    private static final int SIZE = SpriteBoundsTexture.size();

    private static ByteBuffer grid() {
        return ByteBuffer.allocate(SIZE * SIZE * 4 * Float.BYTES);
    }

    private static SpriteHeightRanges.Range range(float u0, float v0, float u1, float v1) {
        // 40..250 true, 60..230 trimmed: four distinct, non-zero values, so a cell that picked up
        // the wrong channel or the wrong sprite is distinguishable from one that picked up nothing.
        return new SpriteHeightRanges.Range(u0, v0, u1, v1, 40, 250, 60, 230);
    }

    /** The four channels of one cell, as the shader's texelFetch would see them. */
    private static float[] cell(ByteBuffer grid, int x, int y) {
        int offset = (y * SIZE + x) * 4 * Float.BYTES;
        return new float[] {grid.getFloat(offset), grid.getFloat(offset + 4),
                grid.getFloat(offset + 8), grid.getFloat(offset + 12)};
    }

    /** The cell a fragment at normalised (u, v) fetches -- the shader's `ivec2(v_TexCoord * 512)`. */
    private static int fetchCell(float uv) {
        return Math.min(SIZE - 1, Math.max(0, (int) (uv * SIZE)));
    }

    @Test
    void aFragmentInsideASpriteFetchesThatSpritesOwnRange() {
        // Deliberately in the FAR corner of the atlas. A halving bug is invisible near the origin and
        // total near (1,1), and a sprite at three-quarters across is where a real block atlas puts
        // most of its content.
        ByteBuffer grid = grid();
        SpriteHeightRanges.Range brick = range(0.750f, 0.6250f, 0.8125f, 0.6875f);
        SpriteBoundsTexture.writeRanges(grid, List.of(brick));

        float midU = 0.5f * (brick.u0() + brick.u1());
        float midV = 0.5f * (brick.v0() + brick.v1());
        float[] fetched = cell(grid, fetchCell(midU), fetchCell(midV));

        assertEquals(40 / 255.0f, fetched[0], 1e-6f, "true minimum");
        assertEquals(250 / 255.0f, fetched[1], 1e-6f, "true maximum");
        assertEquals(60 / 255.0f, fetched[2], 1e-6f, "trimmed minimum");
        assertEquals(230 / 255.0f, fetched[3], 1e-6f, "trimmed maximum");
    }

    @Test
    void nothingIsFiledAtHalfTheSpritesPosition() {
        // The bug's signature, pinned directly. Under the old conversion this sprite's range landed
        // at (0.375, 0.3125) -- plausible coordinates inside the grid, holding a real range, for a
        // fragment that will never look there. Asserting the CENTRE is right is not enough on its
        // own: a mapping that wrote BOTH places would satisfy the previous test.
        ByteBuffer grid = grid();
        SpriteHeightRanges.Range brick = range(0.750f, 0.6250f, 0.8125f, 0.6875f);
        SpriteBoundsTexture.writeRanges(grid, List.of(brick));

        float[] halved = cell(grid, fetchCell(0.375f), fetchCell(0.3125f));
        assertEquals(0.0f, halved[1], 0.0f,
                "a range at half the sprite's coordinates is a unit mismatch, not a layout");
    }

    @Test
    void everySpriteAcrossTheWholeAtlasResolvesItsOwnRange() {
        // An 8x8 tiling of the whole atlas, each tile carrying a range no other tile carries. Under
        // a halved mapping the 48 tiles beyond the first quadrant resolve to zero and the 16 inside
        // it resolve to a NEIGHBOUR's range -- so this separates "not found" from "found the wrong
        // one", which the world cannot: both render as a surface that looks subtly wrong.
        ByteBuffer grid = grid();
        List<SpriteHeightRanges.Range> ranges = new ArrayList<>();
        int tiles = 8;
        for (int ty = 0; ty < tiles; ty++) {
            for (int tx = 0; tx < tiles; tx++) {
                int id = ty * tiles + tx + 1;   // 1..64, distinct per tile
                ranges.add(new SpriteHeightRanges.Range(
                        tx / (float) tiles, ty / (float) tiles,
                        (tx + 1) / (float) tiles, (ty + 1) / (float) tiles,
                        id, 250, id, 230));
            }
        }
        SpriteBoundsTexture.writeRanges(grid, ranges);

        for (int ty = 0; ty < tiles; ty++) {
            for (int tx = 0; tx < tiles; tx++) {
                int id = ty * tiles + tx + 1;
                float midU = (tx + 0.5f) / tiles;
                float midV = (ty + 0.5f) / tiles;
                assertEquals(id / 255.0f, cell(grid, fetchCell(midU), fetchCell(midV))[0], 1e-6f,
                        "tile (" + tx + ", " + ty + ") must resolve its own range");
            }
        }
    }

    @Test
    void anAtlasFullOfSpritesLeavesNoCellWithoutARange() {
        // Coverage, which is what the log now reports and what a glance can check. The halved mapping
        // could never exceed 25% here however many sprites it was given, so this fails on the bug
        // without needing to know which sprite belongs where.
        ByteBuffer grid = grid();
        List<SpriteHeightRanges.Range> ranges = new ArrayList<>();
        int tiles = 16;
        for (int ty = 0; ty < tiles; ty++) {
            for (int tx = 0; tx < tiles; tx++) {
                ranges.add(range(tx / (float) tiles, ty / (float) tiles,
                        (tx + 1) / (float) tiles, (ty + 1) / (float) tiles));
            }
        }
        SpriteBoundsTexture.writeRanges(grid, ranges);

        int covered = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (cell(grid, x, y)[1] > 0.0f) {
                    covered++;
                }
            }
        }
        assertEquals(SIZE * SIZE, covered,
                "sprites tiling the whole atlas must claim the whole grid");
    }

    @Test
    void theRangeGridAndTheBoundsGridUseOneMapping() {
        // The two grids are read with a SINGLE texelFetch coordinate and are only meaningful
        // together: the fragment takes its rectangle from one and that rectangle's height range from
        // the other. Their cell mapping is now literally one pair of methods, and this is what says
        // so -- if a future change gives either grid its own conversion again, the sprite whose
        // bounds resolve while its range does not is the exact failure this file exists for.
        // Exercised as the PAIR they are -- a rectangle's opening and closing edge -- because that is
        // the only way they are ever called. Asking lastCell about a left edge is meaningless (a
        // rectangle closing at 0.0 has no cells, and it correctly says so with -1), and a test that
        // conflates the two ends up pinning an edge case neither grid can reach.
        float[][] rects = {
            {0.0f, 0.001f}, {0.0f, 1.0f}, {0.25f, 0.5f}, {0.7499f, 0.75f},
            {0.75f, 0.8125f}, {0.999f, 1.0f}, {0.5f, 0.50001f},
        };
        for (float[] rect : rects) {
            int first = SpriteBoundsTexture.firstCell(rect[0]);
            int last = SpriteBoundsTexture.lastCell(rect[1]);
            assertTrue(first >= 0 && first < SIZE, "first cell on the grid for " + rect[0]);
            assertTrue(last >= 0 && last < SIZE, "last cell on the grid for " + rect[1]);
            assertTrue(first <= last,
                    "a real rectangle claims at least one cell: " + rect[0] + ".." + rect[1]);
            // A fragment exactly on a sprite's left edge fetches the cell that edge opens.
            assertEquals(fetchCell(rect[0]), first, 1e-9,
                    "the first cell must be the one a fragment on that edge fetches, at " + rect[0]);
        }
    }

    @Test
    void aRangeMeasuredOnASIDECARRECTANGLEStillLandsOnTheSPRITE() {
        // The same boundary, now that the sidecar atlas is no longer a fixed half of the block
        // atlas. Its scale is chosen per pack -- 1/2 for maps that match the colour, up to 4x that
        // for a pack shipping 512px maps over 64px colour -- so "divide by two" is no longer even
        // the WRONG constant, it is a constant that does not exist any more. The producer measures
        // its histogram over a rectangle in sidecar texels and must publish the SPRITE's normalised
        // edges; this walks every scale the sizing can pick and checks the fragment still finds it.
        int blockAtlas = 4096;
        int albedo = 64;
        int spriteX = 3072;         // three-quarters across: far from the origin, where a
        int spriteY = 2560;         // scale error is visible instead of rounding away
        float u0 = spriteX / (float) blockAtlas;
        float v0 = spriteY / (float) blockAtlas;
        float u1 = (spriteX + albedo) / (float) blockAtlas;
        float v1 = (spriteY + albedo) / (float) blockAtlas;

        for (int log2Scale = -1; log2Scale <= 3; log2Scale++) {
            int sidecarAtlas = dev.icehunter.fornax.atlas.PbrSidecarAtlasScale
                    .atlasDimension(blockAtlas, log2Scale);
            int rectX = Math.round(u0 * sidecarAtlas);
            int rectWidth = dev.icehunter.fornax.atlas.PbrSidecarAtlasScale
                    .spriteExtent(albedo, log2Scale);

            // The rectangle the histogram is counted over, expressed in the ONE unit both atlases
            // agree on, must be the sprite's own slot -- whatever scale the sidecar atlas is at.
            assertEquals(u0, rectX / (float) sidecarAtlas, 1e-6f,
                    "sidecar rectangle drifted off the sprite at scale " + log2Scale);
            assertEquals(u1 - u0, rectWidth / (float) sidecarAtlas, 1e-6f,
                    "sidecar rectangle changed width at scale " + log2Scale);

            ByteBuffer grid = grid();
            SpriteBoundsTexture.writeRanges(grid, List.of(range(u0, v0, u1, v1)));
            float[] fetched = cell(grid, fetchCell(0.5f * (u0 + u1)), fetchCell(0.5f * (v0 + v1)));
            assertEquals(250 / 255.0f, fetched[1], 1e-6f,
                    "a fragment on the sprite found no range at scale " + log2Scale);
        }
    }

    @Test
    void aSpriteThinnerThanOneCellStillClaimsTheCellItSitsIn() {
        // Small sprites are the normal case at the far end of the atlas -- a 16px sprite on a 16384px
        // atlas is a thousandth of it, well under one 512th-of-the-atlas cell. Claiming nothing would
        // leave those blocks silently without parallax, which is the same invisible failure one step
        // smaller.
        ByteBuffer grid = grid();
        SpriteHeightRanges.Range tiny = range(0.50010f, 0.75010f, 0.50070f, 0.75070f);
        SpriteBoundsTexture.writeRanges(grid, List.of(tiny));

        assertTrue(cell(grid, fetchCell(0.50040f), fetchCell(0.75040f))[1] > 0.0f,
                "a sub-cell sprite must still file its range in the cell containing it");
    }
}
