package dev.icehunter.fornax.mixin.sodium;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pipeline.FornaxRenderState;
import dev.icehunter.fornax.pipeline.FornaxRenderPasses;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * Appends a {@code USE_DEFERRED} shader preprocessor constant to terrain shaders compiled for
 * deferred (G-buffer/MRT) passes -- SOLID/CUTOUT, per {@link FornaxRenderPasses#isDeferred} -- a
 * {@code SHADOW_PASS} constant (instead of {@code USE_DEFERRED}) for {@link
 * FornaxRenderPasses#SHADOW}, and a {@code USE_WATER_PREPASS} constant for {@link
 * FornaxRenderPasses#WATER_PREPASS}, but only while a pack is active ({@link
 * GraphRunner#isActive()}). With no pack loaded, terrain compiles against plain vanilla Sodium's own
 * shader (see {@code ShaderChunkRendererShaderLocationMixin}), which has no {@code USE_DEFERRED}/
 * {@code SHADOW_PASS}/{@code USE_WATER_PREPASS} branch and no G-buffer MRT outputs to write -- none
 * of these constants may be added in that case, or {@code RenderPipeline} construction would ask a
 * shader that never declares deferred outputs to satisfy a deferred color-target-state layout.
 *
 * <p>The official {@code private static List<String> createShaderConstants(TerrainRenderPass)}
 * only ever adds {@code USE_VERTEX_COMPRESSION} and {@code USE_FOG} -- no {@code USE_DEFERRED}/{@code
 * SHADOW_PASS} constant and no per-pass gating exists upstream ({@code TerrainRenderPass} has only
 * {@code isTranslucent()} and {@code supportsFragmentDiscard()}).
 *
 * <p>Must stay gated per-pass: TRANSLUCENT's pipeline has a single color-target-state (see {@code
 * ShaderChunkRendererDeferredPipelineMixin}), so a shader compiled with {@code USE_DEFERRED} for
 * that pass would attempt to write G-buffer MRT outputs a single-target render pass can't accept;
 * {@code SHADOW_PASS} is exclusive with {@code USE_DEFERRED} for the same reason -- {@link
 * FornaxRenderPasses#SHADOW} is non-translucent (see its javadoc) but must not pick up the deferred
 * branch's MRT constant, since its pipeline has a single default color-target-state, same as
 * SOLID/CUTOUT's own non-deferred fallback (see {@code ShaderChunkRendererDeferredPipelineMixin}),
 * not five. {@code USE_WATER_PREPASS} is exclusive with both for the same single-target reason --
 * {@link FornaxRenderPasses#WATER_PREPASS} is also non-translucent, but its pipeline's one
 * color-target-state is RGBA16_SNORM (matching {@code waterNormal}), a different format from either
 * SHADOW's RGBA8_UNORM fallback or TRANSLUCENT's own single target, so its shader must declare
 * exactly the {@code waterNormalOut} arm and nothing else.
 */
@Mixin(ShaderChunkRenderer.class)
public class ShaderChunkRendererConstantsMixin {
    @ModifyReturnValue(method = "createShaderConstants", at = @At("RETURN"))
    private static List<String> fornax$appendDeferredConstant(List<String> original, @Local(argsOnly = true) TerrainRenderPass pass) {
        if (!FornaxRenderState.isActive()) {
            return original;
        }

        if (FornaxRenderPasses.isShadow(pass)) {
            List<String> shadowResult = new ArrayList<>(original);
            shadowResult.add("SHADOW_PASS");
            fornax$addPagedAtlasConstants(shadowResult);
            shadowResult.add(fornax$generationConstant());
            return shadowResult;
        }

        if (FornaxRenderPasses.isWaterPrepass(pass)) {
            // Parallel to the USE_DEFERRED constant SOLID/CUTOUT get below -- compiles terrain.fsh's
            // mutually-exclusive USE_WATER_PREPASS output arm (single waterNormalOut, matching this
            // pass's 1-CTS pipeline -- see ShaderChunkRendererDeferredPipelineMixin) instead of either
            // the 5-attachment deferred arm or the forward single-attachment arm.
            List<String> waterResult = new ArrayList<>(original);
            waterResult.add("USE_WATER_PREPASS");
            fornax$addPagedAtlasConstants(waterResult);
            waterResult.add(fornax$generationConstant());
            return waterResult;
        }

        if (!FornaxRenderPasses.isDeferred(pass)) {
            // The forward/translucent arm (glass etc.) still samples the block atlas and gets the
            // paged constant so spilled translucent sprites resolve full-res too; it keeps its
            // original cache behavior otherwise (no generation constant added here historically).
            List<String> forwardResult = new ArrayList<>(original);
            fornax$addPagedAtlasConstants(forwardResult);
            return forwardResult;
        }

        List<String> result = new ArrayList<>(original);
        result.add("USE_DEFERRED");
        fornax$addPagedAtlasConstants(result);
        result.add(fornax$generationConstant());
        // ecv2 instrumentation: fires once per terrain shader compile -- proves WHEN pipelines
        // (re)compile and with which generation stamp. Cheap, keep until the channel verifies.
        dev.icehunter.fornax.FornaxMod.LOGGER.info("[Fornax][diag] terrain shader constants for pass {}: {}", pass, result);
        return result;
    }

    /**
     * Cache-busting constant (ecv2 attachment fix, round 2): blaze3d's device shader-module cache
     * is keyed (Identifier, type, defines) WITHOUT source text, so a pack republish that changes
     * shader TEXT under the same identifier+defines silently serves the stale SPIR-V module --
     * pipelines recompiled after the sourcesReady cache clear still drew with the old 5-output
     * fragment, never writing the gAlbedoRaw attachment (live-caught, cold-relaunch-verified).
     * Embedding the rebuild generation in the define NAME makes every republish a distinct cache
     * key; the define itself is never referenced by any shader (a harmless no-op in the source).
     */
    private static String fornax$generationConstant() {
        return "FORNAX_PACK_GEN_" + GraphRunner.shaderCacheGeneration();
    }

    /**
     * The paged block atlas's overflow page count, compiled into every terrain arm so
     * {@code fornax:block_atlas.glsl} collapses to a plain page-0 sample when unpaged and knows
     * how many strip cells remap when paged. Freshness is owned by {@code
     * BlockAtlasOverflow.rebuild}, which clears Sodium's terrain program cache whenever this value
     * changes between atlas generations -- the value read here is always the one the published
     * overflow layers actually have.
     */
    private static void fornax$addPagedAtlasConstants(List<String> constants) {
        constants.add("FORNAX_ATLAS_OVERFLOW_PAGES "
                + dev.icehunter.fornax.atlas.BlockAtlasOverflow.overflowPageCount());
        if (dev.icehunter.fornax.atlas.BlockAtlasOverflow.debugTint()) {
            // Live diagnostic (toggled by its debug keybind, which also clears the program cache):
            // the sampling include tints overflow-layer samples red and ghost samples yellow so an
            // artifact's provenance is readable straight off the screen.
            constants.add("FORNAX_ATLAS_DEBUG_TINT");
        }
    }
}
