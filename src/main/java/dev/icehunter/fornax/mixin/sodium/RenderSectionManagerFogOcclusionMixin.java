package dev.icehunter.fornax.mixin.sodium;

import dev.icehunter.fornax.pack.graph.GraphRunner;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Disables Sodium's fog-occlusion culling while a pack is active. {@code
 * RenderSectionManager.getSearchDistance} collapses the section-visibility search radius to {@code
 * fogParameters.cullDistance()} whenever the {@code useFogOcclusion} performance option is on and
 * VANILLA fog reads fully opaque. Underwater, vanilla fog is short-range and opaque almost
 * immediately, so with a pack active the visible world collapses to a small bubble around the
 * camera, most blocks missing except near the very bottom, with the bubble expanding as sections
 * re-enter the shrunken radius as the player moves. The pack renders its OWN fog (translucent,
 * much longer range), so vanilla fog's opacity is the wrong signal entirely -- the culled sections
 * are plainly visible through pack fog.
 *
 * <p>The override is therefore blanket rather than conditional: once a pack owns fog, vanilla's
 * fog opacity describes nothing that is on screen, so there is no distance at which culling
 * against it is correct. Gated on {@link
 * GraphRunner#isActive()}: packs-off keeps stock Sodium behavior (the optimization is correct for
 * vanilla fog), and the user's own Fog Occlusion video setting stays honored either way -- this
 * redirect only ever forces the read toward {@code false}, never toward {@code true}.
 */
@Mixin(RenderSectionManager.class)
public class RenderSectionManagerFogOcclusionMixin {
    @Redirect(method = "getSearchDistance",
            at = @At(value = "FIELD",
                    target = "Lnet/caffeinemc/mods/sodium/client/gui/SodiumOptions$PerformanceSettings;useFogOcclusion:Z"))
    private boolean fornax$disableFogOcclusionWhilePackActive(SodiumOptions.PerformanceSettings settings) {
        return settings.useFogOcclusion && !GraphRunner.isActive();
    }
}
