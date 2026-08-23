package dev.icehunter.fornax.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import dev.icehunter.fornax.pipeline.FornaxRenderPasses;
import dev.icehunter.fornax.pipeline.FornaxRenderState;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * DIAGNOSTIC, not a fix -- celestial rework decision, Bug A investigation (2026-08-11). Forces the
 * shadow pipeline's {@code RenderPipeline.Builder.withCull(boolean)} argument to {@code false},
 * disabling GPU-level rasterizer backface culling for {@link FornaxRenderPasses#SHADOW}/{@link
 * FornaxRenderPasses#SHADOW_CUTOUT} only, to test one specific hypothesis for why a caster known (by
 * {@code SHADOW_QUERY_3}'s own min/max reading) to be write-side-absent from the shadow map is
 * missing: {@code ShaderChunkRenderer.createShader} calls {@code .withCull(true)} unconditionally,
 * once, for every {@code TerrainRenderPass} -- {@link ShaderChunkRendererDeferredPipelineMixin}
 * (color targets, depth-stencil compare op), {@link ShaderChunkRendererShaderLocationMixin} (shader
 * source), and {@link ShaderChunkRendererConstantsMixin} (preprocessor defines) each wrap a DIFFERENT
 * call in the same method, but none of the three touches this one -- an uninspected inheritance, the
 * same shape the already-fixed {@link DefaultChunkRendererFaceCullingMixin} bug had, but at the GPU
 * rasterizer level rather than Sodium's CPU-side {@code getVisibleFaces} face-group heuristic that
 * fix already patches. That fix is real and necessary but is a DIFFERENT mechanism (whole
 * axis-aligned face-groups, culled by section-to-CAMERA orientation before draw-command generation)
 * from this one (individual triangles, culled by screen-space winding AFTER projection, by the GPU
 * itself) -- disabling one does not disable the other, and both apply independently to whatever
 * survives the caster list.
 *
 * <p><b>If the missing geometry appears with this diagnostic active</b>, the correct permanent
 * response is very likely a deliberate cull-mode CHOICE for the shadow pass (many shadow-mapping
 * techniques intentionally cull FRONT faces instead of back faces, trading some peter-panning for
 * fewer acne artifacts) rather than simply shipping {@code withCull(false)} and eating the overdraw
 * -- do not treat a positive result here as the fix itself. See the investigation's own report for
 * the reasoning that motivated this test (matches the {@code SHADOW_QUERY_3} min/max reading: a
 * culled-front-face map would contain only away-facing real surfaces, which by construction can
 * never occlude anything -- real content, zero occluders, exactly what was measured; also matches
 * the ORIGINAL "trees and grass cast shadows, blocks don't" symptom that motivated this whole
 * investigation, via a mechanism independent of and un-fixed by the face-culling round: cross-model
 * foliage is two-sided and survives a winding cull, axis-aligned block faces are not and do not).
 *
 * <p>Same targeting shape as {@link DefaultChunkRendererFaceCullingMixin} deliberately: gated on
 * {@link FornaxRenderState#isActive()} and {@link FornaxRenderPasses#isShadow}, so global player
 * settings and every other pass (SOLID/CUTOUT/TRANSLUCENT/water-prepass) are completely unaffected.
 * Remove this mixin (and its {@code fornax.mixins.json} entry) once the hypothesis is settled one way
 * or the other -- it exists to answer a question, not to ship.
 */
@Mixin(ShaderChunkRenderer.class)
public class ShaderChunkRendererShadowCullDiagnosticMixin {
    @ModifyArg(
            method = "createShader",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;withCull(Z)Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;"
            )
    )
    private boolean fornax$disableCullForShadowPasses(boolean cull, @Local(argsOnly = true) TerrainRenderPass pass) {
        if (!FornaxRenderState.isActive() || !FornaxRenderPasses.isShadow(pass)) {
            return cull;
        }
        return false;
    }
}
