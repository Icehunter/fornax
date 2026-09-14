package dev.icehunter.fornax.metalfx;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import dev.icehunter.fornax.pipeline.SkyReprojection;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.joml.Vector4f;
import org.joml.Vector2fc;

import java.util.Optional;

/**
 * Supplies the sky motion absent from the geometry-only G-buffer before MetalFX copy-in. Geometry
 * vectors are copied unchanged; cleared-depth pixels use the same jitter-free rotational map as
 * the engine temporal resolve. This is an independent target: changing gMotion would alter other
 * consumers' geometry contract. It does not infer finite cloud distance or atmospheric wind.
 */
final class MetalFxSkyMotionPass {
    // std140 mat4 plus a padded jitter vec4: twenty float lanes, matching the GLSL block.
    private static final int SETTINGS_BUFFER_SIZE = 20 * Float.BYTES;
    private static final BindGroupLayout BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_Motion")
            .withSampler("u_Depth")
            .withUniform("u_SkyMotionSettings", UniformType.UNIFORM_BUFFER)
            .build();
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "metalfx_sky_motion"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/metalfx_sky_motion"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.RG16_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    @Nullable private static GpuTexture texture;
    @Nullable private static GpuTextureView view;
    private static int width;
    private static int height;
    private static MappableRingBuffer settingsData;

    private MetalFxSkyMotionPass() {}

    static GpuTextureView render(GpuTextureView motion, GpuTextureView depth,
            Vector2fc jitterNdc, int requestedWidth, int requestedHeight) {
        ensureSize(requestedWidth, requestedHeight);
        if (view == null) throw new IllegalStateException("MetalFX sky motion target unavailable");
        if (settingsData == null) {
            settingsData = new MappableRingBuffer(() -> "Fornax MetalFX sky motion settings",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, SETTINGS_BUFFER_SIZE);
        }
        MappableRingBuffer settings = settingsData;
        settings.rotate();
        try (var data = settings.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(data.data()).putMat4f(SkyReprojection.current())
                    .putVec4(jitterNdc.x(), jitterNdc.y(), 0.0f, 0.0f).get();
        }
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "MetalFX sky motion", view, Optional.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("u_SkyMotionSettings", settings.currentBuffer());
            pass.bindTexture("u_Motion", motion, nearest);
            pass.bindTexture("u_Depth", depth, nearest);
            pass.draw(3, 1, 0, 0);
        }
        return view;
    }

    private static void ensureSize(int requestedWidth, int requestedHeight) {
        if (texture != null && width == requestedWidth && height == requestedHeight) return;
        GpuDevice device = RenderSystem.getDevice();
        GpuTexture nextTexture = null;
        GpuTextureView nextView = null;
        try {
            nextTexture = device.createTexture("Fornax MetalFX sky motion",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                            | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RG16_FLOAT, requestedWidth, requestedHeight, 1, 1);
            nextView = device.createTextureView(nextTexture);
            device.createCommandEncoder().clearColorTexture(nextTexture, new Vector4f());
        } catch (RuntimeException e) {
            if (nextView != null) nextView.close();
            if (nextTexture != null) nextTexture.close();
            throw e;
        }
        GpuTexture oldTexture = texture;
        GpuTextureView oldView = view;
        // A previous Vulkan copy may still read this texture. The scaler's Metal timeline alone
        // does not cover every graphics/transfer reader; use the shared resize retirement fence.
        if (oldTexture != null) VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
        if (oldView != null) oldView.close();
        if (oldTexture != null) oldTexture.close();
        texture = nextTexture;
        view = nextView;
        width = requestedWidth;
        height = requestedHeight;
    }
}
