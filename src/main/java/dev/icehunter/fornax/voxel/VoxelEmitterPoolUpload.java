package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.graph.EngineBufferUploadQueue;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Dirty-only immutable staging snapshots; pending consumers may skip a frame or rebuild.
 * Queue acceptance is counted here, not GPU completion. No extra submission or fence is added. */
final class VoxelEmitterPoolUpload {
    private VoxelEmitterPoolUpload() { }

    static boolean publish(VoxelEmitterPool pool) {
        var publication = pool.preparePublication();
        if (publication == null) return false;
        EngineBufferUploadQueue.publish(VoxelEmitterPool.TARGET, false, ranges(publication.bytes()));
        pool.markPublished(publication);
        return true;
    }

    static List<EngineBufferUploadQueue.Range> ranges(ByteBuffer bytes) {
        List<EngineBufferUploadQueue.Range> ranges = new ArrayList<>();
        int offset = 0;
        while (offset < bytes.remaining()) {
            int length = Math.min(EngineBufferUploadQueue.MAX_RANGE_BYTES, bytes.remaining() - offset);
            ranges.add(new EngineBufferUploadQueue.Range(offset,
                    bytes.slice(bytes.position() + offset, length).asReadOnlyBuffer()));
            offset += length;
        }
        return ranges;
    }
}
