package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.pack.graph.EngineBufferUploadQueue;
import dev.icehunter.fornax.pack.graph.EntityOccluderBuffer;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Packs {@link EntityOccluderFrameState} into its std430 buffer once per frame. */
public final class EntityOccluderUpload {
    private static final ByteBuffer SCRATCH =
            MemoryUtil.memAlloc((int) EntityOccluderBuffer.BYTE_SIZE).order(ByteOrder.nativeOrder());

    /** A float can only store a whole number exactly up to this many values. An int written with
     * {@code putFloat} only reads back correctly within that range, so the header wraps the frame
     * counter into this range first. */
    private static final int FRAME_COUNTER_WRAP = 1 << 24;

    private EntityOccluderUpload() {}

    /**
     * Writes the header (live count, frame counter wrapped to {@link #FRAME_COUNTER_WRAP} so it
     * stays exact as a float, {@link EntityOccluderBuffer#RANGE_BLOCKS}, 0), then up to {@link
     * EntityOccluderBuffer#MAX_OCCLUDERS} records nearest first, a zeroed tail, then flips
     * {@code out} for reading. Touches nothing outside {@code out} and needs no GPU, so a test
     * can read every value back by index.
     */
    static void pack(List<EntityOccluderFrameState.Occluder> occluders, int frameCounter, ByteBuffer out) {
        int count = Math.min(occluders.size(), EntityOccluderBuffer.MAX_OCCLUDERS);
        out.clear();
        out.putFloat(count).putFloat(frameCounter & (FRAME_COUNTER_WRAP - 1))
                .putFloat((float) EntityOccluderBuffer.RANGE_BLOCKS).putFloat(0.0f);
        for (int i = 0; i < count; i++) {
            EntityOccluderFrameState.Occluder o = occluders.get(i);
            out.putFloat(o.minX()).putFloat(o.minY()).putFloat(o.minZ()).putFloat(o.kind());
            out.putFloat(o.maxX()).putFloat(o.maxY()).putFloat(o.maxZ()).putFloat(o.yawRadians());
            out.putFloat(o.deltaX()).putFloat(o.deltaY()).putFloat(o.deltaZ()).putFloat(o.eyeHeight());
        }
        // The tail is zeroed rather than left stale: a reader that trusts the header never reads
        // it, but a reader that walks the whole array finds empty records, not last frame's body
        // still sitting in a slot past the count.
        while (out.hasRemaining()) {
            out.putFloat(0.0f);
        }
        out.flip();
    }

    /**
     * Sends this frame's occluders out before the graphics passes that read them are recorded.
     *
     * <p>ORDERING IS LOAD-BEARING: this runs inside {@code GraphRunner.prepare}, which gets this
     * frame's camera directly, so every bound sent here is measured against the same centre a
     * pack's shadow march reads this frame.
     */
    public static void onFrame(TargetRegistry registry, double camX, double camY, double camZ) {
        if (registry == null || registry.getBuffer(EntityOccluderBuffer.TARGET) == null) {
            return;
        }
        EntityOccluderFrameState.commitFromClient(camX, camY, camZ);
        pack(EntityOccluderFrameState.current(), EntityOccluderFrameState.frameCounter(), SCRATCH);
        EngineBufferUploadQueue.publish(EntityOccluderBuffer.TARGET, false,
                List.of(new EngineBufferUploadQueue.Range(0L, SCRATCH.duplicate())));
    }
}
