package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.buffers.Std140Builder;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * The pure geometry of the paged block atlas's GHOST STRIP: when a pack overflows one page, page 0
 * is stitched at three-quarter height and the bottom quarter of the canvas is reserved for ghosts --
 * each overflow page reproduced at one-quarter scale in its own square cell. Every consumer that can
 * only ever sample page 0 (model-baked UVs, item icons, particles, the crumbling overlay, any
 * shader pack that never adopts page-aware sampling) reads a spilled sprite's GHOST rect and gets
 * correct-if-soft content; the full-resolution truth lives on the overflow layers, reachable from
 * the ghost UV by the affine map below.
 *
 * <p><b>The whole design hangs on one exactness property:</b> for overflow page {@code N} (1-based)
 * on an {@code S}x{@code S} canvas, the ghost cell origin is {@code ((N-1) * S/4, S - S/4)} and a
 * page texel {@code t} lands at {@code cellOrigin + (t >> 2)} -- so in UV space
 * {@code pageUV = (ghostUV - vec2(0.25 * (N-1), 0.75)) * 4.0} recovers the overflow layer
 * coordinate with no lookup table, no uniforms, nothing but the page index. The shader-side sampling
 * include (phase 1b) and {@link #ghostX}/{@link #ghostY} below are two spellings of that one
 * equation; {@code BlockAtlasGhostLayoutTest} pins them against each other.
 *
 * <p>Divisions here are exact by construction: {@code S} is a power of two (a device max texture
 * dimension), so {@code S/4} is integral, and page texel coordinates shift right by
 * {@link #GHOST_SHIFT}. The one deliberate inexactness: a page placement whose x/y is not a
 * multiple of 4 lands its ghost within one ghost texel of the ideal spot (UV getters use float
 * division and stay exact; the integer blit rounds). Ghosts are quarter-scale fallback imagery --
 * sub-texel softness there is the accepted cost, stated in the phase-3 plan.
 *
 * <p>Pure and device-free like the rest of this package's math ({@code BlockAtlasPaging} et al.):
 * no GPU handle, no statics, nothing Minecraft beyond primitives, fully unit-testable.
 */
public final class BlockAtlasGhostLayout {
    /** Ghosts are quarter scale: {@code >> 2} on every dimension, 1/16 the area per page. */
    public static final int GHOST_SHIFT = 2;

    /** Mip offset equivalent of {@link #GHOST_SHIFT}: ghost mip {@code m} draws from sprite mip
     * {@code m + 2}, so vanilla's own per-sprite blit machinery downsamples for free. */
    public static final int GHOST_MIP_OFFSET = GHOST_SHIFT;

    /** The strip holds four quarter-scale cells across a full-width canvas. The first three carry
     * one overflow page each; the fourth is the {@linkplain #animatedCellX ANIMATED CELL}, so at
     * most three overflow pages (four total) are addressable. {@code
     * FornaxChunkVertex.MAX_ATLAS_PAGES} (32) stays the vertex-lane ceiling; this is the tighter,
     * layout-imposed one. */
    public static final int MAX_OVERFLOW_PAGES = 3;

    /** 0-based index of the strip cell reserved for ANIMATED spilled sprites. Vanilla's stitch
     * order is name-deterministic (height desc, width desc, then name -- decompile-verified), so
     * animated sprites cannot be pinned to page 0; instead their ghosts are packed into this
     * dedicated cell by their own quarter-scale stitch, and the sampling include treats the cell
     * as NEVER-REMAP: an animated spilled sprite always renders its ghost, which vanilla's own
     * per-frame blit keeps animating. Its full-resolution overflow placement is never sampled and
     * never composited. */
    public static final int ANIMATED_CELL = MAX_OVERFLOW_PAGES;

    private BlockAtlasGhostLayout() {
    }

    /** Height reserved at the bottom of page 0 for the ghost strip: one quarter of the canvas. */
    public static int stripHeight(int canvasSize) {
        return canvasSize >> GHOST_SHIFT;
    }

    /** The canvas height page 0's own stitch is constrained to when the strip is reserved. */
    public static int pageZeroStitchHeight(int canvasSize) {
        return canvasSize - stripHeight(canvasSize);
    }

    /** Ghost-strip X of a texel at page-local {@code pageX} on overflow page {@code page} (1-based). */
    public static int ghostX(int page, int canvasSize, int pageX) {
        checkPage(page);
        return (page - 1) * (canvasSize >> GHOST_SHIFT) + (pageX >> GHOST_SHIFT);
    }

    /** Ghost-strip Y of a texel at page-local {@code pageY} on any overflow page. */
    public static int ghostY(int canvasSize, int pageY) {
        return pageZeroStitchHeight(canvasSize) + (pageY >> GHOST_SHIFT);
    }

    /** Ghost-strip X inside the {@linkplain #ANIMATED_CELL animated cell} for a cell-local
     * coordinate produced by the animated ghosts' own quarter-scale stitch (already ghost-sized --
     * no {@code >> 2} here, unlike {@link #ghostX}'s page-texel input). */
    public static int animatedCellX(int canvasSize, int cellLocalX) {
        return ANIMATED_CELL * (canvasSize >> GHOST_SHIFT) + cellLocalX;
    }

    /** Ghost-strip Y for a cell-local Y from the animated ghosts' own stitch (already ghost-sized). */
    public static int animatedCellY(int canvasSize, int cellLocalY) {
        return pageZeroStitchHeight(canvasSize) + cellLocalY;
    }

    /**
     * Ghost UV rect endpoints, float-exact counterparts of what {@code TextureAtlasSprite}'s own
     * constructor computes for a page-0 sprite ({@code (x + padding) / atlasWidth} etc.), scaled by
     * the ghost divisor: content start {@code (ghostOrigin + padding/4) / S}, content end
     * {@code (ghostOrigin + (padding + extent)/4) / S}. Padding and extent divide as floats on
     * purpose -- the reported UV rect must be the mathematically exact quarter of the page rect,
     * not the integer-rounded blit box, so page-UV recovery in the sampling include stays affine.
     */
    public static float ghostU0(int ghostX, int padding, int canvasSize) {
        return (ghostX + padding / 4.0f) / canvasSize;
    }

    public static float ghostU1(int ghostX, int padding, int spriteWidth, int canvasSize) {
        return (ghostX + (padding + spriteWidth) / 4.0f) / canvasSize;
    }

    public static float ghostV0(int ghostY, int padding, int canvasSize) {
        return (ghostY + padding / 4.0f) / canvasSize;
    }

    public static float ghostV1(int ghostY, int padding, int spriteHeight, int canvasSize) {
        return (ghostY + (padding + spriteHeight) / 4.0f) / canvasSize;
    }

    /**
     * The ghost counterpart of {@code TextureAtlasSprite.uploadSpriteUbo}, byte-layout-identical to
     * the vanilla writer (decompile-verified loop: per mip {@code m}, an std140 block of
     * {@code ProjectionMatrix} = {@code ortho2D(0, atlasW >> m, 0, atlasH >> m)},
     * {@code SpriteMatrix} = {@code translate(x >> m, y >> m) * scale(paddedW >> m, paddedH >> m)},
     * {@code UPadding} = {@code padding / spriteW}, {@code VPadding} = {@code padding / spriteH},
     * {@code MipMapLevel} = {@code m}) with exactly three deltas: the translate reads the GHOST
     * origin, the scale shrinks the padded box by {@link #GHOST_SHIFT} extra, and the sampled
     * staging-texture level is {@code m + }{@link #GHOST_MIP_OFFSET} (clamped to the staging
     * chain's own top) -- vanilla's blit shader does {@code textureLod(Sprite, uv, MipMapLevel)}
     * from a per-sprite staging texture that carries the full mip chain, so pointing it two levels
     * deeper IS the downsample. The padding ratio floats are unchanged: they are sprite-space
     * ratios, and the ghost preserves aspect exactly.
     *
     * <p>Scale clamps to a 1-texel floor per axis: a sprite whose padded box shifts to zero at deep
     * ghost mips must still emit a degenerate-but-valid quad rather than a zero-area one.
     */
    public static void writeGhostSpriteUbo(ByteBuffer buffer, int startOffset, int maxMipLevel,
                                           int atlasWidth, int atlasHeight, int spriteUboSize,
                                           int ghostX, int ghostY, int spriteWidth, int spriteHeight,
                                           int padding) {
        int paddedWidth = spriteWidth + 2 * padding;
        int paddedHeight = spriteHeight + 2 * padding;
        for (int mip = 0; mip <= maxMipLevel; mip++) {
            Std140Builder.intoBuffer(MemoryUtil.memSlice(buffer, startOffset + mip * spriteUboSize, spriteUboSize))
                    .putMat4f(new Matrix4f().ortho2D(0.0f, atlasWidth >> mip, 0.0f, atlasHeight >> mip))
                    .putMat4f(new Matrix4f()
                            .translate(ghostX >> mip, ghostY >> mip, 0.0f)
                            .scale(Math.max(1, paddedWidth >> (mip + GHOST_SHIFT)),
                                    Math.max(1, paddedHeight >> (mip + GHOST_SHIFT)), 1.0f))
                    .putFloat((float) padding / spriteWidth)
                    .putFloat((float) padding / spriteHeight)
                    .putInt(Math.min(mip + GHOST_MIP_OFFSET, maxMipLevel));
        }
    }

    private static void checkPage(int page) {
        if (page < 1 || page > MAX_OVERFLOW_PAGES) {
            throw new IllegalArgumentException(
                    "overflow page must be in 1.." + MAX_OVERFLOW_PAGES + ", got " + page);
        }
    }
}
