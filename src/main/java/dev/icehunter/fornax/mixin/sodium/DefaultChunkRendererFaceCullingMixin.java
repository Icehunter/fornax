package dev.icehunter.fornax.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import dev.icehunter.fornax.pipeline.FornaxRenderPasses;
import dev.icehunter.fornax.pipeline.FornaxRenderState;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Forces {@code DefaultChunkRenderer.render}'s private {@code fillCommandBuffer} helper to treat
 * every block face as visible during a shadow-pass draw ({@link FornaxRenderPasses#SHADOW}/{@link
 * FornaxRenderPasses#SHADOW_CUTOUT}), instead of running Sodium's normal block-face culling.
 *
 * <p><b>Why:</b> when the global {@code SodiumClientMod.options().performance.useBlockFaceCulling}
 * is on (Sodium's default), {@code fillCommandBuffer} calls {@code
 * getVisibleFaces(camera.intX, camera.intY, camera.intZ, ...)} to strip whichever of a section's six
 * axis-aligned face groups point away from {@code camera}'s integer position -- a valid optimization
 * only when the pass actually renders from that same camera's viewpoint. The shadow pass does not:
 * {@code SodiumWorldRendererOrchestrationMixin.fornax$renderShadowPass} builds its {@code
 * CameraTransform} from the player's position (correct for vertex-precision translation, see that
 * mixin's own javadoc) while the pass itself projects through the light matrix {@code
 * u_SunViewProj}. So solid terrain faces were being culled by orientation-to-player, unrelated to
 * orientation-to-sun -- a face pointing away from the player got dropped from the shadow draw batch,
 * wrote no depth, and so occluded nothing, even when it faced the sun and should have cast a shadow.
 * Foliage/cutout quads were unaffected because {@code ModelQuadFacing.fromNormal} classifies any
 * non-axis-aligned quad (cross-model grass/leaves) as {@code UNASSIGNED}, and {@code
 * getVisibleFaces} unconditionally includes that bit in its result -- this is the entire
 * solid-vs-foliage split observed in Plague's RT(Sun) Shadow debug view.
 *
 * <p><b>Decompile evidence (Sodium mc26.2-0.9.1, the exact {@code sodium_maven_version} dependency,
 * cfr-decompiled):</b> {@code render} calls the private static {@code fillCommandBuffer(MultiDrawBatch,
 * RenderRegion, SectionRenderDataStorage, ChunkRenderList, CameraTransform, TerrainRenderPass,
 * boolean useBlockFaceCulling, boolean useIndexedTessellation)} once per render list entry.
 * {@code useBlockFaceCulling} (argument index 6) gates the {@code getVisibleFaces} call directly;
 * forcing it to {@code false} here for shadow passes makes {@code fillCommandBuffer} take its own
 * existing {@code ModelQuadFacing.ALL} fallback branch (the same branch a player with the culling
 * option disabled would take) rather than duplicating that constant in this mixin. Global player
 * settings and every other pass (SOLID/CUTOUT/TRANSLUCENT/water-prepass) are unaffected: {@code
 * FornaxRenderPasses#isShadow} covers only {@link FornaxRenderPasses#SHADOW}/{@link
 * FornaxRenderPasses#SHADOW_CUTOUT}, and {@code SodiumClientMod.options()} itself is never touched.
 * Verified against Sodium mc26.2-0.9.1; no Sodium source is reproduced here.
 */
@Mixin(DefaultChunkRenderer.class)
public class DefaultChunkRendererFaceCullingMixin {
    @ModifyArg(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer;"
                            + "fillCommandBuffer(Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;"
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/data/SectionRenderDataStorage;"
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderList;"
                            + "Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;"
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;ZZ)V"
            ),
            index = 6
    )
    private boolean fornax$disableFaceCullingForShadowPasses(boolean useBlockFaceCulling,
            @Local(argsOnly = true) TerrainRenderPass renderPass) {
        if (!FornaxRenderState.isActive() || !FornaxRenderPasses.isShadow(renderPass)) {
            return useBlockFaceCulling;
        }
        return false;
    }
}
