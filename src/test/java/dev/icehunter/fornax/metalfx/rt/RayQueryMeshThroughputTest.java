package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.rt.RayTier;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Ray throughput against a structure the size a world builds, rather than a fixture that fits in
 * cache.
 *
 * <p>The figures this engine had were 12 triangles and 3072 triangles, and every budget statement
 * past them was an extrapolation from two points. Those two disagree by a factor of four over two
 * orders of magnitude of geometry, which is the shape BVH traversal has and precisely why
 * extrapolating further is not evidence. This measures a third point half a million triangles up,
 * built through {@link MeshShadowTracer}'s own decode-and-build path so what is timed is the
 * structure the mesh tier actually traces.
 *
 * <p>Prints rather than asserts a rate: a benchmark that fails a build on a number is a flaky test
 * on a shared machine. The assertions cover only that real work happened, which is the failure the
 * 12-triangle figure nearly shipped with: a ray fan aimed at empty air measures beautifully.
 */
class RayQueryMeshThroughputTest {

    private static final int RAYS = 1 << 20;
    private static final int DISPATCHES_PER_RUN = 8;
    private static final int RUNS = 7;

    /** Sections across, per axis. 6^3 = 216 meshes, a plausible shadow caster set. */
    private static final int SECTIONS = 6;

    /** Quads per mesh. 216 x 1200 x 2 triangles = 518,400. */
    private static final int QUADS = 1200;

    private static final int BLOCKS = 16;

    @Test
    void halfAMillionMeshTrianglesRecordTheirThroughput() throws Exception {
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
            for (int sx = 0; sx < SECTIONS; sx++) {
                for (int sy = 0; sy < SECTIONS; sy++) {
                    for (int sz = 0; sz < SECTIONS; sz++) {
                        long packed = keep(owned, MetalRtAcceleration.createBuffer(device, QUADS * 96L));
                        writeQuads(packed, sx * 7 + sy * 13 + sz * 29);
                        meshes.add(new MeshShadowTracer.Mesh(
                                new MeshShadowTracer.Key(sx, sy, sz, false), 1L, packed, QUADS * 4,
                                sx * BLOCKS, sy * BLOCKS, sz * BLOCKS));
                    }
                }
            }
            int triangles = meshes.size() * QUADS * 2;

            float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
            long built = System.nanoTime();
            tracer.trace(queue, meshes, atlas, output, 8, identity, identity, 0, 0, 0, 512f, 0f, 0f, 0, 0, 0);
            awaitQueue(queue);
            double buildMs = (System.nanoTime() - built) / 1e6;

            long scene = nativeField(tracer, "tlas");
            assertNotEquals(0L, scene, "the tracer must have built an instance structure");
            List<Long> resources = residentResources(tracer);

            long rayAtlas = keep(owned, TestAtlas.opaque(device, queue));
            rayQuery = MetalRtShaders.compileKernel(
                    device, MetalRtShaders.RAY_QUERY_RESOURCE, MetalRtShaders.RAY_QUERY_FUNCTION);

            long requestBuffer = keep(owned, MetalRtAcceleration.createBuffer(device, RayQueryAbi.requestByteSize(RAYS)));
            long hitBuffer = keep(owned, MetalRtAcceleration.createBuffer(device, RayQueryAbi.hitByteSize(RAYS)));

            System.out.printf("%n  rt_ray_query throughput [uploaded meshes, %d triangles, %d instances,"
                            + " structure built in %.1f ms]%n", triangles, meshes.size(), buildMs);

            // Two ray sets against ONE structure, which is the point: the earlier figures varied
            // geometry and ray set together and could not separate them. Inside the volume a ray
            // terminates almost at once; from far outside it walks the whole structure and usually
            // finds nothing, which is what a shadow ray toward an unoccluded sky does.
            float span = SECTIONS * BLOCKS;
            float insideHitRate = measure("from inside, rays terminate early", queue, rayQuery, scene,
                    resources, rayAtlas, requestBuffer, hitBuffer, span * 0.5f, span * 0.5f, span * 0.5f);
            float outsideHitRate = measure("from just outside, rays cross the whole volume", queue, rayQuery,
                    scene, resources, rayAtlas, requestBuffer, hitBuffer, -span * 0.75f, span * 0.5f, span * 0.5f);

            assertTrue(insideHitRate > 0.5f,
                    "a fan from the centre of the volume must mostly hit it; all-miss means the "
                            + "structure was never resident and the figure is measuring empty air");
            // Not an efficiency claim, a sanity one: if the outside fan hit as often as the inside
            // fan, the two sets are not measuring different traversal depths and the comparison
            // below means nothing.
            assertTrue(outsideHitRate < insideHitRate,
                    "the outside fan must miss more often than the inside one, or the two ray sets "
                            + "are not exercising different traversal costs");
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

    /** Runs one ray set and prints its rate. Returns the measured hit rate, 0 to 1. */
    private static float measure(String label, long queue, MetalRtShaders.CompiledKernel rayQuery,
            long scene, List<Long> resources, long atlas, long requestBuffer, long hitBuffer,
            float originX, float originY, float originZ) {
        writeFloats(requestBuffer, RayFan.sphere(RAYS, originX, originY, originZ, 4000.0f));

        // Discarded: the first dispatch pays pipeline warm-up and first-touch page faults.
        dispatch(queue, rayQuery, scene, resources, atlas, requestBuffer, hitBuffer, DISPATCHES_PER_RUN);

        long[] nanos = new long[RUNS];
        for (int run = 0; run < RUNS; run++) {
            nanos[run] = dispatch(queue, rayQuery, scene, resources, atlas, requestBuffer, hitBuffer, DISPATCHES_PER_RUN);
        }
        Arrays.sort(nanos);
        long median = nanos[RUNS / 2];
        double raysPerSecond = (double) RAYS * DISPATCHES_PER_RUN / (median / 1e9);
        // Sampled across the whole buffer with a stride, not the first N: the golden-angle
        // spiral emits in polar order, so any prefix is one band of the sphere and its hit rate
        // says nothing about the set. The first version of this took the first 4096 and reported
        // 0% for a fan that really hit about a fifth of the time.
        float hitRate = sampledHitRate(hitBuffer, 64);

        System.out.printf("    %-42s %8.1f Mrays/s   %2.0f%% hit   "
                        + "1080p 1spp %5.2f ms   2048^2 %5.2f ms%n",
                label, raysPerSecond / 1e6, hitRate * 100.0f,
                2_073_600.0 / raysPerSecond * 1e3, 4_194_304.0 / raysPerSecond * 1e3);
        assertTrue(raysPerSecond > 0.0, "the benchmark traced no rays");
        return hitRate;
    }

    /**
     * Quads scattered through the section on a deterministic walk rather than a regular lattice: a
     * regular grid gives the BVH an easy split and flatters traversal, which is the opposite of
     * what a benchmark should do.
     */
    private static void writeQuads(long buffer, int seed) {
        MemorySegment data = MemorySegment
                .ofAddress(Objc.msgSendId(buffer, Objc.selector("contents"))).reinterpret(QUADS * 96L);
        data.fill((byte) 0);
        int state = seed | 1;
        for (int quad = 0; quad < QUADS; quad++) {
            state = state * 1664525 + 1013904223;
            float x = ((state >>> 8) & 0xFF) / 255.0f * (BLOCKS - 1);
            state = state * 1664525 + 1013904223;
            float y = ((state >>> 8) & 0xFF) / 255.0f * (BLOCKS - 1);
            state = state * 1664525 + 1013904223;
            float z = ((state >>> 8) & 0xFF) / 255.0f * (BLOCKS - 1);
            float[][] corners = {{x, y, z}, {x, y + 1, z}, {x + 1, y + 1, z}, {x + 1, y, z}};
            for (int corner = 0; corner < 4; corner++) {
                long at = quad * 96L + corner * 24L;
                for (int axis = 0; axis < 3; axis++) {
                    data.set(ValueLayout.JAVA_SHORT, at + axis * 2L,
                            (short) Math.round((corners[corner][axis] + 8.0f) * 2048.0f));
                }
                // Face 3 (south, +Z) for every quad: the face byte is not what this measures.
                data.set(ValueLayout.JAVA_BYTE, at + 20, (byte) 3);
            }
        }
    }

    private static long dispatch(long queue, MetalRtShaders.CompiledKernel rayQuery, long scene,
            List<Long> resources, long atlas, long requestBuffer, long hitBuffer, int iterations) {
        long start = System.nanoTime();
        long cb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
        long encoder = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
        Objc.msgSendVoid(encoder, Objc.selector("setComputePipelineState:"), rayQuery.pipeline());
        Objc.msgSendVoidIdLong(encoder, Objc.selector("setAccelerationStructure:atBufferIndex:"), scene, 1L);
        for (long resource : resources) {
            Objc.msgSendVoidIdLong(encoder, Objc.selector("useResource:usage:"), resource, 1L);
        }
        Objc.msgSendVoidIdLong(encoder, Objc.selector("setTexture:atIndex:"), atlas, 0L);
        long constants = MetalRtAcceleration.createBuffer(Objc.msgSendId(queue, Objc.selector("device")), 32L);
        try {
            MemorySegment seg = MemorySegment
                    .ofAddress(Objc.msgSendId(constants, Objc.selector("contents"))).reinterpret(32L);
            seg.fill((byte) 0);
            seg.set(ValueLayout.JAVA_INT, 0L, RayQueryAbi.ABI_VERSION);
            seg.set(ValueLayout.JAVA_INT, 4L, RAYS);
            seg.set(ValueLayout.JAVA_INT, 8L, RayTier.HARDWARE_MESH.ordinal());
            seg.set(ValueLayout.JAVA_INT, 12L, 0);
            Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), constants, 0L, 0L);
            Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), requestBuffer, 0L, 2L);
            Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), hitBuffer, 0L, 3L);
            long threads = Math.min(64L, Objc.msgSendLong(rayQuery.pipeline(),
                    Objc.selector("maxTotalThreadsPerThreadgroup")));
            long groups = (RAYS + threads - 1) / threads;
            for (int i = 0; i < iterations; i++) {
                Objc.dispatchThreadgroups(encoder, groups, 1L, 1L, threads, 1L, 1L);
            }
            Objc.msgSendVoid(encoder, Objc.selector("endEncoding"));
            Objc.msgSendVoid(cb, Objc.selector("commit"));
            Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));
            return System.nanoTime() - start;
        } finally {
            Objc.msgSendVoid(constants, Objc.selector("release"));
        }
    }

    private static float sampledHitRate(long hitBuffer, int stride) {
        MemorySegment seg = MemorySegment
                .ofAddress(Objc.msgSendId(hitBuffer, Objc.selector("contents")))
                .reinterpret(RayQueryAbi.hitByteSize(RAYS));
        long hits = 0;
        long sampled = 0;
        for (int i = 0; i < RAYS; i += stride) {
            long base = (long) i * RayQueryAbi.HIT_WORDS * Float.BYTES;
            int tier = seg.get(ValueLayout.JAVA_INT, base + RayQueryAbi.HIT_TIER_WORD * 4L);
            float distance = seg.get(ValueLayout.JAVA_FLOAT, base + RayQueryAbi.HIT_DISTANCE_WORD * 4L);
            sampled++;
            if (RayQueryAbi.isAnswered(tier) && !RayQueryAbi.isMiss(distance)) {
                hits++;
            }
        }
        return hits / (float) sampled;
    }

    private static void writeFloats(long buffer, float[] values) {
        MemorySegment seg = MemorySegment
                .ofAddress(Objc.msgSendId(buffer, Objc.selector("contents")))
                .reinterpret((long) values.length * Float.BYTES);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_FLOAT, i, values[i]);
        }
    }

    private static List<Long> residentResources(MeshShadowTracer tracer) throws Exception {
        var field = MeshShadowTracer.class.getDeclaredField("cache");
        field.setAccessible(true);
        List<Long> out = new ArrayList<>();
        for (Object entry : ((Map<?, ?>) field.get(tracer)).values()) {
            for (String name : new String[]{"blas", "vertices", "primitives"}) {
                var handle = entry.getClass().getDeclaredField(name);
                handle.setAccessible(true);
                out.add(handle.getLong(entry));
            }
        }
        return out;
    }

    private static long nativeField(MeshShadowTracer tracer, String name) throws Exception {
        var field = MeshShadowTracer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(tracer);
    }

    private static void awaitQueue(long queue) {
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
