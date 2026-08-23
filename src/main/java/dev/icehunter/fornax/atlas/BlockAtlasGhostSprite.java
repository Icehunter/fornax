package dev.icehunter.fornax.atlas;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;

/**
 * The {@link TextureAtlasSprite} a SPILLED sprite becomes on the paged block atlas: it lives on
 * overflow page {@link #overflowPage()} at page-local ({@link #pageX()}, {@link #pageY()}), but
 * everything vanilla-facing about it -- reported UVs, {@code getX()}/{@code getY()}, and the blit
 * this sprite's own {@link #uploadSpriteUbo} override drives -- describes its quarter-scale GHOST
 * in page 0's reserved strip (see {@link BlockAtlasGhostLayout}). Model baking, item icons,
 * particles, and every other page-0-only consumer therefore work unmodified on spilled sprites;
 * page-aware terrain sampling (phase 1b+) recovers the full-resolution overflow coordinate from
 * the ghost UV plus {@link #overflowPage()}.
 *
 * <p><b>Why a subclass carries the whole mechanism.</b> {@code TextureAtlasSprite}'s constructor is
 * protected and its UV fields private-final, but every consumer reads them through non-final
 * getters, and both of vanilla's atlas-blit UBO writers ({@code TextureAtlas.uploadInitialContents}
 * and the animation path via {@code createAnimationState}'s buffer slices) call the virtual
 * {@code uploadSpriteUbo} -- javap-verified. Overriding the six UV getters plus that one writer is
 * therefore sufficient: spilled ANIMATED sprites animate in their ghost through vanilla's own
 * per-frame draw with no further interception, because their draw UBOs are written by the same
 * override.
 *
 * <p>The super constructor is given the GHOST origin as its {@code x}/{@code y} (so
 * {@code getX()}/{@code getY()} and anything else position-shaped agrees with the ghost rect); the
 * UVs it derives from them are wrong (it spans the full sprite extent from that origin) and are
 * shadowed by the overrides below, which delegate to {@link BlockAtlasGhostLayout}'s float-exact
 * quarter-scale math. {@code getU}/{@code getV} are overridden too: vanilla's are plain lerps over
 * the private fields, not over the getters.
 */
public final class BlockAtlasGhostSprite extends TextureAtlasSprite {
    private final int overflowPage;
    private final int pageX;
    private final int pageY;
    private final int ghostPadding;
    private final int canvasSize;

    private BlockAtlasGhostSprite(Identifier atlasLocation, SpriteContents contents, int canvasSize,
                                  int ghostX, int ghostY, int overflowPage, int pageX, int pageY,
                                  int padding) {
        super(atlasLocation, contents, canvasSize, canvasSize, ghostX, ghostY, padding);
        this.overflowPage = overflowPage;
        this.pageX = pageX;
        this.pageY = pageY;
        this.ghostPadding = padding;
        this.canvasSize = canvasSize;
    }

    /**
     * A spilled STATIC sprite: ghost in the page's strip cell, full-resolution truth composited
     * onto overflow layer {@code page - 1}.
     *
     * @param page    1-based overflow page the sprite's full-resolution content belongs to
     * @param pageX   padded-region origin on that page, exactly as the page's {@code Stitcher}
     *                placed it (the same coordinate a page-0 sprite's {@code x} would be)
     * @param padding the stitcher's uniform padding, unmodified -- ghost geometry divides it at
     *                use sites, never here, so page-local coordinates stay round-trip exact
     */
    public static BlockAtlasGhostSprite spilled(Identifier atlasLocation, SpriteContents contents,
                                                int canvasSize, int page, int pageX, int pageY,
                                                int padding) {
        return new BlockAtlasGhostSprite(atlasLocation, contents, canvasSize,
                BlockAtlasGhostLayout.ghostX(page, canvasSize, pageX),
                BlockAtlasGhostLayout.ghostY(canvasSize, pageY),
                page, pageX, pageY, padding);
    }

    /**
     * A spilled ANIMATED sprite: ghost in the {@linkplain BlockAtlasGhostLayout#ANIMATED_CELL
     * animated cell} (which the sampling include never remaps, so the ghost's vanilla-driven
     * animation is what terrain shows), no overflow-layer copy at all --
     * {@link #overflowPage()} is 0 and {@link #hasOverflowCopy()} is false.
     *
     * @param cellLocalX placement inside the animated cell from the animated ghosts' own
     *                   quarter-scale stitch (already ghost-sized)
     */
    public static BlockAtlasGhostSprite animated(Identifier atlasLocation, SpriteContents contents,
                                                 int canvasSize, int cellLocalX, int cellLocalY,
                                                 int padding) {
        return new BlockAtlasGhostSprite(atlasLocation, contents, canvasSize,
                BlockAtlasGhostLayout.animatedCellX(canvasSize, cellLocalX),
                BlockAtlasGhostLayout.animatedCellY(canvasSize, cellLocalY),
                0, 0, 0, padding);
    }

    /** 1-based overflow page holding this sprite's full-resolution content; 0 for an animated
     * ghost, which has none. */
    public int overflowPage() {
        return this.overflowPage;
    }

    /** Whether a full-resolution overflow-layer copy exists to composite and sample. */
    public boolean hasOverflowCopy() {
        return this.overflowPage > 0;
    }

    /** The stitcher's uniform padding this sprite was placed with. */
    public int padding() {
        return this.ghostPadding;
    }

    /** Padded-region X on the overflow page (stitcher coordinate, full resolution). */
    public int pageX() {
        return this.pageX;
    }

    /** Padded-region Y on the overflow page (stitcher coordinate, full resolution). */
    public int pageY() {
        return this.pageY;
    }

    @Override
    public float getU0() {
        return BlockAtlasGhostLayout.ghostU0(getX(), this.ghostPadding, this.canvasSize);
    }

    @Override
    public float getU1() {
        return BlockAtlasGhostLayout.ghostU1(getX(), this.ghostPadding, contents().width(), this.canvasSize);
    }

    @Override
    public float getV0() {
        return BlockAtlasGhostLayout.ghostV0(getY(), this.ghostPadding, this.canvasSize);
    }

    @Override
    public float getV1() {
        return BlockAtlasGhostLayout.ghostV1(getY(), this.ghostPadding, contents().height(), this.canvasSize);
    }

    @Override
    public float getU(float offset) {
        return getU0() + (getU1() - getU0()) * offset;
    }

    @Override
    public float getV(float offset) {
        return getV0() + (getV1() - getV0()) * offset;
    }

    @Override
    public void uploadSpriteUbo(ByteBuffer buffer, int startOffset, int maxMipLevel,
                                int atlasWidth, int atlasHeight, int spriteUboSize) {
        BlockAtlasGhostLayout.writeGhostSpriteUbo(buffer, startOffset, maxMipLevel,
                atlasWidth, atlasHeight, spriteUboSize,
                getX(), getY(), contents().width(), contents().height(), this.ghostPadding);
    }
}
