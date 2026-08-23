package dev.icehunter.fornax.pass;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Frame generation's UNIFIED real-frame fill (see {@code framegen_sky_fill.fsh} for the full
 * rationale of all three pixel classes it now covers -- sky, responsive/no-motion-vector pixels, and
 * edge disocclusion). Originally sky-only: sky/clouds carry no motion vectors, so {@code
 * MTLFXFrameInterpolator} hallucinates a dither/stipple pattern across them on generated frames; the
 * shader/pass were extended in place rather than adding a second pass, per this engine's "one
 * fullscreen dispatch, cheap" bias. This pass runs as a {@link FrameGenPresenter} composite step,
 * after {@link dev.icehunter.fornax.metalfx.FrameGenPass#copyGeneratedInto} has landed the
 * interpolated image in {@code staging} and BEFORE {@link UiLayerCapture#compositeOnto} stamps the
 * HUD on top (so a HUD element over a filled pixel is not overwritten back).
 *
 * <p>Mirrors {@code MetalFxReactiveMaskPass}'s fullscreen-pass shape (own {@link BindGroupLayout} +
 * {@link RenderPipeline}, {@code core/screenquad} vertex stage, nearest sampling) and {@link
 * UiLayerCapture#compositeOnto}'s "draw onto an existing destination" pattern, and reuses that same
 * pass's {@code u_SceneDepth}/{@code u_GBufferDepth} scene-depth-vs-G-buffer-depth predicate (see the
 * shader for the exact reused threshold) to identify vanilla-drawn, no-motion-vector content. Unlike
 * either mirrored pass, this one writes SELECTIVELY: classes 1/2/3b still do a full opaque replace
 * (alpha 1.0), but class 3's edge-band feather needs a genuine partial-coverage blend, so the target
 * now carries {@link BlendFunction#TRANSLUCENT} (straight, non-premultiplied alpha-over) instead of
 * the old {@code Optional.empty()} (hard opaque-overwrite) state -- the fragment shader still
 * discards every pixel matching none of the three classes, so the overwhelming common case (already-
 * generated terrain/entity pixels) does zero blend work, identical to before.
 */
public final class FrameGenSkyFillPass {
    private static final BindGroupLayout BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_GBufferDepth")
            .withSampler("u_SceneDepth")
            .withSampler("u_Motion")
            .withSampler("u_RealColor")
            .build();

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "framegen_sky_fill"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/framegen_sky_fill"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    private FrameGenSkyFillPass() {
    }

    /**
     * Replaces every pixel of {@code dest} matching one of the three fill classes (sky, responsive,
     * edge-disocclusion -- see the shader) with the matching pixel of {@code realColor}, sampling all
     * three predicate inputs at {@code dest}'s normalized UV. {@code gbufferDepth}/{@code sceneDepth}/
     * {@code motion} are all render-res (the SAME textures {@code MetalFxReactiveMaskPass} and {@code
     * MetalFxFrameInterpolator} consumed this frame); {@code realColor} is native-res. Render-res
     * versus native-res is not a problem here: every texture is sampled by normalized {@code
     * texCoord} in the shader, not texel-for-texel.
     */
    public static void compositeOnto(RenderTarget dest, GpuTextureView gbufferDepth,
            GpuTextureView sceneDepth, GpuTextureView motion, GpuTextureView realColor) {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(
                () -> "Fornax Frame Gen Fill", dest.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(PIPELINE);
            pass.bindTexture("u_GBufferDepth", gbufferDepth, nearest);
            pass.bindTexture("u_SceneDepth", sceneDepth, nearest);
            pass.bindTexture("u_Motion", motion, nearest);
            pass.bindTexture("u_RealColor", realColor, nearest);
            pass.draw(3, 1, 0, 0);
        }
    }
}
