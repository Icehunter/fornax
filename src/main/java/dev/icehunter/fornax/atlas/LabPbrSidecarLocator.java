package dev.icehunter.fornax.atlas;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.resources.Identifier;
import java.util.Optional;

/**
 * Maps a block-atlas sprite to the resource id of one of its labPBR sidecars.
 *
 * <p>Extracted because {@link NormalMapAtlasReloadListener} and
 * {@link MaterialMapAtlasReloadListener} had this derivation copied verbatim, and each now needs it
 * TWICE -- once to measure the sidecar's dimensions before the atlas is sized, once to blit it. Four
 * copies of a rule with a Continuity special case in it is three too many.
 */
public final class LabPbrSidecarLocator {
    /**
     * Synthetic sprite-path prefix Continuity substitutes for a stripped {@code "optifine/"} prefix
     * when registering connected-texture (CTM) tile sprites.
     */
    private static final String CONTINUITY_RESERVED_PREFIX = "continuity_reserved/";

    private LabPbrSidecarLocator() {
    }

    /**
     * The resource id of {@code sprite}'s sidecar with the given suffix ({@code "_n"} or
     * {@code "_s"}).
     *
     * <p>Continuity (the connected-texture mod) registers CTM tile sprites -- the numbered
     * {@code 2.png}/{@code 3.png}/... tiles produced by CTM {@code .properties} files under
     * {@code assets/<ns>/optifine/ctm/<block>/} -- under a synthetic identifier with a leading
     * {@code "optifine/"} replaced by {@code "continuity_reserved/"}, rather than one that maps back
     * to their real file location. {@link SpriteSource#TEXTURE_ID_CONVERTER}'s normal
     * {@code textures/<path>.png} reversal can never find those sprites' files or their sidecars, so
     * Continuity's substitution is undone here instead: strip the prefix, put {@code "optifine/"}
     * back, append {@code ".png"} (e.g. {@code continuity_reserved/ctm/cobblestone/2} ->
     * {@code optifine/ctm/cobblestone/2.png}), then append the sidecar suffix exactly as normal.
     */
    public static Optional<Identifier> sidecarId(TextureAtlasSprite sprite, String suffix) {
        return albedoId(sprite).map(id -> withSuffix(id, suffix));
    }

    /** Exact albedo resource file that owns {@code sprite}'s LabPBR sidecars and metadata. */
    public static Optional<Identifier> albedoId(TextureAtlasSprite sprite) {
        Identifier spriteName = sprite.contents().name();

        Optional<Identifier> exact = LabPbrAtlasProvenance.resolve(sprite.contents());
        if (exact.isPresent()) {
            return exact;
        }

        // Continuity's CTM owner is a documented synthetic encoding of the exact source path.
        // Other generated/unknown sprite sources stay neutral instead of guessing from an alias.
        if (spriteName.getPath().startsWith(CONTINUITY_RESERVED_PREFIX)) {
            String baseTexturePath = "optifine/"
                    + spriteName.getPath().substring(CONTINUITY_RESERVED_PREFIX.length()) + ".png";
            return Optional.of(Identifier.fromNamespaceAndPath(
                    spriteName.getNamespace(), baseTexturePath));
        }
        return Optional.empty();
    }

    static Identifier withSuffix(Identifier albedoId, String suffix) {
        return Identifier.fromNamespaceAndPath(albedoId.getNamespace(),
                withSuffix(albedoId.getPath(), suffix));
    }

    /** {@code textures/block/stone.png} + {@code _n} -> {@code textures/block/stone_n.png}. */
    static String withSuffix(String baseTexturePath, String suffix) {
        int lastDot = baseTexturePath.lastIndexOf('.');
        if (lastDot < 0) {
            return baseTexturePath + suffix;
        }
        return baseTexturePath.substring(0, lastDot) + suffix + baseTexturePath.substring(lastDot);
    }
}
