package dev.icehunter.fornax.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code RenderSectionManager}'s private {@code regions} field (javap-verified against
 * sodium-fabric-mc26.2-0.9.0: {@code private final RenderRegionManager regions;}, no getter --
 * {@code getLoadedRegions()} lives on {@link RenderRegionManager} itself and is already public, but
 * reaching the manager instance in the first place requires this accessor).
 *
 * <p>Consumed by {@code dev.icehunter.fornax.pass.shadow.ShadowCasterLists} to enumerate every
 * loaded region for the sun-shadow pass's radius-based caster list, independent of the player
 * frustum -- see that class's javadoc for the full mechanism. Modeled on the existing {@link
 * ShaderChunkRendererAccessor} pattern already in this codebase.
 */
@Mixin(RenderSectionManager.class)
public interface RenderSectionManagerAccessor {
    @Accessor(value = "regions", remap = false)
    RenderRegionManager fornax$getRegions();
}
