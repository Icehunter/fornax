package dev.icehunter.fornax.mixin.sodium;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pipeline.FornaxRenderState;
import dev.icehunter.fornax.pipeline.FornaxRenderPasses;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Builds the 6-attachment G-buffer color-target-state set for deferred (SOLID/CUTOUT) terrain
 * pipelines, replacing the single {@code ColorTargetState.DEFAULT} the official (non-translucent)
 * branch uses. The shadow pass (see {@code FornaxRenderPasses#SHADOW}) is deliberately NOT
 * special-cased here -- see the LIVE-FIX comment inline below for why the pipeline must keep exactly
 * one {@code ColorTargetState} (matching the real, unread dummy color attachment {@code
 * DefaultChunkRendererRenderPassMixin} now builds for the shadow render pass), even though {@code
 * shadow.fsh} has no {@code out} variable and never writes it.
 *
 * <p>{@code ShaderChunkRenderer.createShader(String, TerrainRenderPass)} calls the single-argument
 * {@code RenderPipeline.Builder.withColorTargetState(ColorTargetState)} overload exactly once,
 * branching only on {@code isTranslucent()} -- {@code new ColorTargetState(Optional.of(TRANSLUCENT),
 * RGBA8_UNORM, -1)} when true, {@code ColorTargetState.DEFAULT} when false. There is no per-pass
 * {@code isDeferred()}/{@code isShadow()} upstream at all (see {@code FornaxRenderPasses}), and no
 * indexed multi-attachment call anywhere in the official method.
 *
 * <p>Rather than {@code @Overwrite}-replacing the whole (~40-line) method, this wraps that single
 * call: for deferred passes (see {@link FornaxRenderPasses#isDeferred}), the six indexed {@code
 * withColorTargetState(int, ColorTargetState)} calls (an overload that already exists on the same
 * official {@code RenderPipeline.Builder}) are issued directly against the same builder instance
 * instead of delegating to the wrapped call, matching {@code GBuffer}'s texture formats and {@code
 * terrain.fsh}'s {@code layout(location = 0..5)} outputs under {@code USE_DEFERRED}
 * (gNormal/gAlbedo/gMaterial/gAo/gMotion/gAlbedoRaw); for the shadow pass, {@code isDeferred(pass)} is already
 * {@code false} (see {@code FornaxRenderPasses#isDeferred}), so it falls through to the same original
 * single-target call as SOLID/CUTOUT, unmodified, giving {@code ColorTargetState.DEFAULT}; for
 * translucent passes, the original single-target call also runs unmodified. This keeps the mixin's
 * blast radius to exactly the branch that differs (deferred), with zero risk of silently drifting
 * from any future upstream change to the rest of {@code createShader}.
 *
 * <p>The water pre-pass ({@link FornaxRenderPasses#isWaterPrepass}) IS explicitly special-cased,
 * unlike the shadow pass: it needs a real, single {@code ColorTargetState} matching {@code
 * waterNormal}'s RGBA16_SNORM format (the shadow pass's fallthrough {@code ColorTargetState.DEFAULT}
 * is RGBA8_UNORM, wrong format for a signed wave-normal target), so it gets its own explicit
 * single-{@code withColorTargetState} branch rather than falling through to the wrapped call. This
 * is still exactly ONE color-target-state -- the minimum LOCKSTEP footprint, matching the single
 * {@code waterNormal} color attachment {@code DefaultChunkRendererRenderPassMixin} builds for this
 * identity -- checked BEFORE {@code isDeferred(pass)} since {@code WATER_PREPASS} is excluded from
 * that predicate (see {@code FornaxRenderPasses#isDeferred}) and would otherwise fall through to the
 * same single-default-target branch as the shadow pass, which is the wrong format.
 *
 * <p>Also wraps the same method's single, unconditional {@code
 * RenderPipeline.Builder.withDepthStencilState(DepthStencilState)} call: for the shadow pass, {@code
 * DepthStencilState.DEFAULT}'s reversed-Z {@code GREATER_THAN_OR_EQUAL} test is substituted with a
 * forward-Z {@code LESS_THAN_OR_EQUAL} test, matching {@code ShadowCamera}'s forward {@code [0,1]}
 * ortho projection and {@code ShadowMapManager}'s {@code 1.0f} far/no-occluder clear -- see {@link
 * #fornax$shadowDepthStencilState}'s javadoc for the full derivation. SOLID/CUTOUT/TRANSLUCENT keep
 * {@code DepthStencilState.DEFAULT} unmodified.
 * Verified against Sodium mc26.2-0.9.0 (bf93ed83); no Sodium source is reproduced here.
 */
@Mixin(ShaderChunkRenderer.class)
public class ShaderChunkRendererDeferredPipelineMixin {
    @WrapOperation(
            method = "createShader",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;withColorTargetState(Lcom/mojang/blaze3d/pipeline/ColorTargetState;)Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;"
            )
    )
    private RenderPipeline.Builder fornax$deferredColorTargets(RenderPipeline.Builder builder, ColorTargetState state, Operation<RenderPipeline.Builder> original, @Local(argsOnly = true) TerrainRenderPass pass) {
        if (!FornaxRenderState.isActive()) {
            return original.call(builder, state);
        }

        // LIVE-FIX (see shadowmap-livefix-2-report.md): the shadow pass used to drop this wrapped
        // call entirely here (depth-only, "no color targets at all"), but decompiling
        // RenderPipeline.Builder.build() (game jar) shows dropping the call does NOT yield a
        // zero-length color-target-state array -- build() silently substitutes a single
        // ColorTargetState.DEFAULT whenever zero withColorTargetState calls were made, and there is
        // no Builder API to force a genuine zero-length list. So the shadow pipeline was already
        // reporting color-target-state count 1 even with the call dropped -- while
        // DefaultChunkRendererRenderPassMixin's render pass reported 0 color attachments, tripping
        // RenderPass.setPipeline's "attachment count must match" check at runtime. Falling through to
        // the unmodified original call below (SHADOW.isTranslucent() == false, same as
        // SOLID/CUTOUT) gives the *same* ColorTargetState.DEFAULT explicitly instead of relying on
        // that implicit substitution, matching the real (now non-empty) dummy color attachment
        // DefaultChunkRendererRenderPassMixin builds for the shadow render pass.
        if (FornaxRenderPasses.isWaterPrepass(pass)) {
            // LOCKSTEP: exactly ONE color-target-state, matching the single waterNormal color
            // attachment DefaultChunkRendererRenderPassMixin builds for this identity. RGBA16_SNORM =
            // signed wave world-normal in xyz, water-present flag in a. Reversed-Z depth-stencil is
            // the inherited DEFAULT (main-camera projection), NOT the shadow forward-Z substitution
            // -- fornax$shadowDepthStencilState below is scoped to isShadow(pass), so WATER_PREPASS
            // falls through it unmodified.
            builder.withColorTargetState(0, new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA16_SNORM, ColorTargetState.WRITE_ALL));
            return builder;
        }

        if (!FornaxRenderPasses.isDeferred(pass)) {
            return original.call(builder, state);
        }

        builder.withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_SNORM, ColorTargetState.WRITE_ALL)); // gNormalOut
        builder.withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));  // gAlbedoOut
        builder.withColorTargetState(2, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));  // gMaterialOut
        builder.withColorTargetState(3, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));   // gAoOut (RGBA8 since ecv2: R = AO, GBA = raw albedo)
        builder.withColorTargetState(4, new ColorTargetState(Optional.empty(), GpuFormat.RG16_FLOAT, ColorTargetState.WRITE_ALL));    // gMotionOut

        return builder;
    }

    /**
     * Substitutes a forward-Z {@code DepthStencilState} for the shadow pass in place of the wrapped
     * call's {@code DepthStencilState.DEFAULT} argument, which is {@code new
     * DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true)} (decompiled from the game jar) --
     * vanilla's reversed-Z convention for the main camera (clears to {@code 0.0f} = far, nearer
     * fragments have a larger NDC z, so {@code GREATER_THAN_OR_EQUAL} keeps the nearest). {@code
     * ShaderChunkRenderer.createShader(String, TerrainRenderPass)} calls the single-argument {@code
     * RenderPipeline.Builder.withDepthStencilState(DepthStencilState)} overload exactly once,
     * unconditionally, with no per-pass branch of its own -- see {@code
     * shadowmap-task-5-report.md}'s "Engine fix" section for the full decompile evidence this mixin
     * addition is based on.
     *
     * <p>{@link dev.icehunter.fornax.pass.shadow.ShadowCamera}'s ortho projection is built with
     * JOML's {@code setOrtho(..., zZeroToOne = true)} -- forward-Z, linear-depth {@code [0, 1]}, NOT
     * reversed: NDC {@code z = 0} at the light-space near plane, {@code z = 1} at the far plane, and
     * depth increases monotonically with distance from the light. Reversed-Z's whole rationale
     * (redistributing floating-point precision toward the near plane to compensate for a
     * perspective projection's non-linear 1/z depth distribution) does not apply here at all --
     * {@code ShadowCamera} is an *orthographic* projection, already perfectly linear, so there is no
     * precision benefit reversed-Z would even buy back. {@link
     * dev.icehunter.fornax.pass.shadow.ShadowMapManager} clears the shadow depth target to literal
     * {@code 1.0f} ("no occluder" / far sentinel), matching this forward convention. Under the
     * inherited {@code GREATER_THAN_OR_EQUAL} test, every real shadow-caster fragment (whose NDC
     * {@code z} is essentially always {@code < 1.0}) fails {@code newZ >= storedZ} against the {@code
     * 1.0} clear and is discarded by the rasterizer before it ever reaches the depth attachment --
     * the shadow map would read back "empty" everywhere, forever, regardless of how the resolve
     * shader compares against it.
     *
     * <p>The fix is a same-shaped substitution as {@code CompareOp.LESS_THAN_OR_EQUAL}: the nearer
     * fragment to the light (smaller {@code z}) wins, matching forward-Z's "smaller = closer to the
     * light" convention and correctly overwriting the {@code 1.0} far/no-occluder clear with real
     * occluder depth. {@code writeDepth = true} is unchanged from {@code DEFAULT} -- the shadow pass
     * still needs to write depth, only the comparison direction differs. This substitution is scoped
     * to {@link FornaxRenderPasses#isShadow}, so SOLID/CUTOUT/TRANSLUCENT keep {@code
     * DepthStencilState.DEFAULT} completely unmodified -- combined with {@code
     * ShaderChunkRenderer}'s identity-keyed {@code TerrainRenderPass -> RenderPipeline} pipeline
     * cache (see {@link FornaxRenderPasses#SHADOW}'s javadoc), the substitute state can only ever be
     * baked into the two shadow-identity pipeline entries; it has no way to leak into (or replace)
     * the separately-cached, separately-built pipeline entries the three vanilla passes compile and
     * cache under their own distinct {@code TerrainRenderPass} identities.
     */
    @WrapOperation(
            method = "createShader",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;withDepthStencilState(Lcom/mojang/blaze3d/pipeline/DepthStencilState;)Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;"
            )
    )
    private RenderPipeline.Builder fornax$shadowDepthStencilState(RenderPipeline.Builder builder, DepthStencilState state, Operation<RenderPipeline.Builder> original, @Local(argsOnly = true) TerrainRenderPass pass) {
        if (!FornaxRenderState.isActive()) {
            return original.call(builder, state);
        }

        if (FornaxRenderPasses.isShadow(pass)) {
            // Forward-Z [0,1] light-space ortho (linear depth -- reversed-Z's precision
            // redistribution buys nothing for an orthographic projection); clear = 1.0 = far/no
            // occluder. GREATER_THAN_OR_EQUAL (DEFAULT) would reject every real caster fragment
            // (z < 1.0) against that clear -- see this method's javadoc for the full derivation.
            return original.call(builder, new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true));
        }

        return original.call(builder, state);
    }
}
