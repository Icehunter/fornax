package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.rt.RayTier;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The general ray query against uploaded chunk meshes rather than the voxel brick grid: the same
 * kernel, the same hit record, a different acceleration structure underneath.
 *
 * <p>What this proves that the voxel round trip cannot: the face a mesh hit reports comes from
 * {@code FornaxChunkVertex} byte 20, a byte nothing else in the ray path reads, and the UVs come
 * from bytes 8 and 10 of the same vertices interpolated by the intersector's own barycentrics. The
 * six quads are positioned so that geometry and face byte agree, which is what makes a misread byte
 * visible: a decode reading the wrong offset still returns a unit axis, just not the one the quad
 * faces.
 */
class RayQueryMeshRoundTripTest {

    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    /** Half-extent of each quad, in blocks. 0.75 is 1536/2048, exact in the vertex format. */
    private static final float H = 0.75f;

    /** Where inside the quad the ray lands, along its two in-plane axes. Both exact in 1/2048ths. */
    private static final float S1 = 0.25f;
    private static final float S2 = -0.375f;

    /** Distance from the quad's plane to the ray origin. */
    private static final float STANDOFF = 4.0f;

    /**
     * Minecraft's Direction order: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east. Each row is the
     * outward normal, then the two in-plane axes the quad's corners and UVs run along.
     */
    private static final float[][][] FACES = {
            {{0, -1, 0}, {1, 0, 0}, {0, 0, 1}},
            {{0, 1, 0}, {1, 0, 0}, {0, 0, 1}},
            {{0, 0, -1}, {1, 0, 0}, {0, 1, 0}},
            {{0, 0, 1}, {1, 0, 0}, {0, 1, 0}},
            {{-1, 0, 0}, {0, 0, 1}, {0, 1, 0}},
            {{1, 0, 0}, {0, 0, 1}, {0, 1, 0}},
    };

    @Test
    void everyMeshFaceReportsItsOwnOutwardNormalItsInterpolatedUvAndTheTracingTier() throws Exception {
        assumeTrue(Objc.isLoaded(), "Metal bridge not linked on this platform");
        long device = Objc.createSystemDefaultMetalDevice();
        assumeTrue(device != 0L, "no system default Metal device");

        long pool = Objc.autoreleasePoolPush();
        Deque<Long> owned = new ArrayDeque<>();
        MetalRtShaders.CompiledKernel rayQuery = null;
        try (MeshShadowTracer tracer = new MeshShadowTracer()) {
            long queue = keep(owned, Objc.msgSendId(device, Objc.selector("newCommandQueue")));
            long atlas = keep(owned, texture(device, 2, 1));
            long output = keep(owned, texture(device, 8, 8));

            List<MeshShadowTracer.Mesh> meshes = new ArrayList<>();
            for (int face = 0; face < FACES.length; face++) {
                long packed = keep(owned, MetalRtAcceleration.createBuffer(device, 96));
                writeQuad(packed, face);
                meshes.add(new MeshShadowTracer.Mesh(
                        new MeshShadowTracer.Key(face, 0, 0, false), 1L, packed, 4, 0, 0, 0));
            }

            // Only to make the tracer build its BLASes and TLAS. The shadow image it writes is not
            // what this test reads; the structures it leaves behind are.
            tracer.trace(queue, meshes, atlas, output, 8, IDENTITY, IDENTITY, 0, 0, 0, 32f, 0f, 0f, 0, 0, 0);

            long scene = nativeField(tracer, "tlas");
            assertNotEquals(0L, scene, "the tracer must have built an instance structure");

            rayQuery = MetalRtShaders.compileKernel(
                    device, MetalRtShaders.RAY_QUERY_RESOURCE, MetalRtShaders.RAY_QUERY_FUNCTION);

            int rays = FACES.length;
            float[] requests = new float[rays * RayQueryAbi.REQUEST_WORDS];
            for (int face = 0; face < rays; face++) {
                float[] n = FACES[face][0];
                float[] e1 = FACES[face][1];
                float[] e2 = FACES[face][2];
                int at = face * RayQueryAbi.REQUEST_WORDS;
                for (int axis = 0; axis < 3; axis++) {
                    requests[at + axis] = n[axis] * (H + STANDOFF) + e1[axis] * S1 + e2[axis] * S2;
                    requests[at + 4 + axis] = -n[axis];
                }
                requests[at + 3] = 0.0f;
                requests[at + 7] = 2.0f * (H + STANDOFF);
            }

            float[] hits = dispatch(device, queue, rayQuery, tracer, scene, requests, rays);

            for (int face = 0; face < rays; face++) {
                float[] n = FACES[face][0];
                int at = face * RayQueryAbi.HIT_WORDS;
                float distance = hits[at + RayQueryAbi.HIT_DISTANCE_WORD];
                int flags = Float.floatToRawIntBits(hits[at + RayQueryAbi.HIT_FLAGS_WORD]);
                int surface = Float.floatToRawIntBits(hits[at + RayQueryAbi.HIT_SURFACE_WORD]);
                int atlasUv = Float.floatToRawIntBits(hits[at + RayQueryAbi.HIT_ATLAS_UV_WORD]);
                int tier = Float.floatToRawIntBits(hits[at + RayQueryAbi.HIT_TIER_WORD]);
                float nx = hits[at + RayQueryAbi.HIT_NORMAL_WORD];
                float ny = hits[at + RayQueryAbi.HIT_NORMAL_WORD + 1];
                float nz = hits[at + RayQueryAbi.HIT_NORMAL_WORD + 2];

                String where = "face " + face;
                assertTrue(RayQueryAbi.isAnswered(tier), where + " must be answered");
                // The caller declares the tier; this test declares HARDWARE_MESH because it is
                // tracing uploaded meshes, and the kernel copies it into every record it writes.
                assertEquals(RayTier.HARDWARE_MESH.ordinal(), tier, where + " tier");
                assertEquals(STANDOFF, distance, 1e-3f, where + " hit distance");
                assertEquals(n[0], nx, 1e-6f, where + " normal x");
                assertEquals(n[1], ny, 1e-6f, where + " normal y");
                assertEquals(n[2], nz, 1e-6f, where + " normal z");
                // The ray travels along -n, so this cross-checks the whole table at once: a face
                // table with two entries swapped cannot satisfy it even if the per-face
                // expectations above were copied from the same wrong table.
                assertEquals(-1.0f, -n[0] * nx + -n[1] * ny + -n[2] * nz, 1e-6f,
                        where + " direction dot normal");
                assertEquals(face, (flags >>> RayQueryAbi.FLAG_FACE_SHIFT) & RayQueryAbi.FLAG_FACE_MASK,
                        where + " flag word face field");
                assertEquals(0, surface,
                        where + " surface word: a mesh triangle has no palette entry to name");
                assertNotEquals(0, flags & RayQueryAbi.FLAG_UV_KNOWN, where + " must report a UV");

                // u runs along e1 and v along e2, from 0 at -H to 1 at +H.
                float expectedU = (S1 + H) / (2.0f * H);
                float expectedV = (S2 + H) / (2.0f * H);
                float u = Float.float16ToFloat((short) (atlasUv & 0xFFFF));
                float v = Float.float16ToFloat((short) (atlasUv >>> 16));
                // 1e-3, not the 1/65535 the vertex UVs are stored at: the record packs two halves
                // into one word, and half spacing over [0.5, 1) is 2^-11, about 4.9e-4.
                assertEquals(expectedU, u, 1e-3f, where + " interpolated u");
                assertEquals(expectedV, v, 1e-3f, where + " interpolated v");
            }
        } finally {
            if (rayQuery != null) {
                rayQuery.release();
            }
            while (!owned.isEmpty()) {
                Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            }
            Objc.autoreleasePoolPop(pool);
            Objc.msgSendVoid(device, Objc.selector("release"));
        }
    }

    /**
     * One quad, four vertices at FornaxChunkVertex's 24-byte stride. Positions are 16-bit unorm
     * codes, {@code (coord + 8) * 2048}; UVs are 16-bit unorm over the unit square; byte 20 is the
     * face, the low byte of the same word that carries the material id.
     */
    private static void writeQuad(long buffer, int face) {
        float[] n = FACES[face][0];
        float[] e1 = FACES[face][1];
        float[] e2 = FACES[face][2];
        float[][] corners = {{-H, -H}, {-H, H}, {H, H}, {H, -H}};
        float[][] uvs = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        MemorySegment data = MemorySegment
                .ofAddress(Objc.msgSendId(buffer, Objc.selector("contents"))).reinterpret(96);
        data.fill((byte) 0);
        for (int corner = 0; corner < 4; corner++) {
            long at = corner * 24L;
            for (int axis = 0; axis < 3; axis++) {
                float value = n[axis] * H + e1[axis] * corners[corner][0] + e2[axis] * corners[corner][1];
                data.set(ValueLayout.JAVA_SHORT, at + axis * 2L,
                        (short) Math.round((value + 8.0f) * 2048.0f));
            }
            data.set(ValueLayout.JAVA_SHORT, at + 8, (short) Math.round(uvs[corner][0] * 65535.0f));
            data.set(ValueLayout.JAVA_SHORT, at + 10, (short) Math.round(uvs[corner][1] * 65535.0f));
            data.set(ValueLayout.JAVA_BYTE, at + 20, (byte) face);
        }
    }

    private static float[] dispatch(long device, long queue, MetalRtShaders.CompiledKernel rayQuery,
            MeshShadowTracer tracer, long scene, float[] requests, int rays) throws Exception {
        long requestBuffer = MetalRtAcceleration.createBuffer(device, RayQueryAbi.requestByteSize(rays));
        long hitBuffer = MetalRtAcceleration.createBuffer(device, RayQueryAbi.hitByteSize(rays));
        long constants = MetalRtAcceleration.createBuffer(device, 16L);
        try {
            MemorySegment requestData = MemorySegment
                    .ofAddress(Objc.msgSendId(requestBuffer, Objc.selector("contents")))
                    .reinterpret(RayQueryAbi.requestByteSize(rays));
            for (int i = 0; i < requests.length; i++) {
                requestData.setAtIndex(ValueLayout.JAVA_FLOAT, i, requests[i]);
            }
            MemorySegment constantData = MemorySegment
                    .ofAddress(Objc.msgSendId(constants, Objc.selector("contents"))).reinterpret(16L);
            constantData.set(ValueLayout.JAVA_INT, 0L, RayQueryAbi.ABI_VERSION);
            constantData.set(ValueLayout.JAVA_INT, 4L, rays);
            constantData.set(ValueLayout.JAVA_INT, 8L, RayTier.HARDWARE_MESH.ordinal());
            constantData.set(ValueLayout.JAVA_INT, 12L, 0);

            long cb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
            long encoder = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
            Objc.msgSendVoid(encoder, Objc.selector("setComputePipelineState:"), rayQuery.pipeline());
            Objc.msgSendVoidIdLong(encoder,
                    Objc.selector("setAccelerationStructure:atBufferIndex:"), scene, 1L);
            // The instance structure references each mesh's own structure and its two buffers, and
            // the encoder cannot see through it to them. Without this the trace reads unresident
            // memory and every ray comes back a miss.
            for (long resource : meshResources(tracer)) {
                Objc.msgSendVoidIdLong(encoder, Objc.selector("useResource:usage:"), resource, 1L);
            }
            Objc.msgSendVoidIdLongLong(encoder,
                    Objc.selector("setBuffer:offset:atIndex:"), constants, 0L, 0L);
            Objc.msgSendVoidIdLongLong(encoder,
                    Objc.selector("setBuffer:offset:atIndex:"), requestBuffer, 0L, 2L);
            Objc.msgSendVoidIdLongLong(encoder,
                    Objc.selector("setBuffer:offset:atIndex:"), hitBuffer, 0L, 3L);
            long threads = Math.min(64L, Objc.msgSendLong(rayQuery.pipeline(),
                    Objc.selector("maxTotalThreadsPerThreadgroup")));
            Objc.dispatchThreadgroups(encoder, (rays + threads - 1) / threads, 1L, 1L, threads, 1L, 1L);
            Objc.msgSendVoid(encoder, Objc.selector("endEncoding"));
            Objc.msgSendVoid(cb, Objc.selector("commit"));
            Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));

            MemorySegment hitData = MemorySegment
                    .ofAddress(Objc.msgSendId(hitBuffer, Objc.selector("contents")))
                    .reinterpret(RayQueryAbi.hitByteSize(rays));
            float[] words = new float[rays * RayQueryAbi.HIT_WORDS];
            for (int i = 0; i < words.length; i++) {
                words[i] = hitData.getAtIndex(ValueLayout.JAVA_FLOAT, i);
            }
            return words;
        } finally {
            Objc.msgSendVoid(constants, Objc.selector("release"));
            Objc.msgSendVoid(hitBuffer, Objc.selector("release"));
            Objc.msgSendVoid(requestBuffer, Objc.selector("release"));
        }
    }

    /** Every native handle the tracer's TLAS refers to: each mesh's structure, vertices and data. */
    private static List<Long> meshResources(MeshShadowTracer tracer) throws Exception {
        var field = MeshShadowTracer.class.getDeclaredField("cache");
        field.setAccessible(true);
        List<Long> resources = new ArrayList<>();
        for (Object entry : ((Map<?, ?>) field.get(tracer)).values()) {
            for (String name : new String[]{"blas", "vertices", "primitives"}) {
                var handle = entry.getClass().getDeclaredField(name);
                handle.setAccessible(true);
                resources.add(handle.getLong(entry));
            }
        }
        return resources;
    }

    private static long nativeField(MeshShadowTracer tracer, String name) throws Exception {
        var field = MeshShadowTracer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(tracer);
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
