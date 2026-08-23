package dev.icehunter.fornax.pack.graph;

/**
 * One allocated buffer-kind graph target: a raw Vulkan storage buffer plus its VMA allocation
 * handle. Unlike {@link TargetInstance}, there is no history/ping-pong pair -- nothing in this
 * milestone needs a buffer's "previous frame" value, and adding one before something needs it would
 * be exactly the speculative plumbing this project's house style avoids.
 */
public final class BufferInstance implements AutoCloseable {
    private final String name;
    private long vkBuffer;
    private long vmaAllocation;
    private long sizeBytes;

    BufferInstance(String name, long vkBuffer, long vmaAllocation, long sizeBytes) {
        this.name = name;
        this.vkBuffer = vkBuffer;
        this.vmaAllocation = vmaAllocation;
        this.sizeBytes = sizeBytes;
    }

    public String name() {
        return name;
    }

    public long vkBuffer() {
        return vkBuffer;
    }

    public long vmaAllocation() {
        return vmaAllocation;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    void reassign(long vkBuffer, long vmaAllocation, long sizeBytes) {
        this.vkBuffer = vkBuffer;
        this.vmaAllocation = vmaAllocation;
        this.sizeBytes = sizeBytes;
    }

    @Override
    public void close() {
        // Actual vmaDestroyBuffer happens in TargetRegistry, which owns the VulkanComputeBackend
        // reference needed to reach device.vma() -- this class is a pure data holder, mirroring how
        // TargetInstance itself only closes GpuTexture/GpuTextureView handles it directly owns.
    }
}
