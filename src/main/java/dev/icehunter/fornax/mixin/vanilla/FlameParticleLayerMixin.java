package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.pack.graph.GraphRunner;
import net.minecraft.client.particle.FlameParticle;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes torch/candle/soul-fire flames through the translucent particle pipeline while a pack is
 * active -- {@link SmokeParticleLayerMixin}'s sibling; see that class for the full opaque-pipeline
 * flashing mechanism this family shares.
 *
 * <p>{@link FlameParticle} (the FLAME, SMALL_FLAME, and SOUL_FIRE_FLAME providers all construct
 * this one class) hardcodes {@code Layer.OPAQUE}. Unlike smoke, the pack's 32x128 flame sprite has
 * strictly binary source alpha -- the partial alpha that flickers is SYNTHESIZED at render time:
 * bilinear/mip filtering box-averages across the flame's long, detailed silhouette edge,
 * manufacturing intermediate alpha values an 8x8 vanilla sprite's short edge never produces in
 * meaningful quantity. Under TAAU's per-frame subpixel jitter, which filtered edge texels clear the
 * 0.1 discard shifts every frame -- the torch-flame quad visibly flashes. This flame sits directly
 * above the same torches whose smoke the sibling mixin fixes separately; flame and smoke each need
 * their own translucent-layer gate. Same gate, same vanilla-untouched no-pack path, same
 * shared-atlas safety argument as the sibling.
 */
@Mixin(FlameParticle.class)
public abstract class FlameParticleLayerMixin {
    @Inject(method = "getLayer", at = @At("HEAD"), cancellable = true)
    private void fornax$blendFilteredEdgeFlame(
            CallbackInfoReturnable<SingleQuadParticle.Layer> cir) {
        if (GraphRunner.isActive()) {
            cir.setReturnValue(SingleQuadParticle.Layer.TRANSLUCENT);
        }
    }
}
