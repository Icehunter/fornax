package dev.icehunter.fornax.pipeline;

import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.world.item.DyeColor;

/**
 * Moves a banner's opaque base dye onto the deferred cloth draw.
 *
 * <p>Vanilla submits the flag twice: first as an untinted {@code ENTITY_SOLID} model, then as the
 * fully opaque {@link Sheets#BANNER_PATTERN_BASE} layer. Fornax resolves the first draw before the
 * second one arrives, so the late base layer otherwise replaces the correctly shaded cloth with a
 * separately lit forward quad. Actual pattern masks remain on that forward route.</p>
 */
public final class BannerBaseLayerRoute {
    private BannerBaseLayerRoute() {
    }

    public static int deferredFlagColor(DyeColor baseColor) {
        return baseColor.getTextureDiffuseColor();
    }

    public static boolean suppressForwardLayer(SpriteId sprite) {
        return Sheets.BANNER_PATTERN_BASE.equals(sprite);
    }
}
