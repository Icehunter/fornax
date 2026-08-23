package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.AaMethod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.GBufferDebugView;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pass.reconstruct.ReconstructPass;
import dev.icehunter.fornax.pass.taa.CameraJitter;
import dev.icehunter.fornax.pipeline.SkyReprojection;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import org.joml.Vector2f;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a {@code type = "temporal"} graph pass: the engine's motion-reprojected history
 * accumulation ({@code fornax:post/temporal_accumulate.fsh}) at a pack-chosen point INSIDE the
 * graph -- after the input target's last writer, before whatever reads the output. The pack
 * declares only the data flow; the shader, motion sources and phase rules are engine-owned. See
 * {@code GraphValidator.checkTemporalPass} for the shape this runner may assume.
 *
 * <p><b>Why accumulation moved into the graph at all:</b> the end-of-frame reconstruct consumes
 * the pack's FINISHED frame -- bloom included -- and a bright mover's bloom halo holds the
 * neighborhood clamp open along its own path, so history at passed pixels never crushes back to
 * background: every star drags a permanent comet tail (Plague's
 * {@code tools/verify_star_trails.py} measures +3 display codes on the drift path, and 0.0 with
 * accumulation moved before bloom). Accumulate-then-bloom is also the reference ordering the
 * ported shaderpack family runs.
 *
 * <p><b>Phase rules (the recon landmine this class exists to get right):</b> this pass runs
 * MID-GRAPH, which is PRE-swap -- {@code TargetRegistry.swapHistory()} fires at the end of
 * {@code GraphRunner.finish()}. So last frame's accumulation is the output target's
 * {@code .history} view (what last frame's write slot became at the swap), and this frame's write
 * goes to the CURRENT view. That is the exact opposite slot choice from {@code ReconstructPass},
 * whose end-of-frame placement is post-swap -- copying its accessors here would read two-frame-old
 * history with no test failing.
 *
 * <p><b>Accumulation is live only under TAA.</b> Under TAAU/METALFX the end-of-frame temporal
 * machinery still runs and a second accumulation here would double-smooth; under SSAA/OFF no
 * jitter is applied so there is nothing to integrate. In all of those, and under any active
 * G-buffer debug view, the blend factor is forced to 0 -- the pass degrades to an identity copy
 * (pure current sample, age reset), so downstream readers of the output target always see valid
 * content whatever the AA method. {@code GameRendererMixin} correspondingly skips the
 * end-of-frame ACCUMULATION under TAA when the active pack declares a temporal pass
 * ({@code GraphRunner.activePackHasTemporalPass()}), presenting sharpen-only instead, and the
 * end-of-frame sceneHistory copy resumes (the flag that skipped it is never set).
 */
public final class TemporalPassRunner {
    private static final BindGroupLayout BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_Source")
            .withSampler("u_History")
            .withSampler("u_Motion")
            .withSampler("u_Depth")
            .withSampler("u_SurfaceClass")
            .withUniform("u_ReconstructSettings", UniformType.UNIFORM_BUFFER)
            .build();

    /**
     * One pipeline per color format, built lazily and cached for the process lifetime. The
     * fragment shader is a fixed ENGINE asset -- no pack-republish staleness, so no
     * generation-suffixed location is needed (contrast FullscreenPassRunner's cache-busting): the
     * format-suffixed location is stable and correct.
     */
    private static final Map<TargetFormat, RenderPipeline> PIPELINES = new EnumMap<>(TargetFormat.class);

    private static MappableRingBuffer settingsData;

    private final PassSpec spec;
    private final RenderPipeline pipeline;
    private boolean invalid;

    private TemporalPassRunner(PassSpec spec, RenderPipeline pipeline) {
        this.spec = spec;
        this.pipeline = pipeline;
    }

    public static TemporalPassRunner build(PassSpec spec, TargetFormat outputFormat) {
        RenderPipeline pipeline = PIPELINES.computeIfAbsent(outputFormat, f -> RenderPipeline.builder()
                .withBindGroupLayout(BIND_GROUP)
                .withLocation(Identifier.fromNamespaceAndPath("fornax",
                        "temporal_accumulate_" + f.name().toLowerCase(java.util.Locale.ROOT)))
                .withCull(false)
                .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/temporal_accumulate"))
                .withDepthStencilState(Optional.empty())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(Optional.empty(),
                        TargetRegistry.gpuFormat(f), ColorTargetState.WRITE_ALL))
                .build());
        return new TemporalPassRunner(spec, pipeline);
    }

    private static MappableRingBuffer settingsBuffer() {
        if (settingsData == null) {
            settingsData = new MappableRingBuffer(() -> "Fornax temporal pass settings buffer",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
                    ReconstructPass.settingsBufferSize());
        }
        return settingsData;
    }

    /** Whether accumulation is live this frame -- see the class doc; false degrades to a copy. */
    static boolean accumulationLive() {
        return FornaxConfig.get().aaMethod == AaMethod.TAA
                && FornaxConfig.get().debugView == GBufferDebugView.OFF;
    }

    public void run(TargetRegistry registry, Map<String, MipchainRunner> mipchainTargets,
            PassParams params) {
        if (invalid) {
            return;
        }
        String in = spec.inputs().get(0);
        String out = spec.outputs().get(0);
        GpuTextureView sourceView;
        GpuTextureView historyView;
        GpuTextureView outputView;
        GpuTextureView motionView;
        GpuTextureView depthView;
        GpuTextureView surfaceClassView;
        try {
            sourceView = GraphInputResolver.resolveView(in, registry, mipchainTargets);
            // PRE-swap phase: last frame's accumulation IS the history slot (see class doc).
            historyView = GraphInputResolver.resolveView(out + ".history", registry, mipchainTargets);
            outputView = GraphInputResolver.resolveView(out, registry, mipchainTargets);
            motionView = GraphInputResolver.resolveView("builtin.gMotion", registry, mipchainTargets);
            depthView = GraphInputResolver.resolveView("builtin.depth", registry, mipchainTargets);
            surfaceClassView = GraphInputResolver.resolveView("builtin.gAo", registry, mipchainTargets);
        } catch (RuntimeException e) {
            invalid = true;
            FornaxMod.LOGGER.error("[Fornax] TemporalPassRunner: pass '{}' failed to resolve a "
                    + "target -- skipping it for the rest of this runner's lifetime", spec.name(), e);
            return;
        }

        float blendFactor = accumulationLive() ? FornaxConfig.get().taaBlendFactor : 0.0f;
        Vector2f jitterNdc = CameraJitter.currentOffsetNdc();

        MappableRingBuffer settings = settingsBuffer();
        settings.rotate();
        try (var data = settings.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(data.data())
                    .putVec2(params.texelSizeX(), params.texelSizeY())
                    .putVec2(params.texelSizeX(), params.texelSizeY())
                    .putVec2(jitterNdc)
                    .putFloat(blendFactor)
                    .putFloat(0.0f)   // u_Sharpen: layout parity, unused by temporal_accumulate
                    .putFloat(1.0f)   // u_RatioIsOne: validator-enforced same-shape input/output
                    .putMat4f(SkyReprojection.current())
                    .get();
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler linearSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearestSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "Fornax " + spec.name(),
                outputView, Optional.empty())) {
            try {
                pass.setPipeline(pipeline);
            } catch (RuntimeException e) {
                invalid = true;
                FornaxMod.LOGGER.error("[Fornax] TemporalPassRunner: pass '{}' failed to bind its "
                        + "pipeline -- skipping it for the rest of this runner's lifetime",
                        spec.name(), e);
                return;
            }
            pass.setUniform("u_ReconstructSettings", settings.currentBuffer());
            pass.bindTexture("u_Source", sourceView, linearSampler);
            pass.bindTexture("u_History", historyView, linearSampler);
            pass.bindTexture("u_Motion", motionView, nearestSampler);
            pass.bindTexture("u_Depth", depthView, nearestSampler);
            pass.bindTexture("u_SurfaceClass", surfaceClassView, nearestSampler);
            pass.draw(3, 1, 0, 0);
        }
    }
}
