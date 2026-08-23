package dev.icehunter.fornax.mixin.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pipeline.ForwardPipelineMap;
import dev.icehunter.fornax.pipeline.GeometryPipelineMap;
import dev.icehunter.fornax.pipeline.GeometryProgramSource;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Substitutes pack-supplied GLSL for vanilla's own when a pipeline maps to a {@code GeometrySlot} the
 * active pack claims (see {@link GeometryProgramSource} for why substitution happens at the source
 * level rather than by swapping compiled pipelines).
 *
 * <p>{@code ShaderManager.apply} clears the device pipeline cache and then recompiles every static
 * pipeline through a {@code ShaderSource} callback. Wrapping that one call is enough to redirect any
 * pipeline's source, for both the GL and Vulkan backends at once, and it re-runs on every resource
 * reload -- which is when a pack switch or compile-option change needs to take effect.
 *
 * <p>The wrapper is deliberately transparent: for any identifier the pack has no replacement for, it
 * delegates to vanilla's own {@code CompilationCache::getShaderSource}. That covers every unmapped
 * pipeline, every unclaimed slot, and the {@code #moj_import} includes a substituted shader pulls in,
 * which must keep resolving against vanilla's own assets.
 */
@Mixin(ShaderManager.class)
public class ShaderManagerGeometrySourceMixin {

    @WrapOperation(
            method = "apply",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/GpuDevice;precompilePipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lcom/mojang/blaze3d/shaders/ShaderSource;)Lcom/mojang/blaze3d/pipeline/CompiledRenderPipeline;"
            )
    )
    private CompiledRenderPipeline fornax$substitutePackGeometrySource(
            GpuDevice device, RenderPipeline pipeline, ShaderSource vanillaSource, Operation<CompiledRenderPipeline> original) {

        if (GeometryPipelineMap.slotOf(pipeline) != null || ForwardPipelineMap.slotOf(pipeline) != null) {
            // Claimed slots are shaded by a variant built in DeferredGeometryPipelines, which carries
            // both the pack's program and the matching pipeline state. Substituting here as well would
            // compile that program into VANILLA's own pipeline, which is a different pipeline layout
            // in both directions:
            //
            //   * a DEFERRED program writes five outputs, and vanilla's pipeline has one colour target
            //     -- locations 1..4 write attachments that do not exist. Observed live as entities
            //     rendering as flat garbage colour or vanishing entirely while their shadows stayed.
            //   * a FORWARD program declares u_Globals and u_PackOptions, and vanilla's pipeline
            //     declares neither -- a bind-group mismatch, which on Vulkan is a pipeline-layout
            //     error rather than anything legible.
            //
            // The forward half is checked here rather than left to fall through, even though the
            // `else` branch below would in fact decline it today (the routed ShaderSource asks
            // GeometryProgramSource.replacementIdentifierFor, whose one-argument form resolves the
            // slot through GeometryPipelineMap and so returns null for a forward pipeline). That
            // makes the whole else branch dead code for forward pipelines BY ACCIDENT -- it happens
            // to be right because of how a helper resolves a slot two files away. Widening the guard
            // makes it right on purpose, and survives someone widening that helper.
            //
            // Leaving vanilla's pipeline pristine also gives both substitution paths a
            // guaranteed-good fallback to fall back TO, which is what makes a broken pack program
            // survivable rather than a black frame.
            return original.call(device, pipeline, vanillaSource);
        }

        boolean[] substituted = {false};
        ShaderSource routed = (id, type) -> {
            Identifier replacement = GeometryProgramSource.replacementIdentifierFor(pipeline, type);
            if (replacement == null) {
                return vanillaSource.get(id, type);
            }
            // Ask vanilla's own cache for the pack identifier's source, so #moj_import directives are
            // resolved exactly as they are for any other shader. Falling back on null keeps a pack
            // whose file failed to load rendering as vanilla rather than compiling nothing at all.
            String packSource = vanillaSource.get(replacement, type);
            if (packSource == null) {
                return vanillaSource.get(id, type);
            }
            substituted[0] = true;
            return packSource;
        };

        CompiledRenderPipeline compiled = original.call(device, pipeline, routed);
        if (!substituted[0] || compiled.isValid()) {
            return compiled;
        }

        // The pack's GLSL did not compile. Recompile this pipeline from vanilla's own source rather
        // than handing back an invalid one.
        //
        // This is not politeness -- an invalid pipeline is genuinely dangerous. A broken pack shader
        // once took out every entity pipeline at once, which blacked out the frame and made Minecraft
        // demote itself to OpenGL *and persist that setting*, so the symptom outlived the fix by two
        // sessions and pointed at the wrong subsystem the whole time. Authoring shaders means
        // compile errors are routine; each one costing a manual backend reset is not survivable as a
        // workflow. Degrading to vanilla keeps the failure legible (loud log, wrong-looking entities)
        // and local to the pack that caused it.
        FornaxMod.LOGGER.error("[Fornax] Pack program for pipeline {} failed to compile -- falling back to"
                + " vanilla's own shader for it. The pack's GLSL error is logged above.", pipeline.getLocation());
        return original.call(device, pipeline, vanillaSource);
    }
}
