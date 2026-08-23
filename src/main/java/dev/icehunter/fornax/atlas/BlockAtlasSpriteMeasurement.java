package dev.icehunter.fornax.atlas;

import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.resources.Identifier;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates one resolved pack stack's sprite list before it reaches {@link BlockAtlasPaging}.
 *
 * <p>"Resolved" means the list already reflects the enabled pack stack flattened to one entry per
 * sprite -- disabled packs contribute nothing, and where two enabled packs ship the same sprite
 * path, only the higher-priority pack's entry survives. By the time vanilla's own {@code
 * SpriteLoader.stitch(List<SpriteContents>, ...)} runs, its {@code contents} parameter already is
 * such a list (each {@link net.minecraft.client.renderer.texture.SpriteContents} implements {@link
 * Stitcher.Entry} directly), so this class does no flattening itself -- it only guards the
 * invariants paging depends on before committing to an allocation: every entry names a distinct
 * sprite, and every entry's dimensions are usable. A resolved stack should already satisfy both,
 * but "should" is exactly the malformed-input case this class exists to catch before {@link
 * Stitcher} (whose own registration silently tolerates a duplicate {@link Identifier}, letting one
 * entry shadow the other with no diagnostic) gets a chance to hide it.
 */
final class BlockAtlasSpriteMeasurement {
    private BlockAtlasSpriteMeasurement() {
    }

    /**
     * Returns {@code resolvedSprites} unchanged (as a defensive copy) once every entry is verified
     * to have positive dimensions and a name distinct from every other entry's.
     *
     * @throws IllegalArgumentException if any entry's width or height is not positive, naming the
     *                                   offending sprite
     * @throws IllegalStateException    if two entries share a name -- the stack was not actually
     *                                   resolved, since a resolved stack has exactly one entry per
     *                                   sprite
     */
    static <T extends Stitcher.Entry> List<T> measure(List<T> resolvedSprites) {
        Set<Identifier> seen = new HashSet<>(resolvedSprites.size());
        for (T entry : resolvedSprites) {
            if (entry.width() <= 0 || entry.height() <= 0) {
                throw new IllegalArgumentException(
                        "sprite " + entry.name() + " has non-positive dimensions " + entry.width()
                                + "x" + entry.height());
            }
            if (!seen.add(entry.name())) {
                throw new IllegalStateException(
                        "sprite " + entry.name() + " appears more than once in a resolved pack "
                                + "stack -- the stack was not flattened before measurement");
            }
        }
        return List.copyOf(resolvedSprites);
    }
}
