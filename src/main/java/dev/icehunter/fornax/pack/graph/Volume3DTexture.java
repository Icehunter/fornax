package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import dev.icehunter.fornax.mixin.vulkan.GpuDeviceBackendAccessor;
import dev.icehunter.fornax.pack.RawVolumeAsset;
import dev.icehunter.fornax.util.GpuFatalException;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.function.Consumer;

/**
 * The engine's one creation path for a genuine {@code VK_IMAGE_TYPE_3D} image, sampled as
 * {@code sampler3D}. Blaze3D cannot build one at any layer: {@code GpuDevice.createTexture} throws
 * {@code "Array or 3D textures are not yet supported"} for {@code depthOrLayers > 1}, and the
 * backend seam underneath it is no better: {@code VulkanGpuTexture}'s constructor hardcodes
 * {@code imageType(VK_IMAGE_TYPE_2D)} and {@code extent().set(width, height, 1)}, forwarding its
 * depth argument to {@code arrayLayers} instead (decompiled from the 26.2 client jar). So unlike
 * {@code ArrayTextures}, which hand-builds only the VIEW because Blaze3D's 2D-array IMAGE is
 * already real, this class must hand-build both halves; the image half follows
 * {@code VulkanMetalInterop.createImage}, the existing hand-rolled {@code vmaCreateImage} precedent
 * in this tree.
 *
 * <p><b>The placeholder.</b> The {@code super(...)} call allocates a real, throwaway 1x1x1 image as
 * an unavoidable side effect (that constructor always calls {@code vmaCreateImage}). The overrides
 * below ({@link #vkImage()}, {@link #getWidth(int)}, {@link #getHeight(int)},
 * {@link #getDepthOrLayers()}) make this object describe the REAL volume instead. That is safe
 * because {@code VulkanGpuTexture.destroy()} reads its {@code private final vkImage}/
 * {@code vmaAllocation} fields with {@code getfield} and never dispatches through the overridable
 * {@code vkImage()} getter, so the inherited destroy always frees exactly the placeholder it
 * allocated (bytecode-verified; the same shape {@code ArrayTextures.ArrayView} already relies on
 * one level down). {@link #destroy()} frees the real volume alongside it.
 *
 * <p><b>Why {@link #vkImage()} is published late.</b> {@code VulkanGpuTextureView}'s
 * super-constructor calls {@code texture.vkImage()} VIRTUALLY and builds a stock
 * {@code VK_IMAGE_VIEW_TYPE_2D} view of whatever comes back, using {@code texture.getFormat()}. A
 * 2D view of a 3D image is invalid Vulkan (a 3D image admits only a 3D view unless it was created
 * {@code 2D_ARRAY_COMPATIBLE}), and the placeholder's format need not match the volume's either,
 * so {@code vkImage()} must keep returning the placeholder until that stock view exists. It does:
 * {@code volumeImage} is assigned only after {@link Volume3DTextureView} is constructed, and until
 * then the getter falls back to {@code super.vkImage()}. The {@code 0} sentinel is unambiguous:
 * a successful {@code vmaCreateImage} never yields {@code VK_NULL_HANDLE}, and a failed one crashes
 * in {@code crashIfFailure} before the field is written. <b>Do not make this eager.</b>
 *
 * <p><b>Layout: the uploader owns it, and the destination is {@code GENERAL}.</b> The real volume
 * leaves this constructor in {@code VK_IMAGE_LAYOUT_UNDEFINED}. This class allocates and records no
 * GPU work at all: Blaze3D transitions its own textures through a package-private init command
 * buffer nothing outside {@code com.mojang.blaze3d.vulkan} can reach, and the reachable alternative
 * (a transient command buffer submitted from a constructor) throws
 * {@code "Cannot end command buffer while inside RenderPass"} if a volume is ever created mid-pass.
 * So <b>whatever fills a volume must first barrier it out of {@code UNDEFINED}, and the layout it
 * must barrier TO is {@code VK_IMAGE_LAYOUT_GENERAL}</b>, not the correct-sounding
 * {@code SHADER_READ_ONLY_OPTIMAL}. {@code VulkanRenderPass} writes the sampled-texture descriptor
 * with a hardcoded {@code imageLayout = VK_IMAGE_LAYOUT_GENERAL} (bytecode-verified:
 * {@code iconst_1} into {@code VkDescriptorImageInfo.imageLayout}), so any other choice is a layout
 * mismatch that nothing in Java will report. The same is true of skipping the barrier entirely: a
 * volume bound and sampled before anything uploads reads undefined memory, with no error anywhere.
 * {@link #upload(RawVolumeAsset)} is that uploader, and it is not optional: a volume that is never
 * uploaded is never barriered out of {@code UNDEFINED} either.
 *
 * <p><b>Never fill a volume through {@code CommandEncoder.writeToTexture} or
 * {@code copyBufferToTexture}: they do not throw, and they do not work.</b> Those two paths carry
 * no "multiple depths or layers are not supported" guard (unlike {@code copyTextureToTexture},
 * {@code copyTextureToBuffer} and the clears, which all do). What they check is a BOUNDS condition,
 * {@code depthOrLayer < texture.getDepthOrLayers()}, and {@link #getDepthOrLayers()} below widens
 * that bound to the real volume depth, so a {@code writeToTexture(volume, ..., z)} for any
 * {@code z < depth} sails through validation. The backend then builds an ARRAY-layer copy region
 * ({@code baseArrayLayer(z)}, {@code layerCount(1)}) against an image that has exactly one array
 * layer and puts its depth in the extent instead: invalid Vulkan, dressed as a validated call. A
 * volume's texels must go up through a hand-recorded {@code vkCmdCopyBufferToImage} whose region
 * addresses z as {@code imageOffset.z}/{@code imageExtent.depth}: that is the reason for the
 * hand-rolled upload path, not a preference for one.
 */
public final class Volume3DTexture extends VulkanGpuTexture {
    /** Set once {@link #create} has seen a non-Vulkan backend; the seam below never retries. */
    private static boolean unavailableOnThisBackend;

    /**
     * Host-wait budget for {@link #upload}'s one submission. Deliberately Blaze3D's OWN figure:
     * {@code VulkanCommandEncoder.submit()} waits {@code 5000000000L} on its submit semaphore
     * (bytecode: {@code ldc2_w 5000000000l}), rather than {@code VulkanMetalInterop}'s 2s. That
     * one guards a per-frame diagnostic path; this one flushes whatever the encoder had already
     * recorded this frame ALONG WITH the volume copy, so its wait can legitimately be as long as a
     * whole frame's submission, and a spurious timeout here is a {@link GpuFatalException} at pack
     * load.
     */
    private static final long UPLOAD_FENCE_TIMEOUT_NANOS = 5_000_000_000L;

    private final VulkanDevice device;
    private final int volumeWidth;
    private final int volumeHeight;
    private final int volumeDepth;
    /** The caller's raw {@code VkFormat}, kept so {@link #upload} can size-check an asset. */
    private final int volumeVkFormat;
    private final Volume3DTextureView volumeView;
    /** {@code 0} until {@link #volumeView} exists; see the class doc on late publication. */
    private final long volumeImage;
    private final long volumeAllocation;
    /** Latched by {@link #upload}; a second call is rejected rather than re-barriered. */
    private boolean uploaded;

    private Volume3DTexture(VulkanDevice device, String label, int usage, int vkFormat,
                           int width, int height, int depth) {
        // The throwaway 1x1x1 placeholder. Its usage and label mirror the caller's real intent so
        // it is at least self-consistent; nothing ever samples it.
        super(device, usage, label + " (placeholder)", blaze3dFormatOf(vkFormat), 1, 1, 1, 1);
        this.device = device;
        this.volumeWidth = width;
        this.volumeHeight = height;
        this.volumeDepth = depth;
        this.volumeVkFormat = vkFormat;

        long image;
        long allocation;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK13.VK_IMAGE_TYPE_3D)
                    .format(vkFormat)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK13.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK13.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VulkanConst.textureUsageToVk(usage, blaze3dFormatOf(vkFormat)))
                    .sharingMode(VK13.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK13.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(width, height, depth);
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            int result = Vma.vmaCreateImage(
                    device.vma(), imageInfo, allocInfo, pImage, pAlloc, null);
            VulkanUtils.crashIfFailure(device, result,
                    "Failed to create 3D volume image '" + label + "'");
            image = pImage.get(0);
            allocation = pAlloc.get(0);
        } catch (RuntimeException e) {
            // super(...) has already allocated the placeholder and the caller will get no object to
            // close it with, so it has to go here or it is unreachable forever.
            releasePlaceholderAfterFailedConstruction();
            throw e;
        }

        try {
            // Reads vkImage() virtually -> still the placeholder, by design (see the class doc);
            // the real image and format reach the 3D view as explicit arguments instead.
            this.volumeView = new Volume3DTextureView(device, this, image, vkFormat);
        } catch (RuntimeException e) {
            // The VOLUME image is safe to free inline: this class records no GPU work, so no
            // command buffer has ever named it (it is still in UNDEFINED). The PLACEHOLDER is not
            // safe to free that way and goes out through close() instead; see the helper.
            Vma.vmaDestroyImage(device.vma(), image, allocation);
            releasePlaceholderAfterFailedConstruction();
            throw e;
        }
        this.volumeImage = image;
        this.volumeAllocation = allocation;
    }

    /**
     * Cold-path cleanup for a constructor that has already allocated the placeholder and is about
     * to throw. Routed through {@code super.close()} rather than a direct {@code vmaDestroyImage},
     * because the placeholder is <b>not</b> an unreferenced handle at this point: the
     * super-constructor recorded its {@code UNDEFINED -> GENERAL} initial-layout barrier into the
     * current command buffer, which is unsubmitted or in flight: exactly the hazard the deferred
     * free in {@link #destroy()} exists for. {@code close()} hands it to the frame-fenced
     * {@code queueForDestroy} path the same way a normal close would; the {@code destroy()} that
     * eventually runs sees a {@code 0} volume handle and skips it.
     *
     * <p>The honest limit, since this is cleanup and not a guarantee: {@code close()} only reaches
     * {@code queueForDestroy} once the texture's live-view count falls to zero, so if the failure
     * came from {@code createVolumeView}, after the view's own super-constructor had already run
     * its {@code addViews()}, the count stays at one and neither the placeholder nor the stock
     * view is freed. That is accepted rather than worked around: every route into this method is a
     * {@code vkCreateImage}/{@code vkCreateImageView} failure, and each one reaches
     * {@code VulkanUtils.crashIfFailure} first, which takes the process down with it. Reclaiming
     * device memory on the way to a crash report is not worth a more intricate teardown.
     */
    private void releasePlaceholderAfterFailedConstruction() {
        super.close();
    }

    /**
     * Creates a {@code width x height x depth} volume in the raw Vulkan format {@code vkFormat}
     * (e.g. {@code VK_FORMAT_R8_UNORM} = 9), with a {@code VK_IMAGE_VIEW_TYPE_3D} view over it.
     * Returns {@code null} before any GPU device exists yet (retry next frame, the established
     * device-not-ready convention) or permanently on a non-Vulkan backend, mirroring
     * {@code ArrayTextures.create}'s contract exactly.
     */
    @Nullable
    public static Volume3DTexture create(String label, int usage, int vkFormat,
                                         int width, int height, int depth) {
        if (width < 1 || height < 1 || depth < 1) {
            throw new IllegalArgumentException("volume extent must be >= 1 on every axis, got "
                    + width + "x" + height + "x" + depth);
        }
        if ((usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) {
            // Blaze3D turns that flag into VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT on the placeholder
            // and a 6-layer CUBE view over it; neither is legal against a single-layer image, and
            // a cubemap is not a volume in any case.
            throw new IllegalArgumentException(
                    "USAGE_CUBEMAP_COMPATIBLE is meaningless for a 3D volume");
        }
        if (unavailableOnThisBackend) {
            return null;
        }
        GpuDevice gpuDevice = RenderSystem.tryGetDevice();
        if (gpuDevice == null) {
            return null;
        }
        GpuDeviceBackend backend = ((GpuDeviceBackendAccessor) (Object) gpuDevice).fornax$backend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            unavailableOnThisBackend = true;
            return null;
        }
        return new Volume3DTexture(vulkanDevice, label, usage, vkFormat, width, height, depth);
    }

    /**
     * Blaze3D's {@link GpuFormat} for a raw {@code VkFormat}, found by inverting
     * {@code VulkanConst.toVk} over the enum itself. Blaze3D exposes no reverse lookup, and this
     * borrows its table rather than restating one. Feeds two things: the placeholder's declared
     * format (never sampled, so a miss costs nothing) and {@code textureUsageToVk}, which consults
     * the format only to pick the colour vs. depth ATTACHMENT bit, so an unmapped format falls
     * back to {@code RGBA8_UNORM}, which is safe for the {@code TEXTURE_BINDING | COPY_DST} usage
     * volumes are created with. The real image's own {@code .format()} is always the caller's raw
     * {@code vkFormat}, never this.
     */
    private static GpuFormat blaze3dFormatOf(int vkFormat) {
        for (GpuFormat candidate : GpuFormat.values()) {
            if (VulkanConst.toVk(candidate) == vkFormat) {
                return candidate;
            }
        }
        return GpuFormat.RGBA8_UNORM;
    }

    @Override
    public long vkImage() {
        return volumeImage != 0L ? volumeImage : super.vkImage();
    }

    @Override
    public int getWidth(int mipLevel) {
        return volumeWidth >> mipLevel;
    }

    @Override
    public int getHeight(int mipLevel) {
        return volumeHeight >> mipLevel;
    }

    @Override
    public int getDepthOrLayers() {
        return volumeDepth;
    }

    public int width() {
        return volumeWidth;
    }

    public int height() {
        return volumeHeight;
    }

    public int depth() {
        return volumeDepth;
    }

    /** The {@code VK_IMAGE_VIEW_TYPE_3D} view to bind; {@link #close()} closes it with this. */
    public VulkanGpuTextureView view() {
        return volumeView;
    }

    /**
     * Uploads {@code asset}'s texel bytes into the real volume and leaves the image in
     * {@code VK_IMAGE_LAYOUT_GENERAL}: the layout {@code VulkanRenderPass} binds EVERY sampled
     * texture's descriptor with (bytecode-verified: {@code iconst_1} into
     * {@code VkDescriptorImageInfo.imageLayout}). The optimal-for-sampling layout a general Vulkan
     * renderer would pick is a silent mismatch against this engine, not an improvement.
     *
     * <p><b>Call exactly once, on the render thread, immediately after {@link #create} and never
     * inside a render pass.</b> Each clause is load-bearing:
     * <ul>
     *   <li><b>Once</b>: the first barrier's {@code oldLayout} is {@code UNDEFINED} with
     *       {@code srcStage = TOP_OF_PIPE} / {@code srcAccess = 0}, which is correct only for an
     *       image nothing has read yet. A second call is rejected with
     *       {@code IllegalStateException} rather than silently under-synchronizing against frames
     *       that may already be sampling the volume; a re-uploadable volume would need real source
     *       stage/access masks, not just a relaxed guard.</li>
     *   <li><b>Not inside a render pass</b>: {@code VulkanCommandEncoder.execute} throws
     *       {@code "Cannot execute command buffer while inside RenderPass"} (bytecode-verified).</li>
     *   <li><b>Render thread</b>: this appends to, and then flushes, the encoder's CURRENT
     *       submission: it submits whatever the frame had already recorded, and host-waits
     *       for all of it.</li>
     * </ul>
     *
     * <p><b>Never fill a volume through {@code CommandEncoder.writeToTexture} or
     * {@code copyBufferToTexture} instead</b>; see the class doc: they do not throw on a 3D image,
     * they build an array-layer copy against a one-layer image and hardcode
     * {@code imageExtent.depth = 1}. This method's whole reason for existing is the one region
     * below, whose {@code imageExtent} carries the real depth.
     *
     * @throws IllegalStateException if called twice, or if the volume was created without
     *         {@link GpuTexture#USAGE_COPY_DST} (so the image has no
     *         {@code VK_IMAGE_USAGE_TRANSFER_DST_BIT} and the copy would be invalid Vulkan)
     * @throws IllegalArgumentException if the asset's dimensions, texel size or byte count disagree
     *         with the image; {@code vkCmdCopyBufferToImage} sizes its read from the IMAGE's
     *         format and extent, so any of those mismatches is an out-of-bounds device read of the
     *         staging buffer that nothing in Java would report
     * @see #validateUpload the device-independent half of this method's contract, and the only half
     *      with headless test coverage
     */
    public void upload(RawVolumeAsset asset) {
        long sizeBytes = validateUpload(getLabel(), uploaded, usage(),
                volumeWidth, volumeHeight, volumeDepth, volumeVkFormat, asset);
        // duplicate() so the asset's own buffer position is left alone; RawVolumeAsset hands out
        // the same ByteBuffer instance to every caller.
        ByteBuffer texels = asset.texels().duplicate();
        // Latched BEFORE any GPU work: past this point a throw may leave the image mid-transition,
        // and a retry would barrier it from UNDEFINED a second time.
        uploaded = true;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Staging buffer: host-visible, persistently mapped, TRANSFER_SRC. Same
            // vmaCreateBuffer(VkBufferCreateInfo, VmaAllocationCreateInfo, ...) shape as
            // AnalyticLightListDebug's readback staging buffer, with SEQUENTIAL_WRITE in place of
            // its RANDOM; this one is only ever written by the CPU, front to back.
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(sizeBytes)
                    .usage(VK13.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK13.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo stagingAllocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                            | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer pStagingBuffer = stack.mallocLong(1);
            PointerBuffer pStagingAlloc = stack.mallocPointer(1);
            VmaAllocationInfo stagingInfo = VmaAllocationInfo.calloc(stack);
            int result = Vma.vmaCreateBuffer(device.vma(), bufferInfo, stagingAllocInfo,
                    pStagingBuffer, pStagingAlloc, stagingInfo);
            VulkanUtils.crashIfFailure(device, result,
                    "Failed to create staging buffer for volume '" + getLabel() + "'");
            long stagingBuffer = pStagingBuffer.get(0);
            long stagingAllocation = pStagingAlloc.get(0);
            try {
                // MAPPED_BIT guarantees pMappedData() is a real CPU pointer; no vmaMapMemory.
                MemoryUtil.memByteBuffer(stagingInfo.pMappedData(), (int) sizeBytes).put(texels);
                // Make the CPU write available to the device on non-coherent memory too. A no-op
                // when VMA landed the allocation on HOST_COHERENT memory, which is not guaranteed
                // under AUTO_PREFER_HOST: the write-side mirror of the vmaInvalidateAllocation
                // every readback path in this engine already does.
                Vma.vmaFlushAllocation(device.vma(), stagingAllocation, 0, VK13.VK_WHOLE_SIZE);

                VulkanCommandEncoder encoder = device.createCommandEncoder();
                recordAndFlush(encoder, cmd -> {
                    try (MemoryStack cmdStack = MemoryStack.stackPush()) {
                        imageBarrier(cmd, cmdStack, volumeImage,
                                VK13.VK_IMAGE_LAYOUT_UNDEFINED,
                                VK13.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                VK13.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                                0, VK13.VK_ACCESS_TRANSFER_WRITE_BIT);

                        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, cmdStack);
                        // bufferRowLength/bufferImageHeight stay 0 (calloc): "tightly packed to
                        // imageExtent", which is exactly RawVolumeAsset's x-then-y-then-z layout.
                        region.imageSubresource()
                                .aspectMask(VK13.VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(0)
                                .baseArrayLayer(0)
                                .layerCount(1);
                        region.imageOffset().set(0, 0, 0);
                        // The real depth. This single field is what Blaze3D's own two
                        // buffer-to-texture entry points hardcode to 1.
                        region.imageExtent().set(volumeWidth, volumeHeight, volumeDepth);
                        VK13.vkCmdCopyBufferToImage(cmd, stagingBuffer, volumeImage,
                                VK13.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

                        // ... -> GENERAL. Not SHADER_READ_ONLY_OPTIMAL; see this method's doc.
                        //
                        // The destination scope is deliberately the widest one this codebase
                        // already uses: ALL_COMMANDS with MEMORY_READ|MEMORY_WRITE, the shape of
                        // VulkanMetalInterop.fullBarrier's own destination half, because the set
                        // of things that may touch a volume next is genuinely open: ComputePassRunner
                        // binds a pack-declared image either as COMBINED_IMAGE_SAMPLER (a read) or
                        // as STORAGE_IMAGE (a write), both at GENERAL, and a fullscreen pass may
                        // sample it as well. Naming only the sampling stage and only SHADER_READ
                        // would leave the write case a WAW hazard against the transfer above, and
                        // neither the stage nor the access half of that hole has a Java-side
                        // symptom. srcAccess stays the precise TRANSFER_WRITE_BIT: what wrote this
                        // image is known exactly, it is only the consumer that is not. One barrier,
                        // once, at pack load: the breadth costs nothing measurable.
                        imageBarrier(cmd, cmdStack, volumeImage,
                                VK13.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                VK13.VK_IMAGE_LAYOUT_GENERAL,
                                VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                                VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                                VK13.VK_ACCESS_TRANSFER_WRITE_BIT,
                                VK13.VK_ACCESS_MEMORY_READ_BIT | VK13.VK_ACCESS_MEMORY_WRITE_BIT);
                    }
                });
            } finally {
                // Reached only after recordAndFlush's fence wait returned, which is a correctness
                // requirement and not tidy-up: destroying a buffer a submitted copy still reads is
                // a use-after-free on the GPU timeline.
                Vma.vmaDestroyBuffer(device.vma(), stagingBuffer, stagingAllocation);
            }
        }
    }

    /**
     * Every device-independent precondition {@link #upload} has, in one place, returning the staging
     * size in bytes. Split out from {@code upload} so it can be unit-tested without a GPU: the
     * upload path itself needs a live Vulkan device and so cannot run headless at all, which would
     * otherwise leave these guards (the one part of that path provable offline) entirely
     * unexercised.
     *
     * <p>Every check here covers a failure with no Java-side symptom, which is why each throws
     * rather than logs. {@code vkCmdCopyBufferToImage} sizes its read from the IMAGE's own format
     * and extent, never from anything computed here, so a dimension, texel-size or byte-count
     * disagreement is an out-of-bounds device read of the staging buffer.
     *
     * @param label            the texture's label, for the messages only
     * @param alreadyUploaded  the caller's latch; {@code true} rejects the call
     * @param usage            the {@code GpuTexture.USAGE_*} mask the volume was created with
     * @param volumeVkFormat   the image's raw {@code VkFormat}
     * @return the exact staging-buffer size, {@code w*h*d*bytesPerTexel}, guaranteed to fit an
     *         {@code int}
     */
    static long validateUpload(String label, boolean alreadyUploaded, int usage,
                               int volumeWidth, int volumeHeight, int volumeDepth,
                               int volumeVkFormat, RawVolumeAsset asset) {
        if (alreadyUploaded) {
            throw new IllegalStateException("volume '" + label
                    + "' has already been uploaded; see upload()'s own contract");
        }
        if ((usage & GpuTexture.USAGE_COPY_DST) == 0) {
            throw new IllegalStateException("volume '" + label + "' was created without"
                    + " USAGE_COPY_DST, so its image has no VK_IMAGE_USAGE_TRANSFER_DST_BIT");
        }
        if (asset.width() != volumeWidth || asset.height() != volumeHeight
                || asset.depth() != volumeDepth) {
            throw new IllegalArgumentException("volume asset is " + asset.width() + "x"
                    + asset.height() + "x" + asset.depth() + " but the image is " + volumeWidth
                    + "x" + volumeHeight + "x" + volumeDepth);
        }
        int bytesPerTexel = asset.format().bytesPerTexel();
        // blaze3dFormatOf falls back to RGBA8_UNORM for a VkFormat Blaze3D's own table does not
        // carry. Re-run the inversion's own test to tell a real match from that fallback, and
        // cross-check the texel size only when it really matched. Comparing against the fallback
        // would reject a perfectly good asset.
        GpuFormat declared = blaze3dFormatOf(volumeVkFormat);
        if (VulkanConst.toVk(declared) == volumeVkFormat && declared.blockSize() != bytesPerTexel) {
            throw new IllegalArgumentException("volume asset is " + asset.format() + " ("
                    + bytesPerTexel + " B/texel) but the image format is " + declared + " ("
                    + declared.blockSize() + " B/texel)");
        }
        long sizeBytes = (long) volumeWidth * volumeHeight * volumeDepth * bytesPerTexel;
        if (sizeBytes > Integer.MAX_VALUE) {
            // MemoryUtil.memByteBuffer takes an int length; a volume this large has no in-range
            // mapping and would otherwise wrap to a nonsense capacity.
            throw new IllegalArgumentException("volume '" + label + "' needs " + sizeBytes
                    + " staging bytes, past the addressable limit of a mapped ByteBuffer");
        }
        int available = asset.texels().remaining();
        if (available != sizeBytes) {
            throw new IllegalArgumentException("volume asset carries " + available
                    + " texel bytes but " + volumeWidth + "x" + volumeHeight + "x" + volumeDepth
                    + " " + asset.format() + " needs " + sizeBytes);
        }
        return sizeBytes;
    }

    /**
     * Records into a transient command buffer from the render encoder's own pool, appends it to the
     * encoder's current submission, flushes, and host-waits the fence: a same-package copy of
     * {@code VulkanMetalInterop.recordAndFlush}, whose own version is package-private to
     * {@code dev.icehunter.fornax.metalfx} and additionally MetalFX-scoped, so it cannot be reused
     * from here. Structure is deliberately identical, down to the create-fence-then-submit-then-wait
     * order.
     *
     * <p><b>{@code createFence()} must be taken BEFORE {@code submit()}</b>, and the failure if it
     * is not is loud and immediate rather than subtle: the fence snapshots
     * {@code currentSubmitIndex} in its constructor and {@code submit()} increments it, so a fence
     * created afterwards names a submission that has not happened. {@code awaitSubmitCompletion}
     * tests exactly that case ({@code submitIndex == currentSubmitIndex} with a nonzero timeout)
     * and throws {@code IllegalStateException("Cannot wait on a fence for the current submit")}
     * straight away (bytecode-verified, offsets 11-38). It does not hang and it does not burn the
     * timeout; reordering these two lines fails on the spot.
     */
    private static void recordAndFlush(VulkanCommandEncoder encoder,
                                       Consumer<VkCommandBuffer> recorder) {
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        recorder.accept(cmd);
        int result = VK13.vkEndCommandBuffer(cmd);
        if (result != VK13.VK_SUCCESS) {
            throw new GpuFatalException("vkEndCommandBuffer failed on volume upload: " + result);
        }
        encoder.execute(cmd);
        try (GpuFence fence = encoder.createFence()) {
            encoder.submit();
            if (!fence.awaitCompletion(UPLOAD_FENCE_TIMEOUT_NANOS)) {
                throw new GpuFatalException(
                        "volume upload fence timeout (" + UPLOAD_FENCE_TIMEOUT_NANOS + "ns)");
            }
        }
    }

    /**
     * One-subresource image barrier, mirroring {@code VulkanMetalInterop.imageBarrier} with the
     * aspect fixed to {@code COLOR}: a volume is always a colour image, since {@link #create}
     * builds a {@code VK_IMAGE_ASPECT_COLOR_BIT} view over it unconditionally. The hardcoded
     * {@code levelCount}/{@code layerCount} of 1 match the image: volumes are single-mip,
     * single-array-layer by construction, and depth is extent, not layers.
     */
    private static void imageBarrier(VkCommandBuffer cmd, MemoryStack stack, long image,
                                     int oldLayout, int newLayout, int srcStage, int dstStage,
                                     int srcAccess, int dstAccess) {
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
                .aspectMask(VK13.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VK13.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
    }

    @Override
    public void close() {
        // View first, then texture: the stock ownership order (the view's deferred destroy
        // decrements the texture's live-view count, which the texture's own close waits on).
        volumeView.close();
        super.close();
    }

    /**
     * Runs on the frame-fenced {@code queueForDestroy} path {@code close()} schedules, never
     * inline, which is why the real volume is freed HERE and not in {@link #close()}: frames in
     * flight may still be sampling it.
     */
    @Override
    public void destroy() {
        super.destroy(); // frees the placeholder image, from its own private fields
        if (volumeImage != 0L) {
            // 0 only when a failed constructor queued the placeholder for destruction before
            // assigning these; see releasePlaceholderAfterFailedConstruction.
            Vma.vmaDestroyImage(device.vma(), volumeImage, volumeAllocation);
        }
    }

    /**
     * {@code VK_IMAGE_VIEW_TYPE_3D} view over {@link Volume3DTexture}'s hand-built image. Mirrors
     * {@code ArrayTextures.ArrayView} field-for-field with three deltas: {@code viewType = 3D}, no
     * layer count to widen (a 3D view has none: depth comes from the image's own extent), and
     * both the image handle and the format arrive as explicit arguments. That last one is
     * load-bearing: {@code texture.vkImage()}/{@code texture.getFormat()} still describe the 1x1x1
     * placeholder at this point in construction (class doc), so building the view from them would
     * silently produce a view of the wrong image in the wrong format: {@code RGBA8_UNORM} over an
     * {@code R8_UNORM} volume, four bytes read per texel instead of one.
     *
     * <p>The super-constructor unavoidably builds and keeps a stock single-layer 2D view of the
     * placeholder; it is kept alive until {@link #destroy()} rather than freed early, because
     * {@code close()} routes destruction through the frame-fenced {@code queueForDestroy} path and
     * an early free would make the inherited {@code destroy()} a double-free. Both handles are
     * freed exactly once, together.
     */
    private static final class Volume3DTextureView extends VulkanGpuTextureView {
        private final VulkanDevice vulkanDevice;
        private final long volumeVkImageView;

        Volume3DTextureView(VulkanDevice device, Volume3DTexture texture, long volumeImage,
                            int vkFormat) {
            super(device, texture, 0, 1);
            this.vulkanDevice = device;
            this.volumeVkImageView = createVolumeView(device, volumeImage, vkFormat);
        }

        @Override
        public long vkImageView() {
            return volumeVkImageView;
        }

        @Override
        public void destroy() {
            super.destroy();
            VK12.vkDestroyImageView(vulkanDevice.vkDevice(), volumeVkImageView, null);
        }

        private static long createVolumeView(VulkanDevice device, long volumeImage, int vkFormat) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .image(volumeImage)
                        .viewType(VK10.VK_IMAGE_VIEW_TYPE_3D)
                        .format(vkFormat);
                info.subresourceRange()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);
                LongBuffer out = stack.callocLong(1);
                int result = VK12.vkCreateImageView(device.vkDevice(), info, null, out);
                VulkanUtils.crashIfFailure(device, result, "Can't create 3D volume texture view");
                return out.get(0);
            }
        }
    }
}
