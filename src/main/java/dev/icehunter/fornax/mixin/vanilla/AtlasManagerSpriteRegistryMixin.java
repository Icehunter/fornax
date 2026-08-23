package dev.icehunter.fornax.mixin.vanilla;

import net.minecraft.client.resources.model.sprite.AtlasManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops Fornax's interned sprites whenever the atlases are restitched.
 *
 * <p>{@link SpriteIndexRegistry} keys on {@code TextureAtlasSprite} instances so it can hand each one
 * a stable index. Those instances are replaced wholesale on a resource reload, so without this the map
 * would retain every sprite from every previous reload -- and a sprite retains its {@code
 * SpriteContents}, which retains native image memory. On Apple Silicon that memory is unified with the
 * GPU's, so the leak does not merely grow the heap: it eats the budget Metal allocates command buffers
 * from, and shows up as an out-of-device-memory device loss several reloads later rather than as
 * anything resembling a leak.
 *
 * <p>The indices themselves must be dropped too, not just the keys. They are positions into the sprite
 * bounds buffer, and a stale index would point a shader at the previous atlas's layout -- parallax
 * clamped to a rectangle where some other texture now lives.
 *
 * <p>Hooks {@code updateSpriteMaps} because that is the point where the newly stitched atlases are
 * adopted; clearing here means the first quad meshed against the new atlas re-registers from empty.
 */
@Mixin(AtlasManager.class)
public class AtlasManagerSpriteRegistryMixin {
    @Inject(method = "updateSpriteMaps", at = @At("HEAD"))
    private void fornax$forgetStaleSprites(CallbackInfo ci) {
        dev.icehunter.fornax.pipeline.SpriteBoundsTexture.invalidate();
    }
}
