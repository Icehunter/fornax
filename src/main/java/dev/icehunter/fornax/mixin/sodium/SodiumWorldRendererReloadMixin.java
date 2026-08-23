package dev.icehunter.fornax.mixin.sodium;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pipeline.FornaxRenderState;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single boundary where Fornax's render-state latch advances: {@code
 * SodiumWorldRenderer.initRenderer()}, the private method both renderer-recreation paths funnel
 * through -- {@code reload()} (reached from {@code LevelExtractor.allChanged()}, i.e. Sodium's own
 * {@code REQUIRES_RENDERER_RELOAD} flag handling AND Fornax's {@code RendererReload.request()}) and
 * {@code loadLevel(...)} at world join. Both run synchronously between frames, never mid terrain
 * rendering, so a toggle landing during a frame cannot advance the latch until the recreation
 * actually happens.
 *
 * <p>Also clears {@code ShaderChunkRenderer}'s process-wide static pipeline cache (see {@link
 * ShaderChunkRendererAccessor}): recreation constructs a new {@code DefaultChunkRenderer} but the
 * static {@code programs} map would happily serve pipelines compiled under the PREVIOUS latch state
 * -- the exact torn state (old color-target-state count vs new render-pass attachment count) behind
 * the live toggle crash. Clearing here makes the next terrain draw recompile every pass's pipeline
 * under the same state every other consumer of {@link FornaxRenderState} now observes.
 */
@Mixin(SodiumWorldRenderer.class)
public class SodiumWorldRendererReloadMixin {
    @Inject(method = "initRenderer", at = @At("HEAD"), remap = false)
    private void fornax$latchRenderState(CallbackInfo ci) {
        boolean desired = FornaxConfig.get().shadersEnabled && GraphRunner.currentPack() != null;
        if (desired != FornaxRenderState.isActive()) {
            FornaxMod.LOGGER.info("[Fornax] Render state latched: pack graph {}", desired ? "ACTIVE" : "inactive");
        }
        FornaxRenderState.latch(desired);
        ShaderChunkRendererAccessor.fornax$getPrograms().clear();
    }
}
