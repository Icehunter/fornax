package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import dev.icehunter.fornax.pipeline.DeferredGeometryPipelines;
import dev.icehunter.fornax.pipeline.FornaxRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the pack's render graph after vanilla's solid feature draws, for packs that claim a non-terrain
 * geometry slot.
 *
 * <p>Deferred shading requires every G-buffer writer to have drawn before anything resolves it.
 * Terrain-only packs satisfy that at the end of the opaque terrain layer, which is where the graph has
 * always run; a pack that also shades entities does not, because those draw here -- after that point.
 *
 * <p><b>After solid, before translucent, and nowhere else.</b> That boundary is the single point where
 * all opaque geometry has drawn and no blended geometry has. Moving the resolve later, to catch
 * geometry that only draws during the translucent pass, is the tempting mistake and it breaks blended
 * geometry outright: the full-screen resolve overwrites whatever the translucent pass already drew, so
 * layered blended geometry such as banner patterns loses its colour and comes out blank white.
 *
 * <p>When something needed in the G-buffer has not drawn by this point, the answer is to draw that
 * thing explicitly before resolving -- never to move the resolve. Iris settles on the same boundary and
 * renders the hand by hand right here for exactly this reason; {@code PlayerShadowCaster} is the same
 * idea applied to the shadow map.
 *
 * <p>Targets {@code PreparedFrame.executeSolid} rather than the {@code LevelRenderer.addMainPass}
 * call site: the call there lives inside a frame-graph lambda, whose synthetic method name shifts with
 * unrelated edits to the surrounding method, while this one is a real named method on a public type.
 */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public class FeatureSolidFeaturesGraphMixin {

    @org.spongepowered.asm.mixin.Unique
    private boolean fornax$reportedShadowSkip;

    @Inject(method = "executeSolid", at = @At("RETURN"))
    private void fornax$runGraphAfterSolidFeatures(CallbackInfo ci) {
        // The shadow phase below re-executes these same solid draws, which re-enters this method.
        // Without this guard the nested pass would start its own shadow replay and resolve the graph
        // midway through building the shadow map.
        if (DeferredGeometryPipelines.isShadowPhase()) {
            return;
        }

        // Cast entity shadows by running the very same prepared draws a second time, aimed at the
        // shadow map. Vanilla bakes each draw's transforms at submit time and offers no way to
        // re-submit from another viewpoint, so rather than duplicating the submit machinery the draws
        // are replayed and the pack's shadow program reprojects them through the light's matrix.
        //
        // Must happen BEFORE the graph runs: the resolve samples the shadow map, so a caster added
        // afterwards would not appear until the following frame.
        boolean wantShadowCasters = FornaxRenderState.isActive()
                && GraphRunner.isCompileOptionEnabled("SHADOWS")
                && ShadowMapManager.getView() != null;
        if (!wantShadowCasters && !fornax$reportedShadowSkip) {
            fornax$reportedShadowSkip = true;
            dev.icehunter.fornax.FornaxMod.LOGGER.info(
                    "[Fornax][diag] entity shadow casting inactive: packActive={} SHADOWS={} map={}",
                    FornaxRenderState.isActive(), GraphRunner.isCompileOptionEnabled("SHADOWS"),
                    ShadowMapManager.getView() != null);
        }
        if (wantShadowCasters) {
            DeferredGeometryPipelines.setShadowPhase(true);
            try {
                FeatureRenderDispatcher.PreparedFrame frame = (FeatureRenderDispatcher.PreparedFrame) (Object) this;
                // Solid draws only. Vanilla has not executed the translucent set yet at this point,
                // and running it early here would consume it before it ever reaches the screen. The
                // player -- whose skin is translucent -- is submitted separately below, so the one
                // caster that would otherwise be missed is covered.
                frame.executeSolid();
                dev.icehunter.fornax.pass.shadow.PlayerShadowCaster.cast();
            } finally {
                // Cleared in a finally so a throwing draw cannot strand the flag raised, which would
                // send every subsequent frame's entities into the shadow map instead of the screen.
                DeferredGeometryPipelines.setShadowPhase(false);
            }
        }

        if (GraphRunner.deferGraphUntilAfterSolidFeatures()) {
            GraphRunner.finishDeferred();
        }
    }
}
