package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.mixin.vulkan.GpuDeviceBackendAccessor;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A process-lifetime {@code VkPipelineCache}, persisted to disk, shared by every pipeline-creation
 * call site in this mod AND (via {@code VulkanRenderPipelineMixin}) Blaze3D's own. It sits one
 * layer BELOW Blaze3D's Java-side shader/pipeline maps: vanilla's {@code
 * GpuDevice.clearPipelineCache()} wipes those unconditionally on every resource reload (see {@code
 * ShaderManager.apply}), but a {@code VkPipelineCache} is a separate Vulkan object those maps never
 * touch, so it survives that wipe -- and survives a process restart, since it round-trips through
 * disk. When {@code vkCreateGraphicsPipelines}/{@code vkCreateComputePipelines} is asked to build
 * the exact SPIR-V + pipeline state it already built once, the driver returns the cached result
 * instead of re-doing the expensive SPIR-V-to-ISA translation; when the content genuinely changed,
 * it naturally misses and compiles fresh. No hashing, no invalidation logic to get wrong.
 *
 * <p>Loading never validates the on-disk blob itself: {@code vkCreatePipelineCache} is
 * spec-required to check the blob's {@code VkPipelineCacheHeaderVersionOne} (size, version, vendor
 * ID, device ID, the 16-byte {@code pipelineCacheUUID}) and silently start an empty-but-valid cache
 * on any mismatch -- a stale blob from a driver update, an OS update, or a different GPU costs one
 * cold compile cycle and nothing else, never corruption. That guarantee is exactly why this class
 * does no header inspection of its own.
 *
 * <p>Render-thread only, like every other static Vulkan-touching class in this mod (see {@code
 * GraphRunner}'s own doc on the same convention) -- every call site this feeds from is already
 * render-thread-only.
 */
public final class PersistentPipelineCache {
    private PersistentPipelineCache() {
    }

    private static final Path CACHE_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("fornax_pipeline_cache.bin");

    private static long handle = VK13.VK_NULL_HANDLE;
    /** Set once a creation attempt has genuinely failed against a real Vulkan device, so a broken
     * driver doesn't retry (and re-log) the same failure on every pipeline built for the rest of
     * the session. Does NOT gate the "device not ready yet" / "GL backend" cases below -- those are
     * expected to be retried, exactly like {@code VulkanComputeBackend.tryCreate()}'s own callers
     * already tolerate. */
    private static boolean creationFailed = false;

    /**
     * Returns the live {@code VkPipelineCache} handle, creating it (loading from disk if present)
     * on first call. Returns {@code VK_NULL_HANDLE} if no Vulkan device exists yet, the active
     * backend is GL, or creation ever failed -- every call site already treats
     * {@code VK_NULL_HANDLE} as "no cache," which is this mod's behaviour before this class existed,
     * so any of those cases degrade to exactly today's status quo rather than throwing.
     */
    public static long handle() {
        if (handle != VK13.VK_NULL_HANDLE || creationFailed) {
            return handle;
        }

        // Same device-resolution idiom as VulkanComputeBackend.tryCreate(): reached from Fornax's
        // own pipeline builders AND (via the mixin) from inside Blaze3D's own pipeline compile, so
        // this can't assume a VulkanComputeBackend already exists -- it resolves the device itself.
        GpuDevice gpuDevice = RenderSystem.tryGetDevice();
        if (gpuDevice == null) {
            return VK13.VK_NULL_HANDLE; // not ready yet -- retry on the next call, not an error
        }
        GpuDeviceBackend backend = ((GpuDeviceBackendAccessor) (Object) gpuDevice).fornax$backend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            return VK13.VK_NULL_HANDLE; // GL backend: no Vulkan pipeline cache to build
        }

        byte[] initialData = readCacheFile();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer initialDataBuffer = null;
            try {
                if (initialData.length > 0) {
                    initialDataBuffer = MemoryUtil.memAlloc(initialData.length).put(initialData).flip();
                }
                VkPipelineCacheCreateInfo createInfo = VkPipelineCacheCreateInfo.calloc(stack)
                        .sType$Default();
                if (initialDataBuffer != null) {
                    createInfo.pInitialData(initialDataBuffer);
                }
                LongBuffer handleOut = stack.mallocLong(1);
                int result = VK13.vkCreatePipelineCache(vulkanDevice.vkDevice(), createInfo, null, handleOut);
                if (result != VK13.VK_SUCCESS) {
                    creationFailed = true;
                    FornaxMod.LOGGER.warn("[Fornax] vkCreatePipelineCache failed with VkResult {}; "
                            + "pipeline compiles will not be cached this session", result);
                    return VK13.VK_NULL_HANDLE;
                }
                handle = handleOut.get(0);
                FornaxMod.LOGGER.info("[Fornax] Pipeline cache created ({})",
                        initialData.length > 0 ? initialData.length + " bytes loaded from disk" : "starting empty");
                return handle;
            } finally {
                if (initialDataBuffer != null) {
                    MemoryUtil.memFree(initialDataBuffer);
                }
            }
        }
    }

    private static byte[] readCacheFile() {
        if (!Files.exists(CACHE_FILE)) {
            return new byte[0];
        }
        try {
            return Files.readAllBytes(CACHE_FILE);
        } catch (IOException e) {
            FornaxMod.LOGGER.warn("[Fornax] Failed to read pipeline cache {}; starting empty", CACHE_FILE, e);
            return new byte[0];
        }
    }

    /**
     * Writes the cache's current contents to disk, atomically. Safe to call often: a no-op (cheap
     * query, tiny write) when nothing new has been compiled since the last persist, and never
     * throws -- a failed write here costs this session's incremental cache gains, nothing else.
     */
    public static void persist() {
        if (handle == VK13.VK_NULL_HANDLE) {
            return;
        }
        GpuDevice gpuDevice = RenderSystem.tryGetDevice();
        if (gpuDevice == null) {
            return;
        }
        GpuDeviceBackend backend = ((GpuDeviceBackendAccessor) (Object) gpuDevice).fornax$backend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer sizeOut = stack.mallocPointer(1);
            int sizeResult = VK13.vkGetPipelineCacheData(vulkanDevice.vkDevice(), handle, sizeOut, null);
            if (sizeResult != VK13.VK_SUCCESS) {
                FornaxMod.LOGGER.warn("[Fornax] vkGetPipelineCacheData (size query) failed with VkResult {}", sizeResult);
                return;
            }
            long size = sizeOut.get(0);
            if (size <= 0) {
                return;
            }
            ByteBuffer dataBuffer = MemoryUtil.memAlloc((int) size);
            try {
                PointerBuffer sizeInOut = stack.pointers(size);
                int dataResult = VK13.vkGetPipelineCacheData(vulkanDevice.vkDevice(), handle, sizeInOut, dataBuffer);
                if (dataResult != VK13.VK_SUCCESS) {
                    FornaxMod.LOGGER.warn("[Fornax] vkGetPipelineCacheData (data query) failed with VkResult {}", dataResult);
                    return;
                }
                byte[] bytes = new byte[(int) size];
                dataBuffer.get(bytes);
                writeCacheFile(bytes);
                FornaxMod.LOGGER.info("[Fornax] Pipeline cache persisted ({} bytes)", bytes.length);
            } finally {
                MemoryUtil.memFree(dataBuffer);
            }
        }
    }

    private static void writeCacheFile(byte[] bytes) {
        try {
            Path parent = CACHE_FILE.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, CACHE_FILE.getFileName().toString(), ".tmp");
            try {
                Files.write(tmp, bytes);
                moveIntoPlace(tmp, CACHE_FILE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            FornaxMod.LOGGER.warn("[Fornax] Failed to write pipeline cache {}", CACHE_FILE, e);
        }
    }

    // Same tmp-file-then-atomic-move-with-fallback shape as PackValuesFile.moveIntoPlace -- kept
    // local rather than shared since it's five lines and this class has no other dependency on
    // the pack package.
    private static void moveIntoPlace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Best-effort teardown, called at {@code CLIENT_STOPPING} after a final {@link #persist()}. */
    public static void destroy() {
        if (handle == VK13.VK_NULL_HANDLE) {
            return;
        }
        GpuDevice gpuDevice = RenderSystem.tryGetDevice();
        if (gpuDevice != null) {
            GpuDeviceBackend backend = ((GpuDeviceBackendAccessor) (Object) gpuDevice).fornax$backend();
            if (backend instanceof VulkanDevice vulkanDevice) {
                VK13.vkDestroyPipelineCache(vulkanDevice.vkDevice(), handle, null);
            }
        }
        handle = VK13.VK_NULL_HANDLE;
    }
}
