package dev.icehunter.fornax.voxel;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.graph.BufferInstance;
import dev.icehunter.fornax.pack.graph.PrecipClipmapBuffer;
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
 * Fills {@link PrecipClipmapBuffer}'s field from vanilla's own per-column precipitation answer, a few
 * rows per frame.
 *
 * <p>See {@link PrecipClipmapBuffer} for what the field is, how it is addressed, and why the engine
 * has to own it. This class is only the harvest and the upload.
 *
 * <h2>Why the answer comes from {@code ClientLevel.getPrecipitationAt} and nothing else</h2>
 *
 * It is the same call the chunk-mesh precipitation lane already uses ({@code
 * BlockRendererMaterialIdMixin}) and the same one vanilla's own weather renderer consults, so the
 * pack's ground weather and vanilla's sky weather cannot disagree about where the boundary is. Its
 * body, javap-verified against {@code minecraft-merged.jar}:
 *
 * <pre>
 *   chunkSource.hasChunk(blockToSectionCoord(x), blockToSectionCoord(z)) ? ... : Precipitation.NONE
 *   getBiome(pos).value().getPrecipitationAt(pos, getSeaLevel())
 * </pre>
 *
 * <p>THE UNLOADED-CHUNK GUARD IS THE REASON THIS CLASS DOES ITS OWN {@code hasChunk} FIRST. Vanilla
 * folds "no chunk" into {@code NONE}, which is indistinguishable from a desert -- and a desert melts
 * snow. Writing that would make an unloaded column actively destroy accumulated state instead of
 * merely failing to add to it. So an unloaded column is not written at all: the element keeps its
 * last known value if it had one, and otherwise keeps a foreign tag and reports itself unknown to
 * every reader.
 *
 * <h2>Why the surface Y is looked up rather than assumed</h2>
 *
 * {@code Biome.getPrecipitationAt(pos, seaLevel)} is height-dependent, and not marginally:
 * {@code getHeightAdjustedTemperature} (javap-verified) subtracts a Perlin-modulated altitude term
 * for every block above {@code seaLevel + 17}. That is exactly what makes a mountain snow while the
 * valley below it rains, which is the most visible case this whole field exists to get right. The
 * heightmap used is {@code MOTION_BLOCKING}, matching {@code ClientLevel.animateTick}'s own choice
 * for where precipitation lands -- this is Fornax's first {@link Heightmap} use.
 *
 * <h2>Amortisation</h2>
 *
 * {@link #ROWS_PER_FRAME} world rows per frame, cursor-swept, so the whole 16384-column field
 * refreshes every {@code GRID / ROWS_PER_FRAME} frames. Biomes do not change, so the sweep is not
 * chasing a moving quantity -- it is only chasing the player, and the tag means a column the sweep
 * has not reached yet reports "unknown" rather than reporting a stale neighbour. That is the same
 * bargain {@code VoxelWindow.recenterAndResync} makes with its shell, without needing the executor:
 * one row is 128 biome queries against already-resident chunk data, not a section harvest.
 */
public final class PrecipClipmapUpload {
    /** One row is 128 columns; at 8 per frame the field refreshes every 16 frames. */
    private static final int ROWS_PER_FRAME = 8;
    private static final int GRID = PrecipClipmapBuffer.GRID;
    private static final int ROW_BYTES = GRID * Integer.BYTES;
    private static final long FENCE_WAIT_TIMEOUT = 0xFFFF_FFFF_FFFF_FFFFL;

    /**
     * What was last uploaded, so an unloaded column can keep its last known answer instead of being
     * overwritten with a value that means "desert" to every reader. 64 KiB, allocated once.
     *
     * <p>Also the dimension guard: cleared whenever the level instance changes, because the tag
     * identifies a COLUMN and says nothing about which world it was in -- without this, stepping
     * through a Nether portal would have the overworld's biomes describing the Nether for the ~16
     * frames it takes the cursor to sweep past them.
     */
    private static final int[] MIRROR = new int[PrecipClipmapBuffer.COLUMNS];

    /**
     * Reused across frames rather than allocated per call: this runs every frame for the whole
     * session, and {@code memAlloc}/{@code memFree} of the same 4 KiB sixty times a second is pure
     * churn. Render-thread only ({@code GraphRunner.prepare} is the sole caller), so no guard is
     * needed; deliberately never freed, since its lifetime is the process's.
     */
    private static ByteBuffer scratch;
    private static final int[] ROW = new int[GRID];
    private static final int[] ROW_INDICES = new int[ROWS_PER_FRAME];

    private static ClientLevel mirrorLevel;
    private static int rowCursor;
    private static boolean submitFailureLogged;

    private PrecipClipmapUpload() {}

    /**
     * Advances the sweep by {@link #ROWS_PER_FRAME} rows and uploads them. No-ops when there is no
     * level, no player, or no allocated buffer -- {@code GraphRunner.prepare} allocates before
     * calling, so a null buffer here means a torn-down registry rather than an ordering mistake.
     */
    public static void onFrame(TargetRegistry registry) {
        if (registry == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            mirrorLevel = null;
            return;
        }
        if (mirrorLevel != level) {
            java.util.Arrays.fill(MIRROR, 0);
            mirrorLevel = level;
            rowCursor = 0;
        }

        // The player BODY, matching u_WeatherAnchor exactly (GlobalUniformsWriteMixin's own tail):
        // Entity.getPosition(partialTick) carries no head bob, no walk sway and no view roll. Snapping
        // to ANCHOR_SNAP makes the sub-block difference between this and any consumer's own read of
        // the same lane irrelevant, but taking the same lane costs nothing and removes the question.
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var body = client.player.getPosition(partialTick);
        int baseX = PrecipClipmapBuffer.windowBase((int) Math.floor(body.x));
        int baseZ = PrecipClipmapBuffer.windowBase((int) Math.floor(body.z));

        if (scratch == null) {
            scratch = MemoryUtil.memAlloc(ROWS_PER_FRAME * ROW_BYTES).order(ByteOrder.nativeOrder());
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < ROWS_PER_FRAME; i++) {
            int worldZ = baseZ + ((rowCursor + i) & (GRID - 1));
            int toroidalRow = worldZ & (GRID - 1);
            fillRow(level, pos, baseX, worldZ, toroidalRow, ROW);
            ROW_INDICES[i] = toroidalRow;
            // Absolute puts, so the buffer's own position stays at 0 for upload() to set per row.
            for (int x = 0; x < GRID; x++) {
                scratch.putInt((i * GRID + x) * Integer.BYTES, ROW[x]);
            }
        }
        rowCursor = (rowCursor + ROWS_PER_FRAME) & (GRID - 1);
        upload(registry, scratch, ROW_INDICES, ROWS_PER_FRAME);
    }

    /**
     * One world row's 128 columns, written into {@code row} indexed by TOROIDAL x -- which is not
     * world order. World x runs contiguously across the window, but its toroidal index wraps
     * somewhere inside that run, and writing in world order would upload a rotated row.
     */
    private static void fillRow(ClientLevel level, BlockPos.MutableBlockPos pos,
                                int baseX, int worldZ, int toroidalRow, int[] row) {
        int chunkZ = SectionPos.blockToSectionCoord(worldZ);
        int rowBase = toroidalRow * GRID;
        for (int i = 0; i < GRID; i++) {
            int worldX = baseX + i;
            int toroidalX = worldX & (GRID - 1);
            int slot = rowBase + toroidalX;

            if (!level.hasChunk(SectionPos.blockToSectionCoord(worldX), chunkZ)) {
                // Keep the last known answer for THIS column; anything else is a lie. If the mirror
                // holds a different column's element the tag already says so, and every reader is
                // required to treat a tag mismatch as "do not integrate".
                row[toroidalX] = PrecipClipmapBuffer.describes(MIRROR[slot], worldX, worldZ)
                        ? MIRROR[slot]
                        : 0;
                continue;
            }
            pos.set(worldX, level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX, worldZ), worldZ);
            int element = PrecipClipmapBuffer.encode(worldX, worldZ, typeOf(level.getPrecipitationAt(pos)));
            row[toroidalX] = element;
            MIRROR[slot] = element;
        }
    }

    /** Vanilla's three-valued enum, in the 0/1/2 encoding the chunk-mesh lane already uses. */
    private static int typeOf(Biome.Precipitation precipitation) {
        return switch (precipitation) {
            case NONE -> PrecipClipmapBuffer.TYPE_NONE;
            case RAIN -> PrecipClipmapBuffer.TYPE_RAIN;
            case SNOW -> PrecipClipmapBuffer.TYPE_SNOW;
        };
    }

    /**
     * One {@code vkCmdUpdateBuffer} per row -- 512 bytes at a 512-aligned offset, far inside the
     * 65536-byte inline limit and trivially 4-aligned -- all in one command buffer, one submit, one
     * fence, exactly as {@link BrickGridUpload#uploadBatchLocked} does. Rows are not merged even when
     * their toroidal indices happen to be adjacent: eight extra commands per frame is not worth the
     * wrap-handling, and the wrap is guaranteed to occur once per sweep.
     */
    private static void upload(TargetRegistry registry, ByteBuffer scratch, int[] rowIndices, int rowCount) {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            BufferInstance buffer = registry.getBuffer(PrecipClipmapBuffer.TARGET);
            if (buffer == null || buffer.sizeBytes() < PrecipClipmapBuffer.BYTE_SIZE) {
                return;
            }
            VulkanComputeBackend backend = VulkanComputeBackend.tryCreate();
            if (backend == null) {
                return;
            }
            try {
                uploadLocked(backend, buffer.vkBuffer(), scratch, rowIndices, rowCount);
            } finally {
                backend.close();
            }
        }
    }

    /** Caller holds {@code SHARED_QUEUE_LOCK}. */
    private static void uploadLocked(VulkanComputeBackend backend, long dst, ByteBuffer scratch,
                                     int[] rowIndices, int rowCount) {
        VulkanDevice device = backend.device();
        VkCommandBuffer cmd = backend.commandPool().allocateBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK13.vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default());
            for (int i = 0; i < rowCount; i++) {
                scratch.limit((i + 1) * ROW_BYTES).position(i * ROW_BYTES);
                VK13.vkCmdUpdateBuffer(cmd, dst, (long) rowIndices[i] * ROW_BYTES, scratch);
            }
            scratch.clear();
            // Same global transfer -> shader-read barrier BrickGridUpload records, and for the same
            // reason: without it a compute pass reading this buffer intermittently sees a stale page
            // of an otherwise static field.
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_SHADER_READ_BIT);
            VK13.vkCmdPipelineBarrier(cmd, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
            VK13.vkEndCommandBuffer(cmd);

            LongBuffer fenceOut = stack.mallocLong(1);
            if (VK13.vkCreateFence(device.vkDevice(), VkFenceCreateInfo.calloc(stack).sType$Default(),
                    null, fenceOut) != VK13.VK_SUCCESS) {
                return;
            }
            long fence = fenceOut.get(0);
            try {
                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(cmd));
                int submitResult = VK13.vkQueueSubmit(backend.computeQueue().vkQueue(), submitInfo, fence);
                if (submitResult != VK13.VK_SUCCESS) {
                    // Never wait on a fence nothing was submitted against -- that blocks this
                    // SHARED_QUEUE_LOCK-holding thread for the full timeout, i.e. forever.
                    if (!submitFailureLogged) {
                        submitFailureLogged = true;
                        FornaxMod.LOGGER.error("[Fornax] PrecipClipmapUpload: vkQueueSubmit failed with"
                                + " VkResult {} -- per-column precipitation will stop updating", submitResult);
                    }
                    return;
                }
                VK13.vkWaitForFences(device.vkDevice(), fence, true, FENCE_WAIT_TIMEOUT);
            } finally {
                VK13.vkDestroyFence(device.vkDevice(), fence, null);
            }
        }
    }
}
