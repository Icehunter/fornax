package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.pack.graph.GraphRunner;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SuspendedParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes the suspended-mote family through the translucent particle pipeline while a pack is
 * active.
 *
 * <p>The identical defect and the identical fix as {@link SmokeParticleLayerMixin}, on the family
 * that mixin's 2026-07-22 sweep missed: {@link SuspendedParticle} hardcodes {@code Layer.OPAQUE},
 * whose pipeline carries no blend state, so through the pack's deferred particle path every texel
 * surviving the 0.1 alpha discard lands fully opaque. For a family whose whole job is to be a
 * faint ambient speck -- the underwater motes, spore blossom drips, crimson and warped spores all
 * share this one class -- that renders as hard-edged solid squares. Photographed underwater on
 * 2026-08-06 (Plague capture uw_9: uniform 26x26-px squares in exactly the underwater provider's
 * {@code setColor(0.4, 0.4, 0.7)} violet, hanging in the veil), reported as "block texture on
 * screen"; the screen-locked feel is the known camera-only particle motion-vector limitation, not
 * a transform bug.
 *
 * <p>ALL FOUR providers of this class move together -- family identity exists only at
 * {@code getLayer()}, the engine's draw path sees one interleaved opaque buffer (group
 * granularity), and blending is the correct treatment for every one of them: they are all
 * sub-block ambient motes authored around soft edges.
 *
 * <p>Same active-pack gate, same reasoning as the smoke mixin: no pack, byte-for-byte vanilla.
 */
@Mixin(SuspendedParticle.class)
public abstract class SuspendedParticleLayerMixin {
    @Inject(method = "getLayer", at = @At("HEAD"), cancellable = true)
    private void fornax$blendAmbientMotes(
            CallbackInfoReturnable<SingleQuadParticle.Layer> cir) {
        if (GraphRunner.isActive()) {
            cir.setReturnValue(SingleQuadParticle.Layer.TRANSLUCENT);
        }
    }
}
