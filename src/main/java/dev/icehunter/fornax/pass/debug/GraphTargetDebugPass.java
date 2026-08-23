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
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pack.graph.TargetFilter;
import dev.icehunter.fornax.pack.graph.TargetInstance;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Presents a pack-owned graph target over the native frame for a live debug view. Target routing
 * belongs to {@link GBufferDebugView}; this class is deliberately pack-agnostic and replaces the
 * former one-class-per-target presentation pattern.
 */
public final class GraphTargetDebugPass {
    private static final BindGroupLayout BLIT_BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_Source")
            .build();

    private static final RenderPipeline RGB_PIPELINE = pipeline(
            "graph_target_debug", "post/graph_target_debug_blit");
    private static final RenderPipeline RED_AS_GRAY_PIPELINE = pipeline(
            "graph_target_red_debug", "post/graph_target_red_debug_blit");

    private GraphTargetDebugPass() {
    }

    public static void presentIfEnabled(RenderTarget nativeTarget) {
        GBufferDebugView debugView = FornaxConfig.get().debugView;
        if (debugView.graphTargetCandidates().isEmpty()) {
            return;
        }

        TargetRegistry registry = GraphRunner.registry();
        if (registry == null) {
            return;
        }

        String targetName = null;
        TargetInstance target = null;
        for (String candidate : debugView.graphTargetCandidates()) {
            target = registry.get(candidate);
            if (target != null) {
                targetName = candidate;
                break;
            }
        }
        if (target == null) {
            return;
        }

        GpuTextureView source = target.view();
        FilterMode filter = registry.filterFor(targetName) == TargetFilter.LINEAR
                ? FilterMode.LINEAR : FilterMode.NEAREST;
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(filter);
        RenderPipeline pipeline = debugView == GBufferDebugView.CELESTIAL_SHADOW_VOXEL
                ? RED_AS_GRAY_PIPELINE : RGB_PIPELINE;

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(() -> "Fornax Graph Target Debug Blit",
                nativeTarget.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.bindTexture("u_Source", source, sampler);
            pass.draw(3, 1, 0, 0);
        }
    }

    private static RenderPipeline pipeline(String name, String fragmentShader) {
        return RenderPipeline.builder()
                .withBindGroupLayout(BLIT_BIND_GROUP)
                .withLocation(Identifier.fromNamespaceAndPath("fornax", name))
                .withCull(false)
                .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", fragmentShader))
                .withDepthStencilState(Optional.empty())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                        ColorTargetState.WRITE_ALL))
                .build();
    }
}
