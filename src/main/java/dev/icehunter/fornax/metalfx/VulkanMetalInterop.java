package dev.icehunter.fornax.metalfx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.mixin.vulkan.GpuDeviceBackendAccessor;
import dev.icehunter.fornax.util.GpuFatalException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.EXTMetalObjects;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkExportMetalObjectCreateInfoEXT;
import org.lwjgl.vulkan.VkExportMetalObjectsInfoEXT;
import org.lwjgl.vulkan.VkExportMetalTextureInfoEXT;
import org.lwjgl.vulkan.VkExportMetalSharedEventInfoEXT;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;

import java.nio.LongBuffer;
import java.util.Locale;

/**
 * Vulkan-to-Metal texture interop core (MetalFX spike M1, generalized for M2). Provides
 * export-flagged interop {@code VkImage}s ({@link InteropImage}) allocated through the game's OWN
 * VMA allocator ({@code VulkanDevice.vma()}, same {@code AUTO_PREFER_DEVICE} usage Blaze3D's
 * {@code VulkanGpuTexture} uses), their exported {@code MTLTexture}s (via {@code
 * vkExportMetalObjectsEXT} -- requires {@code VulkanDeviceExtensionMixin}), and the shared
 * copy/sync helpers both the M1 passthrough (here) and the M2 upscale pass
 * ({@link MetalFxUpscalePass}) build on.
 *
 * <p>ORDERING/SYNC MODEL: the production scaler path exports a Vulkan timeline semaphore as the
 * same {@code MTLSharedEvent} Metal encodes waits/signals against. Vulkan copy-in signals v, Metal
 * waits v and signals v+1, and Vulkan copy-back waits v+1 and signals v+2. No steady-state host
 * wait is involved. {@link #recordAndFlush} remains for the passthrough self-test and the explicit
 * hard-sync diagnostic path.
 *
 * <p>LAYOUTS: Blaze3D-owned images stay {@code GENERAL}, matching its own tracking. Interop-owned
 * inputs transition to {@code TRANSFER_DST_OPTIMAL} for copy-in and back to {@code GENERAL} for
 * Metal reads; the output transitions from {@code GENERAL} to {@code TRANSFER_SRC_OPTIMAL} for
 * copy-back and returns to {@code GENERAL}. Barriers use transfer/graphics access masks; the
 * shared event supplies cross-API availability.
 *
 * <p>M1 PASSTHROUGH (retained as the interop's standing self-test): {@code
 * -Dfornax.metalfx.passthrough=true} roundtrips the low-res frame color through a Metal blit --
 * live-verified 2026-07-23 ("MetalFX passthrough live: 1144x643 vkFormat=37 -> pixelFormat=70",
 * frame visually normal). Fail-safe: first failure logs once and permanently disables for the
 * session.
 */
public final class VulkanMetalInterop {
    private static final boolean PASSTHROUGH_REQUESTED =
            Boolean.getBoolean("fornax.metalfx.passthrough");
    static final long FENCE_TIMEOUT_NANOS = 2_000_000_000L;

    private static boolean failed;
    private static boolean loggedOnce;

    // M1 passthrough image pair.
    private static InteropImage imageA;
    private static InteropImage imageB;

    // Fornax-owned Metal command queue (retained, process lifetime), created on first use --
    // shared by the passthrough and the M2 upscale pass.
    private static long metalCommandQueue;

    private VulkanMetalInterop() {}

    /** One export-flagged interop VkImage + its exported MTLTexture. */
    static final class InteropImage {
        final long image;
        final long allocation;
        final long mtlTexture;
        final int width;
        final int height;
        final int vkFormat;
        final int aspect;
        int layout = VK13.VK_IMAGE_LAYOUT_UNDEFINED;

        private InteropImage(long image, long allocation, long mtlTexture,
                int width, int height, int vkFormat, int aspect) {
            this.image = image;
            this.allocation = allocation;
            this.mtlTexture = mtlTexture;
            this.width = width;
            this.height = height;
            this.vkFormat = vkFormat;
            this.aspect = aspect;
        }
    }

    /** The live VulkanDevice, or null on the GL backend / before a device exists. */
    static VulkanDevice vulkanDevice() {
        GpuDevice gpuDevice = RenderSystem.tryGetDevice();
        if (gpuDevice == null) {
            return null;
        }
        GpuDeviceBackend backend = ((GpuDeviceBackendAccessor) (Object) gpuDevice).fornax$backend();
        return backend instanceof VulkanDevice vulkanDevice ? vulkanDevice : null;
    }

    /** The fornax-owned MTLCommandQueue (lazily created, retained forever). */
    static long metalCommandQueue() {
        if (metalCommandQueue == 0) {
            metalCommandQueue = Objc.msgSendId(MetalFxSupport.metalDevice(),
                    Objc.selector("newCommandQueue"));
            if (metalCommandQueue == 0) {
                throw new IllegalStateException("newCommandQueue returned nil");
            }
        }
        return metalCommandQueue;
    }

    /**
     * Allocates an export-flagged interop image (VMA, {@code AUTO_PREFER_DEVICE}) and exports its
     * MTLTexture. {@code usage} flags matter beyond Vulkan: MoltenVK derives the MTLTexture's own
     * usage set from them (SAMPLED->ShaderRead, STORAGE->ShaderRead|Write,
     * COLOR_ATTACHMENT->RenderTarget), and MetalFX validates those usages on its input/output
     * textures.
     */
    static InteropImage createImage(VulkanDevice device, int width, int height, int vkFormat,
            int usage, int aspect) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExportMetalObjectCreateInfoEXT exportDecl = VkExportMetalObjectCreateInfoEXT.calloc(stack)
                    .sType$Default()
                    .exportObjectType(EXTMetalObjects.VK_EXPORT_METAL_OBJECT_TYPE_METAL_TEXTURE_BIT_EXT);
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(exportDecl)
                    .imageType(VK13.VK_IMAGE_TYPE_2D)
                    .format(vkFormat)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK13.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK13.VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK13.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK13.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(width, height, 1);
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            int result = Vma.vmaCreateImage(device.vma(), imageInfo, allocInfo, pImage, pAlloc, null);
            if (result != VK13.VK_SUCCESS) {
                throw new IllegalStateException("vmaCreateImage failed: " + result);
            }
            long image = pImage.get(0);

            VkExportMetalTextureInfoEXT texInfo = VkExportMetalTextureInfoEXT.calloc(stack)
                    .sType$Default()
                    .image(image)
                    .plane(VK13.VK_IMAGE_ASPECT_PLANE_0_BIT);
            VkExportMetalObjectsInfoEXT exportInfo = VkExportMetalObjectsInfoEXT.calloc(stack)
                    .sType$Default()
                    .pNext(texInfo);
            EXTMetalObjects.vkExportMetalObjectsEXT(device.vkDevice(), exportInfo);
            long mtlTexture = texInfo.mtlTexture();
            if (mtlTexture == 0) {
                Vma.vmaDestroyImage(device.vma(), image, pAlloc.get(0));
                throw new IllegalStateException("vkExportMetalObjectsEXT returned nil MTLTexture");
            }
            return new InteropImage(image, pAlloc.get(0), mtlTexture, width, height, vkFormat, aspect);
        }
    }

    /**
     * Destroys an already-quiesced image. Callers must first wait for their own final fence or
     * timeline value; keeping synchronization at the resource-set level avoids one device-wide
     * idle per image during resize.
     */
    static void destroyImage(VulkanDevice device, InteropImage img) {
        if (img == null) {
            return;
        }
        Vma.vmaDestroyImage(device.vma(), img.image, img.allocation);
    }

    /** An exported cross-API sync pair: one Vulkan timeline semaphore = one MTLSharedEvent. */
    static final class SharedTimeline {
        final long vkSemaphore;
        final long mtlSharedEvent;

        private SharedTimeline(long vkSemaphore, long mtlSharedEvent) {
            this.vkSemaphore = vkSemaphore;
            this.mtlSharedEvent = mtlSharedEvent;
        }
    }

    /**
     * Creates a Vulkan TIMELINE semaphore declared exportable and exports its backing
     * {@code MTLSharedEvent} (MoltenVK implements timeline semaphores over MTLSharedEvent, so the
     * export hands back the SAME event object the Vulkan side signals/waits) -- the M3 zero-stall
     * sync primitive: Vulkan signals value v after the copy-in, Metal GPU-waits v and signals v+1
     * after the scaler, Vulkan GPU-waits v+1 before the copy-back. No host blocking anywhere.
     */
    static SharedTimeline createSharedTimeline(VulkanDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExportMetalObjectCreateInfoEXT exportDecl = VkExportMetalObjectCreateInfoEXT.calloc(stack)
                    .sType$Default()
                    .exportObjectType(EXTMetalObjects.VK_EXPORT_METAL_OBJECT_TYPE_METAL_SHARED_EVENT_BIT_EXT);
            VkSemaphoreTypeCreateInfo typeInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(exportDecl.address())
                    .semaphoreType(VK13.VK_SEMAPHORE_TYPE_TIMELINE)
                    .initialValue(0);
            VkSemaphoreCreateInfo createInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(typeInfo.address());
            LongBuffer out = stack.mallocLong(1);
            int result = VK13.vkCreateSemaphore(device.vkDevice(), createInfo, null, out);
            if (result != VK13.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateSemaphore(timeline, exportable) failed: " + result);
            }
            long semaphore = out.get(0);

            VkExportMetalSharedEventInfoEXT eventInfo = VkExportMetalSharedEventInfoEXT.calloc(stack)
                    .sType$Default()
                    .semaphore(semaphore);
            VkExportMetalObjectsInfoEXT exportInfo = VkExportMetalObjectsInfoEXT.calloc(stack)
                    .sType$Default()
                    .pNext(eventInfo);
            EXTMetalObjects.vkExportMetalObjectsEXT(device.vkDevice(), exportInfo);
            long sharedEvent = eventInfo.mtlSharedEvent();
            if (sharedEvent == 0) {
                VK13.vkDestroySemaphore(device.vkDevice(), semaphore, null);
                throw new IllegalStateException("vkExportMetalObjectsEXT returned nil MTLSharedEvent");
            }
            return new SharedTimeline(semaphore, sharedEvent);
        }
    }

    /** Host-waits one interop timeline value on resize/teardown, never in the per-frame path. */
    static void waitTimeline(VulkanDevice device, SharedTimeline timeline, long value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc(stack)
                    .sType$Default()
                    .pSemaphores(stack.longs(timeline.vkSemaphore))
                    .pValues(stack.longs(value));
            int result = VK13.vkWaitSemaphores(
                    device.vkDevice(), waitInfo, FENCE_TIMEOUT_NANOS);
            if (result != VK13.VK_SUCCESS) {
                throw new GpuFatalException(
                        "interop timeline wait failed at " + value + ": " + result);
            }
        }
    }

    interface CmdRecorder {
        void record(VkCommandBuffer cmd);
    }

    /**
     * Records into a transient command buffer and appends it to the encoder's CURRENT submission
     * WITHOUT flushing -- for the event-synced path, where ordering against Metal comes from
     * {@link SharedTimeline} waits instead of host fences.
     */
    static void recordIntoStream(VulkanCommandEncoder encoder, CmdRecorder recorder) {
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        recorder.record(cmd);
        int result = VK13.vkEndCommandBuffer(cmd);
        if (result != VK13.VK_SUCCESS) {
            throw new GpuFatalException("vkEndCommandBuffer failed: " + result);
        }
        encoder.execute(cmd);
    }

    /**
     * Records into a transient command buffer from the encoder's own pool, appends it to the
     * encoder's submission (ordering after all prior recorded work), then flushes and host-waits.
     */
    static void recordAndFlush(VulkanCommandEncoder encoder, CmdRecorder recorder) {
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        recorder.record(cmd);
        int result = VK13.vkEndCommandBuffer(cmd);
        if (result != VK13.VK_SUCCESS) {
            throw new GpuFatalException("vkEndCommandBuffer failed: " + result);
        }
        encoder.execute(cmd);
        try (GpuFence fence = encoder.createFence()) {
            encoder.submit();
            if (!fence.awaitCompletion(FENCE_TIMEOUT_NANOS)) {
                throw new GpuFatalException("interop fence timeout (" + FENCE_TIMEOUT_NANOS + "ns)");
            }
        }
    }

    /** Conservative diagnostic-only barrier retained by the M1 passthrough path. */
    static void fullBarrier(VkCommandBuffer cmd, MemoryStack stack, long image, int aspect,
            int oldLayout, int newLayout) {
        imageBarrier(cmd, stack, image, aspect, oldLayout, newLayout,
                VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK13.VK_ACCESS_MEMORY_WRITE_BIT | VK13.VK_ACCESS_MEMORY_READ_BIT,
                VK13.VK_ACCESS_MEMORY_WRITE_BIT | VK13.VK_ACCESS_MEMORY_READ_BIT);
    }

    static void imageBarrier(VkCommandBuffer cmd, MemoryStack stack, long image, int aspect,
            int oldLayout, int newLayout, int srcStage, int dstStage, int srcAccess,
            int dstAccess) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(srcAccess)
                .dstAccessMask(dstAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK13.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK13.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange()
                .aspectMask(aspect)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VK13.vkCmdPipelineBarrier(cmd,
                srcStage, dstStage,
                0, null, null, barrier);
    }

    /** Barrier an interop image into GENERAL (first use transitions from UNDEFINED). */
    static void prepareInterop(VkCommandBuffer cmd, MemoryStack stack, InteropImage img) {
        fullBarrier(cmd, stack, img.image, img.aspect, img.layout, VK13.VK_IMAGE_LAYOUT_GENERAL);
        img.layout = VK13.VK_IMAGE_LAYOUT_GENERAL;
    }

    /** Blaze3D-owned GENERAL image: make prior graphics writes visible to a transfer read. */
    static void prepareGeneralTransferRead(
            VkCommandBuffer cmd, MemoryStack stack, long image, int aspect) {
        imageBarrier(cmd, stack, image, aspect,
                VK13.VK_IMAGE_LAYOUT_GENERAL, VK13.VK_IMAGE_LAYOUT_GENERAL,
                VK13.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK13.VK_ACCESS_MEMORY_WRITE_BIT, VK13.VK_ACCESS_TRANSFER_READ_BIT);
    }

    /** Blaze3D-owned GENERAL image: prepare a transfer write after prior graphics use. */
    static void prepareGeneralTransferWrite(
            VkCommandBuffer cmd, MemoryStack stack, long image, int aspect) {
        imageBarrier(cmd, stack, image, aspect,
                VK13.VK_IMAGE_LAYOUT_GENERAL, VK13.VK_IMAGE_LAYOUT_GENERAL,
                VK13.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK13.VK_ACCESS_MEMORY_READ_BIT | VK13.VK_ACCESS_MEMORY_WRITE_BIT,
                VK13.VK_ACCESS_TRANSFER_WRITE_BIT);
    }

    /** Publish a transfer write back to later Blaze3D graphics consumers. */
    static void finishGeneralTransferWrite(
            VkCommandBuffer cmd, MemoryStack stack, long image, int aspect) {
        imageBarrier(cmd, stack, image, aspect,
                VK13.VK_IMAGE_LAYOUT_GENERAL, VK13.VK_IMAGE_LAYOUT_GENERAL,
                VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, VK13.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT,
                VK13.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK13.VK_ACCESS_MEMORY_READ_BIT | VK13.VK_ACCESS_MEMORY_WRITE_BIT);
    }

    /** Interop input: transition from Metal-readable GENERAL to Vulkan TRANSFER_DST. */
    static void prepareInteropTransferWrite(
            VkCommandBuffer cmd, MemoryStack stack, InteropImage img) {
        imageBarrier(cmd, stack, img.image, img.aspect,
                img.layout, VK13.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK13.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, VK13.VK_ACCESS_TRANSFER_WRITE_BIT);
        img.layout = VK13.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    }

    /** Interop input: publish the Vulkan copy for the following Metal read. */
    static void finishInteropTransferWrite(
            VkCommandBuffer cmd, MemoryStack stack, InteropImage img) {
        imageBarrier(cmd, stack, img.image, img.aspect,
                img.layout, VK13.VK_IMAGE_LAYOUT_GENERAL,
                VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, VK13.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                VK13.VK_ACCESS_TRANSFER_WRITE_BIT, 0);
        img.layout = VK13.VK_IMAGE_LAYOUT_GENERAL;
    }

    /** Interop output's first-use layout transition before Metal writes it. */
    static void prepareInteropMetalWrite(
            VkCommandBuffer cmd, MemoryStack stack, InteropImage img) {
        if (img.layout == VK13.VK_IMAGE_LAYOUT_GENERAL) {
            return;
        }
        imageBarrier(cmd, stack, img.image, img.aspect,
                img.layout, VK13.VK_IMAGE_LAYOUT_GENERAL,
                VK13.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK13.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                0, 0);
        img.layout = VK13.VK_IMAGE_LAYOUT_GENERAL;
    }

    /** Interop output: make the semaphore-ordered Metal write available to a Vulkan copy. */
    static void prepareInteropTransferRead(
            VkCommandBuffer cmd, MemoryStack stack, InteropImage img) {
        imageBarrier(cmd, stack, img.image, img.aspect,
                img.layout, VK13.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK13.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, VK13.VK_ACCESS_TRANSFER_READ_BIT);
        img.layout = VK13.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    }

    /** Restore an interop output to GENERAL for the next Metal encode. */
    static void finishInteropTransferRead(
            VkCommandBuffer cmd, MemoryStack stack, InteropImage img) {
        imageBarrier(cmd, stack, img.image, img.aspect,
                img.layout, VK13.VK_IMAGE_LAYOUT_GENERAL,
                VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, VK13.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                VK13.VK_ACCESS_TRANSFER_READ_BIT, 0);
        img.layout = VK13.VK_IMAGE_LAYOUT_GENERAL;
    }

    static void copyImage(VkCommandBuffer cmd, MemoryStack stack, long src, long dst,
            int aspect, int width, int height) {
        copyImage(cmd, stack, src, VK13.VK_IMAGE_LAYOUT_GENERAL,
                dst, VK13.VK_IMAGE_LAYOUT_GENERAL, aspect, width, height);
    }

    static void copyImage(VkCommandBuffer cmd, MemoryStack stack,
            long src, int srcLayout, long dst, int dstLayout,
            int aspect, int width, int height) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.srcSubresource().aspectMask(aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.dstSubresource().aspectMask(aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.extent().set(width, height, 1);
        VK13.vkCmdCopyImage(cmd,
                src, srcLayout,
                dst, dstLayout,
                region);
    }

    /** Maps the Blaze3D formats this interop handles to VkFormat. */
    static int mapFormat(String gpuFormatName) {
        String name = gpuFormatName.toUpperCase(Locale.ROOT);
        if (name.contains("RGBA8")) {
            return VK13.VK_FORMAT_R8G8B8A8_UNORM;
        }
        if (name.contains("BGRA8")) {
            return VK13.VK_FORMAT_B8G8R8A8_UNORM;
        }
        if (name.contains("RG16") && name.contains("FLOAT")) {
            return VK13.VK_FORMAT_R16G16_SFLOAT;
        }
        if (name.contains("D32")) {
            return VK13.VK_FORMAT_D32_SFLOAT;
        }
        throw new IllegalStateException("unsupported format for interop: " + gpuFormatName);
    }

    // ------------------------------------------------------------------------------------------
    // M1 passthrough (see class header)
    // ------------------------------------------------------------------------------------------

    /**
     * M1 passthrough: copies the low-res frame color into interop image A, Metal-blits A into B,
     * and copies B back over the frame -- a visually-identity roundtrip. No-op unless
     * {@code -Dfornax.metalfx.passthrough=true}.
     */
    public static void passthroughIfEnabled(RenderTarget lowRes) {
        if (!PASSTHROUGH_REQUESTED || failed) {
            return;
        }
        if (!MetalFxSupport.isAvailable()) {
            failed = true;
            FornaxMod.LOGGER.warn("[Fornax] MetalFX passthrough requested but probe says unavailable");
            return;
        }
        try {
            runPassthrough(lowRes);
        } catch (Throwable t) {
            failed = true;
            FornaxMod.LOGGER.error("[Fornax] MetalFX passthrough FAILED -- disabled for this session", t);
        }
    }

    private static void runPassthrough(RenderTarget lowRes) {
        VulkanDevice device = vulkanDevice();
        if (device == null) {
            throw new IllegalStateException("no Vulkan device (GL backend?)");
        }
        VulkanGpuTexture colorTex = (VulkanGpuTexture) lowRes.getColorTextureView().texture();
        int width = colorTex.getWidth(0);
        int height = colorTex.getHeight(0);
        int vkFormat = mapFormat(colorTex.getFormat().toString());
        if (imageA != null && (imageA.width != width || imageA.height != height
                || imageA.vkFormat != vkFormat)) {
            destroyImage(device, imageA);
            destroyImage(device, imageB);
            imageA = null;
            imageB = null;
        }
        if (imageA == null) {
            int usage = VK13.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK13.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            imageA = createImage(device, width, height, vkFormat, usage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
            imageB = createImage(device, width, height, vkFormat, usage, VK13.VK_IMAGE_ASPECT_COLOR_BIT);
        }

        VulkanCommandEncoder encoder = device.createCommandEncoder();
        long srcImage = colorTex.vkImage();

        recordAndFlush(encoder, cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                fullBarrier(cmd, stack, srcImage, VK13.VK_IMAGE_ASPECT_COLOR_BIT,
                        VK13.VK_IMAGE_LAYOUT_GENERAL, VK13.VK_IMAGE_LAYOUT_GENERAL);
                prepareInterop(cmd, stack, imageA);
                prepareInterop(cmd, stack, imageB);
                copyImage(cmd, stack, srcImage, imageA.image, VK13.VK_IMAGE_ASPECT_COLOR_BIT, width, height);
                fullBarrier(cmd, stack, imageA.image, VK13.VK_IMAGE_ASPECT_COLOR_BIT,
                        VK13.VK_IMAGE_LAYOUT_GENERAL, VK13.VK_IMAGE_LAYOUT_GENERAL);
            }
        });

        long pool = Objc.autoreleasePoolPush();
        try {
            long cb = Objc.msgSendId(metalCommandQueue(), Objc.selector("commandBuffer"));
            long blit = Objc.msgSendId(cb, Objc.selector("blitCommandEncoder"));
            if (cb == 0 || blit == 0) {
                throw new GpuFatalException("Metal command buffer/blit encoder nil");
            }
            Objc.msgSendVoid(blit, Objc.selector("copyFromTexture:toTexture:"),
                    imageA.mtlTexture, imageB.mtlTexture);
            Objc.msgSendVoid(blit, Objc.selector("endEncoding"));
            Objc.msgSendVoid(cb, Objc.selector("commit"));
            Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));
        } finally {
            Objc.autoreleasePoolPop(pool);
        }

        recordAndFlush(encoder, cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                fullBarrier(cmd, stack, imageB.image, VK13.VK_IMAGE_ASPECT_COLOR_BIT,
                        VK13.VK_IMAGE_LAYOUT_GENERAL, VK13.VK_IMAGE_LAYOUT_GENERAL);
                copyImage(cmd, stack, imageB.image, srcImage, VK13.VK_IMAGE_ASPECT_COLOR_BIT, width, height);
                fullBarrier(cmd, stack, srcImage, VK13.VK_IMAGE_ASPECT_COLOR_BIT,
                        VK13.VK_IMAGE_LAYOUT_GENERAL, VK13.VK_IMAGE_LAYOUT_GENERAL);
            }
        });

        if (!loggedOnce) {
            loggedOnce = true;
            FornaxMod.LOGGER.info(
                    "[Fornax] MetalFX passthrough live: {}x{} vkFormat={} -> MTLTexture A {}x{} pixelFormat={}",
                    width, height, vkFormat,
                    Objc.msgSendLong(imageA.mtlTexture, Objc.selector("width")),
                    Objc.msgSendLong(imageA.mtlTexture, Objc.selector("height")),
                    Objc.msgSendLong(imageA.mtlTexture, Objc.selector("pixelFormat")));
        }
    }
}
