package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import dev.icehunter.fornax.mixin.vulkan.GpuDeviceBackendAccessor;
import dev.icehunter.fornax.util.GpuFatalException;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * The engine's one creation path for GPU ARRAY textures ({@code depthOrLayers > 1}, sampled as
 * {@code sampler2DArray}): Blaze3D refuses to build them through its public surface, so this class
 * reaches the Vulkan backend directly, the same seam {@code ShadowComparisonSampler} uses for its
 * comparison sampler. Two independent walls in the public surface make that necessary (both
 * decompiled from the game jar, mc26.2, not assumed):
 *
 * <ul>
 * <li>{@code GpuDevice.createTexture} routes through {@code verifyTextureCreationArgs}, which
 *     throws {@code UnsupportedOperationException("Array or 3D textures are not yet supported")}
 *     for any non-cubemap {@code depthOrLayers > 1} -- and the cubemap branch caps at exactly six
 *     layers, so no usage-flag combination reaches a real array. The backend interface method
 *     ({@code GpuDeviceBackend.createTexture}, same signature) has no such guard, and
 *     {@code VulkanGpuTexture}'s constructor honestly forwards the argument
 *     ({@code VkImageCreateInfo.arrayLayers(depthOrLayers)}) and covers every layer in its
 *     initial layout barrier ({@code subresourceRange.layerCount(depthOrLayers)}) -- the image
 *     that comes back is a genuine, fully-initialized Vulkan array image.</li>
 * <li>Every {@code VulkanGpuTextureView} is built {@code VK_IMAGE_VIEW_TYPE_2D} with
 *     {@code subresourceRange.layerCount(1)} (or CUBE/6 for cubemaps) -- a view only ever scopes
 *     MIP levels through the public API, so even a hand-created array image could never be sampled
 *     past layer 0 through a stock view. {@link ArrayView} below fills that gap: it hand-builds a
 *     {@code VK_IMAGE_VIEW_TYPE_2D_ARRAY} view spanning all layers and overrides
 *     {@code vkImageView()} to return it. That override is sufficient because
 *     {@code VulkanRenderPass.bindTexture}/{@code pushDescriptors} {@code checkcast} the bound
 *     view to the concrete {@code VulkanGpuTextureView} type (a subclass passes) and read the
 *     native handle via {@code invokevirtual vkImageView()} -- the identical
 *     extend-and-override-the-handle-getter shape {@code ShadowComparisonSampler.ComparisonSampler}
 *     already uses on {@code VulkanGpuSampler}.</li>
 * </ul>
 *
 * <p><b>Every array allocation must come through here, even single-layer ones.</b> A one-layer
 * fallback texture bound to a {@code sampler2DArray} still needs a {@code 2D_ARRAY}-typed view --
 * Vulkan requires the view's type to match the sampler's dimensionality, and a stock view is
 * {@code 2D} regardless of layer count.
 *
 * <p><b>What an array texture can never do through Blaze3D's own {@code CommandEncoder}</b> (API
 * walls, not driver quirks, whenever a texture has {@code getDepthOrLayers() > 1}): be a
 * render-pass attachment, be cleared, or be either side of {@code copyTextureToTexture}/
 * {@code copyTextureToBuffer}. The only CPU write path is layer-aware, bounds-checked
 * {@code writeToTexture(..., mipLevel, depthOrLayer, ...)}; see {@code ArrayTextureLayerProbe} for
 * the shader-sample proof a layer written that way is actually readable. {@link #copyLayer}
 * reaches past the same wall from the GPU side, like {@link #create} reaches past
 * {@code GpuDevice.createTexture}: a hand-recorded {@code vkCmdCopyImage} that never goes through
 * {@code CommandEncoder.copyTextureToTexture}.
 *
 * <p>Vulkan-backend-only by construction: on any other {@code GpuDeviceBackend} implementation
 * {@link #create} latches unavailable and returns {@code null} permanently, mirroring
 * {@code ShadowComparisonSampler}'s GL early-out. Callers treat {@code null} as "paging
 * unavailable" and stay on the single-page path.
 */
public final class ArrayTextures {
    /** Set once {@link #create} has determined the active backend is not Vulkan -- the raw view
     * seam below only reaches {@code VulkanDevice}, so this never retries on GL. */
    private static boolean unavailableOnThisBackend;

    private ArrayTextures() {
    }

    /**
     * One array texture plus the array-typed view that makes its layers reachable from a
     * {@code sampler2DArray}. Close the pair with {@link #close()} -- view first, then texture,
     * matching the stock ownership order (the view's deferred destroy decrements the texture's
     * live-view count, which the texture's own close waits on).
     */
    public record Allocation(GpuTexture texture, GpuTextureView view) implements AutoCloseable {
        @Override
        public void close() {
            view.close();
            texture.close();
        }
    }

    /**
     * Creates a {@code layers}-deep RGBA-style array texture (usage
     * {@code TEXTURE_BINDING | COPY_DST} -- the only usages an array supports here, see the class
     * doc) with an all-layer {@code 2D_ARRAY} view. Returns {@code null} before any GPU device
     * exists yet (retry next frame, the established device-not-ready convention) or permanently on
     * a non-Vulkan backend.
     *
     * <p>Zero-fills every layer of the base mip before returning, since VRAM isn't zero-filled
     * (docs/ARCHITECTURE.md §12) and arrays can't use a render-pass clear. A caller that populates
     * layers gradually via {@link #copyLayer} never shows a stale layer before its first copy.
     */
    @Nullable
    public static Allocation create(String label, GpuFormat format, int width, int height,
                                    int layers, int mipLevels) {
        if (layers < 1) {
            throw new IllegalArgumentException("layers must be >= 1, got " + layers);
        }
        if (unavailableOnThisBackend) {
            return null;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return null;
        }

        GpuDeviceBackend backend = ((GpuDeviceBackendAccessor) (Object) device).fornax$backend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            unavailableOnThisBackend = true;
            return null;
        }

        GpuTexture texture = vulkanDevice.createTexture(label,
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                format, width, height, layers, mipLevels);
        GpuTextureView view;
        try {
            zeroFillLayers(vulkanDevice, texture, format, width, height, layers);
            view = new ArrayView(vulkanDevice, (VulkanGpuTexture) texture);
        } catch (RuntimeException e) {
            // The zero-fill or ArrayView's constructor can throw after texture already succeeded;
            // free it here rather than leak it.
            texture.close();
            throw e;
        }
        return new Allocation(texture, view);
    }

    /** Zero-fills every layer of a freshly created array texture's base mip; arrays can't satisfy
     * the "cleared explicitly at allocation" law (docs/ARCHITECTURE.md §12) via render-pass clear. */
    private static void zeroFillLayers(VulkanDevice vulkanDevice, GpuTexture texture,
                                       GpuFormat format, int width, int height, int layers) {
        int byteSize = width * height * format.blockSize();
        ByteBuffer zero = MemoryUtil.memCalloc(byteSize);
        try {
            var encoder = vulkanDevice.createCommandEncoder();
            for (int layer = 0; layer < layers; layer++) {
                zero.position(0);
                encoder.writeToTexture(texture, zero, 0, layer, 0, 0, width, height);
            }
        } finally {
            MemoryUtil.memFree(zero);
        }
    }

    /**
     * Copies an ordinary, already-rendered 2D texture into one layer of an array texture from
     * {@link #create}, via a hand-recorded {@code vkCmdCopyImage} spliced into the current frame's
     * submission, never through {@code CommandEncoder.copyTextureToTexture} (refuses whenever
     * either side has {@code getDepthOrLayers() > 1}). Both textures stay permanently in
     * {@code VK_IMAGE_LAYOUT_GENERAL}, so every barrier is GENERAL to GENERAL: visibility/ordering
     * only, never a layout transition, and no queue-family transfer since nothing crosses queues.
     *
     * <p>No flush, no host wait: appended to the encoder's current submission like
     * {@code VulkanMetalInterop.recordIntoStream}. Must run between {@code GraphRunner}'s declared
     * passes; {@code VulkanCommandEncoder.execute} throws if called inside a {@code RenderPass}.
     *
     * <p>A no-op on a device or backend {@link #create} would also refuse.
     *
     * @param source the already-rendered source view; its texture's width/height/aspect must match
     *               {@code destination}'s exactly ({@code vkCmdCopyImage} requires it; not
     *               re-checked here)
     * @param destination the array texture and view pair from {@link #create}
     * @param layer which layer of {@code destination} receives the copy
     */
    public static void copyLayer(GpuTextureView source, Allocation destination, int layer) {
        GpuTexture destTexture = destination.texture();
        if (layer < 0 || layer >= destTexture.getDepthOrLayers()) {
            throw new IllegalArgumentException("layer " + layer + " out of range for a "
                    + destTexture.getDepthOrLayers() + "-layer array texture");
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return;
        }
        GpuDeviceBackend backend = ((GpuDeviceBackendAccessor) (Object) device).fornax$backend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            return;
        }

        long srcImage = ((VulkanGpuTexture) source.texture()).vkImage();
        long dstImage = ((VulkanGpuTexture) destTexture).vkImage();
        int aspect = destTexture.getFormat().hasColorAspect()
                ? VK10.VK_IMAGE_ASPECT_COLOR_BIT : VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
        int width = destTexture.getWidth(0);
        int height = destTexture.getHeight(0);

        VulkanCommandEncoder encoder = vulkanDevice.createCommandEncoder();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Make the source's prior graphics writes visible to this transfer read.
            copyBarrier(cmd, stack, srcImage, aspect, 0, 1,
                    VK13.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK13.VK_ACCESS_MEMORY_WRITE_BIT, VK13.VK_ACCESS_TRANSFER_READ_BIT);
            // Order this write after any prior graphics read/write of this same destination layer.
            copyBarrier(cmd, stack, dstImage, aspect, layer, 1,
                    VK13.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK13.VK_ACCESS_MEMORY_READ_BIT | VK13.VK_ACCESS_MEMORY_WRITE_BIT,
                    VK13.VK_ACCESS_TRANSFER_WRITE_BIT);

            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.srcSubresource().aspectMask(aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.dstSubresource().aspectMask(aspect).mipLevel(0).baseArrayLayer(layer).layerCount(1);
            region.extent().set(width, height, 1);
            VK13.vkCmdCopyImage(cmd,
                    srcImage, VK13.VK_IMAGE_LAYOUT_GENERAL,
                    dstImage, VK13.VK_IMAGE_LAYOUT_GENERAL,
                    region);

            // Publish the write back to whatever graphics pass samples this layer next (the
            // resolve, ordinarily).
            copyBarrier(cmd, stack, dstImage, aspect, layer, 1,
                    VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, VK13.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT,
                    VK13.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK13.VK_ACCESS_MEMORY_READ_BIT | VK13.VK_ACCESS_MEMORY_WRITE_BIT);
        }

        int result = VK13.vkEndCommandBuffer(cmd);
        if (result != VK13.VK_SUCCESS) {
            throw new GpuFatalException("vkEndCommandBuffer failed: " + result);
        }
        encoder.execute(cmd);
    }

    /** GENERAL-to-GENERAL visibility/ordering barrier: see {@link #copyLayer}'s own doc for why
     * this is never a layout transition and never a queue-family ownership transfer. */
    private static void copyBarrier(VkCommandBuffer cmd, MemoryStack stack, long image, int aspect,
            int baseLayer, int layerCount, int srcStage, int dstStage, int srcAccess, int dstAccess) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(srcAccess)
                .dstAccessMask(dstAccess)
                .oldLayout(VK13.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK13.VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK13.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK13.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange()
                .aspectMask(aspect).baseMipLevel(0).levelCount(1)
                .baseArrayLayer(baseLayer).layerCount(layerCount);
        VK13.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
    }

    /**
     * A {@code VulkanGpuTextureView} whose native handle is a hand-built
     * {@code VK_IMAGE_VIEW_TYPE_2D_ARRAY} view spanning every layer of its texture. The
     * super-constructor unavoidably builds and stores a plain single-layer {@code 2D} view of its
     * own first (there is no lighter super-constructor to call); unlike
     * {@code ShadowComparisonSampler}'s immediately-destroyed throwaway sampler, that stock view is
     * kept alive until {@link #destroy()} -- {@code close()} routes destruction through the
     * frame-fenced {@code queueForDestroy} path, and destroying the stock handle early would make
     * the inherited {@code destroy()} a double-free. Both handles are freed exactly once, together,
     * when the deferred destroy runs.
     */
    private static final class ArrayView extends VulkanGpuTextureView {
        private final VulkanDevice vulkanDevice;
        private final long arrayVkImageView;

        ArrayView(VulkanDevice device, VulkanGpuTexture texture) {
            super(device, texture, 0, texture.getMipLevels());
            this.vulkanDevice = device;
            this.arrayVkImageView = createArrayView(device, texture);
        }

        @Override
        public long vkImageView() {
            return arrayVkImageView;
        }

        @Override
        public void destroy() {
            super.destroy();
            VK12.vkDestroyImageView(vulkanDevice.vkDevice(), arrayVkImageView, null);
        }

        /**
         * Mirrors the stock view constructor's {@code VkImageViewCreateInfo} field-for-field
         * (format via {@code VulkanConst.toVk}, aspect from {@code GpuFormat.hasColorAspect}, full
         * mip range) with exactly two deltas: {@code viewType = 2D_ARRAY} and
         * {@code layerCount = getDepthOrLayers()}.
         */
        private static long createArrayView(VulkanDevice device, VulkanGpuTexture texture) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .image(texture.vkImage())
                        .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY)
                        .format(VulkanConst.toVk(texture.getFormat()));
                info.subresourceRange()
                        .aspectMask(texture.getFormat().hasColorAspect()
                                ? VK10.VK_IMAGE_ASPECT_COLOR_BIT : VK10.VK_IMAGE_ASPECT_DEPTH_BIT)
                        .baseMipLevel(0)
                        .levelCount(texture.getMipLevels())
                        .baseArrayLayer(0)
                        .layerCount(texture.getDepthOrLayers());

                LongBuffer out = stack.callocLong(1);
                int result = VK12.vkCreateImageView(device.vkDevice(), info, null, out);
                VulkanUtils.crashIfFailure(device, result, "Can't create array texture view");
                return out.get(0);
            }
        }
    }
}
