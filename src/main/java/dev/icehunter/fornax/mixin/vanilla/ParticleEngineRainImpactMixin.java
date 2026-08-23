package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.pack.graph.GraphRunner;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses vanilla's rain SPLASH particle for a pack that declares
 * {@code PACK_RAIN_IMPACTS} and draws its own impact effect. Same shape and the same
 * "absent counts as off" contract as {@code HIDE_VANILLA_BLOB_SHADOWS}, which suppresses the
 * ellipse vanilla stamps under every entity for exactly the same reason: the pack draws something
 * better in the same place, and the two stack into a mess.
 *
 * <p><b>Why this is enforced at particle creation.</b> The splash is not drawn by
 * {@code WeatherEffectRenderer} and is untouched by anything done to the weather render pass.
 * Vanilla normally requests it from {@code ClientLevel.tickWeatherEffects}, but mods and resource
 * integrations may request the same particle elsewhere. Visuality goes further and independently
 * spawns {@code visuality:water_circle} above full water blocks during rain. Filtering both at
 * {@link ParticleEngine#createParticle} owns every route to the renderer instead of one caller.
 *
 * <p><b>Only competing rain-impact particles.</b> Lava smoke and every unrelated particle remain
 * untouched. Optional particles are matched by registry id, keeping Fornax independent of the mod
 * that registered them. A cancelled creation returns {@code null}, which is already
 * ParticleEngine's result when no provider can create a requested particle.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineRainImpactMixin {
    private static final String VISUALITY_WATER_CIRCLE = "visuality:water_circle";

    @Inject(
            method = "createParticle",
            at = @At("HEAD"),
            cancellable = true
    )
    private void fornax$maybeHideRainSplash(ParticleOptions particle,
            double x, double y, double z, double dx, double dy, double dz,
            CallbackInfoReturnable<Particle> cir) {
        Identifier particleId = BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType());
        boolean isCompetingRainImpact = particle == ParticleTypes.RAIN
                || (particleId != null && VISUALITY_WATER_CIRCLE.equals(particleId.toString()));
        if (isCompetingRainImpact
                && GraphRunner.isActive()
                && GraphRunner.isCompileOptionEnabled("PACK_RAIN_IMPACTS")) {
            cir.setReturnValue(null);
        }
    }
}
