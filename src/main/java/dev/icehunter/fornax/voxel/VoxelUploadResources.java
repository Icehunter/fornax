package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

/** Batch workspace owned by the registry. Every access goes through SHARED_QUEUE_LOCK, one at a time. */
public final class VoxelUploadResources implements AutoCloseable {
    private final ByteBuffer allocation;
    final ByteBuffer occupancy, payload, faceSeal, palette, summary, faceTexture, lightmap, lightZero, sectionState, sourceSummary;
    private boolean closed;

    private VoxelUploadResources() {
        int[] sizes = {(int) BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, BrickGridUpload.VOXELS_PER_SECTION,
                (int) BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT, (int) BrickGridUpload.PALETTE_BYTES_PER_SLOT,
                (int) BrickGridUpload.BRICK_SUMMARY_BYTES_PER_SLOT, VoxelFaceTexture.BYTES_PER_SLOT,
                VoxelLightmap.BYTES_PER_SLOT, Math.toIntExact(BrickGridUpload.lightVolumeBytesPerSlot()),
                VoxelSectionState.BYTES_PER_SLOT, VoxelSourceSummary.BYTES_PER_SLOT};
        int bytes = 0;
        for (int size : sizes) bytes = Math.addExact(bytes, size);
        allocation = MemoryUtil.memCalloc(bytes);
        try {
            ByteBuffer[] slices = new ByteBuffer[sizes.length];
            int offset = 0;
            for (int i = 0; i < sizes.length; i++) {
                slices[i] = allocation.slice(offset, sizes[i]).order(ByteOrder.nativeOrder());
                offset += sizes[i];
            }
            occupancy = slices[0]; payload = slices[1]; faceSeal = slices[2]; palette = slices[3];
            summary = slices[4]; faceTexture = slices[5]; lightmap = slices[6]; lightZero = slices[7];
            sectionState = slices[8]; sourceSummary = slices[9];
        } catch (RuntimeException | Error failure) {
            MemoryUtil.memFree(allocation);
            throw failure;
        }
    }

    public static @Nullable VoxelUploadResources tryCreate() {
        requireLock();
        return new VoxelUploadResources();
    }

    public boolean matchesLightLayout() {
        return lightZero.capacity() == BrickGridUpload.lightVolumeBytesPerSlot();
    }

    void execute(Consumer<VkCommandBuffer> record) {
        VoxelUploadResources.requireLock();
        if (closed) throw new IllegalStateException("Voxel upload scratch is closed");
        if (!VoxelUploadFrame.record(record))
            throw new IllegalStateException("Voxel writes must be recorded by the pre-opaque frame drain");
    }

    public static void requireLock() {
        if (!Thread.holdsLock(VulkanComputeBackend.SHARED_QUEUE_LOCK))
            throw new IllegalStateException("Voxel upload resources require the shared queue lock");
    }

    @Override public void close() {
        requireLock();
        if (closed) return;
        MemoryUtil.memFree(allocation);
        closed = true;
    }

}
