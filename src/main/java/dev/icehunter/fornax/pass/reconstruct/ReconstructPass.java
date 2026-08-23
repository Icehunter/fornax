package dev.icehunter.fornax.pass.reconstruct;

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
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.GBufferDebugView;
import dev.icehunter.fornax.pack.graph.TargetInstance;
import dev.icehunter.fornax.pipeline.SceneHistory;
import dev.icehunter.fornax.pipeline.SkyReprojection;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import org.joml.Vector2f;

import java.util.Optional;

/**
 * Engine-owned temporal reconstruct: motion-reprojected history blend with a neighborhood clamp,
 * disocclusion reset, confidence-ramped accumulation, and contrast-adaptive sharpen. TAA is this
 * pass at render/output ratio 1.0, where the shader is FUNCTIONALLY EQUIVALENT to the retired
 * {@code taa_blend.fsh} (point current sample and point motion fetch at texCoord, same 3x3 clamp,
 * same validity test and threshold; the Catmull-Rom kernel, the un-jitter, and the depth-dilated
 * motion fetch run only below ratio 1.0, where upscaling justifies them). {@link #reconstruct}
 * derives {@code u_RatioIsOne} itself by comparing {@code lowResSource} and {@code nativeDest}
 * dimensions. Replaces {@code RenderScaleBlitPass} for both TAA and TAAU; SSAA keeps {@link
 * dev.icehunter.fornax.pass.ssaa.SsaaDownsamplePass}'s box downsample untouched.
 *
 * <p>Two render passes per frame, splitting accumulation from presentation so the sharpen NEVER
 * re-enters the temporal feedback loop (a sharpened output that becomes next frame's history gets
 * edge enhancement re-applied to its own output every frame at the blend's ~0.9 recycle rate --
 * divergent iteration, live-caught as red/rainbow speckle webs on distant foliage):
 * <ol>
 *   <li>{@code post/reconstruct} renders the UNSHARPENED accumulation (rgb) + accumulation age (a)
 *       directly into {@link SceneHistory#writeSlotView} -- the same post-swap history slot, same
 *       phase, the end-of-frame copy would write, so this pass REPLACES that copy under TAA/TAAU
 *       ({@code GameRendererMixin} skips it) and the age stays private to sceneHistory. First-person
 *       content (hand, held items, screen overlays -- present in the source color since the pass
 *       runs at renderLevel RETURN, but absent from G-buffer motion/depth) is detected by the
 *       scene-vs-gbuffer depth delta and rendered fully from the current sample with its age reset
 *       every frame: no ghosting, no accumulation (responsive-pixel masking);</li>
 *   <li>{@code post/reconstruct_sharpen} reads that accumulation and writes the presented native
 *       target: luma-driven contrast-adaptive sharpen (scalar weight -- per-channel weights cause
 *       chromatic ringing where channels disagree), with a ratio-scaled floor for TAAU, alpha
 *       restored to 1.0.</li>
 * </ol>
 */
public final class ReconstructPass {
    private static final BindGroupLayout BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_Source")
            .withSampler("u_History")
            .withSampler("u_Motion")
            .withSampler("u_Depth")
            .withSampler("u_SceneDepth")
            .withUniform("u_ReconstructSettings", UniformType.UNIFORM_BUFFER)
            .build();

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "reconstruct"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/reconstruct"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    private static final BindGroupLayout SHARPEN_BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_Source")
            .withUniform("u_ReconstructSettings", UniformType.UNIFORM_BUFFER)
            .build();

    private static final RenderPipeline SHARPEN_PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(SHARPEN_BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "reconstruct_sharpen"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/reconstruct_sharpen"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    // u_ReconstructSettings std140 layout (offsets documented on the fields, not just here):
    // 0: vec2 u_SourceTexelSize, 8: vec2 u_OutputTexelSize, 16: vec2 u_JitterOffsetNdc,
    // 24: float u_BlendFactor, 28: float u_Sharpen, 32: float u_RatioIsOne, 36..48: padding to the
    // mat4's own 16-byte alignment, 48: mat4 u_SkyReprojection, ending at 112 (already a multiple
    // of 16, so no tail padding).
    //
    // u_SkyReprojection is a 3x3 homography carried in a mat4's upper-left corner: putMat4f is the
    // one matrix upload path this codebase has proven end to end, and a bare std140 mat3's
    // column-padding is exactly the kind of layout assumption that fails silently on the GPU rather
    // than in a test. The 16 wasted bytes buy an unambiguous layout. See SkyReprojection.
    private static final int SETTINGS_BUFFER_SIZE = 112;

    private static MappableRingBuffer settingsData;

    private ReconstructPass() {
    }

    /** Byte size of {@code u_ReconstructSettings} -- unit-tested for the std140 layout in {@code ReconstructSettingsTest}. */
    public static int settingsBufferSize() {
        return SETTINGS_BUFFER_SIZE;
    }

    private static MappableRingBuffer settingsBuffer() {
        if (settingsData == null) {
            settingsData = new MappableRingBuffer(() -> "Fornax reconstruct settings buffer",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, SETTINGS_BUFFER_SIZE);
        }

        return settingsData;
    }

    /**
     * @param lowResSource   this frame's resolved scene color -- native-sized for TAA (scale 1.0),
     *                       genuinely smaller than native for TAAU -- {@code GameRendererMixin}'s
     *                       {@code mainRenderTarget} at the point {@code fornax$restoreNativeTarget}
     *                       calls this, before it swaps the field back to the native-resolution target
     * @param nativeDest     the native-resolution target to write the reconstructed result into --
     *                       {@code GameRendererMixin}'s {@code fornax$ssaaNativeTargetBackup}
     * @param motionView     render-res {@code builtin.gMotion} (currentUV - previousUV, jitter-corrected)
     * @param depthView      render-res depth (reversed-Z)
     * @param sceneHistory   the engine-owned {@code sceneHistory} target; {@link
     *                       SceneHistory#reconstructReadSlot} -- the POST-SWAP current slot, which
     *                       holds the previous frame's final native color at the point this pass
     *                       runs -- is sampled as {@code u_History}. NOT {@code historyView()}: a
     *                       pack's SSR pass reads that slot correctly only because it runs
     *                       PRE-swap; post-swap it is two frames stale, and blending toward it
     *                       trails the camera by a full frame of velocity (see the read-slot
     *                       javadoc and SceneHistoryPhaseTest). {@link SceneHistory#writeSlotView}
     *                       is simultaneously pass 1's render target -- the two slots are disjoint
     *                       physical textures, so the read never aliases the write
     * @param jitterNdc      {@code CameraJitter.currentOffsetNdc()}, the NDC jitter this frame's
     *                       projection matrix carried
     * @param blendFactor    {@code FornaxConfig.get().taaBlendFactor} -- forced to 0 (100% current
     *                       frame, no history accumulation) whenever a G-buffer debug view is
     *                       active (see the debug-view override below), independent of what the
     *                       caller passes
     * @param sharpen        {@code FornaxConfig.get().reconstructSharpen}
     */
    public static void reconstruct(RenderTarget lowResSource, RenderTarget nativeDest,
                                    GpuTextureView motionView, GpuTextureView depthView,
                                    TargetInstance sceneHistory,
                                    Vector2f jitterNdc, float blendFactor, float sharpen) {
        // Debug views (raw normals/albedo/motion/etc, not the lit composite) must show the CURRENT
        // frame only -- several frames of accumulated TAA history otherwise smear/ghost a debug
        // channel that's meant to be inspected exactly as terrain wrote it this frame. Forcing the
        // blend weight to 0 is the cheapest correct override: the shader's confidence-ramped
        // weight is min(ramp, u_BlendFactor), so 0 collapses every frame to the pure current
        // sample regardless of accumulated age (see reconstruct.fsh's weight computation). Normal
        // (debugView == OFF) TAA/TAAU behavior is completely unaffected -- this only overrides the
        // value fed into the settings buffer below, never the caller's own FornaxSettings field.
        if (FornaxConfig.get().debugView != GBufferDebugView.OFF) {
            blendFactor = 0.0f;
        }

        MappableRingBuffer settings = settingsBuffer();
        settings.rotate();

        float sourceTexelX = 1.0f / lowResSource.width;
        float sourceTexelY = 1.0f / lowResSource.height;
        float outputTexelX = 1.0f / nativeDest.width;
        float outputTexelY = 1.0f / nativeDest.height;
        float ratioIsOne = (lowResSource.width == nativeDest.width && lowResSource.height == nativeDest.height)
                ? 1.0f : 0.0f;

        try (var data = settings.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(data.data())
                    .putVec2(sourceTexelX, sourceTexelY)
                    .putVec2(outputTexelX, outputTexelY)
                    .putVec2(jitterNdc)
                    .putFloat(blendFactor)
                    .putFloat(sharpen)
                    .putFloat(ratioIsOne)
                    // Sky reprojection (see SkyReprojection): the shader's only source of a motion
                    // vector for pixels the G-buffer never wrote one for. Committed this frame from
                    // GraphRunner's finish-opaque, which runs earlier in the same frame than this
                    // pass; identity on the very first frame, which degrades to the old behaviour.
                    .putMat4f(SkyReprojection.current())
                    .get();
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler linearSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearestSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        // Pass 1: accumulate into sceneHistory's write slot (reads the read slot -- the OTHER
        // physical texture, so no same-texture read/write hazard; SceneHistoryPhaseTest pins the
        // slots' disjointness). This write replaces the end-of-frame copy under TAA/TAAU.
        try (RenderPass pass = encoder.createRenderPass(() -> "Reconstruct accumulate",
                SceneHistory.writeSlotView(sceneHistory), Optional.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("u_ReconstructSettings", settings.currentBuffer());
            pass.bindTexture("u_Source", lowResSource.getColorTextureView(), linearSampler);
            pass.bindTexture("u_History", SceneHistory.reconstructReadSlot(sceneHistory), linearSampler);
            pass.bindTexture("u_Motion", motionView, nearestSampler);
            pass.bindTexture("u_Depth", depthView, nearestSampler);
            // The scaled target's own depth: cleared far, then vanilla wrote the first-person
            // hand/held items/screen overlays into it AFTER the engine depth copyback -- the
            // responsive-pixel mask compares it against the terrain-only G-buffer depth to render
            // first-person content fully from the current sample (no history, age reset). Depth
            // sampled as a plain texture, the same proven path as u_Depth; sampler inputs are not
            // color-target state, so CTS count stays 1 == the one color attachment.
            pass.bindTexture("u_SceneDepth", lowResSource.getDepthTextureView(), nearestSampler);

            // 3 vertices, 1 instance, no vertex/index buffer -- core/screenquad.vsh derives its
            // full-screen triangle purely from gl_VertexID (0, 1, 2), same convention as
            // SsaaDownsamplePass.
            pass.draw(3, 1, 0, 0);
        }

        // Pass 2: presentation sharpen from the just-written accumulation into the native target.
        // Sharpened pixels exist only here, never in sceneHistory -- the feedback-loop law.
        try (RenderPass pass = encoder.createRenderPass(() -> "Reconstruct sharpen",
                nativeDest.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(SHARPEN_PIPELINE);
            pass.setUniform("u_ReconstructSettings", settings.currentBuffer());
            pass.bindTexture("u_Source", SceneHistory.writeSlotView(sceneHistory), linearSampler);

            pass.draw(3, 1, 0, 0);
        }
    }

    /**
     * Reuses the presentation sharpen for an externally accumulated native-resolution image
     * (MetalFX). {@code renderWidth/renderHeight} preserve the original upscale ratio so the same
     * ratio-scaled sharpen floor applies even though {@code source} itself is already native-sized.
     */
    public static void presentSharpened(GpuTextureView source, RenderTarget nativeDest,
            int renderWidth, int renderHeight, float sharpen) {
        MappableRingBuffer settings = settingsBuffer();
        settings.rotate();
        try (var data = settings.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(data.data())
                    .putVec2(1.0f / renderWidth, 1.0f / renderHeight)
                    .putVec2(1.0f / nativeDest.width, 1.0f / nativeDest.height)
                    .putVec2(0.0f, 0.0f)
                    .putFloat(0.0f)
                    .putFloat(sharpen)
                    .putFloat(renderWidth == nativeDest.width && renderHeight == nativeDest.height
                            ? 1.0f : 0.0f)
                    .get();
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        try (RenderPass pass = encoder.createRenderPass(
                () -> "MetalFX presentation sharpen",
                nativeDest.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(SHARPEN_PIPELINE);
            pass.setUniform("u_ReconstructSettings", settings.currentBuffer());
            pass.bindTexture("u_Source", source, linear);
            pass.draw(3, 1, 0, 0);
        }
    }
}
