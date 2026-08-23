package dev.icehunter.fornax.mixin.vanilla;

import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Exposes the two fields of vanilla's package-private {@code QuadParticleFeatureRenderer.PreparedGroup}
 * that decide whether a particle group may be deferred.
 *
 * <p>{@code translucent} is the ground truth: it is the very bit {@code executeGroup} itself branches
 * on when picking a render target, so reading it means Fornax's deferral decision and vanilla's target
 * choice can never disagree. The alternative -- recomputing it as {@code submits.getFirst().translucent()},
 * the way {@code prepareGroup} originally derived it -- would be a second source of truth for the same
 * fact, free to drift.
 *
 * <p>{@code layers} is needed because the group flag alone is a summary: {@code prepareGroup} sets it
 * from the FIRST submit only, while the map it builds can in principle hold layers from later submits.
 * Scanning the keys makes "no translucent layer is ever deferred" a property of the geometry actually
 * present rather than of a summary bit.
 *
 * <p>Reached through {@code targets} rather than a class literal because {@code PreparedGroup} is
 * package-private and cannot be named from this package. {@link SingleQuadParticle.Layer} is public,
 * so the map's keys are usable directly; its values are not named here because nothing reads them.
 */
@Mixin(targets = "net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer$PreparedGroup")
public interface QuadParticlePreparedGroupAccessor {
    @Accessor("translucent")
    boolean fornax$translucent();

    @Accessor("layers")
    Map<SingleQuadParticle.Layer, ?> fornax$layers();
}
