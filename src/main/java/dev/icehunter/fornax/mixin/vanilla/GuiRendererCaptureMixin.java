package dev.icehunter.fornax.mixin.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.icehunter.fornax.metalfx.FrameGenPass;
import dev.icehunter.fornax.metalfx.MetalFxUpscalePass;
import dev.icehunter.fornax.pass.FrameGenPresenter;
import dev.icehunter.fornax.pass.UiLayerCapture;
import dev.icehunter.fornax.pipeline.DeferredGeometryPipelines;
import dev.icehunter.fornax.pipeline.GBuffer;
import dev.icehunter.fornax.pipeline.GBufferManager;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Captures vanilla's HUD draw into {@link UiLayerCapture}'s own transparent-background target
 * whenever this frame produced a MetalFX-generated (interpolated) frame -- {@code
 * FrameGenPass.generatedFrameReady()} -- so that generated frame, which never runs {@code
 * GuiRenderer.render()} itself (it is assembled entirely from {@code MetalFxUpscalePass}'s
 * already-produced native color, upstream of the HUD draw this method brackets), can still be
 * presented with a HUD instead of a HUD-less flash between two real frames. Task 6's present seam
 * reads {@link UiLayerCapture#activeThisFrame()}/{@link UiLayerCapture#compositeOnto} to stamp the
 * same captured layer onto that generated frame.
 *
 * <p>Wraps the single {@code GuiRenderer.render()} call inside {@code GameRenderer.render} (bracketed
 * rather than two sibling {@code @Inject}s, for the same reason {@code
 * GameRendererMixin#fornax$endFrame}'s own header gives for its single end-of-frame injection: a
 * paired before/after action expressed as one atomic method body is a plain-Java ordering guarantee
 * instead of a mixin-application-order subtlety). {@code render()} calls {@code renderLevel(...)}
 * (where {@code FrameGenPass} runs, at the tail of the SSAA/TAA/TAAU/MetalFX off-screen-target
 * restore) before reaching {@code GuiRenderer.render()} later in the same method, so {@code
 * generatedFrameReady()} already reflects this frame's outcome by the time this wrap fires.
 *
 * <p>When ungated (no generated frame this frame -- the overwhelmingly common case, since frame
 * generation is a dev-only flag gated behind a successful MetalFX upscale): zero behavior delta,
 * {@code original.call(instance)} runs with nothing else touched.
 */
@Mixin(GameRenderer.class)
public class GuiRendererCaptureMixin {
    @Shadow
    @Final
    @Mutable
    private RenderTarget mainRenderTarget;

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void fornax$captureUiLayer(GuiRenderer instance, Operation<Void> original) {
        if (!FrameGenPass.generatedFrameReady()) {
            UiLayerCapture.endFrame();
            // Item pipelines are shared with the world; GUI item draws must not be deferred into the
            // G-buffer. Set in a finally so a throwing HUD draw cannot strand the flag raised, which
            // would leave every subsequent frame's world items undeferred.
            DeferredGeometryPipelines.setGuiPhase(true);
            try {
                original.call(instance);
            } finally {
                DeferredGeometryPipelines.setGuiPhase(false);
            }
            return;
        }

        // Same shadow-and-reassign technique GameRendererMixin#fornax$ssaaBeginFrame/
        // #fornax$restoreNativeTarget use on this identical field: GuiRenderer.draw() re-reads
        // GameRenderer.mainRenderTarget() fresh on every call rather than caching a reference, so
        // swapping the shadowed field here is sufficient to redirect the HUD draw -- no separate
        // accessor or Minecraft-level indirection needed.
        //
        // uiTarget is sized from realTarget's OWN width/height, not any window/SsaaManager-derived
        // value: a window-based size read (SsaaManager.nativeWidth/Height) was tried first and
        // resolved to logical points rather than physical pixels on a Retina display, stretching the
        // composited HUD to a corner quadrant (live-caught). realTarget is exactly the target
        // vanilla's HUD draw would otherwise have used, so it is definitionally the correct size.
        RenderTarget realTarget = this.mainRenderTarget;
        this.mainRenderTarget = UiLayerCapture.uiTarget(realTarget.width, realTarget.height);
        DeferredGeometryPipelines.setGuiPhase(true);
        try {
            original.call(instance);
        } finally {
            DeferredGeometryPipelines.setGuiPhase(false);
            this.mainRenderTarget = realTarget;
        }

        // Frame-gen staging preparation: MUST run BEFORE the UiLayerCapture.compositeOnto(realTarget)
        // call below, which is about to bake the captured HUD onto realTarget for the REAL frame.
        // realTarget right here is exactly the scene-only (HUD-free) native color FrameGenSkyFillPass
        // needs -- the present seam used to sample mainRenderTarget() at present time instead, by
        // which point THIS composite call had already run, feeding the sky fill a HUD-contaminated
        // source and double-alpha-ing translucent HUD edges over sky (see FrameGenPresenter's own
        // class header). GBufferManager.getInstance() is guaranteed non-null here by the same
        // invariant GameRendererMixin#fornax$reconstruct already relies on (generatedFrameReady()
        // true implies a successful MetalFxUpscalePass ran this frame, which requires a live
        // GBuffer) -- null-checked anyway since a violation here must never throw out of this wrap
        // and disrupt the real frame's own HUD composite immediately below.
        //
        // MetalFxUpscalePass.sceneDepthView() is the same guarantee: generatedFrameReady() true
        // implies THIS frame's MetalFxUpscalePass.run() populated it (see that accessor's own
        // header), so it is never null here in practice; null-checked for the identical
        // never-throw-out-of-this-wrap reason.
        GBuffer gbuffer = GBufferManager.getInstance();
        var sceneDepthView = MetalFxUpscalePass.sceneDepthView();
        if (gbuffer != null && sceneDepthView != null) {
            FrameGenPresenter.prepareGeneratedFrame(
                    realTarget, gbuffer.getDepthView(), sceneDepthView, gbuffer.getMotionView());
        } else {
            FrameGenPresenter.logOnceMissingGBufferResources();
        }

        // Only reached if the HUD draw above completed without throwing: blend the captured layer
        // back over the real native target so the on-screen result is unchanged from vanilla.
        UiLayerCapture.compositeOnto(realTarget);
    }
}
