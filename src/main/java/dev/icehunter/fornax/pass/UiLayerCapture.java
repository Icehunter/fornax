package dev.icehunter.fornax.pass;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Holds a single frame's vanilla HUD (drawn by {@code GuiRenderer.render()}) in an off-screen,
 * native-resolution, transparent-background target, for {@code GuiRendererCaptureMixin} to
 * assemble back over the real frame and for MetalFX frame generation's present seam (Task 6) to
 * additionally stamp over the ML-generated interpolated frame, which never runs vanilla's HUD
 * draw itself.
 *
 * <p>Only ever active while {@code FrameGenPass.generatedFrameReady()} gated the mixin's swap this
 * frame -- {@link #uiTarget(int, int)} is the single per-frame activation point (lazily
 * builds/resizes the target, clears it to transparent zero, and marks {@link #activeThisFrame()}
 * true) and is meant to be called exactly once per frame, by that mixin, immediately before handing
 * the target to vanilla for the HUD draw. Sized from the caller-supplied width/height -- the REAL
 * {@code mainRenderTarget}'s own {@code .width}/{@code .height} at capture time, i.e. exactly the
 * target vanilla's HUD draw would have used had this mixin not swapped it out -- rather than any
 * independently-derived size (a window/{@code SsaaManager}-based read was tried first and produced
 * logical-point dimensions on a Retina display instead of the physical-pixel size every other target
 * in the chain uses, stretching the composited HUD to a corner quadrant; live-caught and reverted).
 * {@link #compositeOnto(RenderTarget)} is a pure read afterward -- callable any number of times
 * against any destination (the real native target from the capturing mixin itself, and separately
 * the generated frame from Task 6) without touching the captured content.
 *
 * <p>Composite uses the pipeline's own blend state rather than a shader-side {@code mix} -- {@code
 * ColorTargetState} exposes a real {@code BlendFunction} slot (see {@code
 * MetalFxReactiveMaskPass}/{@code WaterPrepassDebugPass} for the sibling fullscreen passes this
 * mirrors), so the fragment shader only needs to sample and output the UI texel as-is. The blend
 * function is {@link BlendFunction#TRANSLUCENT_PREMULTIPLIED_ALPHA} ({@code ONE}/{@code
 * ONE_MINUS_SRC_ALPHA}), NOT plain {@code TRANSLUCENT} ({@code SRC_ALPHA}/{@code
 * ONE_MINUS_SRC_ALPHA}): {@code uiTarget} is cleared to transparent zero and vanilla's HUD draws its
 * own translucent elements directly against that transparent background with straight-alpha
 * blending, which leaves the buffer's rgb already premultiplied ({@code rgb = src.rgb * src.a}) --
 * compositing a premultiplied source with {@code SRC_ALPHA} would multiply by alpha a second time,
 * darkening every translucent HUD element (chat background, tooltips, GUI panels).
 */
public final class UiLayerCapture {
    private static final BindGroupLayout BIND_GROUP = BindGroupLayout.builder()
            .withSampler("u_UiLayer")
            .build();

    private static final RenderPipeline COMPOSITE_PIPELINE = RenderPipeline.builder()
            .withBindGroupLayout(BIND_GROUP)
            .withLocation(Identifier.fromNamespaceAndPath("fornax", "framegen_ui_composite"))
            .withCull(false)
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("fornax", "post/framegen_ui_composite"))
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(
                    Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    @Nullable
    private static RenderTarget uiTarget;
    private static int width;
    private static int height;
    private static boolean activeThisFrame;

    private UiLayerCapture() {
    }

    /** True only for a frame where {@link #uiTarget(int, int)} was actually activated (a capture is live). */
    public static boolean activeThisFrame() {
        return activeThisFrame;
    }

    /**
     * Lazily builds/resizes the capture target to {@code (width, height)} -- callers MUST pass the
     * real {@code mainRenderTarget}'s own dimensions at capture time (see class header: this must
     * always exactly match the target vanilla's HUD draw would otherwise have used, never an
     * independently-derived size) -- clears its color to transparent zero (a real clear-only render
     * pass, mirroring {@code TargetRegistry.clear}'s convention -- MoltenVK does not zero-fill new or
     * reused VRAM), and marks the frame active. Meant to be called exactly once per frame, by {@code
     * GuiRendererCaptureMixin}, right before swapping it in as the HUD draw's target.
     */
    public static RenderTarget uiTarget(int width, int height) {
        ensureSize(width, height);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(() -> "Fornax UI Layer Clear",
                uiTarget.getColorTextureView(), Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 0.0f)))) {
            // Clear-only: the attachment's own clear value does the work, no draw call needed.
        }

        activeThisFrame = true;
        return uiTarget;
    }

    /**
     * Alpha-blends the captured HUD over {@code dest} (pipeline blend state -- see class header), a
     * fullscreen triangle read against whatever was activated this frame by {@link #uiTarget(int,
     * int)}. No-ops if nothing was captured this frame (nothing to composite, {@code dest} left
     * untouched) rather than requiring every caller to guard on {@link #activeThisFrame()} itself.
     */
    public static void compositeOnto(RenderTarget dest) {
        if (!activeThisFrame || uiTarget == null) {
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(
                () -> "Fornax Frame Gen UI Composite", dest.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(COMPOSITE_PIPELINE);
            pass.bindTexture("u_UiLayer", uiTarget.getColorTextureView(), sampler);
            pass.draw(3, 1, 0, 0); // full-screen triangle from gl_VertexID, same as SsaaDownsamplePass
        }
    }

    /** Releases the capture target; safe to call anytime (config-off transitions, shutdown). */
    public static void deactivate() {
        if (uiTarget != null) {
            // Same live-per-frame-resize crash class GBufferManager/ShadowMapManager/etc. already
            // guard against (see VulkanComputeBackend.waitForGpuIdleBeforeDestroy's own doc) --
            // this and SsaaManager were the two texture-owning managers in the codebase missing the
            // guard, live-caught via the METALFX+frame-generation crash investigation
            // (mc-vulkan-realism docs/reference/vulkan-renderer-architecture-audit.md follow-up).
            VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
            uiTarget.destroyBuffers();
            uiTarget = null;
            width = 0;
            height = 0;
        }
        activeThisFrame = false;
    }

    /**
     * Resets the per-frame activation flag; {@code GuiRendererCaptureMixin} calls this on the
     * ungated (framegen-inactive) path so a leftover {@code true} from a previous frame's capture
     * never survives into a frame that captured nothing.
     */
    public static void endFrame() {
        activeThisFrame = false;
    }

    private static void ensureSize(int requestedWidth, int requestedHeight) {
        if (uiTarget != null && width == requestedWidth && height == requestedHeight) {
            return;
        }

        // Build the new target before destroying the old one: if MainTarget's constructor throws
        // (GPU OOM, invalid size), uiTarget still points at a valid, non-destroyed instance instead
        // of a dangling one -- mirrors SsaaManager.ensureScaledTarget's own rebuild-then-destroy order.
        RenderTarget next = new MainTarget(requestedWidth, requestedHeight);
        RenderTarget previous = uiTarget;

        uiTarget = next;
        width = requestedWidth;
        height = requestedHeight;

        if (previous != null) {
            // See deactivate()'s own comment -- the identical live-per-frame-resize hazard.
            VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
            previous.destroyBuffers();
        }

        FornaxMod.LOGGER.info("[Fornax] (Re)built frame-gen UI layer target at {}x{}", requestedWidth, requestedHeight);
    }
}
