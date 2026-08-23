package dev.icehunter.fornax.atlas;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The published result of a paged block-atlas stitch takeover: which layout the current atlas
 * generation actually has, for every later consumer -- the overflow-layer compositor (phase 1b),
 * the block-to-page map builder, and diagnostics. {@code null} means the current atlas is a plain
 * single-page vanilla stitch (fitting pack, flag off, or takeover declined) and every paged code
 * path must stay dormant.
 *
 * <p>Same publication idiom as {@link BlockAtlasPages}: a volatile swap of an immutable value,
 * installed and cleared ONLY by the stitch mixin ({@code SpriteLoaderPagedStitchMixin}) on the
 * reload path, read from anywhere. The ghost-sprite list holds the same {@link
 * BlockAtlasGhostSprite} instances the atlas itself holds in its regions map, so this adds no
 * texture data of its own -- it is an index, not a copy.
 *
 * @param canvasSize        the shared square canvas extent (page 0 texture and every overflow
 *                          layer), the device max texture dimension the stitch ran against
 * @param mipLevel          the FINAL mip level the takeover stitched with, after vanilla's own
 *                          per-sprite lowering rules -- overflow layers must allocate {@code
 *                          mipLevel + 1} levels to match page 0
 * @param overflowPageCount how many overflow pages exist (1..{@link
 *                          BlockAtlasGhostLayout#MAX_OVERFLOW_PAGES})
 * @param ghosts            every spilled sprite, carrying its overflow page and page-local
 *                          placement
 */
public record BlockAtlasPagedLayout(int canvasSize, int mipLevel, int overflowPageCount,
                                    List<BlockAtlasGhostSprite> ghosts) {
    public BlockAtlasPagedLayout {
        ghosts = List.copyOf(ghosts);
    }

    @Nullable
    private static volatile BlockAtlasPagedLayout current;

    /** Publishes the layout of the atlas generation being stitched right now. */
    public static void install(BlockAtlasPagedLayout layout) {
        current = layout;
    }

    /** Marks the current atlas generation unpaged (plain vanilla single-page stitch). */
    public static void clear() {
        current = null;
    }

    /** The current generation's layout, or {@code null} when the atlas is unpaged. */
    @Nullable
    public static BlockAtlasPagedLayout current() {
        return current;
    }
}
