package dev.icehunter.fornax.atlas;

import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides which sprites in a block-atlas stitch set are labPBR sidecars that nothing ever samples
 * from that atlas, and can therefore be left out of it.
 *
 * <p><b>Why this exists.</b> labPBR packs put {@code foo_n.png} (normal + height) and
 * {@code foo_s.png} (smoothness/F0/porosity/emission) next to {@code foo.png} in
 * {@code assets/<ns>/textures/block/}. Vanilla's {@code assets/minecraft/atlases/blocks.json} lists
 * that directory with a {@code minecraft:directory} source, which enumerates <em>every</em> PNG in
 * it -- vanilla has no concept of a sidecar, so each one is stitched as an ordinary sprite. Nothing
 * then reads it: no vanilla or pack model references a {@code _n}/{@code _s} texture (verified
 * against the client jar and the user's packs), and Fornax's own {@link NormalMapAtlasReloadListener}
 * and {@link MaterialMapAtlasReloadListener} read the sidecar PNGs straight from the
 * {@code ResourceManager} into their own atlases, never from the block atlas.
 *
 * <p>So the block atlas has been carrying two extra copies of the pack's texture set purely as
 * ballast, and it is the copies -- not the colour -- that make it overflow. Measured on the user's
 * own packs by running vanilla's real {@link net.minecraft.client.renderer.texture.Stitcher} over
 * the exact stitch set (the model reproduces every atlas size their logs report, exactly):
 *
 * <pre>
 *   pack                  sprites          block atlas before      after
 *   64x                   3154 -> 1770      8192x4096  134 MB      4096x4096   67 MB
 *   64x + 512 wood-brick  3154 -> 1770     16384x8192  537 MB      8192x8192  268 MB
 *   64x + 512 MAPS        3154 -> 1770     StitcherException       4096x4096   67 MB
 *   128x                  3154 -> 1770     16384x8192  537 MB      8192x8192  268 MB
 *   256x                  3154 -> 1770     StitcherException      16384x16384 1074 MB
 *   512x                  3154 -> 1770     StitcherException       StitcherException
 * </pre>
 *
 * <p>Two of those lines are the point. <b>256x stops failing</b> -- it fits inside Apple's 16384
 * dimension cap with no paging at all, so the "we need a paged block atlas to reach 256x" premise is
 * simply wrong; only 512x colour still needs one. And the 64x-colour + 512x-maps pack, which the
 * user built specifically to test how much of the look is carried by map resolution, goes from
 * "cannot load, resource packs disabled" to the same 4096x4096 atlas plain 64x uses.
 *
 * <p><b>The safety rule is "the base sprite is present", not the suffix alone.</b> A sprite is only
 * dropped when the identically-named sprite <em>without</em> the suffix is in the same stitch set --
 * which is exactly what makes it a sidecar under the labPBR convention. A pack that genuinely ships
 * a block texture called {@code something_s} with no {@code something} beside it keeps its sprite and
 * its model keeps working. (No vanilla block or item texture ends in {@code _n} or {@code _s} at
 * all, so vanilla alone is untouched.)
 *
 * <p>REJECTED: filtering at the sprite SOURCE instead, which would avoid even decoding the PNGs.
 * {@code SpriteSource.Loader} exposes only {@code get(...)}, not the identifier, so nothing there
 * can tell a sidecar from anything else; and {@code DirectoryLister} does not know which atlas it is
 * feeding or what else will end up in it, so the "base sprite is present" rule -- the whole safety
 * argument -- could not be evaluated. The stitch call is the first point where the atlas's identity
 * and its complete sprite set are both in hand.
 */
public final class LabPbrSidecarStitchFilter {
    /** The labPBR sidecar suffixes Fornax reads from source files: normal+height, and specular. */
    private static final String[] SIDECAR_SUFFIXES = {"_n", "_s"};

    private LabPbrSidecarStitchFilter() {
    }

    /**
     * The subset of {@code stitchSet} that is a labPBR sidecar of another sprite in the same set.
     *
     * @param stitchSet every sprite name about to be stitched into one atlas
     * @return the names safe to leave out; empty when the pack ships no sidecars
     */
    public static Set<Identifier> sidecarsToDrop(Collection<Identifier> stitchSet) {
        Set<Identifier> present = new HashSet<>(stitchSet);
        Set<Identifier> drop = new HashSet<>();
        for (Identifier name : stitchSet) {
            Identifier base = baseOf(name);
            if (base != null && present.contains(base)) {
                drop.add(name);
            }
        }
        return drop;
    }

    /**
     * The sprite {@code name} would be a sidecar of, or {@code null} if its path carries no sidecar
     * suffix or stripping it would not leave a real texture name behind.
     */
    private static Identifier baseOf(Identifier name) {
        String path = name.getPath();
        for (String suffix : SIDECAR_SUFFIXES) {
            if (!path.endsWith(suffix)) {
                continue;
            }
            String base = path.substring(0, path.length() - suffix.length());
            // "block/_n" would strip to "block/", which is not a texture name -- and Identifier
            // would reject it anyway. Refusing here keeps the caller free of exception handling.
            if (base.isEmpty() || base.endsWith("/")) {
                return null;
            }
            return Identifier.fromNamespaceAndPath(name.getNamespace(), base);
        }
        return null;
    }
}
