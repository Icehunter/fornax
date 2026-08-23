package dev.icehunter.fornax.pass.ssaa;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Box-filter downsample from SsaaManager's scaled render target back to native resolution,
 * averaging the supersampled pixels rather than a plain bilinear blit.
 */
public final class SsaaDownsamplePass {
    private static final BindGroupLayout BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_Source")
            .withUniform("u_DownsampleSettings", UniformType.UNIFORM_BUFFER)
            .build();

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "ssaa_downsample"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/ssaa_downsample"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    // u_DownsampleSettings std140 layout: int u_TapRadius (4) + <4 bytes padding, vec2 needs 8-byte
    // alignment> + vec2 u_SourceTexelSize (8, occupying bytes 8-16) = 16 bytes total.
    private static final int DOWNSAMPLE_SETTINGS_BUFFER_SIZE = 16;

    private static MappableRingBuffer downsampleSettingsData;

    private SsaaDownsamplePass() {
    }

    private static MappableRingBuffer downsampleSettingsBuffer() {
        if (downsampleSettingsData == null) {
            downsampleSettingsData = new MappableRingBuffer(() -> "Sodium SSAA downsample settings buffer",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, DOWNSAMPLE_SETTINGS_BUFFER_SIZE);
        }

        return downsampleSettingsData;
    }

    /**
     * @param scaledSource the SSAA-scaled render target this frame's terrain and G-buffer resolve
     *                     drew into -- {@code GameRendererMixin}'s {@code mainRenderTarget} at the
     *                     point {@code fornax$restoreNativeTargetAfterSsaa} calls this, before it swaps the field
     *                     back to the native-resolution target
     * @param nativeDest   the native-resolution target to write the downsampled result into --
     *                     {@code GameRendererMixin}'s {@code fornax$ssaaNativeTargetBackup}
     * @param scaleFactor  {@code SsaaManager.getScaleFactor()}, the linear per-dimension scale,
     *                     rounded to the nearest int tap radius
     */
    public static void downsample(RenderTarget scaledSource, RenderTarget nativeDest, float scaleFactor) {
        MappableRingBuffer settings = downsampleSettingsBuffer();
        settings.rotate();

        int tapRadius = Math.round(scaleFactor);
        float texelSizeX = 1.0f / scaledSource.width;
        float texelSizeY = 1.0f / scaledSource.height;

        try (var data = settings.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(data.data())
                    .putInt(tapRadius)
                    .putVec2(texelSizeX, texelSizeY)
                    .get();
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler sourceSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        try (RenderPass pass = encoder.createRenderPass(() -> "SSAA Downsample",
                nativeDest.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("u_DownsampleSettings", settings.currentBuffer());
            pass.bindTexture("u_Source", scaledSource.getColorTextureView(), sourceSampler);

            // 3 vertices, 1 instance, no vertex/index buffer -- core/screenquad.vsh derives its
            // full-screen triangle purely from gl_VertexID (0, 1, 2).
            pass.draw(3, 1, 0, 0);
        }
    }
}
