package dev.icehunter.fornax.pack.graph;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pending CPU-to-engine-buffer writes recorded into the first consuming compute command buffer.
 *
 * <p>This deliberately does not submit or wait on a queue. {@link ComputePassRunner}'s existing
 * frames-in-flight command buffer owns the transfer, barrier, dispatch, fence, and reuse wait, so a
 * per-frame data producer cannot accidentally add a render-thread queue-idle or fence stall.
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

    public static synchronized void discard(String target) {
        PENDING.remove(target);
    }

    /** Records and consumes updates for buffer targets bound by this compute pass. */
    static synchronized void recordForBindings(VkCommandBuffer cmd, MemoryStack stack,
                                               TargetRegistry registry, List<String> bindings) {
        boolean wrote = false;
        for (String target : bindings) {
            Update update = PENDING.remove(target);
            if (update == null) continue;
            BufferInstance buffer = registry.getBuffer(target);
            if (buffer == null) continue;
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
                    VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
        }
    }
}
