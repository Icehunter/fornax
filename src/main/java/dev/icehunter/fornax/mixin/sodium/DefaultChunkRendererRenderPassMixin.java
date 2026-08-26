package dev.icehunter.fornax.mixin.sodium;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pass.shadow.ShadowMapManager;
import dev.icehunter.fornax.pass.water.WaterSurfaceManager;
import dev.icehunter.fornax.pipeline.FornaxRenderState;
import dev.icehunter.fornax.pipeline.FornaxRenderPasses;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.pipeline.GBuffer;
import dev.icehunter.fornax.pipeline.GBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.joml.Vector4fc;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Redirects deferred (SOLID/CUTOUT) terrain draws into a 6-attachment G-buffer {@link RenderPass}
 * instead of the single-attachment render target the official {@code DefaultChunkRenderer.render}
 * always uses, and shadow ({@link FornaxRenderPasses#SHADOW}) terrain draws into a depth-only-in-
 * intent {@link RenderPass} targeting {@link ShadowMapManager}'s depth target plus its one real,
 * unread dummy color attachment (a true zero-color-attachment pass is not achievable here -- see
 * {@link ShadowMapManager}'s javadoc), and the water pre-pass ({@link
 * FornaxRenderPasses#isWaterPrepass}) into a real 1-color+depth {@link RenderPass} targeting {@link
 * dev.icehunter.fornax.pass.water.WaterSurfaceManager}'s own normal+depth targets -- unlike the
 * shadow branch, {@code waterNormal} IS a real, sampled output, so no dummy attachment is needed
 * here. Translucent draws are left completely untouched.
 *
 * <p>{@code render(...)} calls the 5-argument {@code CommandEncoder.createRenderPass(Supplier,
 * GpuTextureView, Optional, GpuTextureView, OptionalDouble)} overload exactly once,
 * unconditionally -- no deferred/G-buffer or shadow branch exists upstream at all. {@code
 * RenderPassDescriptor} (the multi-attachment descriptor type both branches below build) is itself
 * an official, already-present blaze3d class -- Sodium's own {@code DefaultChunkRenderer} just
 * never uses it, since upstream Sodium has neither a G-buffer nor a shadow map to render into.
 *
 * <p>This wraps that one {@code createRenderPass} call rather than {@code @Overwrite}-replacing the
 * whole (~150-line) method: for deferred passes (see {@link FornaxRenderPasses#isDeferred}), the
 * wrapped call is skipped entirely and a {@code RenderPassDescriptor} targeting {@link GBuffer}'s
 * six color attachments + depth is built and passed to the same {@code encoder} instead; for the
 * shadow pass (see {@link FornaxRenderPasses#isShadow}), the wrapped call is likewise skipped and a
 * {@code RenderPassDescriptor} with {@link ShadowMapManager}'s depth attachment PLUS one real, unread
 * dummy color attachment (see {@link ShadowMapManager}'s javadoc -- a true zero-color-attachment pass
 * is not achievable against this Blaze3D version) is built instead; for translucent passes, the
 * original single-attachment call runs unmodified. Everything else in {@code render(...)} (the
 * draw-command batching loop, texture binds, index/vertex buffer setup) is untouched and shared by
 * all three branches.
 * Verified against Sodium mc26.2-0.9.0 (bf93ed83); no Sodium source is reproduced here.
 */
@Mixin(DefaultChunkRenderer.class)
public class DefaultChunkRendererRenderPassMixin {
    private static Object fornax$lastLoggedGBuffer;
    private static int fornax$gbufferGenerations;
    // Same once-per-generation instrumentation the deferred branch below carries, mirrored onto the
    // SHADOW branch: without it, every layer of the terrain-shadow chain (this file's shadow
    // branch, ShaderChunkRendererConstantsMixin's shadow branch) stays silent while every
    // equivalent layer of the deferred chain logs, so "no terrain shadow lines in the log" carries
    // no information about whether the shadow pass actually ran. This answers "does the terrain
    // shadow pass run this session" empirically.
    private static Object fornax$lastLoggedShadowView;
    private static int fornax$shadowGenerations;

    @WrapOperation(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"
            )
    )
    private RenderPass fornax$maybeDeferredRenderPass(
            CommandEncoder encoder,
            Supplier<String> label,
            GpuTextureView colorView,
            Optional<Vector4fc> originalClearColor,
            GpuTextureView depthView,
            OptionalDouble originalDepthClear,
            Operation<RenderPass> original,
            @Local(argsOnly = true) TerrainRenderPass renderPass
    ) {
        if (!FornaxRenderState.isActive()) {
            return original.call(encoder, label, colorView, originalClearColor, depthView, originalDepthClear);
        }

        if (FornaxRenderPasses.isShadow(renderPass)) {
            // A genuinely zero-color-attachment render pass is NOT legal against this Blaze3D
            // version: decompiling RenderPipeline.Builder.build() shows it silently substitutes a
            // single ColorTargetState.DEFAULT (RGBA8_UNORM) whenever zero withColorTargetState calls were
            // made (no Builder API forces a true zero-length color-target-state list), so the shadow
            // pipeline always reports color-target-state count 1 -- never 0. Decompiling
            // CommandEncoder.createRenderPass(RenderPassDescriptor) additionally shows it
            // unconditionally dereferences colorAttachments.getFirst().textureView() whenever the
            // list is non-empty, so a withUnusedColorAttachment() placeholder (null entry) crashes
            // there instead; the one attachment must be a real, non-null texture view. A real, unread
            // dummy color attachment (ShadowMapManager.getDummyColorView(), RGBA8_UNORM, matching the
            // shadow resolution) is therefore required to keep RenderPass.setPipeline's
            // attachment-count invariant satisfied.
            GpuTextureView shadowDepthView = ShadowMapManager.getView();
            GpuTextureView shadowDummyColorView = ShadowMapManager.getDummyColorView();
            if (shadowDepthView == null || shadowDummyColorView == null) {
                // ShadowMapManager.ensureSize() is expected to run earlier this same frame, mirroring
                // GBufferManager's own lifecycle; this should be unreachable in practice, but terrain
                // must never silently no-op if it somehow is.
                throw new IllegalStateException("Shadow terrain pass requested but no shadow map is built");
            }

            if (fornax$lastLoggedShadowView != shadowDepthView) {
                fornax$lastLoggedShadowView = shadowDepthView;
                fornax$shadowGenerations++;
                dev.icehunter.fornax.FornaxMod.LOGGER.info(
                        "[Fornax][diag] terrain SHADOW render pass: instance #{} ({}x{})",
                        fornax$shadowGenerations, shadowDepthView.getWidth(0), shadowDepthView.getHeight(0));
            }

            RenderPassDescriptor shadowDescriptor = RenderPassDescriptor.create(() -> "Terrain (Shadow)")
                    // Never sampled or copied -- see ShadowMapManager's javadoc for why this
                    // attachment exists at all. Not cleared: nothing reads it.
                    .withColorAttachment(shadowDummyColorView, Optional.empty())
                    // Clear happens once per frame in the shadow orchestration (mirroring how the
                    // G-buffer's depth clear lives in GraphRunner.prepare()), not per layer here.
                    .withDepthAttachment(shadowDepthView, OptionalDouble.empty())
                    .withRenderArea(new RenderPass.RenderArea(0, 0, shadowDepthView.getWidth(0), shadowDepthView.getHeight(0)));

            return encoder.createRenderPass(shadowDescriptor);
        }

        if (FornaxRenderPasses.isWaterPrepass(renderPass)) {
            GpuTextureView waterNormalView = WaterSurfaceManager.getNormalView();
            GpuTextureView waterDepthView = WaterSurfaceManager.getDepthView();
            if (waterNormalView == null || waterDepthView == null) {
                // WaterSurfaceManager.ensureSize() is expected to run earlier this same frame, from
                // SodiumWorldRendererOrchestrationMixin#fornax$renderWaterPrepass (mirroring
                // ShadowMapManager's own lifecycle); this should be unreachable in practice, but
                // terrain must never silently no-op if it somehow is.
                throw new IllegalStateException("Water pre-pass requested but no water surface target is built");
            }

            RenderPassDescriptor waterDescriptor = RenderPassDescriptor.create(() -> "Water (pre-pass)")
                    .withColorAttachment(waterNormalView, Optional.empty())   // exactly 1 -> LOCKSTEP
                    // Not cleared here -- WaterSurfaceManager.clear() runs once per frame from the
                    // orchestration mixin, immediately before the draw (mirroring ShadowMapManager's
                    // own per-frame clear() call, not GBuffer's prepare()-driven depth clear).
                    .withDepthAttachment(waterDepthView, OptionalDouble.empty())
                    .withRenderArea(new RenderPass.RenderArea(0, 0,
                            waterNormalView.getWidth(0), waterNormalView.getHeight(0)));
            return encoder.createRenderPass(waterDescriptor);
        }

        if (!FornaxRenderPasses.isDeferred(renderPass)) {
            return original.call(encoder, label, colorView, originalClearColor, depthView, originalDepthClear);
        }

        GBuffer gbuffer = GBufferManager.getInstance();
        if (gbuffer == null) {
            // GBufferManager.ensureSize() runs earlier this same frame (GraphRunner.prepare(),
            // driven by SodiumWorldRendererOrchestrationMixin); this should be unreachable in
            // practice, but terrain must never silently no-op if it somehow is.
            throw new IllegalStateException("Deferred terrain pass requested but no GBuffer is built");
        }

        if (fornax$lastLoggedGBuffer != gbuffer) {
            fornax$lastLoggedGBuffer = gbuffer;
            // ecv2 instrumentation: once per GBuffer rebuild -- proves the 6-attachment descriptor
            // (incl. a live albedoRaw view) is what terrain actually draws into.
            fornax$gbufferGenerations++;
            dev.icehunter.fornax.FornaxMod.LOGGER.info("[Fornax][diag] terrain G-buffer render pass: instance #{} ({}x{})",
                    fornax$gbufferGenerations, gbuffer.getWidth(), gbuffer.getHeight());
        }
        RenderPassDescriptor descriptor = GBufferManager.claimWriterDescriptor(
                () -> "Terrain (G-buffer)", gbuffer);

        return encoder.createRenderPass(descriptor);
    }
}
