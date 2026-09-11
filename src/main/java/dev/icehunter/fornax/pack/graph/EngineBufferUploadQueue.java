package dev.icehunter.fornax.pack.graph;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pending CPU-to-engine-buffer writes, drained on the queue their readers run on. {@link
 * ComputePassRunner} records compute bindings into its own frames-in-flight command buffer.
 * {@link GraphicsBufferUploads} records graphics bindings into a transient one.
 *
 * <p>This never submits to a queue or waits on one. Each caller's own command buffer owns the
 * transfer, the barrier, the dispatch or draw, the fence if it uses one, and the wait before
 * reuse. Code that feeds data each frame cannot add a queue-idle or fence stall on the render
 * thread.
 */
public final class EngineBufferUploadQueue {
    public record Range(long offset, ByteBuffer bytes) {}

    /**
     * Largest range a single publication may carry: the Vulkan inline-update limit, and a law that
     * fails silently past it. A producer with more to say splits it into ranges of this size.
     */
    public static final int MAX_RANGE_BYTES = 65_536;

    private record Update(boolean clearFirst, List<Range> ranges) {}

    private static final Map<String, Update> PENDING = new LinkedHashMap<>();

    private EngineBufferUploadQueue() {}

    public static synchronized void publish(String target, boolean clearFirst, List<Range> ranges) {
        for (Range range : ranges) {
            int length = range.bytes().remaining();
            if (length > MAX_RANGE_BYTES || (length & 3) != 0 || (range.offset() & 3) != 0) {
                throw new IllegalArgumentException("engine buffer upload for '" + target + "' is not an inline"
                        + " update: offset=" + range.offset() + ", bytes=" + length
                        + " (each range must be 4-byte aligned and at most " + MAX_RANGE_BYTES + " bytes)");
            }
        }
        Update previous = PENDING.get(target);
        // If a pass was skipped while runners rebuilt, never let the next row publication erase a
        // still-pending invalidation. The newest rows may replace older rows; a field clear may not.
        boolean preservedClear = clearFirst || previous != null && previous.clearFirst();
        PENDING.put(target, new Update(preservedClear, List.copyOf(ranges)));
    }

    static synchronized boolean hasPending(String target) {
        return PENDING.containsKey(target);
    }

    static synchronized boolean pendingClear(String target) {
        Update update = PENDING.get(target);
        return update != null && update.clearFirst();
    }

    /**
     * Whether every range pending for {@code target} fits in a buffer of {@code bufferSizeBytes},
     * without removing the entry. Runs the same offset plus length check {@link #recordForBindings}
     * runs while draining, so a caller can drop a bad update before {@code recordForBindings}
     * throws partway through a command buffer it has already started. A target with nothing
     * pending fits.
     */
    static synchronized boolean pendingFitsBuffer(String target, long bufferSizeBytes) {
        Update update = PENDING.get(target);
        if (update == null) {
            return true;
        }
        for (Range range : update.ranges()) {
            long length = range.bytes().remaining();
            if (range.offset() < 0 || range.offset() + length > bufferSizeBytes) {
                return false;
            }
        }
        return true;
    }

    public static synchronized void discard(String target) {
        PENDING.remove(target);
    }

    /**
     * Records the pending updates for the buffer targets this pass binds, on the queue {@code cmd}
     * belongs to, and removes them from the queue. {@code readerStages} is the shader-stage mask
     * for that queue: the compute stage for {@link ComputePassRunner}, the vertex and fragment
     * stages together for {@link GraphicsBufferUploads}. It is used twice. The barrier before the
     * transfer puts this write after the stage that last read the buffer. The barrier after it
     * makes the write visible to that same stage. The access masks stay {@code SHADER_READ} and
     * {@code TRANSFER_WRITE} either way, since a texel-buffer read and a storage-buffer read use
     * the same access flag.
     */
    static synchronized void recordForBindings(VkCommandBuffer cmd, MemoryStack stack,
                                               TargetRegistry registry, List<String> bindings,
                                               int readerStages) {
        boolean wrote = false;
        for (String target : bindings) {
            Update update = PENDING.remove(target);
            if (update == null) continue;
            BufferInstance buffer = registry.getBuffer(target);
            if (buffer == null) continue;
            boolean targetWrites = update.clearFirst();
            for (Range range : update.ranges()) targetWrites |= range.bytes().hasRemaining();
            if (targetWrites) {
                // This target is CPU-written and shader-read. Order prior same-queue reads before
                // overwriting it; the post-transfer barrier below makes the new snapshot visible.
                VkBufferMemoryBarrier.Buffer priorReads = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK13.VK_ACCESS_SHADER_READ_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .srcQueueFamilyIndex(VK13.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK13.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(buffer.vkBuffer()).offset(0).size(buffer.sizeBytes());
                VK13.vkCmdPipelineBarrier(cmd, readerStages,
                        VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, priorReads, null);
            }
            if (update.clearFirst()) {
                VK13.vkCmdFillBuffer(cmd, buffer.vkBuffer(), 0, buffer.sizeBytes(), 0);
                wrote = true;
            }
            for (Range range : update.ranges()) {
                ByteBuffer bytes = range.bytes().duplicate();
                if (!bytes.hasRemaining()) continue;
                if (range.offset() < 0 || range.offset() + bytes.remaining() > buffer.sizeBytes()) {
                    throw new IllegalArgumentException("engine buffer upload exceeds '" + target + "': offset="
                            + range.offset() + ", bytes=" + bytes.remaining() + ", size=" + buffer.sizeBytes());
                }
                VK13.vkCmdUpdateBuffer(cmd, buffer.vkBuffer(), range.offset(), bytes);
                wrote = true;
            }
        }
        if (wrote) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_SHADER_READ_BIT);
            VK13.vkCmdPipelineBarrier(cmd, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    readerStages, 0, barrier, null, null);
        }
    }
}
