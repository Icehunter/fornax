package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.atlas.AtlasGenerationSchedule;
import dev.icehunter.fornax.atlas.BlockAtlasOverflow;
import dev.icehunter.fornax.atlas.BlockAtlasPagedLayout;
import dev.icehunter.fornax.atlas.BlockAtlasView;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the vanilla block atlas ({@link BlockAtlasView}) whenever it is (re)uploaded -- the
 * cutout/cross milestone's generic {@code builtin.blockAtlas} engine capability. A fourth copy of the
 * {@code TextureAtlas*HookMixin} pattern ({@code TextureAtlasCelestialHookMixin}/{@code
 * TextureAtlasMaterialHookMixin}/{@code TextureAtlasNormalHookMixin}), gated on {@link
 * TextureAtlas#LOCATION_BLOCKS} exactly like the Normal/Material hooks (not {@code
 * Sheets.CELESTIAL_SHEET}) -- unlike those two, which build a SECOND, derived texture at the block
 * atlas's UV layout, this one captures the block atlas's OWN GPU texture/view directly, so a generic
 * fullscreen/compute pass can sample the real block atlas (with real alpha) without needing terrain-
 * draw-specific plumbing.
 *
 * <p>At {@code upload} RETURN every sprite is stitched and the atlas's GPU texture/view (inherited
 * from {@link AbstractTexture}) are already valid -- same reasoning {@code
 * TextureAtlasCelestialHookMixin}'s own doc comment gives for its identical hook point.
 */
@Mixin(TextureAtlas.class)
public class TextureAtlasBlockHookMixin {
    @Shadow
    @Final
    private Identifier location;

    @Inject(method = "upload", at = @At("RETURN"))
    private void fornax$captureBlockAtlas(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        if (!this.location.equals(TextureAtlas.LOCATION_BLOCKS)) {
            return;
        }
        AbstractTexture texture = (AbstractTexture) (Object) this;
        BlockAtlasView.capture(texture.getTexture(), texture.getTextureView());
        // The paged overflow layers rebuild from the same hook the sidecar lanes build from: at
        // upload RETURN the stitch takeover (if any) has published its layout and every sprite's
        // mip chain exists (readyForUpload completed before upload). Null layout = unpaged
        // generation -> clears any previous generation's layers.
        //
        // Skipped when TextureAtlasReleaseGenerationMixin's HEAD hook already released this
        // generation and scheduled a deferred rebuild -- see that mixin's and
        // AtlasGenerationSchedule's own docs. Rebuilding here too would allocate the new overflow
        // array in the same call as the release, defeating the whole point of deferring it.
        if (!AtlasGenerationSchedule.hasPending(this.location)) {
            BlockAtlasOverflow.rebuild(BlockAtlasPagedLayout.current());
        }
    }
}
