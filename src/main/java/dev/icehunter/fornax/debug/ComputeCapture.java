package dev.icehunter.fornax.debug;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pipeline.CapturedSamplerState;
import dev.icehunter.fornax.pack.graph.PackTextureRegistry;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

/** One selected raw dispatch, captured on its own compute queue. Staging is first-used only on
 * that queue and survives a timed-out capture until the runner confirms the slot's fence. No GPU
 * copies, allocations or waits occur for unselected passes. */
public final class ComputeCapture {
    // Five seconds bounds a debug readback; timeout does not authorize freeing GPU-owned storage.
    public static final long FENCE_TIMEOUT_NANOS = 5_000_000_000L;
    private final FullscreenCapture.Capture capture;
    private final VulkanComputeBackend backend;
    private final String name;
    private final int inputCount;
    private final List<Map<String, Object>> bindings = new ArrayList<>();
    private final List<Readback> readbacks = new ArrayList<>();
    private boolean dispatched;
    private boolean failed;
    private boolean retired;

    private static final class Readback {
        final Map<String, Object> entry;
        final long sourceBuffer;
        final long sourceOffset;
        final GpuTextureView image;
        final List<Map<String, Object>> mips;
        final long bytes;
        final boolean output;
        long buffer;
        long allocation;
        long mapped;

        Readback(Map<String, Object> entry, long sourceBuffer, long sourceOffset,
                 GpuTextureView image, List<Map<String, Object>> mips, long bytes, boolean output) {
            this.entry = entry; this.sourceBuffer = sourceBuffer; this.sourceOffset = sourceOffset;
            this.image = image; this.mips = mips; this.bytes = bytes; this.output = output;
        }
    }

    public static ComputeCapture begin(PassSpec spec, VulkanComputeBackend backend,
                                       String resolvedSource, byte[] spirv) {
        var request = FullscreenCapture.beginCompute(spec.name(), spec.shader());
        if (request == null) return null;
        ComputeCapture result = new ComputeCapture(request, spec, backend);
        result.guard(() -> {
            result.data().put("shaderSource", result.artifact(spec.name() + ".comp", resolvedSource.getBytes(StandardCharsets.UTF_8)));
            result.data().put("spirv", result.artifact(spec.name() + ".spv", spirv));
            result.data().put("localSize", localSize(spirv));
            result.data().put("declaredLocalSize", spec.localSize());
        });
        return result;
    }

    private ComputeCapture(FullscreenCapture.Capture capture, PassSpec spec, VulkanComputeBackend backend) {
        this.capture = capture; this.backend = backend; this.name = spec.name();
        this.inputCount = spec.inputs().size();
        data().put("bindings", bindings);
        data().put("entryPoint", "main");
        data().put("descriptorSet", 0);
        data().put("computeQueueFamily", backend.computeQueue().queueFamilyIndex());
        data().put("graphicsQueueFamily", backend.device().graphicsQueue().queueFamilyIndex());
        data().put("shaderCacheGeneration", dev.icehunter.fornax.pack.graph.GraphRunner.shaderCacheGeneration());
        data().put("captureFenceTimeoutNanos", FENCE_TIMEOUT_NANOS);
    }

    private Map<String, Object> data() { return capture.computeData(); }

    /** Values come from the same descriptor info submitted to vkUpdateDescriptorSets. */
    public void buffer(int binding, String ref, int type, long handle, long offset, long range,
                       long bufferBytes, boolean copySource) {
        guard(() -> {
            Map<String, Object> entry = binding(binding, ref, type, "buffer");
            entry.put("vkBuffer", Long.toUnsignedString(handle));
            entry.put("sourceOffset", offset);
            entry.put("descriptorRange", range);
            entry.put("bufferBytes", bufferBytes);
            if (!copySource) {
                unavailable(entry, "Bound UBO lacks COPY_SRC; configure capture before startup");
                return;
            }
            long bytes = bufferCopyBytes(offset, range, bufferBytes);
            capture.reserveBytes(bytes);
            readbacks.add(new Readback(entry, handle, offset, null, List.of(), bytes, binding >= inputCount));
        });
    }

    /** The actual resolved view and sampler are supplied by the descriptor binding operation. */
    public void image(int binding, String ref, int type, GpuTextureView view, GpuSampler sampler,
                      PackTextureRegistry.VolumeAsset asset) {
        guard(() -> {
            Map<String, Object> entry = binding(binding, ref, type, asset != null ? "image3d" : "image2d");
            if (sampler != null) {
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("minFilter", sampler.getMinFilter().name());
                state.put("magFilter", sampler.getMagFilter().name());
                state.put("addressModeU", sampler.getAddressModeU().name());
                state.put("addressModeV", sampler.getAddressModeV().name());
                state.put("maxAnisotropy", sampler.getMaxAnisotropy());
                state.put("maxLod", sampler.getMaxLod().isPresent() ? sampler.getMaxLod().getAsDouble() : null);
                Map<String, Object> nativeState = sampler instanceof CapturedSamplerState captured
                        ? captured.fornax$samplerState() : Map.of();
                state.put("native", nativeState);
                entry.put("sampler", state);
                if (nativeState.isEmpty() || ((Number) nativeState.get("pNext")).longValue() != 0) {
                    unavailable(entry, "Exact native sampler state unavailable or uses an unsupported extension chain");
                    return;
                }
            }
            entry.put("imageLayout", VK13.VK_IMAGE_LAYOUT_GENERAL);
            if (asset != null) {
                var upload = asset.upload();
                entry.put("assetFile", asset.file());
                entry.put("format", upload.format().name());
                entry.put("vkFormat", upload.vkFormat());
                entry.put("width", upload.width()); entry.put("height", upload.height()); entry.put("depth", upload.depth());
                entry.put("baseMipLevel", 0); entry.put("mipLevels", upload.mipLevels());
                byte[] raw = copyBytes(upload.texels());
                capture.reserveBytes(raw.length);
                entry.putAll(artifact(filename(binding), raw));
                entry.put("uploadSha256", upload.sha256());
                entry.put("status", "captured");
                return;
            }
            GpuTexture texture = view.texture();
            entry.put("format", texture.getFormat().name());
            entry.put("width", view.getWidth(0)); entry.put("height", view.getHeight(0)); entry.put("depth", 1);
            entry.put("baseMipLevel", view.baseMipLevel()); entry.put("mipLevels", view.mipLevels());
            if (texture.getDepthOrLayers() != 1 || texture.getFormat().hasStencilAspect()
                    || (texture.usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
                unavailable(entry, "Image requires single-layer non-stencil COPY_SRC allocation or an exact volume upload snapshot");
                return;
            }
            List<Map<String, Object>> mips = new ArrayList<>();
            long bytes = 0;
            for (int level = 0; level < view.mipLevels(); level++) {
                // Vulkan buffer-image copy offsets align to four bytes as well as texel blocks.
                bytes = (bytes + 3) & ~3L;
                long count = FullscreenCapture.textureBytes(texture.getFormat(), view.getWidth(level), view.getHeight(level), 1);
                mips.add(Map.of("level", level, "width", view.getWidth(level), "height", view.getHeight(level),
                        "byteOffset", bytes, "byteCount", count));
                bytes = Math.addExact(bytes, count);
            }
            capture.reserveBytes(bytes);
            entry.put("mips", mips);
            readbacks.add(new Readback(entry, 0, 0, view, mips, bytes, binding >= inputCount));
        });
    }

    public void beforeDispatch(VkCommandBuffer cmd, ByteBuffer push, int x, int y, int z, boolean dispatchKernel) {
        guard(() -> {
            dispatched = dispatchKernel;
            data().put("dispatched", dispatched);
            data().put("groups", List.of(x, y, z));
            data().put("pushConstants", artifact(name + "-push.bin", copyBytes(push)));
            if (!dispatchKernel) { fail("Selected compute pass reused its prior result; no kernel dispatched"); return; }
            barrier(cmd, VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK13.VK_ACCESS_MEMORY_WRITE_BIT, VK13.VK_ACCESS_TRANSFER_READ_BIT);
            recordCopies(cmd, false);
            barrier(cmd, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK13.VK_ACCESS_TRANSFER_READ_BIT, VK13.VK_ACCESS_SHADER_READ_BIT | VK13.VK_ACCESS_SHADER_WRITE_BIT);
        });
    }

    public void afterDispatch(VkCommandBuffer cmd) {
        capture.computeDispatched();
        guard(() -> {
            barrier(cmd, VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK13.VK_ACCESS_SHADER_WRITE_BIT, VK13.VK_ACCESS_TRANSFER_READ_BIT);
            recordCopies(cmd, true);
            barrier(cmd, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, VK13.VK_PIPELINE_STAGE_HOST_BIT,
                    VK13.VK_ACCESS_TRANSFER_WRITE_BIT, VK13.VK_ACCESS_HOST_READ_BIT);
        });
    }

    /** True permits the runner to release the capture object; timeout retains it on the ring slot. */
    public boolean awaitFence(long fence) {
        int result = VK13.vkWaitForFences(backend.device().vkDevice(), fence, true, FENCE_TIMEOUT_NANOS);
        data().put("captureFenceResult", result);
        if (result != VK13.VK_SUCCESS) {
            fail("Compute capture fence did not complete within the bounded wait: VkResult " + result);
            return false;
        }
        retireAfterFence();
        return true;
    }

    /** Only after a successful wait for the dispatch fence; includes non-coherent invalidation. */
    public void retireAfterFence() {
        if (retired) return;
        try {
            for (Readback readback : readbacks) {
                if (readback.allocation == 0) continue;
                if (!failed) {
                    Vma.vmaInvalidateAllocation(backend.device().vma(), readback.allocation, 0, readback.bytes);
                    byte[] raw = copyBytes(MemoryUtil.memByteBuffer(readback.mapped, Math.toIntExact(readback.bytes)));
                    readback.entry.putAll(artifact(filename(((Number) readback.entry.get("binding")).intValue()), raw));
                    readback.entry.put("status", "captured");
                }
            }
        } catch (Exception error) {
            fail(error.toString());
        } finally {
            releaseStaging();
            retired = true;
            data().put("fenceCompleted", true);
            capture.computeRetired(!failed && replayComplete(dispatched, true, bindings));
        }
    }

    /** Recording failed before submission, so no device work can refer to these allocations. */
    public void abortBeforeSubmit() {
        if (retired) return;
        fail("Compute capture command buffer was not submitted");
        releaseStaging();
        retired = true;
        capture.computeRetired(false);
    }

    private void recordCopies(VkCommandBuffer cmd, boolean outputs) {
        for (Readback readback : readbacks) {
            if (readback.output != outputs) continue;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default()
                        .size(readback.bytes).usage(VK13.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(VK13.VK_SHARING_MODE_EXCLUSIVE);
                VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                        .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
                var buffer = stack.mallocLong(1);
                PointerBuffer allocation = stack.mallocPointer(1);
                VmaAllocationInfo mapped = VmaAllocationInfo.calloc(stack);
                check(Vma.vmaCreateBuffer(backend.device().vma(), bufferInfo, allocationInfo, buffer, allocation, mapped),
                        "vmaCreateBuffer(capture)");
                readback.buffer = buffer.get(0); readback.allocation = allocation.get(0); readback.mapped = mapped.pMappedData();
                if (readback.mapped == 0) throw new IllegalStateException("Capture staging allocation is not mapped");
                if (readback.image == null) {
                    VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack)
                            .srcOffset(readback.sourceOffset).dstOffset(0).size(readback.bytes);
                    VK13.vkCmdCopyBuffer(cmd, readback.sourceBuffer, readback.buffer, copy);
                } else {
                    VkBufferImageCopy.Buffer copies = VkBufferImageCopy.calloc(readback.mips.size(), stack);
                    for (int i = 0; i < readback.mips.size(); i++) {
                        Map<String, Object> mip = readback.mips.get(i);
                        var copy = copies.get(i).bufferOffset(((Number) mip.get("byteOffset")).longValue());
                        copy.imageSubresource().aspectMask(readback.image.texture().getFormat().hasDepthAspect()
                                        ? VK13.VK_IMAGE_ASPECT_DEPTH_BIT : VK13.VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(readback.image.baseMipLevel() + i).baseArrayLayer(0).layerCount(1);
                        copy.imageExtent().set(((Number) mip.get("width")).intValue(), ((Number) mip.get("height")).intValue(), 1);
                    }
                    VK13.vkCmdCopyImageToBuffer(cmd, ((VulkanGpuTexture) readback.image.texture()).vkImage(),
                            VK13.VK_IMAGE_LAYOUT_GENERAL, readback.buffer, copies);
                }
            }
        }
    }

    private void releaseStaging() {
        for (Readback readback : readbacks) if (readback.allocation != 0) {
            Vma.vmaDestroyBuffer(backend.device().vma(), readback.buffer, readback.allocation);
            readback.allocation = 0;
        }
    }

    private Map<String, Object> binding(int index, String ref, int type, String kind) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("binding", index); entry.put("name", ref); entry.put("descriptorType", type);
        entry.put("direction", index < inputCount ? "input" : "output");
        entry.put("kind", kind); entry.put("status", "pending");
        bindings.add(entry);
        return entry;
    }

    private String filename(int binding) { return name + "-binding-" + binding + ".bin"; }

    private Map<String, Object> artifact(String file, byte[] raw) throws Exception {
        Files.write(capture.directory().resolve(file), raw);
        return Map.of("file", file, "byteCount", raw.length, "sha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
    }

    /** SPIR-V grammar: five-word header; OpEntryPoint=15, OpExecutionMode=16,
     * OpConstant=43, OpExecutionModeId=331; LocalSize=17 and LocalSizeId=38. */
    static List<Integer> localSize(byte[] spirv) {
        if (spirv.length < 20 || (spirv.length & 3) != 0) throw new IllegalArgumentException("Invalid SPIR-V byte length");
        var words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        if (words.get(0) != 0x07230203) throw new IllegalArgumentException("Invalid SPIR-V magic");
        int main = -1;
        Map<Integer, Integer> constants = new LinkedHashMap<>();
        Map<Integer, int[]> literal = new LinkedHashMap<>();
        Map<Integer, int[]> ids = new LinkedHashMap<>();
        for (int offset = 5; offset < words.limit();) {
            int word = words.get(offset), count = word >>> 16, opcode = word & 0xffff;
            if (count == 0 || count > words.limit() - offset) throw new IllegalArgumentException("Invalid SPIR-V instruction bounds");
            if (opcode == 15 && count >= 4) {
                StringBuilder entry = new StringBuilder();
                for (int i = (offset + 3) * 4; i < (offset + count) * 4 && spirv[i] != 0; i++) entry.append((char) spirv[i]);
                if (entry.toString().equals("main")) main = words.get(offset + 2);
            } else if (opcode == 43 && count == 4) {
                constants.put(words.get(offset + 2), words.get(offset + 3));
            } else if ((opcode == 16 || opcode == 331) && count == 6) {
                int mode = words.get(offset + 2);
                int[] size = {words.get(offset + 3), words.get(offset + 4), words.get(offset + 5)};
                if (opcode == 16 && mode == 17) literal.put(words.get(offset + 1), size);
                if (opcode == 331 && mode == 38) ids.put(words.get(offset + 1), size);
            }
            offset += count;
        }
        int[] size = literal.get(main);
        if (size == null && ids.containsKey(main)) {
            int[] values = ids.get(main);
            size = new int[]{constants.getOrDefault(values[0], 0), constants.getOrDefault(values[1], 0), constants.getOrDefault(values[2], 0)};
        }
        if (size == null || size[0] <= 0 || size[1] <= 0 || size[2] <= 0) throw new IllegalArgumentException("No concrete main workgroup size in SPIR-V");
        return List.of(size[0], size[1], size[2]);
    }

    static byte[] copyBytes(ByteBuffer source) {
        byte[] bytes = new byte[source.remaining()];
        source.duplicate().get(bytes);
        return bytes;
    }

    static long bufferCopyBytes(long offset, long range, long size) {
        long bytes = range == VK13.VK_WHOLE_SIZE ? size - offset : range;
        if (offset < 0 || bytes <= 0 || offset > size || bytes > size - offset
                || (offset & 3) != 0 || (bytes & 3) != 0 || bytes > FullscreenCapture.MAX_TEXTURE_BYTES) {
            throw new IllegalArgumentException("Invalid capture buffer slice: offset=" + offset + " range=" + range + " size=" + size);
        }
        return bytes;
    }

    static boolean replayComplete(boolean dispatched, boolean fenceCompleted, List<Map<String, Object>> bindings) {
        return dispatched && fenceCompleted && !bindings.isEmpty()
                && bindings.stream().allMatch(entry -> "captured".equals(entry.get("status")));
    }

    private static void unavailable(Map<String, Object> entry, String reason) {
        entry.put("status", "unavailable"); entry.put("error", reason);
    }

    private void fail(String reason) { failed = true; capture.fail(reason); }

    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private void guard(Operation operation) {
        if (failed) return;
        try { operation.run(); } catch (Exception error) { fail(error.toString()); }
    }

    private static void check(int result, String operation) {
        if (result != VK13.VK_SUCCESS) throw new IllegalStateException(operation + " failed: VkResult " + result);
    }

    private static void barrier(VkCommandBuffer cmd, int srcStage, int dstStage, int srcAccess, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer memory = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            VK13.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, memory, null, null);
        }
    }
}
