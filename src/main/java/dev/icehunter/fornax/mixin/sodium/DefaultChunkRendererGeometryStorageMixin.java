package dev.icehunter.fornax.mixin.sodium;

import dev.icehunter.fornax.pipeline.FornaxRenderPasses;
import dev.icehunter.fornax.pipeline.FornaxRenderState;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects {@code DefaultChunkRenderer.render}'s two {@code RenderRegion.getStorage(TerrainRenderPass)}
 * calls so a shadow-pass draw ({@link FornaxRenderPasses#SHADOW}/{@link
 * FornaxRenderPasses#SHADOW_CUTOUT}) reads the ALREADY-BUILT geometry storage of the corresponding
 * vanilla pass (SOLID/CUTOUT, via {@link FornaxRenderPasses#sourceGeometryPass}) instead of its own
 * -- which, being a Fornax-only {@code TerrainRenderPass} instance Sodium's own mesh-build/upload
 * pipeline never populates storage for, would otherwise always resolve to {@code null} and silently
 * draw zero geometry every frame, forever, with no error.
 *
 * <p><b>Decompile evidence (Sodium mc26.2-0.9.0, bf93ed83):</b> {@code RenderRegionManager}'s
 * per-section mesh upload loop -- the only code that ever calls {@code
 * RenderRegion.createStorage(TerrainRenderPass)}, populating the identity-keyed {@code
 * Map<TerrainRenderPass, SectionRenderDataStorage>} {@code DefaultChunkRenderer.render} reads via
 * {@code RenderRegion.getStorage} -- iterates ONLY {@code DefaultTerrainRenderPasses.ALL} ({@code
 * {SOLID, CUTOUT, TRANSLUCENT}}). There is no extension point for a fourth, engine-added {@code
 * TerrainRenderPass} to ever receive its own storage entry. See {@link
 * FornaxRenderPasses#SHADOW}'s javadoc for the full trace (including why the pipeline/render-pass
 * routing must stay keyed on the shadow passes' OWN identity via {@link FornaxRenderPasses#isShadow}
 * even though this one lookup is redirected).
 *
 * <p>{@code render(...)} calls {@code region.getStorage(renderPass)} exactly twice per invocation (a
 * pre-pass batch-sizing loop, then the actual draw loop) -- both call sites share this one {@code
 * @Redirect} (no {@code ordinal} restricts it to one), so both consistently see the same substituted
 * storage within a single shadow draw. For every non-shadow pass, {@link
 * FornaxRenderPasses#sourceGeometryPass} is an identity passthrough, so SOLID/CUTOUT/TRANSLUCENT
 * draws are completely unaffected.
 * Verified against Sodium mc26.2-0.9.0 (bf93ed83); no Sodium source is reproduced here.
 */
@Mixin(DefaultChunkRenderer.class)
public class DefaultChunkRendererGeometryStorageMixin {
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;getStorage(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)Lnet/caffeinemc/mods/sodium/client/render/chunk/data/SectionRenderDataStorage;"
            )
    )
    private SectionRenderDataStorage fornax$shadowGeometrySource(RenderRegion region, TerrainRenderPass pass) {
        if (!FornaxRenderState.isActive()) {
            return region.getStorage(pass);
        }
        return region.getStorage(FornaxRenderPasses.sourceGeometryPass(pass));
    }
}
