package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Owns the current {@link GBuffer} instance, rebuilding it whenever the requested resolution
 * changes (window resize) -- unlike NormalMapAtlas (rebuilt once per resource reload), the G-buffer
 * must track the live render target size every frame.
 */
public final class GBufferManager {
    private static final Vector4fc FRAME_CLEAR_COLOR = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
    private static final AtomicBoolean frameWriterClaimed = new AtomicBoolean();

    /**
     * The format of the depth attachment every deferred draw tests against, named rather than
     * repeated so a consumer that has to declare a matching format can never drift from what
     * {@link #ensureSize} actually creates. {@code ParticlePassRunner}'s raw-Vulkan graphics
     * pipeline is exactly such a consumer: a {@code VkPipelineRenderingCreateInfo.depthAttachmentFormat}
     * that disagrees with the bound attachment is undefined behavior, not a validation-time error.
     */
    public static final GpuFormat DEPTH_FORMAT = GpuFormat.D32_FLOAT;

    @Nullable
    private static volatile GBuffer instance;

    private GBufferManager() {
    }

    @Nullable
    public static GBuffer getInstance() {
        return instance;
    }

    /** Starts the first-actual-writer lifecycle for the current frame. */
    public static void beginFrame() {
        frameWriterClaimed.set(false);
    }

    /**
     * Builds the descriptor for an actual deferred draw. The first draw of the frame clears every
     * G-buffer attachment as part of its own render-pass load operations; later draws load them.
     */
    public static RenderPassDescriptor claimWriterDescriptor(Supplier<String> label, GBuffer gbuffer) {
        boolean clear = frameWriterClaimed.compareAndSet(false, true);
        return writerDescriptor(label, gbuffer, clear);
    }

    private static RenderPassDescriptor writerDescriptor(
            Supplier<String> label, GBuffer gbuffer, boolean clear) {
        Optional<Vector4fc> colorClear = clear
                ? Optional.of(FRAME_CLEAR_COLOR) : Optional.empty();
        OptionalDouble depthClear = clear ? OptionalDouble.of(RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE) : OptionalDouble.empty();
        return RenderPassDescriptor.create(label)
                .withColorAttachment(gbuffer.getNormalView(), colorClear)
                .withColorAttachment(gbuffer.getAlbedoView(), colorClear)
                .withColorAttachment(gbuffer.getMaterialView(), colorClear)
                .withColorAttachment(gbuffer.getAoView(), colorClear)
                .withColorAttachment(gbuffer.getMotionView(), colorClear)
                .withDepthAttachment(gbuffer.getDepthView(), depthClear)
                .withRenderArea(new RenderPass.RenderArea(
                        0, 0, gbuffer.getWidth(), gbuffer.getHeight()));
    }

    /**
     * Defines the attachments before graph consumers run on a frame with no deferred geometry.
     * Rendered frames never take this path: their first real writer already performed the clears.
     */
    public static void clearIfNoWriterForResolve() {
        GBuffer current = instance;
        if (current == null) {
            return;
        }
        if (!frameWriterClaimed.compareAndSet(false, true)) {
            return;
        }

        RenderPassDescriptor descriptor = writerDescriptor(
                () -> "G-buffer (zero-writer clear)", current, true);
        try (RenderPass ignored = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(descriptor)) {
            // Clear-only fallback. Attachment load operations do all the work.
        }
    }

    /**
     * Ensures a GBuffer sized exactly (width, height) is installed as the current instance,
     * rebuilding it if the size changed or none exists yet. Safe to call every frame; it is a no-op
     * once the requested size matches the current instance.
     */
    public static void ensureSize(int width, int height) {
        GBuffer current = instance;
        if (current != null && current.getWidth() == width && current.getHeight() == height) {
            return;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            FornaxMod.LOGGER.warn("[GBuffer] Skipping (re)build: no GPU device available");
            return;
        }

        // USAGE_COPY_SRC is additionally required (beyond the depth texture's own need below)
        // because HistoryBufferManager copies every G-buffer attachment into the history buffer
        // once per frame via CommandEncoder.copyTextureToTexture -- that flag is required on the
        // source texture of any copy, not only the depth attachment's use case.
        //
        // Every handle below is hoisted into a local and only handed to the GBuffer/instance field
        // once the whole sequence succeeds: a mid-sequence failure (VRAM pressure, a transient
        // driver rejection) otherwise orphaned whatever textures/views had already been created,
        // with no reference left anywhere to close them.
        GpuTexture normalTexture = null;
        GpuTextureView normalView = null;
        GpuTexture albedoTexture = null;
        GpuTextureView albedoView = null;
        GpuTexture materialTexture = null;
        GpuTextureView materialView = null;
        GpuTexture aoTexture = null;
        GpuTextureView aoView = null;
        GpuTexture motionTexture = null;
        GpuTextureView motionView = null;
        GpuTexture depthTexture = null;
        GpuTextureView depthView = null;
        GBuffer next;
        try {
            normalTexture = device.createTexture("Sodium GBuffer Normal",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA16_SNORM, width, height, 1, 1);
            normalView = device.createTextureView(normalTexture);
            albedoTexture = device.createTexture("Sodium GBuffer Albedo",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            albedoView = device.createTextureView(albedoTexture);
            materialTexture = device.createTexture("Sodium GBuffer Material",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            materialView = device.createTextureView(materialTexture);
            // RGBA8 since ecv2: R = baked AO (unchanged consumer contract -- resolve reads .r), GBA =
            // intrinsic (unlit) raw albedo, packed here because a 6th color attachment's writes were
            // lost on this stack (see ecv2-attachment saga in .superpowers/sdd/progress.md) while the
            // proven 5-attachment layout ships the same data in the AO texture's unused channels.
            aoTexture = device.createTexture("Sodium GBuffer AO",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            aoView = device.createTextureView(aoTexture);
            motionTexture = device.createTexture("Sodium GBuffer Motion",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RG16_FLOAT, width, height, 1, 1);
            motionView = device.createTextureView(motionTexture);
            // USAGE_COPY_SRC is required because the resolve copies this depth texture into the main
            // target. Per-frame clearing now happens as a render-pass load operation, so COPY_DST is
            // not required on this attachment.
            depthTexture = device.createTexture("Sodium GBuffer Depth",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                            | GpuTexture.USAGE_COPY_SRC,
                    DEPTH_FORMAT, width, height, 1, 1);
            depthView = device.createTextureView(depthTexture);

            next = new GBuffer(width, height,
                    normalTexture, normalView, albedoTexture, albedoView,
                    materialTexture, materialView, aoTexture, aoView,
                    motionTexture, motionView, depthTexture, depthView);
        } catch (RuntimeException e) {
            closeIfNotNull(depthView);
            closeIfNotNull(depthTexture);
            closeIfNotNull(motionView);
            closeIfNotNull(motionTexture);
            closeIfNotNull(aoView);
            closeIfNotNull(aoTexture);
            closeIfNotNull(materialView);
            closeIfNotNull(materialTexture);
            closeIfNotNull(albedoView);
            closeIfNotNull(albedoTexture);
            closeIfNotNull(normalView);
            closeIfNotNull(normalTexture);
            throw e;
        }

        instance = next;
        if (current != null) {
            // Live per-frame resize path (window resize / SSAA render-scale change), called every
            // frame from GraphRunner.prepare() on the SAME live instance -- identical crash-class
            // hazard to OpaqueDepth.ensureSize() (see VulkanComputeBackend
            // .waitForGpuIdleBeforeDestroy's own doc for the two live MoltenVK crashes this guards
            // against). GraphRunner.closeCurrent()'s own wait-idle does not cover this call site.
            VulkanComputeBackend.waitForGpuIdleBeforeDestroy();
            current.close();
        }

        FornaxMod.LOGGER.info("[GBuffer] (Re)built at {}x{}", width, height);
    }

    private static void closeIfNotNull(@Nullable GpuTexture texture) {
        if (texture != null) {
            texture.close();
        }
    }

    private static void closeIfNotNull(@Nullable GpuTextureView view) {
        if (view != null) {
            view.close();
        }
    }
}
