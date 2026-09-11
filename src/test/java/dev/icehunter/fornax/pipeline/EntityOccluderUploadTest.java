package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.pack.graph.EntityOccluderBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Contract for packing a frame's entity occluder set into its std430 byte layout. */
class EntityOccluderUploadTest {
    private static EntityOccluderFrameState.Occluder occluder(float seed, int kind) {
        return new EntityOccluderFrameState.Occluder(
                seed, seed + 1, seed + 2, kind,
                seed + 3, seed + 4, seed + 5, seed + 6,
                seed + 7, seed + 8, seed + 9, seed + 10);
    }

    private static ByteBuffer scratch() {
        return ByteBuffer.allocateDirect((int) EntityOccluderBuffer.BYTE_SIZE).order(ByteOrder.nativeOrder());
    }

    @Test
    void packerRoundTripsAThreeRecordFixture() {
        List<EntityOccluderFrameState.Occluder> occluders = new ArrayList<>();
        occluders.add(occluder(1.0f, EntityOccluderBuffer.KIND_PLAYER));
        occluders.add(occluder(100.0f, EntityOccluderBuffer.KIND_LIVING));
        occluders.add(occluder(200.0f, EntityOccluderBuffer.KIND_ITEM));

        ByteBuffer out = scratch();
        EntityOccluderUpload.pack(occluders, 42, out);

        assertEquals(3.0f, out.getFloat(0), "header word 0 is the live count");
        assertEquals(42.0f, out.getFloat(4), "header word 1 is the frame counter");
        assertEquals((float) EntityOccluderBuffer.RANGE_BLOCKS, out.getFloat(8), "header word 2 is RANGE_BLOCKS");
        assertEquals(0.0f, out.getFloat(12), "header word 3 is reserved");

        for (int i = 0; i < occluders.size(); i++) {
            EntityOccluderFrameState.Occluder o = occluders.get(i);
            int base = 16 + i * EntityOccluderBuffer.FLOATS_PER_OCCLUDER * Float.BYTES;
            assertEquals(o.minX(), out.getFloat(base));
            assertEquals(o.minY(), out.getFloat(base + 4));
            assertEquals(o.minZ(), out.getFloat(base + 8));
            assertEquals((float) o.kind(), out.getFloat(base + 12));
            assertEquals(o.maxX(), out.getFloat(base + 16));
            assertEquals(o.maxY(), out.getFloat(base + 20));
            assertEquals(o.maxZ(), out.getFloat(base + 24));
            assertEquals(o.yawRadians(), out.getFloat(base + 28));
            assertEquals(o.deltaX(), out.getFloat(base + 32));
            assertEquals(o.deltaY(), out.getFloat(base + 36));
            assertEquals(o.deltaZ(), out.getFloat(base + 40));
            assertEquals(o.eyeHeight(), out.getFloat(base + 44));
        }
    }

    @Test
    void tailIsZeroedPastTheCount() {
        List<EntityOccluderFrameState.Occluder> occluders =
                List.of(occluder(1.0f, EntityOccluderBuffer.KIND_PLAYER));
        ByteBuffer out = scratch();
        EntityOccluderUpload.pack(occluders, 1, out);

        int tailStart = 16 + EntityOccluderBuffer.FLOATS_PER_OCCLUDER * Float.BYTES;
        for (int offset = tailStart; offset < EntityOccluderBuffer.BYTE_SIZE; offset += Float.BYTES) {
            assertEquals(0.0f, out.getFloat(offset), "byte offset " + offset + " must be zeroed past the live count");
        }
    }

    @Test
    void packerClampsToMaxOccluders() {
        List<EntityOccluderFrameState.Occluder> occluders = new ArrayList<>();
        for (int i = 0; i < EntityOccluderBuffer.MAX_OCCLUDERS + 5; i++) {
            occluders.add(occluder(i, EntityOccluderBuffer.KIND_OTHER));
        }
        ByteBuffer out = scratch();
        EntityOccluderUpload.pack(occluders, 0, out);
        assertEquals((float) EntityOccluderBuffer.MAX_OCCLUDERS, out.getFloat(0),
                "the header count stops at MAX_OCCLUDERS even when the list holds more bodies");
    }

    @Test
    void packerWrapsTheFrameCounterAt2To24SoItStaysExactAsAFloat() {
        // A float holds a whole number exactly only up to 2^24. A session runs far more frames
        // than that, so the header must wrap instead of quietly losing the count.
        int wrap = 1 << 24;
        ByteBuffer out = scratch();

        EntityOccluderUpload.pack(List.of(), wrap, out);
        assertEquals(0.0f, out.getFloat(4), "a counter at the wrap must read back as zero");

        EntityOccluderUpload.pack(List.of(), wrap + 5, out);
        assertEquals(5.0f, out.getFloat(4), "a counter past the wrap must read back as the remainder");
    }
}
