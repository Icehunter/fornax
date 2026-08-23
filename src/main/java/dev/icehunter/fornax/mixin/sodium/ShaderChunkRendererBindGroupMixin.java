package dev.icehunter.fornax.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import dev.icehunter.fornax.pipeline.GeometryInputs;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Appends Fornax's G-buffer-related bind-group slots (u_NormalTex, u_MaterialTex samplers, the
 * u_PbrSettings uniform buffer, and the reserved u_GeomInput0..N-1 geometry-input samplers) onto
 * Sodium's shared terrain BIND_GROUP layout.
 *
 * <p>{@code ShaderChunkRenderer.BIND_GROUP} is a {@code public static final BindGroupLayout} built
 * inside {@code <clinit>} as {@code BindGroupLayout.builder().withSampler("u_LightTex")
 * .withSampler("u_BlockTex").withUniform("u_Globals", UniformType.UNIFORM_BUFFER)
 * .withUniform("u_SectionTimeInfo", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT).build()} -- no
 * {@code u_NormalTex}/{@code u_MaterialTex}/{@code u_PbrSettings}/{@code u_GeomInput*} slots exist
 * upstream. This mixin wraps the single {@code BindGroupLayout$Builder.build()} invocation inside
 * the static initializer to append those slots, so the layout stays compatible with the extra
 * texture binds {@link DefaultChunkRendererTextureBindMixin} performs.
 */
@Mixin(ShaderChunkRenderer.class)
public class ShaderChunkRendererBindGroupMixin {
    @WrapOperation(method = "<clinit>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/BindGroupLayout$Builder;build()Lcom/mojang/blaze3d/pipeline/BindGroupLayout;"))
    private static BindGroupLayout fornax$appendGBufferBindings(BindGroupLayout.Builder builder, Operation<BindGroupLayout> original) {
        builder.withSampler("u_NormalTex")
                .withSampler("u_MaterialTex")
                // The paged block atlas's overflow layers (sampler2DArray, one per lane) --
                // declared unconditionally like every slot here (the layout is baked at
                // class-init, before any pack loads); DefaultChunkRendererTextureBindMixin binds
                // each lane's real overflow view or its neutral array when unpaged. Terrain now
                // sits at 15 of Metal's 16 per-stage samplers (see GeometryInputs' budget doc).
                .withSampler("u_BlockPagesTex")
                .withSampler("u_NormalPagesTex")
                .withSampler("u_MaterialPagesTex")
                .withUniform("u_PbrSettings", UniformType.UNIFORM_BUFFER);
        for (int i = 0; i < GeometryInputs.RESERVED; i++) {
            builder.withSampler(GeometryInputs.slot(i));
        }

        return original.call(builder);
    }
}
