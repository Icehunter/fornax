package dev.icehunter.fornax.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.atlas.BlockAtlasOverflow;
import dev.icehunter.fornax.atlas.MaterialMapAtlas;
import dev.icehunter.fornax.atlas.LabPbrNeutralTextures;
import dev.icehunter.fornax.atlas.NormalMapAtlas;
import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pipeline.GeometryInputs;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Binds Fornax's normal-map and material-map atlas textures, plus the pack-declared geometry-input
 * slots ({@code u_GeomInput0..GeometryInputs.RESERVED-1}, see {@link GraphRunner#geometryInputView}),
 * right after the official {@code DefaultChunkRenderer.render()} binds {@code u_BlockTex}.
 *
 * <p>{@code render()} binds exactly two textures in order -- {@code u_LightTex} then
 * {@code u_BlockTex}, both through {@code RenderPass.bindTexture(String, GpuTextureView,
 * GpuSampler)} -- with nothing else bound before the per-region draw loop starts (no {@code
 * u_NormalTex}/{@code u_MaterialTex}/{@code u_PbrSettings} anywhere in the official method).
 * Injecting immediately after the SECOND {@code bindTexture} call (ordinal 1, shifted AFTER) lands
 * precisely after {@code u_BlockTex} and before the draw loop.
 *
 * <p>Falls back to dedicated semantic-neutral textures when a corresponding Fornax atlas has not
 * been built. Albedo is never valid normal/material data.
 * The geometry-input slots have their own, separate fallback chain -- see {@link
 * GraphRunner#geometryInputView} and this method's own comment at that bind loop.
 */
@Mixin(DefaultChunkRenderer.class)
public class DefaultChunkRendererTextureBindMixin {
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER
            )
    )
    private void fornax$bindGBufferAtlasTextures(
            ChunkRenderMatrices matrices,
            ChunkRenderListIterable renderLists,
            TerrainRenderPass renderPass,
            CameraTransform camera,
            FogParameters fogParameters,
            boolean useBlockFaceCulling,
            GpuSampler terrainSampler,
            GpuBufferSlice uniformBuffer,  // 0.9.1: render() signature carries a slice
            GpuBuffer sectionTimeInfo,
            CallbackInfo ci,
            @Local RenderPass pass
    ) {
        NormalMapAtlas normalMapAtlas = NormalMapAtlas.getInstance();
        // Match magnification and minification with LINEAR/LINEAR. This removes the hardware
        // filtering discontinuity at the one-texel footprint boundary that produced the ring.
        // mipmap=false independently keeps this atlas on its authored base level; that retained
        // grid/lattice choice is not the ring fix. Entity and block-entity bindings use the same
        // normal-filter contract in LabPbrGeometryBindings.
        GpuSampler normalTexSampler = RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
        pass.bindTexture("u_NormalTex", normalMapAtlas != null
                ? normalMapAtlas.getTextureView() : LabPbrNeutralTextures.normalView(),
                normalTexSampler);

        // labPBR `_s` is DATA (smoothness, F0, porosity, emission), and blending a conductor's F0
        // code into a dielectric's across a texel boundary invents a material that neither texel
        // describes. That reasoning was already written here and only half applied: magnification
        // was NEAREST while MINIFICATION was left LINEAR, and a 256x sidecar is magnified only
        // within THREE BLOCKS of the camera (256 texels across a face subtending 771/distance
        // pixels at 1080p, 70 degree FOV), so the NEAREST path essentially never ran. Past three
        // blocks the LINEAR filter blended the code with the matrix on 2.9%..5.7% of every ore
        // face, at a mean invented F0 of 0.44 against the stone's 0.039 -- the reported white rim,
        // and the reason coal ore alone looked right: it is the one ore with no metal texels.
        //
        // NEAREST on BOTH ends, plus a real mip chain, so the hardware never interpolates two
        // labPBR values at all. The chain does every average that gets made, per channel and
        // class-aware -- see LabPbrMaterialReduction, which is also why turning this flag on is
        // safe now and was not before: a plain box chain manufactures the same invented code at
        // 6% of texels by level 1 and 94% by level 6.
        //
        // mipmap=true is load-bearing beyond supplying the levels: with it false SamplerCache
        // clamps the sampler's maxLod to 0.0 (its OptionalDouble.of(0.0) branch), so every level
        // above the base would be uploaded and never read.
        //
        // The remaining hardware interpolation is BETWEEN levels -- FilterMode.NEAREST maps to
        // GL_NEAREST_MIPMAP_LINEAR (GlSampler, javap-verified), not to _MIPMAP_NEAREST, and there
        // is no way to ask SamplerCache for the latter. That blend re-invents the code wherever two
        // levels disagree on a texel's class, measured at 0.3%..16% of an ore face and WORSE than
        // today at 10 chunks. terrain.fsh closes it from the other side by fetching at an integer
        // LOD, which drives the inter-level weight to exactly zero; the terrain shader owns that
        // integer-LOD fetch.
        MaterialMapAtlas materialMapAtlas = MaterialMapAtlas.getInstance();
        GpuSampler materialTexSampler = RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, true);
        pass.bindTexture("u_MaterialTex", materialMapAtlas != null
                ? materialMapAtlas.getTextureView() : LabPbrNeutralTextures.materialView(),
                materialTexSampler);

        // Paged overflow lanes: each bound with the SAME sampler its page-0 lane uses -- a
        // remapped page sample must filter exactly like the page-0 sample it replaces. Each falls
        // back to its lane's 1x1x1 neutral array when unpaged (or that lane failed to build);
        // skips the bind entirely only where even that cannot exist (no device yet / non-Vulkan
        // backend), mirroring the geometry slots below -- the shader constant is 0 there, so
        // nothing samples the missing binding.
        GpuTextureView pagesView = BlockAtlasOverflow.albedoView();
        if (pagesView == null) {
            pagesView = BlockAtlasOverflow.neutralArrayView(BlockAtlasOverflow.NEUTRAL_BLACK_RGBA);
        }
        if (pagesView != null) {
            pass.bindTexture("u_BlockPagesTex", pagesView, terrainSampler);
        }
        GpuTextureView normalPagesView = normalMapAtlas != null ? normalMapAtlas.pagesView() : null;
        if (normalPagesView == null) {
            normalPagesView = BlockAtlasOverflow.neutralArrayView(BlockAtlasOverflow.NEUTRAL_NORMAL_RGBA);
        }
        if (normalPagesView != null) {
            pass.bindTexture("u_NormalPagesTex", normalPagesView, normalTexSampler);
        }
        GpuTextureView materialPagesView = materialMapAtlas != null ? materialMapAtlas.pagesView() : null;
        if (materialPagesView == null) {
            materialPagesView = BlockAtlasOverflow.neutralArrayView(BlockAtlasOverflow.NEUTRAL_MATERIAL_RGBA);
        }
        if (materialPagesView != null) {
            pass.bindTexture("u_MaterialPagesTex", materialPagesView, materialTexSampler);
        }

        // Round A, Task 3: bind each reserved geometry-input slot to its pack-resolved view, in
        // declaration order (GraphRunner.geometryInputView already falls back to the noise texture
        // for an undeclared slot, or for a declared one whose resolution is transiently unavailable
        // this frame -- a disabled/mid-reload target, never garbage). Ungated by pass type -- opaque,
        // cutout, and translucent terrain draws all share this bind group (it is a process-wide fixed
        // shape baked in at class-init, before any pack loads -- see GeometryInputs' own doc), so
        // every draw must supply every reserved slot. The noise sampler (LINEAR+REPEAT) stays the
        // shared geometry sampler for every slot -- a per-input filter syntax is deliberately not
        // built until a second filter is actually needed (same YAGNI precedent as builtin.noise in
        // the fullscreen path).
        GpuSampler geomSampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR, false);
        for (int i = 0; i < GeometryInputs.RESERVED; i++) {
            GpuTextureView view = GraphRunner.geometryInputView(GeometrySlot.TERRAIN, i);
            if (view != null) {
                pass.bindTexture(GeometryInputs.slot(i), view, geomSampler);
            }
            // else: even the noise fallback is unavailable (no GPU device yet this session) --
            // skip this slot's bind rather than pass null, mirroring how the normal/material atlas
            // binds above always resolve to SOME non-null fallback instead of a null bind.
        }
    }
}
