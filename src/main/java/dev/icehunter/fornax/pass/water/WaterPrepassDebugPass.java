package dev.icehunter.fornax.pass.water;

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
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Engine-owned debug view: samples {@link WaterSurfaceManager#getNormalView()} directly and blits it
 * over the native frame, bypassing whatever pack is loaded -- mirrors {@code
 * VoxelDebugRaymarchPass}'s "hardcoded engine pass, not pack-declared" shape (a static {@link
 * RenderPipeline} for the presentation blit, invoked from {@code GameRendererMixin}), minus that
 * pass's compute half: {@code waterNormal} is already a real, rasterized GPU texture by the time this
 * runs (written earlier this same frame by {@code SodiumWorldRendererOrchestrationMixin
 * #fornax$renderWaterPrepass}), so there is nothing to dispatch -- only a screenquad sample-and-blit,
 * the same shape {@code SsaaDownsamplePass}/{@code voxel_debug_blit.fsh} already use.
 *
 * <p>Deferred Water Task 1 spike instrumentation: exists solely to let the user visually confirm the
 * {@code WATER_PREPASS} render pass rasterizes real water content (see the deferred-water plan's
 * Task 1 make-or-break question) without needing Water Round Task 2's real graph-wired {@code
 * builtin.waterNormal} input to exist yet. Superseded once that lands.
 */
public final class WaterPrepassDebugPass {
    private static final BindGroupLayout BLIT_BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_Source")
            .build();

    private static final RenderPipeline BLIT_PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BLIT_BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "water_prepass_debug"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/water_prepass_debug_blit"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    private WaterPrepassDebugPass() {
    }

    /**
     * Blits {@link WaterSurfaceManager}'s {@code waterNormal} target over {@code nativeTarget}, called
     * from {@code GameRendererMixin} at {@code renderLevel} RETURN (where {@code mainRenderTarget} is
     * the final native target) -- after {@code VoxelDebugRaymarchPass.presentIfEnabled}, matching that
     * call's own placement rationale (native target already resolved, sceneHistory already captured
     * the pack's real frame). No-ops unless the {@link GBufferDebugView#WATER_PREPASS} debug view is
     * selected and a water surface target is currently allocated (SSR_WATER_MODE > 1 this session) --
     * so a frame with water reflections off, or mid pack-teardown, simply presents the pack's own
     * output untouched.
     */
    public static void presentIfEnabled(RenderTarget nativeTarget) {
        if (FornaxConfig.get().debugView != GBufferDebugView.WATER_PREPASS) {
            return;
        }
        GpuTextureView normalView = WaterSurfaceManager.getNormalView();
        if (normalView == null) {
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "Fornax Water Prepass Debug Blit",
                nativeTarget.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(BLIT_PIPELINE);
            pass.bindTexture("u_Source", normalView, sampler);
            pass.draw(3, 1, 0, 0); // full-screen triangle from gl_VertexID, same as SsaaDownsamplePass
        }
    }
}
