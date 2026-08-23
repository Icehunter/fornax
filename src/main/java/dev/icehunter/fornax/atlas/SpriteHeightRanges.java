package dev.icehunter.fornax.atlas;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The labPBR height range each sprite actually uses, measured while the normal atlas is assembled.
 *
 * <p>labPBR fixes the meaning of the height channel: alpha 255 is the surface, alpha 0 is 25% of a
 * block deep. Resource packs routinely use a sliver of that. Measured on a 128x pack: bricks span
 * 202..255 and cobblestone 200..255 -- about a fifth of the range, so those surfaces are nominally
 * some 5% of a block deep. Traced literally, which is the correct reading of the standard, that gives
 * a handful of pixels of displacement and parallax reads as the texture sliding rather than as relief.
 *
 * <p>Publishing the real range lets a shader rescale it. That is deliberately left to the PACK: how
 * deep a surface should look is a claim about the surface, and overriding what a texture author said
 * about theirs is a look, not plumbing. The engine's job is to make the true range knowable.
 *
 * <p><b>Why a second, ROBUST pair.</b> The true min/max is defined by the two most extreme texels in
 * the sprite, so one stray texel sets the rescale for everything. Measured across the five Optimum
 * Realism R3.9.0 builds (3,460 sprites): the low tail between the 0th and 1st percentile is a median
 * 4.9%-14.1% of the whole span, and reaches 20%-26% at the 90th percentile of sprites -- span that
 * one texel in a hundred is holding open while the other ninety-nine are squeezed into what is left.
 * On {@code block/bricks} at 512x the true span is 169..255 while the 1st percentile is 191: a third
 * of the range spent on 1% of the texels.
 *
 * <p>So the trimmed pair is published ALONGSIDE the true one, never instead of it. A range a single
 * texel can wreck is not knowable in any useful sense -- but a pack that deliberately wants labPBR's
 * literal extremes must still be able to have them, and the fallback ladder a shader needs when the
 * trimmed pair degenerates (see {@link NormalMapAtlasReloadListener#robustBounds}) is exactly the
 * true pair. Publishing more, rather than replacing, is what makes both possible.
 */
public final class SpriteHeightRanges {
    /**
     * A sprite's content rectangle in NORMALISED atlas UV, with the alpha range found inside it.
     *
     * <p><b>Normalised, and that is the whole point of the type.</b> This record crosses a module
     * boundary: it is produced while the PBR sidecar atlas is assembled and consumed by
     * {@link dev.icehunter.fornax.pipeline.SpriteBoundsTexture}, which lays it into a grid keyed by
     * the BLOCK atlas. Those two atlases are deliberately different sizes, and since
     * {@link PbrSidecarAtlasScale} began sizing sidecars from their OWN resolution the ratio between
     * them is not even a constant any more -- it is whatever the loaded pack's maps asked for, 1:2
     * for maps that match the colour and 2:1 for the user's 512px-maps-on-64px-colour build. So a
     * rectangle expressed in "atlas texels" is ambiguous across exactly this boundary, and the
     * ambiguity is silent: the numbers stay plausible and simply describe the wrong place.
     *
     * <p>It was not hypothetical. These fields were once PBR-atlas texels and the consumer divided
     * them by the BLOCK atlas's width, so every range landed at half its true position and a quarter
     * of its true area. The grid answered "no range recorded" for roughly nine cells in ten, the
     * shader's fallback ladder correctly returned the RAW height, and the visible result was a
     * height-map debug view that read near-uniform white with a dead contrast slider -- a fix's worth
     * of arithmetic doing nothing, with nothing anywhere reporting a fault. Normalised UV is the one
     * unit both atlases agree on, so the mismatch cannot be re-expressed.
     *
     * @param u0             the sprite content's left edge, normalised
     * @param v0             the sprite content's top edge, normalised
     * @param u1             the sprite content's right edge, normalised
     * @param v1             the sprite content's bottom edge, normalised
     * @param minAlpha       the true minimum -- the single deepest texel in the sprite
     * @param maxAlpha       the true maximum -- the single shallowest texel
     * @param robustMinAlpha the 1st-percentile height, ignoring the deepest 1% of texels
     * @param robustMaxAlpha the 99th-percentile height, ignoring the shallowest 1% of texels
     */
    public record Range(float u0, float v0, float u1, float v1, int minAlpha, int maxAlpha,
                        int robustMinAlpha, int robustMaxAlpha) {}

    private static final List<Range> RANGES = new CopyOnWriteArrayList<>();

    private SpriteHeightRanges() {}

    /** Replaces the recorded set wholesale -- called once per atlas build with that build's results. */
    public static void replaceAll(List<Range> ranges) {
        RANGES.clear();
        RANGES.addAll(ranges);
    }

    public static List<Range> all() {
        return List.copyOf(RANGES);
    }
}
