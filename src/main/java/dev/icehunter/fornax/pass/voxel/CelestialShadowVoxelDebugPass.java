package dev.icehunter.fornax.pass.voxel;

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
import dev.icehunter.fornax.pack.graph.TargetInstance;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Engine-owned debug view for the M1 DDA sun-shadow prototype: samples the pack's real {@code
 * celestialVisVoxel} graph target (r8, 1.0 = lit / 0.0 = occluded), written directly every frame by
 * a pack's {@code sun_shadow} fullscreen fragment pass. A queue-topology fix retired the former
 * two-step {@code sun_shadow_voxel} compute pass + {@code sun_shadow_resolve} blit chain that used
 * to produce it -- see the pack's own {@code graph.toml} comment on {@code celestialVisVoxel} for
 * the history. Blits the target grayscale over the native frame.
 *
 * <p>Mirrors {@link dev.icehunter.fornax.pass.water.WaterPrepassDebugPass}'s shape -- a real,
 * already-produced target, nothing left to dispatch on this pass's own side -- rather than {@link
 * VoxelDebugRaymarchPass}'s (which owns its own compute dispatch): {@code celestialVisVoxel} is a
 * pack graph target (not an engine-owned manager singleton like {@code WaterSurfaceManager}), so
 * this class reaches it through {@link GraphRunner#registry()} instead.
 */
public final class CelestialShadowVoxelDebugPass {
    // The pack declares TWO mutually-exclusive arms of this target, gated on
    // CELESTIAL_SHADOW_RESOLUTION (graph.toml: celestialVisVoxel is `== 0` / Half, celestialVisVoxelFull
    // is `== 1` / Full) -- exactly one is allocated per session. Hardcoding the Half name meant that on
    // the Full arm this pass presented a never-written allocation, and an un-written allocation reads
    // back on MoltenVK as arbitrary hard-edged junk rather than black: fixed geometric shapes locked to
    // the screen that no shadow-pipeline change could affect. Probe Full first, then Half, and present
    // whichever arm is actually allocated.
    //
    // Points at the ACCUMULATOR arms, not celestialVisVoxelDenoised*: RT_SHADOW (gbuffer_resolve's
    // debugView == 12 branch) already shows the denoised field that lighting actually samples, so staying
    // on the accumulator makes the pair an A/B across the denoise stage -- the only way to see, in-game,
    // whether the a-trous chain is improving its input or degrading it. Temporarily reordering the raw
    // arms (celestialVisVoxelRaw / ...RawFull) to the front turns it into an A/B across the DDA stage
    // instead -- worth trying before blaming a filter for a structured artifact.
    private static final String[] TARGET_NAMES = {"celestialVisVoxelFull", "celestialVisVoxel"};

    private static final BindGroupLayout BLIT_BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_Source")
            .build();

    private static final RenderPipeline BLIT_PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BLIT_BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "celestial_shadow_voxel_debug"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/celestial_shadow_voxel_debug_blit"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    private CelestialShadowVoxelDebugPass() {
    }

    /**
     * Blits the live {@code celestialVisVoxel} target over {@code nativeTarget}, called from {@code
     * GameRendererMixin} at {@code renderLevel} RETURN, after the other engine debug views (same
     * placement rationale: native target already resolved, sceneHistory already captured the pack's
     * real frame). No-ops unless the {@link GBufferDebugView#CELESTIAL_SHADOW_VOXEL} debug view is
     * selected and the target is currently allocated (the M1 prototype's compile gate is on this
     * session and a pack is active) -- so an off session, or one where the gate is compiled off,
     * simply presents the pack's own output untouched.
     */
    public static void presentIfEnabled(RenderTarget nativeTarget) {
        if (FornaxConfig.get().debugView != GBufferDebugView.CELESTIAL_SHADOW_VOXEL) {
            return;
        }
        TargetRegistry registry = GraphRunner.registry();
        if (registry == null) {
            return;
        }
        TargetInstance target = null;
        for (String name : TARGET_NAMES) {
            target = registry.get(name);
            if (target != null) {
                break;
            }
        }
        if (target == null) {
            return; // neither arm allocated this session (gate compiled off, or pack mid-teardown)
        }
        GpuTextureView view = target.view(); // never null once a TargetInstance exists (unlike historyView())

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "Fornax Celestial Shadow Voxel Debug Blit",
                nativeTarget.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(BLIT_PIPELINE);
            pass.bindTexture("u_Source", view, sampler);
            pass.draw(3, 1, 0, 0); // full-screen triangle from gl_VertexID, same as SsaaDownsamplePass
        }
    }
}
