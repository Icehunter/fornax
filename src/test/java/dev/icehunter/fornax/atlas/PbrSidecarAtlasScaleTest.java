package dev.icehunter.fornax.atlas;

import dev.icehunter.fornax.config.SidecarMapResolution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sidecar atlas sizing, and -- more importantly -- sidecar sprite PLACEMENT.
 *
 * <p>The arithmetic half of this is easy and was never the risk. The risk is that a rectangle ends
 * up somewhere the shader does not look, which is exactly how the height-range grid failed: the
 * numbers were right, they described the wrong place, nothing threw, and the surfaces merely looked
 * a bit flat. So the tests below rebuild the real layout -- block-atlas UV in, sidecar-atlas
 * rectangle out -- and assert that each sprite's rectangle occupies precisely its own normalised
 * slot, overlaps no neighbour, and stays inside the atlas.
 */
class PbrSidecarAtlasScaleTest {
    @Test
    void atlasCoordinatesScaleWithoutNegativeShiftWraparound() {
        assertEquals(256, PbrSidecarAtlasScale.atlasCoordinate(128, 1));
        assertEquals(128, PbrSidecarAtlasScale.atlasCoordinate(128, 0));
        assertEquals(64, PbrSidecarAtlasScale.atlasCoordinate(128, -1));
        assertEquals(0, PbrSidecarAtlasScale.atlasCoordinate(0, -3));
    }
    private static final int MAX_DIMENSION = 16384;
    private static final double NO_MIPS = 1.0;
    private static final double FULL_MIP_CHAIN = 4.0 / 3.0;

    /** A sprite as the listener sees it: its albedo size and its slot in the block atlas. */
    private record Sprite(int albedoWidth, int albedoHeight, int atlasX, int atlasY) {}

    private record Rect(int x, int y, int width, int height) {}

    /**
     * Reproduces the listener's placement exactly: origin from the normalised edge times THIS
     * atlas's size, extent from the albedo size times the scale.
     */
    private static Rect place(Sprite sprite, int blockWidth, int blockHeight, int log2Scale) {
        int atlasWidth = PbrSidecarAtlasScale.atlasDimension(blockWidth, log2Scale);
        int atlasHeight = PbrSidecarAtlasScale.atlasDimension(blockHeight, log2Scale);
        float u0 = sprite.atlasX() / (float) blockWidth;
        float v0 = sprite.atlasY() / (float) blockHeight;
        return new Rect(Math.round(u0 * atlasWidth), Math.round(v0 * atlasHeight),
                PbrSidecarAtlasScale.spriteExtent(sprite.albedoWidth(), log2Scale),
                PbrSidecarAtlasScale.spriteExtent(sprite.albedoHeight(), log2Scale));
    }

    /** A 4096x4096 block atlas tiled with 64px sprites, the user's 64x pack's real shape. */
    private static List<Sprite> uniformAtlas(int blockSize, int spriteSize) {
        List<Sprite> sprites = new ArrayList<>();
        for (int y = 0; y < blockSize; y += spriteSize) {
            for (int x = 0; x < blockSize; x += spriteSize) {
                sprites.add(new Sprite(spriteSize, spriteSize, x, y));
            }
        }
        return sprites;
    }

    // ---------------------------------------------------------------- sizing

    @Test
    void mapsThatMatchTheColourRetainTheirAuthoredResolutionWhenTheyFit() {
        int log2Scale = PbrSidecarAtlasScale.chooseLog2Scale(8192, 4096, 1, MAX_DIMENSION, FULL_MIP_CHAIN);

        assertEquals(0, log2Scale);
        assertEquals(8192, PbrSidecarAtlasScale.atlasDimension(8192, log2Scale));
        assertEquals(4096, PbrSidecarAtlasScale.atlasDimension(4096, log2Scale));
        for (int albedo : new int[] {16, 64, 128, 512, 1024}) {
            assertEquals(albedo, PbrSidecarAtlasScale.spriteExtent(albedo, log2Scale));
        }
    }

    @Test
    void a512MapOnA64AlbedoAsksForEightTimesTheAtlas() {
        // The user's 64x-colour + 512x-maps pack. Before this, every one of those maps was resampled
        // into a 32px rectangle -- a 16x loss with nothing in the log to say so.
        assertEquals(3, PbrSidecarAtlasScale.ceilLog2(512 / 64));
    }

    @Test
    void theBudgetCapsTheScaleRatherThanTheBuildFailing() {
        // 4096x4096 block atlas (the 64x pack once its sidecars are out of the stitch) with 512px
        // maps: the pack asks for 2^3, which would be a 16384x16384 sidecar atlas at 1074 MB before
        // mips. The cap has to bring that down, not refuse it.
        int asked = PbrSidecarAtlasScale.ceilLog2(8);
        int got = PbrSidecarAtlasScale.chooseLog2Scale(4096, 4096, 8, MAX_DIMENSION, FULL_MIP_CHAIN);

        assertEquals(3, asked);
        assertTrue(got < asked, "an over-budget request must be capped, got " + got);
        assertTrue(got >= 0, "capping must never fall below the historical sizing here, got " + got);

        int width = PbrSidecarAtlasScale.atlasDimension(4096, got);
        long residentBytes = Math.round((long) width * width * 4L * FULL_MIP_CHAIN);
        assertTrue(residentBytes <= PbrSidecarAtlasScale.MAX_ATLAS_BYTES,
                "capped atlas still over budget: " + residentBytes);
    }

    @Test
    void theDeviceDimensionLimitIsNeverExceeded() {
        // A GPU that reports a smaller ceiling than Apple's 16384 must get a smaller atlas, not a
        // texture-creation failure at reload time.
        for (int maxDimension : new int[] {2048, 4096, 8192, 16384}) {
            int log2Scale = PbrSidecarAtlasScale.chooseLog2Scale(8192, 4096, 8, maxDimension, NO_MIPS);
            assertTrue(PbrSidecarAtlasScale.atlasDimension(8192, log2Scale) <= maxDimension,
                    "width over the limit at maxDimension " + maxDimension);
            assertTrue(PbrSidecarAtlasScale.atlasDimension(4096, log2Scale) <= maxDimension,
                    "height over the limit at maxDimension " + maxDimension);
        }
    }

    @Test
    void theDefaultTierIsBitIdenticalToTheHistoricalSizing() {
        // The claim the tiering rests on: a user who never opens the setting gets what the byte
        // budget alone used to give on the packs this exists for. Checked across the shapes that
        // actually differ, not just one, because "identical" is a per-input property.
        int[][] cases = {{16384, 16384, 1}, {8192, 8192, 4}, {8192, 4096, 8}};
        for (int[] c : cases) {
            int historical = PbrSidecarAtlasScale.chooseLog2Scale(
                    c[0], c[1], c[2], MAX_DIMENSION, FULL_MIP_CHAIN);
            int viaHalf = tier(SidecarMapResolution.HALF, c[0], c[1], c[2], MAX_DIMENSION);
            assertEquals(historical, viaHalf,
                    "HALF must match the historical sizing for " + c[0] + "x" + c[1]
                            + " ratio " + c[2]);
        }
    }

    @Test
    void halfReproducesTheLoggedLoadAndFullRestoresTheAuthoredResolution() {
        // The measured live case, pinned so it cannot silently regress: a 512x pack with the paged
        // block atlas active reports "8192x8192 at scale 2^-1 (pack asked for 2^0)" in the game
        // log. maxSidecarRatio is 1 -- the maps match the colour -- so the pack asks for scale 0
        // and only the ceiling pushes it down.
        int half = tier(SidecarMapResolution.HALF, 16384, 16384, 1, MAX_DIMENSION);
        assertEquals(-1, half, "HALF must reproduce the half-resolution load seen in the log");
        assertEquals(8192, PbrSidecarAtlasScale.atlasDimension(16384, half));

        // FULL has to actually mean full. Its byte ceiling is deliberately absent for exactly this
        // reason: with the old 512 MB budget still binding, FULL would have silently landed on -1
        // and the tier would have been a lie.
        int full = tier(SidecarMapResolution.FULL, 16384, 16384, 1, MAX_DIMENSION);
        assertEquals(0, full, "FULL must give the pack the resolution it shipped");
        assertEquals(16384, PbrSidecarAtlasScale.atlasDimension(16384, full));

        assertEquals(-2, tier(SidecarMapResolution.QUARTER, 16384, 16384, 1, MAX_DIMENSION));
    }

    @Test
    void aTierIsACapAndNeverAnUpscale() {
        // A pack shipping maps BELOW its albedo resolution asks for a negative scale. FULL must not
        // drag it up to 0 -- the loop only ever steps down from what was surveyed, and the cap is a
        // ceiling on that, never a floor.
        int asked = PbrSidecarAtlasScale.chooseLog2Scale(
                4096, 4096, 1, MAX_DIMENSION, FULL_MIP_CHAIN);
        assertEquals(0, asked, "a pack asking for scale 0 must never be pushed above it");
        assertEquals(0, tier(SidecarMapResolution.FULL, 4096, 4096, 1, MAX_DIMENSION));
    }

    @Test
    void noTierCanExceedTheDeviceTextureLimit() {
        // FULL is unbounded by BUDGET, never unbounded. The device limit is checked independently,
        // so an explicit user choice can still never ask the hardware for more than it can hold.
        int clamped = tier(SidecarMapResolution.FULL, 16384, 16384, 1, 2048);
        assertTrue(PbrSidecarAtlasScale.atlasDimension(16384, clamped) <= 2048,
                "device limit must bind regardless of tier, got scale " + clamped);
    }

    @Test
    void aTierIsRelativeToWhatThePackAuthoredNotAnAbsoluteScale() {
        // The bug this pins, caught by an earlier version of the test above: with an ABSOLUTE cap,
        // HALF applied to a pack shipping maps at 4x its albedo resolution (surveyed ratio 4, i.e.
        // asked-for scale +2) landed on -1 -- an EIGHTH of what the author shipped, under a tier
        // labelled "half". Relative keeps the label honest whatever the pack is built like.
        //
        // A large maxDimension and a small atlas so neither limit binds and the tier is what is
        // actually being measured.
        int asked = PbrSidecarAtlasScale.chooseLog2Scale(1024, 1024, 4, MAX_DIMENSION, NO_MIPS);
        assertEquals(2, asked, "the pack asks for +2 when its maps are 4x the albedo");
        assertEquals(2, tier(SidecarMapResolution.FULL, 1024, 1024, 4, MAX_DIMENSION));
        assertEquals(1, tier(SidecarMapResolution.HALF, 1024, 1024, 4, MAX_DIMENSION),
                "HALF of 4x authored is 2x, not an eighth");
        assertEquals(0, tier(SidecarMapResolution.QUARTER, 1024, 1024, 4, MAX_DIMENSION));
    }

    private static int tier(SidecarMapResolution resolution, int width, int height, int ratio,
                            int maxDimension) {
        return PbrSidecarAtlasScale.chooseLog2Scale(width, height, ratio, maxDimension,
                FULL_MIP_CHAIN, resolution.log2ScaleOffset(), resolution.maxAtlasBytes());
    }

    @Test
    void anAtlasTooBigEvenAtTheHistoricalSizingDegradesBelowIt() {
        // The path that keeps a small card usable rather than losing the device: if half the block
        // atlas will not fit, the answer is a quarter of it, not a crash.
        int log2Scale = PbrSidecarAtlasScale.chooseLog2Scale(16384, 16384, 1, 2048, NO_MIPS);

        assertTrue(log2Scale < 0, "must degrade past scale 0, got " + log2Scale);
        assertTrue(PbrSidecarAtlasScale.atlasDimension(16384, log2Scale) <= 2048);
    }

    @Test
    void anAtlasThatStillDoesNotFitAtTheFloorFailsInsteadOfReturningAnOversizeScale() {
        // The loop's short-circuit (`scale > MIN_LOG2_SCALE`) is checked before `fits()`, so it can
        // exit at the floor without that call ever running there. 32768 against a 2048-texel device
        // limit reaches the floor (-3) still oversized: 32768 >> 3 == 4096 > 2048. Before this test's
        // fix, that unfitting scale was returned unqualified and neither caller's "invalid atlas
        // size" bail-out (atlasWidth <= 0) could catch it, since 4096 is a valid positive width -- it
        // is just larger than the device supports. This must fail loudly here, not at texture
        // creation deeper in the reload.
        assertThrows(IllegalStateException.class,
                () -> PbrSidecarAtlasScale.chooseLog2Scale(32768, 32768, 1, 2048, NO_MIPS));
    }

    @Test
    void ceilLog2RoundsUpSoASlotIsNeverTooSmall() {
        assertEquals(0, PbrSidecarAtlasScale.ceilLog2(1));
        assertEquals(1, PbrSidecarAtlasScale.ceilLog2(2));
        assertEquals(2, PbrSidecarAtlasScale.ceilLog2(3));  // rounds UP: 3x needs 4x of room
        assertEquals(2, PbrSidecarAtlasScale.ceilLog2(4));
        assertEquals(3, PbrSidecarAtlasScale.ceilLog2(5));
        assertEquals(3, PbrSidecarAtlasScale.ceilLog2(8));
        assertEquals(4, PbrSidecarAtlasScale.ceilLog2(9));
    }

    @Test
    void aSpriteNeverVanishesHoweverFarTheScaleDegrades() {
        // A 16px vanilla sprite at scale -3 would be 16 >> 4 == 1, and one step further would be 0 --
        // a zero-width rectangle that markOccupied and the height histogram would both loop zero
        // times over, silently.
        for (int log2Scale = 3; log2Scale >= -3; log2Scale--) {
            assertTrue(PbrSidecarAtlasScale.spriteExtent(16, log2Scale) >= 1,
                    "sprite collapsed to nothing at scale " + log2Scale);
            assertTrue(PbrSidecarAtlasScale.atlasDimension(64, log2Scale) >= 1,
                    "atlas collapsed to nothing at scale " + log2Scale);
        }
    }

    // ------------------------------------------------------------- placement

    @Test
    void everySpriteLandsOnItsOwnNormalisedSlotAtEveryScale() {
        // The property the shaders actually depend on: the sidecar atlas's layout is the block
        // atlas's layout, scaled. If a rectangle drifts off its normalised slot, every sample of
        // that sprite reads a neighbour -- and reads something plausible, not something obviously
        // broken.
        int blockSize = 4096;
        List<Sprite> sprites = uniformAtlas(blockSize, 64);

        for (int log2Scale = -1; log2Scale <= 3; log2Scale++) {
            int atlasSize = PbrSidecarAtlasScale.atlasDimension(blockSize, log2Scale);
            for (Sprite sprite : sprites) {
                Rect rect = place(sprite, blockSize, blockSize, log2Scale);

                assertEquals(sprite.atlasX() / (double) blockSize, rect.x() / (double) atlasSize, 1e-9,
                        "left edge moved at scale " + log2Scale);
                assertEquals(sprite.albedoWidth() / (double) blockSize, rect.width() / (double) atlasSize, 1e-9,
                        "width changed as a fraction of the atlas at scale " + log2Scale);
                assertTrue(rect.x() + rect.width() <= atlasSize && rect.y() + rect.height() <= atlasSize,
                        "rect " + rect + " leaves the atlas at scale " + log2Scale);
            }
        }
    }

    @Test
    void aMixedResolutionAtlasPlacesEverySpriteWithoutOverlap() {
        // A pack shipping 512px colour for some blocks and 64px for others -- the user's
        // "64x + 512 wood-brick" build -- laid out as vanilla's stitcher would: big sprites first,
        // small ones filling the remainder. Per-sprite sizing, not one global size, is what has to
        // hold here: a single scale applied to a uniform assumed sprite size would silently overlap.
        int blockSize = 4096;
        List<Sprite> sprites = new ArrayList<>();
        for (int y = 0; y < 1024; y += 512) {
            for (int x = 0; x < blockSize; x += 512) {
                sprites.add(new Sprite(512, 512, x, y));
            }
        }
        for (int y = 1024; y < blockSize; y += 64) {
            for (int x = 0; x < blockSize; x += 64) {
                sprites.add(new Sprite(64, 64, x, y));
            }
        }

        for (int log2Scale : new int[] {0, 1, 2}) {
            int atlasSize = PbrSidecarAtlasScale.atlasDimension(blockSize, log2Scale);
            boolean[] claimed = new boolean[atlasSize * atlasSize];
            for (Sprite sprite : sprites) {
                Rect rect = place(sprite, blockSize, blockSize, log2Scale);
                assertTrue(rect.width() > 0 && rect.height() > 0, "empty rect at scale " + log2Scale);
                for (int row = 0; row < rect.height(); row++) {
                    for (int col = 0; col < rect.width(); col++) {
                        int index = (rect.y() + row) * atlasSize + rect.x() + col;
                        assertFalse(claimed[index],
                                "sprites overlap at scale " + log2Scale + ", rect " + rect);
                        claimed[index] = true;
                    }
                }
            }
        }
    }

    @Test
    void aHighResSidecarGetsMoreTexelsAndTheAlbedoStillDecidesTheSlot() {
        // The whole point, stated as an assertion. A 64px albedo carrying a 512px map: before, its
        // rectangle was 32 texels and 15 of every 16 texels of the pack's map were thrown away;
        // now it is 128 at the scale the budget allows and 256 if it were not capped.
        assertEquals(64, PbrSidecarAtlasScale.spriteExtent(64, 0));
        assertEquals(128, PbrSidecarAtlasScale.spriteExtent(64, 1));
        assertEquals(512, PbrSidecarAtlasScale.spriteExtent(64, 3));
        // ...and a neighbouring 512px albedo in the same atlas scales by the same factor, so the
        // two keep their relative sizes and the layout stays the block atlas's.
        assertEquals(1024, PbrSidecarAtlasScale.spriteExtent(512, 1));
    }

    // ------------------------------------------------------ animated sidecars

    @Test
    void aVerticalFrameStripIsRecognisedAtAnyResolution() {
        // fire_n.png is 32x256 beside a 32x32 sprite at 64x, and 128x1024 at 512x. The old test was
        // `sourceWidth == slotWidth`, which is false the moment the map is not the albedo's
        // resolution -- so a 512px animated map would have had its whole strip squashed into one
        // frame's slot. Comparing ASPECT instead is resolution-independent.
        assertEquals(32, LabPbrSidecarBlitter.firstFrameHeight(32, 256, 16, 16));
        assertEquals(128, LabPbrSidecarBlitter.firstFrameHeight(128, 1024, 16, 16));
        assertEquals(128, LabPbrSidecarBlitter.firstFrameHeight(128, 1024, 64, 64));
    }

    @Test
    void aStillSidecarIsNeverMistakenForAStrip() {
        // A non-square sprite whose map is simply the same shape: 64x256 map, 64x256 slot aspect.
        // Treating that as a 4-frame strip would blit a quarter of the texture over the whole slot.
        assertEquals(256, LabPbrSidecarBlitter.firstFrameHeight(64, 256, 32, 128));
        assertEquals(512, LabPbrSidecarBlitter.firstFrameHeight(512, 512, 64, 64));
        assertEquals(64, LabPbrSidecarBlitter.firstFrameHeight(64, 64, 32, 32));
    }
}
