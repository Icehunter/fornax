package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.metalfx.objc.Objc;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reads back the bytes {@code mesh_shadow_decode} actually wrote, at the offsets
 * {@code mesh_shadow_trace} actually dereferences.
 *
 * <p>The shadow trace reaches its UVs through {@code data->uv0}, so a record whose members moved
 * still compiles and still samples the atlas: it samples it at a coordinate taken from the wrong
 * part of the record. The result is not a black frame, it is a cutout mask that is subtly wrong,
 * which is the hardest kind of defect to attribute from a screenshot. An assertion about the
 * struct's source text cannot catch a layout error, because the compiler's own padding decisions
 * are what would be wrong. This reads the memory.
 */
class MeshPrimitiveRecordLayoutTest {

    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    /** Corner UVs of the test quad, in uploaded vertex order. */
    private static final float[][] QUAD_UV = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};

    /** The uploaded quad index ABI, which the decode preserves for its winding. */
    private static final int[][] TRIANGLE_CORNERS = {{0, 1, 2}, {2, 3, 0}};

    private static final int FACE = 4; // west, and distinct from the zero a cleared buffer holds.

    @Test
    void theDecodeWritesTheSurfaceWordAtByteZeroAndTheThreeUvPairsFromByteEight() throws Exception {
        assumeTrue(Objc.isLoaded(), "Metal bridge not linked on this platform");
        long device = Objc.createSystemDefaultMetalDevice();
        assumeTrue(device != 0L, "no system default Metal device");

        long pool = Objc.autoreleasePoolPush();
        Deque<Long> owned = new ArrayDeque<>();
        try (MeshShadowTracer tracer = new MeshShadowTracer()) {
            long queue = keep(owned, Objc.msgSendId(device, Objc.selector("newCommandQueue")));
            long atlas = keep(owned, texture(device, 2, 1));
            long output = keep(owned, texture(device, 8, 8));
            long packed = keep(owned, MetalRtAcceleration.createBuffer(device, 96));
            writeQuad(packed);

            tracer.trace(queue, List.of(new MeshShadowTracer.Mesh(
                            new MeshShadowTracer.Key(0, 0, 0, true), 1L, packed, 4, 0, 0, 0)),
                    atlas, output, 8, IDENTITY, IDENTITY, 0, 0, 0, 32f, 0f, 0f, 0, 0, 0);
            await(queue);

            MemorySegment records = MemorySegment
                    .ofAddress(Objc.msgSendId(primitiveBuffer(tracer), Objc.selector("contents")))
                    .reinterpret(64L);

            for (int triangle = 0; triangle < 2; triangle++) {
                long base = triangle * 32L;
                String where = "triangle " + triangle;
                // Bit 30 says "six UV floats follow at byte 8"; bits 12-15 carry the face.
                assertEquals((1 << 30) | (FACE << 12), records.get(ValueLayout.JAVA_INT, base),
                        where + " surface word at byte 0");
                assertEquals(0, records.get(ValueLayout.JAVA_INT, base + 4),
                        where + " pad word at byte 4, which keeps float2 on its 8-byte alignment");
                for (int vertex = 0; vertex < 3; vertex++) {
                    float[] uv = QUAD_UV[TRIANGLE_CORNERS[triangle][vertex]];
                    long at = base + 8 + vertex * 8L;
                    assertEquals(uv[0], records.get(ValueLayout.JAVA_FLOAT, at), 1e-5f,
                            where + " u" + vertex + " at byte " + (at - base));
                    assertEquals(uv[1], records.get(ValueLayout.JAVA_FLOAT, at + 4), 1e-5f,
                            where + " v" + vertex + " at byte " + (at - base + 4));
                }
            }
        } finally {
            while (!owned.isEmpty()) {
                Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            }
            Objc.autoreleasePoolPop(pool);
            Objc.msgSendVoid(device, Objc.selector("release"));
        }
    }

    /** One quad at FornaxChunkVertex's 24-byte stride: position, UV, and the face at byte 20. */
    private static void writeQuad(long buffer) {
        float[][] corners = {{-0.75f, -0.75f}, {-0.75f, 0.75f}, {0.75f, 0.75f}, {0.75f, -0.75f}};
        MemorySegment data = MemorySegment
                .ofAddress(Objc.msgSendId(buffer, Objc.selector("contents"))).reinterpret(96);
        data.fill((byte) 0);
        for (int corner = 0; corner < 4; corner++) {
            long at = corner * 24L;
            data.set(ValueLayout.JAVA_SHORT, at, (short) Math.round((corners[corner][0] + 8f) * 2048f));
            data.set(ValueLayout.JAVA_SHORT, at + 2, (short) Math.round((corners[corner][1] + 8f) * 2048f));
            data.set(ValueLayout.JAVA_SHORT, at + 4, (short) Math.round(8f * 2048f));
            data.set(ValueLayout.JAVA_SHORT, at + 8, (short) Math.round(QUAD_UV[corner][0] * 65535f));
            data.set(ValueLayout.JAVA_SHORT, at + 10, (short) Math.round(QUAD_UV[corner][1] * 65535f));
            data.set(ValueLayout.JAVA_BYTE, at + 20, (byte) FACE);
        }
    }

    private static long primitiveBuffer(MeshShadowTracer tracer) throws Exception {
        var cache = MeshShadowTracer.class.getDeclaredField("cache");
        cache.setAccessible(true);
        Object entry = ((Map<?, ?>) cache.get(tracer)).values().iterator().next();
        var primitives = entry.getClass().getDeclaredField("primitives");
        primitives.setAccessible(true);
        return primitives.getLong(entry);
    }

    private static void await(long queue) {
        long cb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
        Objc.msgSendVoid(cb, Objc.selector("commit"));
        Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));
    }

    private static long keep(Deque<Long> owned, long value) {
        assertNotEquals(0L, value, "native allocation failed");
        owned.push(value);
        return value;
    }

    private static long texture(long device, int width, int height) {
        long desc = Objc.msgSendId(Objc.getClass("MTLTextureDescriptor"), Objc.selector("new"));
        try {
            // MTLTextureType2D=2, RGBA32Float=125, read|write=3, shared storage=0.
            Objc.msgSendVoidLong(desc, Objc.selector("setTextureType:"), 2);
            Objc.msgSendVoidLong(desc, Objc.selector("setPixelFormat:"), 125);
            Objc.msgSendVoidLong(desc, Objc.selector("setWidth:"), width);
            Objc.msgSendVoidLong(desc, Objc.selector("setHeight:"), height);
            Objc.msgSendVoidLong(desc, Objc.selector("setUsage:"), 3);
            Objc.msgSendVoidLong(desc, Objc.selector("setStorageMode:"), 0);
            return Objc.msgSendId(device, Objc.selector("newTextureWithDescriptor:"), desc);
        } finally {
            Objc.msgSendVoid(desc, Objc.selector("release"));
        }
    }
}
