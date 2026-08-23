package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.pipeline.CelestialSprites;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the vanilla celestials atlas ({@link CelestialSprites}) whenever it is (re)uploaded --
 * a third copy of the {@code TextureAtlas*HookMixin} pattern ({@code TextureAtlasMaterialHookMixin}/
 * {@code TextureAtlasNormalHookMixin}), gated on {@link Sheets#CELESTIAL_SHEET}
 * ({@code minecraft:textures/atlas/celestials.png}) rather than {@code AtlasIds.CELESTIALS}
 * ({@code minecraft:celestials}) -- the latter is only the {@code AtlasManager} lookup key
 * ({@code definitionLocation}), not the atlas's own {@code location} field this hook's gate
 * compares against (see {@code .superpowers/sdd/celestials-atlas-research.md} Step 1).
 *
 * <p>At {@code upload} RETURN every sprite is stitched and the atlas's GPU texture/view (inherited
 * from {@link AbstractTexture}) are already valid -- confirmed by vanilla's own {@code
 * SkyRenderer.renderMoon}/{@code renderSun}, which bind the atlas exactly this way at draw time. UVs
 * are queried lazily via {@code getSprite(Identifier)} rather than captured from the {@code
 * Preparations} argument, matching vanilla's own usage pattern (the sprite objects are identical
 * either way -- {@code TextureAtlasSprite}'s u0/u1/v0/v1 fields are set once at construction).
 */
@Mixin(TextureAtlas.class)
public class TextureAtlasCelestialHookMixin {
    @Shadow
    @Final
    private Identifier location;

    @Inject(method = "upload", at = @At("RETURN"))
    private void fornax$captureCelestialSprites(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        if (!this.location.equals(Sheets.CELESTIAL_SHEET)) {
            return;
        }

        TextureAtlas atlas = (TextureAtlas) (Object) this;
        AbstractTexture texture = (AbstractTexture) (Object) this;

        float[] sunRect = rectOf(atlas, Identifier.withDefaultNamespace("sun"));

        MoonPhase[] phases = MoonPhase.values();
        float[][] moonRects = new float[phases.length][];
        for (MoonPhase phase : phases) {
            Identifier spriteId = Identifier.withDefaultNamespace("moon/" + phase.getSerializedName());
            moonRects[phase.index()] = rectOf(atlas, spriteId);
        }

        CelestialSprites.capture(texture.getTexture(), texture.getTextureView(), sunRect, moonRects);
    }

    private static float[] rectOf(TextureAtlas atlas, Identifier spriteId) {
        TextureAtlasSprite sprite = atlas.getSprite(spriteId);
        return new float[] {sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()};
    }
}
