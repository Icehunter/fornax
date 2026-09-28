package dev.icehunter.fornax.voxel;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Registry-owned scratch bytes; the frame transfer owns GPU submission and completion. */
public final class VoxelClearResources implements AutoCloseable {
    final ByteBuffer occupancyZeros, faceSealZeros, summaryPending;
    private boolean closed;

    private VoxelClearResources() {
        occupancyZeros = MemoryUtil.memCalloc((int) BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
        faceSealZeros = MemoryUtil.memCalloc((int) BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
        summaryPending = MemoryUtil.memAlloc((int) BrickGridUpload.BRICK_SUMMARY_BYTES_PER_SLOT);
        summaryPending.putInt(0, BrickGridUpload.SUMMARY_PENDING);
    }

    public static @Nullable VoxelClearResources tryCreate() {
        VoxelUploadResources.requireLock();
        return new VoxelClearResources();
    }

    void execute(Consumer<VkCommandBuffer> record) {
        VoxelUploadResources.requireLock();
        if (closed) throw new IllegalStateException("Voxel clear scratch is closed");
        if (!VoxelUploadFrame.record(record))
            throw new IllegalStateException("Voxel writes must be recorded by the pre-opaque frame drain");
    }

    @Override public void close() {
        VoxelUploadResources.requireLock();
        if (closed) return;
        MemoryUtil.memFree(occupancyZeros);
        MemoryUtil.memFree(faceSealZeros);
        MemoryUtil.memFree(summaryPending);
        closed = true;
    }
}
