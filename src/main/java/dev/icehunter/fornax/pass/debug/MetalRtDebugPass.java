package dev.icehunter.fornax.pass.debug;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.GBufferDebugView;
import dev.icehunter.fornax.config.RtDebugMode;
import dev.icehunter.fornax.metalfx.rt.MetalRtShadowPass;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Engine-owned debug views for the Metal ray tracing passes (see {@link MetalRtShadowPass}): blits
 * either the R8 sun-visibility mask (reusing the same red-channel-as-grey blit shader {@link
 * GraphTargetDebugPass} uses for {@code CELESTIAL_SHADOW_VOXEL}, since it is a single scalar mask
 * rather than a colour target) or the colored RGBA16F scene-debug output (the plain rgb passthrough
 * blit {@link GraphTargetDebugPass} uses for its own coloured graph targets) over the native frame.
 * Bypasses the pack graph entirely, the same shape {@link
 * dev.icehunter.fornax.pass.water.WaterPrepassDebugPass} uses.
 */
public final class MetalRtDebugPass {
    private static final BindGroupLayout BLIT_BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_Source")
            .build();

    private static final RenderPipeline RED_AS_GRAY_PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BLIT_BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "metal_rt_debug"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/graph_target_red_debug_blit"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    private static final RenderPipeline RGB_PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BLIT_BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "metal_rt_scene_debug"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/graph_target_debug_blit"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    private static final RenderPipeline RAY_TIER_PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BLIT_BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "ray_tier_map"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/ray_tier_debug_blit"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    private MetalRtDebugPass() {
    }

    /**
     * True when either Metal RT debug view is selected (so its output is about to be shown), {@link
     * dev.icehunter.fornax.config.FornaxSettings#rtDebugMode} is anything but {@code OFF} (so the
     * scene-debug dispatch has a reason to be checked even while some other view is on screen), or
     * the {@code fornax.rt.always} system property forces continued interest regardless (for
     * timing/soak checks without switching views every time). Read live, not cached, so flipping
     * any of these takes effect on the very next frame. Unrelated to whether {@link
     * MetalRtShadowPass#runIfEnabled} runs: that pass is gated on the ray tracing setting alone,
     * since its sun-visibility mask describes world state and must not depend on a debug view.
     */
    public static boolean wanted() {
        GBufferDebugView view = FornaxConfig.get().debugView;
        return view == GBufferDebugView.METAL_RT_SUN_MASK
                || view == GBufferDebugView.RAY_TIER_MAP
                || view == GBufferDebugView.METAL_RT_SCENE_DEBUG
                || FornaxConfig.get().rtDebugMode != RtDebugMode.OFF
                || Boolean.getBoolean("fornax.rt.always");
    }

    /**
     * Blits whichever Metal RT debug output the selected {@link GBufferDebugView} names over
     * {@code nativeTarget}. No-ops when neither {@link GBufferDebugView#METAL_RT_SUN_MASK} nor
     * {@link GBufferDebugView#METAL_RT_SCENE_DEBUG} is selected, or when the relevant pass output
     * is currently null: before the pass has ever produced one, and again on every frame the pass
     * is not currently running or (for the scene debug view) {@code rtDebugMode} is {@code OFF},
     * whether or not it produced one earlier.
     */
    public static void presentIfEnabled(RenderTarget nativeTarget) {
        GBufferDebugView view = FornaxConfig.get().debugView;
        if (view == GBufferDebugView.METAL_RT_SCENE_DEBUG) {
            blit(nativeTarget, RGB_PIPELINE, MetalRtShadowPass.debugSceneView(), "Fornax Metal RT Scene Debug Blit");
        } else if (view == GBufferDebugView.RAY_TIER_MAP) {
            // Reads the image the cascade already filled this frame rather than tracing anything,
            // so this view costs one blit and shows exactly what the pack reads.
            blit(nativeTarget, RAY_TIER_PIPELINE,
                    dev.icehunter.fornax.pass.shadow.TerrainShadowResult.view(), "Fornax Ray Tier Map Blit");
        }
    }

    private static void blit(RenderTarget nativeTarget, RenderPipeline pipeline, GpuTextureView source, String label) {
        if (source == null) {
            return;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> label,
                nativeTarget.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.bindTexture("u_Source", source, sampler);
            pass.draw(3, 1, 0, 0); // full-screen triangle from gl_VertexID, same as SsaaDownsamplePass
        }
    }
}
