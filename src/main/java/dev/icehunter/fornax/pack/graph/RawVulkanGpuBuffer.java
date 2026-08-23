package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;

/**
 * Wraps a TargetRegistry-owned raw VkBuffer as a Blaze3D {@link GpuBuffer} so a fullscreen pass can
 * bind it via {@code RenderPass.setUniform(name, GpuBuffer)} as a TEXEL_BUFFER uniform (Blaze3D's
 * fragment pipeline exposes no STORAGE_BUFFER type -- verified design limit; the Sodium
 * u_SectionTimeInfo texel buffer is the in-the-wild precedent for this bind shape). LIFECYCLE: this
 * wrapper OWNS NOTHING -- the underlying buffer belongs to TargetRegistry (created/freed under
 * SHARED_QUEUE_LOCK); close() is a no-op, exactly like BufferInstance's own. Cached per pass-input
 * and recreated only when the underlying vkBuffer handle changes (a registry reallocation). NOTE
 * (adversarial-review corrected): the caching is a harmless allocation-avoidance optimization, NOT a
 * correctness requirement -- Blaze3D's pushDescriptors creates the VkBufferView fresh per push and
 * queueForDestroy()s it (no identity-keyed view cache exists), so a fresh wrapper every frame would
 * not leak; the staleness check on reallocation is the part that matters (never bind a freed handle).
 *
 * <p>Constructor signature verified via {@code javap -p -c com.mojang.blaze3d.vulkan.VulkanGpuBuffer}
 * against the deployed 26.2 client jar: the declared ctor is {@code (long, int, long)}, and its
 * bytecode stores the FIRST (long) argument into the {@code vkBuffer} field, then forwards the
 * second (int) and third (long) arguments to {@code GpuBuffer.<init>(int usage, long size)}
 * unchanged -- i.e. the real assignment is {@code (long vkBuffer, int usage, long sizeBytes)},
 * matching this class's own parameter order below exactly.
 *
 * <p>CORRECTION vs. the task brief's skeleton: {@code VulkanGpuBuffer} itself is abstract and does
 * NOT implement {@code GpuBuffer.isClosed()}/{@code map(long,long,boolean,boolean)} or {@code
 * Destroyable.destroy()} -- javap on {@code VulkanGpuBuffer.class} shows only the ctor and {@code
 * vkBuffer()} as declared members, and Blaze3D's own concrete sibling implementation, {@code
 * VulkanGpuBuffer$Direct} (the real handle Blaze3D uses for its own buffers), separately declares
 * all four ({@code destroy}/{@code isClosed}/{@code close}/{@code map}). A concrete (non-abstract)
 * subclass must implement all of them or the class fails to compile; only overriding {@code
 * close()} (as the brief's skeleton did) is insufficient. This class implements all four as no-ops
 * / "never closed" / "mapping unsupported", per the same "owns nothing" lifecycle {@code close()}
 * already documents.
 *
 * <p>The {@code usage} argument is Blaze3D's {@code GpuBuffer.USAGE_*} flag space, not raw Vulkan
 * usage bits -- and it is LOAD-BEARING (adversarial-review corrected; the original claim that
 * Blaze3D "never reads usage() on this path" was FALSE): {@code VulkanRenderPass.pushDescriptors()}
 * reads {@code usage()} and throws {@code IllegalStateException} unless
 * {@code usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER} (256) is set. Passing the generic
 * {@code USAGE_UNIFORM} (128) here would crash on the FIRST frame that binds this wrapper. Do NOT
 * "simplify" this constant. (The registry-owned VMA allocation separately carries the real Vulkan
 * {@code VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT}, set in {@code TargetRegistry.ensureBufferSize} --
 * both layers must agree.)
 */
final class RawVulkanGpuBuffer extends VulkanGpuBuffer {
    RawVulkanGpuBuffer(long vkBuffer, int usage, long sizeBytes) {
        super(vkBuffer, usage, sizeBytes);
    }

    @Override
    public void destroy() {
        // TargetRegistry owns the underlying VkBuffer/VMA allocation (vmaDestroyBuffer happens
        // there, under SHARED_QUEUE_LOCK) -- this wrapper never destroys native state.
    }

    @Override
    public boolean isClosed() {
        // This wrapper has no independent lifecycle to be "closed" -- the underlying buffer's
        // liveness is TargetRegistry's concern (a stale wrapper is replaced, never queried post-free;
        // see FullscreenPassRunner's texelWrapperHandles staleness check).
        return false;
    }

    @Override
    public void close() {
        // TargetRegistry owns the underlying VkBuffer/VMA allocation -- never freed from here.
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        // Never called on this path: FullscreenPassRunner only ever binds this wrapper via
        // RenderPass.setUniform(String, GpuBuffer), which (verified via javap on
        // VulkanRenderPass.setUniform) calls only GpuBuffer.slice() -- never map() -- to build the
        // TEXEL_BUFFER descriptor. This buffer is also device-local VMA-managed storage (not
        // host-visible), so a real mapping implementation would need VMA persistent-mapping support
        // this wrapper doesn't have; fail loudly instead of silently returning garbage if some future
        // caller does try to map it.
        throw new UnsupportedOperationException(
                "RawVulkanGpuBuffer is a read-only texel-buffer view over a device-local "
                        + "TargetRegistry buffer -- mapping is not supported");
    }
}
