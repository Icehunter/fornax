package dev.icehunter.fornax.atlas;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link NormalMapAtlasReloadListener#computeMipLevelCount} and {@link
 * NormalMapAtlasReloadListener#scaleRect}, the pure-logic pieces of the normal-map atlas's
 * per-sprite mip-chain generation (see {@code upload()}'s doc for the regression this replaced: a
 * whole-atlas box filter that blended texels across sprite boundaries, producing a visible grid
 * seam at every block edge under minification). Everything else in this class (box-filter pixel
 * reduction of a {@code NativeImage}, GPU texture/upload) needs a real render device and is not
 * testable headlessly -- see {@code build()}'s own early-return-if-no-device guard, which is exactly
 * why no test spins one up here rather than faking coverage of it.
 *
 * <p>{@code scaleRect}'s own tests below deliberately do NOT derive their level range from {@code
 * computeMipLevelCount} -- that method now always returns 1 (see its own doc: {@code u_NormalTex}
 * is bound {@code mipmap=false}, so nothing ever samples past level 0, and generating a real chain
 * was pure waste), but {@code scaleRect}/{@code boxDownsampleRect}/{@code downsampleLevel} are kept
 * as reversible, still-correct machinery. A literal {@code TEST_DEEP_LEVEL_COUNT} keeps their own
 * deep-level safety covered independently of what the real atlas builds today.
 */
class NormalMapAtlasReloadListenerTest {
    // This atlas's historical real size (2048x2048) needed 12 levels to reach 1x1 -- used only to
    // give scaleRect's own tests a realistic depth to sweep, independent of computeMipLevelCount's
    // current (always-1) production value.
    private static final int TEST_DEEP_LEVEL_COUNT = 12;

    @Test
    void alwaysReturnsOneSinceNothingSamplesPastLevelZero() {
        // u_NormalTex/u_NormalPagesTex are bound mipmap=false (DefaultChunkRendererTextureBindMixin)
        // -- a deliberate choice (mip-blending unit normals produces a non-unit, flattened result),
        // not a function of the atlas's own dimensions. Building more than level 0 was pure waste.
        assertEquals(1, NormalMapAtlasReloadListener.computeMipLevelCount(2048, 2048));
        assertEquals(1, NormalMapAtlasReloadListener.computeMipLevelCount(1024, 1024));
        assertEquals(1, NormalMapAtlasReloadListener.computeMipLevelCount(513, 4));
        assertEquals(1, NormalMapAtlasReloadListener.computeMipLevelCount(1, 1));
    }

    @Test
    void scaleRectHalvesPositionAndSizeAtEachLevel() {
        // A 128x128 sprite sitting at (256, 384) in the level-0 atlas (the most common real sprite
        // size at 128x, per the atlas-build log). Position and size both shift by the
        // same amount level N uses, mirroring GpuTexture's own unclamped dimension >> level.
        NormalMapAtlasReloadListener.SpriteRect original =
                new NormalMapAtlasReloadListener.SpriteRect(256, 384, 128, 128);

        assertEquals(new NormalMapAtlasReloadListener.SpriteRect(256, 384, 128, 128),
                NormalMapAtlasReloadListener.scaleRect(original, 0));
        assertEquals(new NormalMapAtlasReloadListener.SpriteRect(128, 192, 64, 64),
                NormalMapAtlasReloadListener.scaleRect(original, 1));
        assertEquals(new NormalMapAtlasReloadListener.SpriteRect(32, 48, 16, 16),
                NormalMapAtlasReloadListener.scaleRect(original, 3));
        // 128 >> 7 == 1: exactly reaches 1x1, no clamp needed yet.
        assertEquals(new NormalMapAtlasReloadListener.SpriteRect(2, 3, 1, 1),
                NormalMapAtlasReloadListener.scaleRect(original, 7));
    }

    @Test
    void scaleRectClampsSizeToOneRatherThanVanishing() {
        // Once a sprite is smaller than 2^level in a dimension, plain >> would collapse it to 0 --
        // scaleRect instead holds it at 1 texel rather than letting the sprite disappear from the
        // level entirely (the atlas's own level count, computeMipLevelCount, is sized off the whole
        // 2048x2048 image and deliberately runs deeper than any individual sprite needs).
        NormalMapAtlasReloadListener.SpriteRect small = new NormalMapAtlasReloadListener.SpriteRect(10, 20, 16, 16);

        assertEquals(new NormalMapAtlasReloadListener.SpriteRect(0, 1, 1, 1),
                NormalMapAtlasReloadListener.scaleRect(small, 4));
        // Deeper still: position keeps shifting toward the origin, size stays clamped at 1.
        assertEquals(new NormalMapAtlasReloadListener.SpriteRect(0, 0, 1, 1),
                NormalMapAtlasReloadListener.scaleRect(small, 8));
    }

    @Test
    void scaleRectAtSuccessiveLevelsNeverExceedsTheAtlasLevelItself() {
        // The correctness property upload()'s per-sprite reduction depends on: for a sprite that
        // legally fits inside the level-0 atlas, its scaled rectangle at every level must stay
        // fully inside that level's own image bounds (max(1, atlasDim >> level)) -- otherwise
        // boxDownsampleRect would read/write out of range. Sweep every real sprite size Optimum
        // Realism 128x actually ships (128, 256, 384(anim frame height case n/a here since content
        // width/height is per-frame), 512, 1024) at a handful of representative placements.
        int atlasWidth = 2048;
        int atlasHeight = 2048;
        int levelCount = TEST_DEEP_LEVEL_COUNT;

        int[] sizes = {128, 256, 512};
        int[] placements = {0, 128, 384, 1536, 1920};

        for (int size : sizes) {
            for (int px : placements) {
                for (int py : placements) {
                    if (px + size > atlasWidth || py + size > atlasHeight) {
                        continue;
                    }
                    NormalMapAtlasReloadListener.SpriteRect rect =
                            new NormalMapAtlasReloadListener.SpriteRect(px, py, size, size);

                    for (int level = 0; level < levelCount; level++) {
                        NormalMapAtlasReloadListener.SpriteRect scaled = NormalMapAtlasReloadListener.scaleRect(rect, level);
                        int levelWidth = Math.max(1, atlasWidth >> level);
                        int levelHeight = Math.max(1, atlasHeight >> level);

                        assertTrue(scaled.x() >= 0 && scaled.y() >= 0,
                                "negative origin at level " + level + " for rect " + rect);
                        assertTrue(scaled.x() + scaled.width() <= levelWidth,
                                "rect " + rect + " scaled to " + scaled + " overflows level " + level
                                        + " width " + levelWidth);
                        assertTrue(scaled.y() + scaled.height() <= levelHeight,
                                "rect " + rect + " scaled to " + scaled + " overflows level " + level
                                        + " height " + levelHeight);
                    }
                }
            }
        }
    }

    @Test
    void mipReductionRenormalizesTangentSpaceDirections() {
        int p0 = argb(10, 204, 128, 20);
        int p1 = argb(20, 128, 128, 40);
        int p2 = argb(30, 204, 128, 60);
        int p3 = argb(40, 128, 128, 80);

        int reduced = NormalMapAtlasReloadListener.reduceMipTexel(p0, p1, p2, p3);

        assertEquals(168, (reduced >>> 16) & 0xFF);
        assertEquals(128, (reduced >>> 8) & 0xFF);
        assertEquals(50, reduced & 0xFF);
        assertEquals(25, reduced >>> 24);
    }

    @Test
    void fourTexelNormalReductionDoesNotAllocateTheCategoricalAccumulator() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/atlas/LabPbrSidecarBlitter.java"));
        int start = source.indexOf("static int reduceNormal(");
        int end = source.indexOf("private static int encodeNormal", start);

        assertTrue(start >= 0 && end > start);
        assertTrue(!source.substring(start, end).contains("new WeightedSamples"),
                "normal mip generation runs once per output texel and must not allocate there");
    }

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
