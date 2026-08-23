package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.pack.graph.EngineBufferUploadQueue;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pack.graph.WaterActorBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Packs {@link WaterActorFrameState} into its std430 buffer once per frame. */
public final class WaterActorUpload {
    private static final ByteBuffer SCRATCH =
            MemoryUtil.memAlloc((int) WaterActorBuffer.BYTE_SIZE).order(ByteOrder.nativeOrder());

    private WaterActorUpload() {}

    /**
     * Publishes the frame's set before this frame's consuming compute passes are recorded.
     *
     * <p>ORDERING IS LOAD-BEARING: this runs inside {@code GraphRunner.prepare}, which
     * {@code SodiumWorldRendererOrchestrationMixin} calls on the line immediately after
     * {@link LocalActorFrameState#commitFromClient()}. Every offset published here is measured
     * against that snapshot, so reading it one call earlier would anchor the whole set to the
     * previous frame's centre and slide every remote wake by a frame of player movement.
     */
    public static void onFrame(TargetRegistry registry) {
        if (registry == null || registry.getBuffer(WaterActorBuffer.TARGET) == null) {
            return;
        }
        WaterActorFrameState.commitFromClient();
        List<WaterActorFrameState.Actor> actors = WaterActorFrameState.current();
        int count = Math.min(actors.size(), WaterActorBuffer.MAX_ACTORS);

        SCRATCH.clear();
        SCRATCH.putFloat(count).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
        for (int i = 0; i < count; i++) {
            WaterActorFrameState.Actor a = actors.get(i);
            SCRATCH.putFloat(a.offsetX()).putFloat(a.worldY())
                    .putFloat(a.offsetZ()).putFloat(a.kind());
            SCRATCH.putFloat(a.deltaX()).putFloat(a.verticalSpeed())
                    .putFloat(a.deltaZ()).putFloat(a.contactDelta());
            SCRATCH.putFloat(a.forwardX()).putFloat(a.forwardZ())
                    .putFloat(a.halfWidth()).putFloat(a.halfLength());
            SCRATCH.putFloat(a.fluidKind()).putFloat(a.surfaceContact())
                    .putFloat(0.0f).putFloat(0.0f);
        }
        // The tail is zeroed rather than left stale: a consumer that trusts the header will never
        // read it, but a consumer that walks the whole array should find empty records, not last
        // frame's boat still sitting in slot six.
        while (SCRATCH.hasRemaining()) {
            SCRATCH.putFloat(0.0f);
        }
        SCRATCH.flip();
        EngineBufferUploadQueue.publish(WaterActorBuffer.TARGET, false,
                List.of(new EngineBufferUploadQueue.Range(0L, SCRATCH.duplicate())));
    }
}
