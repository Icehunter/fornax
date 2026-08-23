package dev.icehunter.fornax.metalfx;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Builds MetalFX's render-resolution reactive mask from the same scene-depth versus terrain-depth
 * signal used by the engine reconstruct. First-person pixels reject history completely; other
 * forward/translucent overlays receive a half-strength response so animated water is not averaged
 * against the static terrain motion underneath it.
 */
final class MetalFxReactiveMaskPass {
    private static final BindGroupLayout BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_SceneDepth")
            .withSampler("u_GBufferDepth")
            .build();

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "metalfx_reactive_mask"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/metalfx_reactive_mask"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    @Nullable
    private static GpuTexture texture;
    @Nullable
    private static GpuTextureView view;
    private static int width;
    private static int height;

    private MetalFxReactiveMaskPass() {
    }

    static GpuTextureView render(GpuTextureView sceneDepth, GpuTextureView gbufferDepth,
            int requestedWidth, int requestedHeight) {
        ensureSize(requestedWidth, requestedHeight);
        if (view == null) {
            throw new IllegalStateException("MetalFX reactive mask target unavailable");
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(
                () -> "MetalFX reactive mask", view, Optional.empty())) {
            pass.setPipeline(PIPELINE);
            pass.bindTexture("u_SceneDepth", sceneDepth, nearest);
            pass.bindTexture("u_GBufferDepth", gbufferDepth, nearest);
            pass.draw(3, 1, 0, 0);
        }
        return view;
    }

    private static void ensureSize(int requestedWidth, int requestedHeight) {
        if (texture != null && width == requestedWidth && height == requestedHeight) {
            return;
        }
        GpuDevice device = RenderSystem.getDevice();
        GpuTexture nextTexture = device.createTexture(
                "Fornax MetalFX Reactive Mask",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                        | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.R8_UNORM, requestedWidth, requestedHeight, 1, 1);
        GpuTextureView nextView = device.createTextureView(nextTexture);

        GpuTexture oldTexture = texture;
        GpuTextureView oldView = view;
        texture = nextTexture;
        view = nextView;
        width = requestedWidth;
        height = requestedHeight;
        // Same live-per-frame-resize crash class GBufferManager/ShadowMapManager/etc. already guard
        // against (see VulkanComputeBackend.waitForGpuIdleBeforeDestroy's own doc). MetalFxUpscalePass
        // having already waited its OWN v+2 timeline value (which this class's only caller sits
        // downstream of) is NOT sufficient on its own: it proves MetalFxUpscalePass's own Vulkan/Metal
        // work is done, but says nothing about a DIFFERENT reader on an independently-timed command
        // buffer (e.g. FrameGenPass, borrowing MetalFxUpscalePass's own interop images the same way)
        // -- the earlier version of this comment overstated what that wait actually covered.
        VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
        if (oldView != null) {
            oldView.close();
        }
        if (oldTexture != null) {
            oldTexture.close();
        }
    }
}
