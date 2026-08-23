package dev.icehunter.fornax.mixin.vanilla;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forces vanilla's cached {@code SkyRenderer} on {@link LevelRenderer} to rebuild every frame.
 *
 * <p>Vanilla only rebuilds {@code SkyRenderer} (capturing whatever
 * {@code GameRenderer.mainRenderTarget} currently is) when
 * {@code levelRenderState.shouldResetSkyRenderer} is true; otherwise it keeps drawing into
 * whatever target it captured last, regardless of SSAA swapping {@code mainRenderTarget} between
 * the native and scaled targets every frame. Left alone, the sky renders into a stale target once
 * SSAA is active. Forcing the reset every frame is cheap: {@code SkyRenderer}'s construction is
 * just a static disc mesh, not meaningful per-frame state.
 *
 * <p>This mixin only touches sky-target invalidation; it does not modify matrices, chunk-section
 * rendering, or filter-mode handling on {@code LevelRenderer}.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(method = "render", at = @At("HEAD"))
    private void fornax$forceSkyRendererResetForSsaa(CallbackInfo ci) {
        this.levelRenderState.shouldResetSkyRenderer = true;
    }
}
