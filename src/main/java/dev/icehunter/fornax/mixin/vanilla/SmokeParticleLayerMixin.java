package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.pack.graph.GraphRunner;
import net.minecraft.client.particle.BaseAshSmokeParticle;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes the generic smoke family through the translucent particle pipeline while a pack is active.
 *
 * <p>{@link BaseAshSmokeParticle} (torch/brewing/explosion smoke: SmokeParticle, LargeSmokeParticle,
 * WhiteSmokeParticle) hardcodes {@code Layer.OPAQUE}, whose {@code RenderPipelines.OPAQUE_PARTICLE}
 * carries no blend state -- the shared particle fragment shader only discards alpha &lt; 0.1, then
 * every surviving texel overwrites the framebuffer fully opaque. Vanilla's own 16x smoke sprites are
 * effectively binary alpha so this never shows, but high-resolution pack sprites (Optimum's smoke is
 * ~46% partial-alpha texels) degenerate into solid rectangles; under TAAU the per-frame subpixel
 * jitter shifts which texels clear the 0.1 discard, so the rectangles flash (2026-07-21 live
 * report). {@code CampfireSmokeParticle} already returns {@code Layer.TRANSLUCENT} in vanilla and
 * demonstrates the identical draw timing/depth behavior is safe -- the two pipelines share the same
 * snippet and differ only in blend state.
 *
 * <p>Gated on an active pack graph: with no pack the vanilla 16x sprites are in play and the
 * no-pack path stays byte-for-byte vanilla (the same law every other vanilla hook in this package
 * follows). Translucent smoke also lands in reconstruct.fsh's existing translucent-overlay tier
 * (capped history weight), which is the correct temporal treatment for unreprojectable content.
 */
@Mixin(BaseAshSmokeParticle.class)
public abstract class SmokeParticleLayerMixin {
    @Inject(method = "getLayer", at = @At("HEAD"), cancellable = true)
    private void fornax$blendPartialAlphaSmoke(
            CallbackInfoReturnable<SingleQuadParticle.Layer> cir) {
        if (GraphRunner.isActive()) {
            cir.setReturnValue(SingleQuadParticle.Layer.TRANSLUCENT);
        }
    }
}
