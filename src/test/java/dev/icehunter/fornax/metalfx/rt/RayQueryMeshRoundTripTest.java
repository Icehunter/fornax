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

    private static final ThreadLocal<List<Long>> RESIDENT = new ThreadLocal<>();

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
            RESIDENT.set(meshResources(tracer));

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

            long rayAtlas = keep(owned, TestAtlas.opaque(device, queue));
            float[] hits = dispatch(device, queue, rayQuery, scene, rayAtlas, requests, rays);

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
            long scene, long atlas, float[] requests, int rays) throws Exception {
        return dispatch(device, queue, rayQuery, scene, atlas, requests, rays, 0);
    }

    private static float[] dispatch(long device, long queue, MetalRtShaders.CompiledKernel rayQuery,
            long scene, long atlas, float[] requests, int rays, int encoding) throws Exception {
        long requestBuffer = MetalRtAcceleration.createBuffer(device, RayQueryAbi.requestByteSize(rays));
        long hitBuffer = MetalRtAcceleration.createBuffer(device, RayQueryAbi.hitByteSize(rays));
        long constants = MetalRtAcceleration.createBuffer(device, 32L);
        try {
            MemorySegment requestData = MemorySegment
                    .ofAddress(Objc.msgSendId(requestBuffer, Objc.selector("contents")))
                    .reinterpret(RayQueryAbi.requestByteSize(rays));
            for (int i = 0; i < requests.length; i++) {
                requestData.setAtIndex(ValueLayout.JAVA_FLOAT, i, requests[i]);
            }
            MemorySegment constantData = MemorySegment
                    .ofAddress(Objc.msgSendId(constants, Objc.selector("contents"))).reinterpret(32L);
            constantData.fill((byte) 0);
            constantData.set(ValueLayout.JAVA_INT, 28L, encoding);
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
            for (long resource : RESIDENT.get()) {
                Objc.msgSendVoidIdLong(encoder, Objc.selector("useResource:usage:"), resource, 1L);
            }
            // Cutout alpha is tested against this: with no atlas bound every UV samples zero and
            // the ray passes through geometry it should have hit.
            Objc.msgSendVoidIdLong(encoder, Objc.selector("setTexture:atIndex:"), atlas, 0L);
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

    /** The owner's 16384x8192 atlas loses up to four texels when UV is rounded to binary16.
     * Trace a sparse cutout atlas, then require the returned address to name the alpha-tested texel.
     * Native only: this cannot establish live descriptors, temporal foliage stability or FPS. */
    @Test
    void exactEncodingReturnsTheSameLargeAtlasTexelThatPassedCutoutAlpha() throws Exception {
        assumeTrue(Objc.isLoaded(), "Metal bridge not linked on this platform");
        long device = Objc.createSystemDefaultMetalDevice();
        assumeTrue(device != 0L, "no system default Metal device");
        long pool = Objc.autoreleasePoolPush();
        Deque<Long> owned = new ArrayDeque<>();
        MetalRtShaders.CompiledKernel rayQuery = null;
        try (MeshShadowTracer tracer = new MeshShadowTracer()) {
            long queue = keep(owned, Objc.msgSendId(device, Objc.selector("newCommandQueue")));
            long atlas = keep(owned, cutoutAtlas(device, queue));
            long output = keep(owned, texture(device, 8, 8));
            long packed = keep(owned, MetalRtAcceleration.createBuffer(device, 96));
            writeQuad(packed, 1);
            List<MeshShadowTracer.Mesh> meshes = List.of(new MeshShadowTracer.Mesh(
                    new MeshShadowTracer.Key(0, 0, 0, true), 1L, packed, 4, 0, 0, 0));
            tracer.trace(queue, meshes, atlas, output, 8, IDENTITY, IDENTITY, 0, 0, 0, 32f, 0f, 0f, 0, 0, 0);
            RESIDENT.set(meshResources(tracer));
            rayQuery = MetalRtShaders.compileKernel(device, MetalRtShaders.RAY_QUERY_RESOURCE, MetalRtShaders.RAY_QUERY_FUNCTION);
            // Odd x and y texels are opaque; all other texels are transparent. Cases straddle the
            // binary16 boundary, include its wrong rounded texel, and the last atlas texel.
            int[][] texels = {{8195,4097},{8192,4096},{8191,4097},{8192,4097},{16383,8191}};
            float[] requests = new float[texels.length * RayQueryAbi.REQUEST_WORDS];
            for (int i=0; i<texels.length; i++) {
                float u=(texels[i][0]+0.5f)/16384f, v=(texels[i][1]+0.5f)/8192f;
                int at=i*RayQueryAbi.REQUEST_WORDS;
                requests[at]=(2*u-1)*H; requests[at+1]=H+STANDOFF; requests[at+2]=(2*v-1)*H;
                requests[at+5]=-1; requests[at+7]=STANDOFF+1;
            }
            long scene = nativeField(tracer, "tlas");
            float[] exact = dispatch(device, queue, rayQuery, scene, atlas, requests, texels.length, 1);
            float[] legacy = dispatch(device, queue, rayQuery, scene, atlas, requests, texels.length, 0);
            for (int i=0; i<texels.length; i++) {
                int at=i*RayQueryAbi.HIT_WORDS;
                boolean opaque=(texels[i][0]&1)==1 && (texels[i][1]&1)==1;
                assertEquals(opaque, exact[at]>=0, "cutout coverage ray " + i);
                assertEquals(opaque, legacy[at]>=0, "legacy cutout coverage ray " + i);
                if (opaque) {
                    // Bit 13 is the next free flag after UV_KNOWN; packed-half consumers keep it clear.
                    int exactFlags=Float.floatToRawIntBits(exact[at+RayQueryAbi.HIT_FLAGS_WORD]);
                    int legacyFlags=Float.floatToRawIntBits(legacy[at+RayQueryAbi.HIT_FLAGS_WORD]);
                    assertNotEquals(0, exactFlags & RayQueryAbi.FLAG_ATLAS_TEXEL_U16, "exact-address format flag");
                    assertEquals(0, legacyFlags & RayQueryAbi.FLAG_ATLAS_TEXEL_U16, "legacy format flag");
                    int address=Float.floatToRawIntBits(exact[at+RayQueryAbi.HIT_ATLAS_UV_WORD]);
                    assertEquals(texels[i][0],address&0xFFFF,"alpha-tested x ray " + i);
                    assertEquals(texels[i][1],address>>>16,"alpha-tested y ray " + i);
                    int halfUv=Float.floatToRawIntBits(legacy[at+RayQueryAbi.HIT_ATLAS_UV_WORD]);
                    int oldX=(int)(Float.float16ToFloat((short)halfUv)*16384);
                    if (i==0) assertNotEquals(texels[i][0],oldX,"legacy control must reproduce the precision defect");
                }
            }
        } finally {
            RESIDENT.remove();
            if (rayQuery != null) rayQuery.release();
            while (!owned.isEmpty()) Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            Objc.autoreleasePoolPop(pool);
        }
    }

    private static long cutoutAtlas(long device, long queue) {
        long desc=Objc.msgSendId(Objc.getClass("MTLTextureDescriptor"),Objc.selector("new"));
        long atlas;
        try {
            // Metal RGBA8Unorm=70. Full live atlas dimensions, 512 MiB rather than RGBA32F's 2 GiB.
            Objc.msgSendVoidLong(desc,Objc.selector("setTextureType:"),2);
            Objc.msgSendVoidLong(desc,Objc.selector("setPixelFormat:"),70);
            Objc.msgSendVoidLong(desc,Objc.selector("setWidth:"),16384);
            Objc.msgSendVoidLong(desc,Objc.selector("setHeight:"),8192);
            Objc.msgSendVoidLong(desc,Objc.selector("setUsage:"),3);
            Objc.msgSendVoidLong(desc,Objc.selector("setStorageMode:"),0);
            atlas=Objc.msgSendId(device,Objc.selector("newTextureWithDescriptor:"),desc);
        } finally { Objc.msgSendVoid(desc,Objc.selector("release")); }
        long library=MetalRtShaders.compileSource(device,"uv_cutout_fixture", """
                #include <metal_stdlib>
                using namespace metal;
                kernel void fill(texture2d<float,access::write> output [[texture(0)]], uint2 p [[thread_position_in_grid]]) {
                    bool opaque=(p.x&1u)!=0u && (p.y&1u)!=0u;
                    output.write(opaque?float4(0,1,0,1):float4(1,1,1,0),p);
                }
                """);
        long function=Objc.msgSendId(library,Objc.selector("newFunctionWithName:"),Objc.nsString("fill"));
        long pipeline=Objc.msgSendIdIdErr(device,Objc.selector("newComputePipelineStateWithFunction:error:"),function).id();
        try {
            long cb=Objc.msgSendId(queue,Objc.selector("commandBuffer"));
            long encoder=Objc.msgSendId(cb,Objc.selector("computeCommandEncoder"));
            Objc.msgSendVoid(encoder,Objc.selector("setComputePipelineState:"),pipeline);
            Objc.msgSendVoidIdLong(encoder,Objc.selector("setTexture:atIndex:"),atlas,0);
            Objc.dispatchThreadgroups(encoder,1024,512,1,16,16,1);
            Objc.msgSendVoid(encoder,Objc.selector("endEncoding"));
            Objc.msgSendVoid(cb,Objc.selector("commit"));
            Objc.msgSendVoid(cb,Objc.selector("waitUntilCompleted"));
        } finally {
            Objc.msgSendVoid(pipeline,Objc.selector("release"));
            Objc.msgSendVoid(function,Objc.selector("release"));
            Objc.msgSendVoid(library,Objc.selector("release"));
        }
        return atlas;
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
