package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.voxel.BrickGridUpload;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Exercises rebuildDirty itself against real Metal buffers, substituting only the Vulkan export
 * boundary. Clamping the build count, omitting the overflow retry, or retrying with the old capacity
 * must fail: each expected triangle is probed in the resulting acceleration structure. Reflection
 * keeps native fixture setup out of the production API, as in MetalRtGeometryTest.
 */
class MetalRtCapacityTest {
    // Two rays per face, one on each side of its diagonal, start 1/100 block outside and travel
    // 2/100 block: only this voxel's face can hit, including the internal faces of adjacent leaves.
    private static final String PROBE = """
            #include <metal_stdlib>
            #include <metal_raytracing>
            using namespace metal;
            using namespace metal::raytracing;
            kernel void capacity_probe(primitive_acceleration_structure scene [[buffer(0)]],
                    device const uint* primitives [[buffer(1)]],
                    device uint* hits [[buffer(2)]], uint tid [[thread_position_in_grid]]) {
                uint packed = primitives[tid];
                uint voxel = packed & 0xfffu;
                uint face = (packed >> 12u) & 7u;
                float3 cell = float3(voxel & 15u, (voxel >> 8u) & 15u, (voxel >> 4u) & 15u);
                float a = (tid & 1u) == 0u ? 0.25 : 0.75;
                float b = 1.0 - a;
                float3 point, normal;
                if (face < 2u) {
                    point = float3(a, float(face), b);
                    normal = float3(0, face == 0u ? -1 : 1, 0);
                } else if (face < 4u) {
                    point = float3(a, b, float(face - 2u));
                    normal = float3(0, 0, face == 2u ? -1 : 1);
                } else {
                    point = float3(float(face - 4u), a, b);
                    normal = float3(face == 4u ? -1 : 1, 0, 0);
                }
                intersector<> tracer;
                tracer.set_triangle_cull_mode(triangle_cull_mode::none);
                ray r(cell + point + normal * 0.01, -normal, 0.0, 0.02);
                auto hit = tracer.intersect(r, scene);
                hits[tid] = hit.type != intersection_type::none ? 1u : 0u;
            }
            """;

    @Test
    void denseOpaqueSectionBuildsEveryTriangleAndReusesItsGrownBuffers() throws Exception {
        // Half of the 4096 cells are occupied; no checkerboard neighbors touch a face:
        // 2048 cubes * 6 faces * 2 triangles = 24576, far beyond the initial 4096 allocation.
        exerciseDenseSection(false, 24576);
    }

    @Test
    void denseCutoutSectionKeepsEveryInternalFaceAfterGrowing() throws Exception {
        // An 8-cube-wide solid canopy: 512 leaves * 6 faces * 2 triangles = 6144. Adjacent
        // cutout cells must keep their internal faces for per-surface alpha testing.
        exerciseDenseSection(true, 6144);
    }

    private static void exerciseDenseSection(boolean cutout, int expectedTriangles) throws Exception {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);
        long pool = Objc.autoreleasePoolPush();
        Deque<Long> owned = new ArrayDeque<>();
        Map<Field, Object> restore = new HashMap<>();
        MetalRtShaders.Compiled compiled = null;
        long probe = 0;
        long queue = 0;
        try {
            owned.push(device);
            compiled = MetalRtShaders.compile(device);
            long library = MetalRtShaders.compileSource(device, "capacity_probe.metal", PROBE);
            owned.push(library);
            long function = Objc.msgSendId(library, Objc.selector("newFunctionWithName:"), Objc.nsString("capacity_probe"));
            assertNotEquals(0L, function);
            owned.push(function);
            Objc.Result pipeline = Objc.msgSendIdIdErr(device,
                    Objc.selector("newComputePipelineStateWithFunction:error:"), function);
            assertNotEquals(0L, pipeline.id(), pipeline.error());
            probe = pipeline.id();
            owned.push(probe);
            queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
            assertNotEquals(0L, queue);
            owned.push(queue);
            long occupancy = exportBuffer(device, "occupancy", BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, owned, restore);
            exportBuffer(device, "payload", BrickGridUpload.VOXELS_PER_SECTION, owned, restore);
            long seals = exportBuffer(device, "faceSeal", BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT, owned, restore);
            long palette = exportBuffer(device, "palette", BrickGridUpload.PALETTE_BYTES_PER_SLOT, owned, restore);
            replaceGeometryField("allocatedDiameter", 1, restore);
            contents(seals, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT).fill((byte) 0x3f);
            contents(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT).set(ValueLayout.JAVA_INT, 0, cutout ? 1 << 30 : 0);
            MemorySegment mask = contents(occupancy, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
            Map<Integer, SectionPos> sections = Map.of(0, SectionPos.of(0, 0, 0));
            mask.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
            MetalRtAcceleration.rebuildDirty(queue, compiled, List.of(0), sections, 0, 0);
            mask.fill((byte) 0);
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (cutout ? x < 8 && y < 8 && z < 8 : ((x + y + z) & 1) == 0) {
                    int voxel = (y << 8) | (z << 4) | x;
                    long offset = voxel / 8;
                    mask.set(ValueLayout.JAVA_BYTE, offset,
                            (byte) (mask.get(ValueLayout.JAVA_BYTE, offset) | (1 << (voxel % 8))));
                }
            }
            MetalRtAcceleration.rebuildDirty(queue, compiled, List.of(0), sections, 0, 0);
            Object slot = slotZero();
            MemorySegment counters = contents(longField(slot, "countersBuffer"), MetalRtAcceleration.COUNTERS_BYTES);
            assertEquals(expectedTriangles, counters.get(ValueLayout.JAVA_INT, 0), "the kernel counts the whole section");
            assertEquals(0, counters.get(ValueLayout.JAVA_INT, 4), "production rebuild must retry until no triangles are dropped");
            long vertex = longField(slot, "vertexBuffer");
            long primitive = longField(slot, "primitiveDataBuffer");
            assertEquals((long) expectedTriangles * 36, Objc.msgSendLong(vertex, Objc.selector("length")),
                    "grow this slot to its exact required vertex capacity");
            assertEquals((long) expectedTriangles * 4, Objc.msgSendLong(primitive, Objc.selector("length")));
            assertAllFacesPresent(queue, probe, slot, expectedTriangles, cutout, owned, device);

            MetalRtAcceleration.rebuildDirty(queue, compiled, List.of(0), sections, 0, 0);
            assertEquals(vertex, longField(slot, "vertexBuffer"), "stable dense sections reuse grown vertex storage");
            assertEquals(primitive, longField(slot, "primitiveDataBuffer"), "stable dense sections reuse primitive storage");
            assertEquals(0, counters.get(ValueLayout.JAVA_INT, 4));
            mask.fill((byte) 0);
            mask.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
            MetalRtAcceleration.rebuildDirty(queue, compiled, List.of(0), sections, 0, 0);
            assertEquals(12, counters.get(ValueLayout.JAVA_INT, 0), "a later sparse section rebuild uses its actual count");
            assertEquals(vertex, longField(slot, "vertexBuffer"), "shrinking geometry does not churn storage");
            assertEquals(primitive, longField(slot, "primitiveDataBuffer"));
        } finally {
            // rebuildDirty submits builds asynchronously; complete the queue before releasing its resources.
            if (queue != 0) {
                long fence = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                Objc.msgSendVoid(fence, Objc.selector("commit"));
                Objc.msgSendVoid(fence, Objc.selector("waitUntilCompleted"));
            }
            MetalRtAcceleration.destroy();
            for (Map.Entry<Field, Object> entry : restore.entrySet()) entry.getKey().set(null, entry.getValue());
            if (compiled != null) compiled.release();
            while (!owned.isEmpty()) Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            Objc.autoreleasePoolPop(pool);
        }
    }

    private static void assertAllFacesPresent(long queue, long probe, Object slot,
            int count, boolean cutout, Deque<Long> owned, long device) throws Exception {
        // Read every primitive: each occupied cell must have all twelve, even if GPU atomics reorder them.
        MemorySegment primitives = contents(longField(slot, "primitiveDataBuffer"), (long) count * 4);
        int[][] faceCounts = new int[4096][6];
        for (int i = 0; i < count; i++) {
            int packed = primitives.getAtIndex(ValueLayout.JAVA_INT, i);
            faceCounts[packed & 0xfff][(packed >>> 12) & 7]++;
        }
        for (int voxel = 0; voxel < 4096; voxel++) {
            int x = voxel & 15, y = voxel >>> 8, z = (voxel >>> 4) & 15;
            boolean occupied = cutout ? x < 8 && y < 8 && z < 8 : ((x + y + z) & 1) == 0;
            for (int face = 0; face < 6; face++) assertEquals(occupied ? 2 : 0, faceCounts[voxel][face],
                    "complete primitive coverage at voxel " + voxel + " face " + face);
        }
        // Probe independently generated expected faces, so dropped geometry cannot hide by also
        // disappearing from the ray list. The query checks the actual production-built structure.
        long expected = MetalRtAcceleration.createBuffer(device, (long) count * 4);
        owned.push(expected);
        MemorySegment expectedData = contents(expected, (long) count * 4);
        int index = 0;
        for (int voxel = 0; voxel < 4096; voxel++) for (int face = 0; face < 6; face++) {
            for (int triangle = 0; triangle < faceCounts[voxel][face]; triangle++) {
                expectedData.setAtIndex(ValueLayout.JAVA_INT, index++, voxel | (face << 12));
            }
        }
        long result = MetalRtAcceleration.createBuffer(device, (long) count * 4);
        owned.push(result);
        contents(result, (long) count * 4).fill((byte) 0);
        long cb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
        long encoder = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
        try {
            Objc.msgSendVoid(encoder, Objc.selector("setComputePipelineState:"), probe);
            Objc.msgSendVoidIdLong(encoder, Objc.selector("setAccelerationStructure:atBufferIndex:"),
                    longField(slot, "accelerationStructure"), 0L);
            Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), expected, 0, 1);
            Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), result, 0, 2);
            MetalRtAcceleration.useResources(encoder);
            Objc.dispatchThreadgroups(encoder, count / 64, 1, 1, 64, 1, 1);
        } finally {
            Objc.msgSendVoid(encoder, Objc.selector("endEncoding"));
        }
        Objc.msgSendVoid(cb, Objc.selector("commit"));
        Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));
        MemorySegment hits = contents(result, (long) count * 4);
        for (int i = 0; i < count; i++) assertEquals(1, hits.getAtIndex(ValueLayout.JAVA_INT, i),
                "built acceleration structure must contain expected face probe " + i);
    }

    private static long exportBuffer(long device, String field, long bytes, Deque<Long> owned,
            Map<Field, Object> restore) throws Exception {
        long buffer = MetalRtAcceleration.createBuffer(device, bytes);
        owned.push(buffer);
        contents(buffer, bytes).fill((byte) 0);
        replaceGeometryField(field, new MetalRtGeometry.ExportedBuffer(0, 0, buffer, bytes), restore);
        return buffer;
    }

    private static void replaceGeometryField(String name, Object value, Map<Field, Object> restore) throws Exception {
        Field field = MetalRtGeometry.class.getDeclaredField(name);
        field.setAccessible(true);
        restore.put(field, field.get(null));
        field.set(null, value);
    }

    private static Object slotZero() throws Exception {
        Field field = MetalRtAcceleration.class.getDeclaredField("slots");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(null)).get(0);
    }

    private static long longField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(target);
    }

    private static MemorySegment contents(long buffer, long bytes) {
        return MemorySegment.ofAddress(Objc.msgSendId(buffer, Objc.selector("contents"))).reinterpret(bytes);
    }
}
