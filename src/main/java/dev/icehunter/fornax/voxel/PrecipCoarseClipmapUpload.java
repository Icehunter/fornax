package dev.icehunter.fornax.voxel;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.graph.BufferInstance;
import dev.icehunter.fornax.pack.graph.PrecipCoarseClipmapBuffer;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

/**
 * Uploads a bounded, coarse field of vanilla precipitation answers for shader packs that sample
 * weather away from the camera.
 *
 * <p>This class supplies raw categorical world data only. It deliberately does not smooth, darken,
 * or otherwise interpret precipitation; a pack owns those visual decisions after it samples the
 * self-tagging field.
 */
public final class PrecipCoarseClipmapUpload {
    /** Eight 128-cell rows make the 16,384-cell field complete after sixteen frames. */
    private static final int ROWS_PER_FRAME = PrecipCoarseClipmapUploadPlan.ROWS_PER_FRAME;
    private static final int GRID = PrecipCoarseClipmapBuffer.GRID;
    private static final int ROW_BYTES = GRID * Integer.BYTES;
    private static final long FENCE_WAIT_TIMEOUT = 0xFFFF_FFFF_FFFF_FFFFL;
    private static final int TYPE_NONE = 0;
    private static final int TYPE_RAIN = 1;
    private static final int TYPE_SNOW = 2;

    /** A word exists for every slot, so a full reset can explicitly write unknown to every one. */
    private static final int[] MIRROR = new int[PrecipCoarseClipmapBuffer.COLUMNS];
    private static final int[] ROW = new int[GRID];
    private static final int[] ROW_INDICES = new int[ROWS_PER_FRAME];

    private static ByteBuffer scratch;
    private static final PrecipCoarseClipmapUploadPlan PLAN = new PrecipCoarseClipmapUploadPlan();
    private static boolean submitFailureLogged;

    private PrecipCoarseClipmapUpload() {}

    /**
     * Refreshes eight rows during steady state, or fully clears and refills the current field before
     * returning after a level change or discontinuous recenter. The latter ordering prevents a
     * teleport from exposing a tag-period alias to a consumer dispatch in the same frame.
     */
    public static boolean onFrame(TargetRegistry registry) {
        if (registry == null) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            PLAN.clear();
            return false;
        }

        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var body = client.player.getPosition(partialTick);
        int baseCellX = PrecipCoarseClipmapBuffer.windowBaseCell((int) Math.floor(body.x));
        int baseCellZ = PrecipCoarseClipmapBuffer.windowBaseCell((int) Math.floor(body.z));
        PrecipCoarseClipmapUploadPlan.UploadPlan plan = PLAN.plan(level, baseCellX, baseCellZ);
        if (plan.fullReset()) {
            clearMirror();
            fillWholeWindow(level, baseCellX, baseCellZ);
            if (uploadWhole(registry)) {
                PLAN.commit(plan, level);
                return true;
            }
            // Do not publish the new base or initialized state: next frame must retry the full
            // clear/refill and GraphRunner will withhold every required consumer this frame.
            return false;
        }

        fillRows(level, baseCellX, baseCellZ, plan);
        if (uploadRows(registry, ROW_INDICES, ROWS_PER_FRAME)) {
            PLAN.commit(plan, level);
        }
        // A failed steady row leaves an already-committed window self-tagging and safe to consume;
        // it simply retries the same rows because the cursor was not committed.
        return PLAN.isReadyFor(level, baseCellX, baseCellZ);
    }

    private static void clearMirror() {
        java.util.Arrays.fill(MIRROR, 0);
    }

    private static void fillWholeWindow(ClientLevel level, int baseCellX, int baseCellZ) {
        ensureScratch();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                int cellX = baseCellX + x;
                int cellZ = baseCellZ + z;
                int slot = PrecipCoarseClipmapBuffer.slotForCell(cellX, cellZ);
                int encoded = sampleCell(level, pos, cellX, cellZ, true);
                MIRROR[slot] = encoded;
                scratch.putInt(slot * Integer.BYTES, encoded);
            }
        }
    }

    private static void fillRows(ClientLevel level, int baseCellX, int baseCellZ,
                                 PrecipCoarseClipmapUploadPlan.UploadPlan plan) {
        ensureScratch();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int row = 0; row < ROWS_PER_FRAME; row++) {
            int cellZ = baseCellZ + ((PLAN.rowCursor() + row) & (GRID - 1));
            int slotRow = plan.slotRows()[row];
            for (int x = 0; x < GRID; x++) {
                int cellX = baseCellX + x;
                int slot = PrecipCoarseClipmapBuffer.slotForCell(cellX, cellZ);
                ROW[cellX & (GRID - 1)] = sampleCell(level, pos, cellX, cellZ, false);
                scratch.putInt((row * GRID + (cellX & (GRID - 1))) * Integer.BYTES,
                        ROW[cellX & (GRID - 1)]);
            }
            ROW_INDICES[row] = slotRow;
        }
    }

    private static int sampleCell(ClientLevel level, BlockPos.MutableBlockPos pos, int cellX, int cellZ,
                                  boolean resetting) {
        int slot = PrecipCoarseClipmapBuffer.slotForCell(cellX, cellZ);
        int worldX = PrecipCoarseClipmapBuffer.representativeBlock(cellX);
        int worldZ = PrecipCoarseClipmapBuffer.representativeBlock(cellZ);
        if (!level.hasChunk(SectionPos.blockToSectionCoord(worldX), SectionPos.blockToSectionCoord(worldZ))) {
            // A reset must make unknown explicit. During a normal sweep, retain only a word that
            // already describes this same cell; any other slot occupant is unknown, never dry.
            return !resetting && PrecipCoarseClipmapBuffer.describesCell(MIRROR[slot], cellX, cellZ)
                    ? MIRROR[slot]
                    : 0;
        }
        pos.set(worldX, level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX, worldZ), worldZ);
        int encoded = PrecipCoarseClipmapBuffer.encodeCell(cellX, cellZ,
                typeOf(level.getPrecipitationAt(pos)));
        MIRROR[slot] = encoded;
        return encoded;
    }

    private static int typeOf(Biome.Precipitation precipitation) {
        return switch (precipitation) {
            case NONE -> TYPE_NONE;
            case RAIN -> TYPE_RAIN;
            case SNOW -> TYPE_SNOW;
        };
    }

    private static void ensureScratch() {
        if (scratch == null) {
            scratch = MemoryUtil.memAlloc((int) PrecipCoarseClipmapBuffer.BYTE_SIZE)
                    .order(ByteOrder.nativeOrder());
        }
    }

    private static boolean uploadRows(TargetRegistry registry, int[] rowIndices, int rowCount) {
        return upload(registry, rowIndices, rowCount, false);
    }

    private static boolean uploadWhole(TargetRegistry registry) {
        return upload(registry, null, 0, true);
    }

    private static boolean upload(TargetRegistry registry, int[] rowIndices, int rowCount, boolean wholeField) {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            BufferInstance buffer = registry.getBuffer(PrecipCoarseClipmapBuffer.TARGET);
            if (buffer == null || buffer.sizeBytes() < PrecipCoarseClipmapBuffer.BYTE_SIZE) {
                return false;
            }
            VulkanComputeBackend backend = VulkanComputeBackend.tryCreate();
            if (backend == null) {
                return false;
            }
            try {
                return uploadLocked(backend, buffer.vkBuffer(), rowIndices, rowCount, wholeField);
            } finally {
                backend.close();
            }
        }
    }

    /** Caller holds {@link VulkanComputeBackend#SHARED_QUEUE_LOCK}. */
    private static boolean uploadLocked(VulkanComputeBackend backend, long dst, int[] rowIndices,
                                        int rowCount, boolean wholeField) {
        VulkanDevice device = backend.device();
        VkCommandBuffer cmd = backend.commandPool().allocateBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (VK13.vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default())
                    != VK13.VK_SUCCESS) {
                return false;
            }
            if (wholeField) {
                scratch.position(0).limit((int) PrecipCoarseClipmapBuffer.BYTE_SIZE);
                VK13.vkCmdUpdateBuffer(cmd, dst, 0, scratch);
            } else {
                for (int row = 0; row < rowCount; row++) {
                    scratch.position(row * ROW_BYTES).limit((row + 1) * ROW_BYTES);
                    VK13.vkCmdUpdateBuffer(cmd, dst, (long) rowIndices[row] * ROW_BYTES, scratch);
                }
            }
            scratch.clear();
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_SHADER_READ_BIT);
            VK13.vkCmdPipelineBarrier(cmd, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
            if (VK13.vkEndCommandBuffer(cmd) != VK13.VK_SUCCESS) {
                return false;
            }

            LongBuffer fenceOut = stack.mallocLong(1);
            if (VK13.vkCreateFence(device.vkDevice(), VkFenceCreateInfo.calloc(stack).sType$Default(),
                    null, fenceOut) != VK13.VK_SUCCESS) {
                return false;
            }
            long fence = fenceOut.get(0);
            try {
                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(cmd));
                int submitResult = VK13.vkQueueSubmit(backend.computeQueue().vkQueue(), submitInfo, fence);
                if (submitResult != VK13.VK_SUCCESS) {
                    if (!submitFailureLogged) {
                        submitFailureLogged = true;
                        FornaxMod.LOGGER.error("[Fornax] PrecipCoarseClipmapUpload queue submit failed with VkResult {}",
                                submitResult);
                    }
                    return false;
                }
                return VK13.vkWaitForFences(device.vkDevice(), fence, true, FENCE_WAIT_TIMEOUT)
                        == VK13.VK_SUCCESS;
            } finally {
                VK13.vkDestroyFence(device.vkDevice(), fence, null);
            }
        }
    }
}
