package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.rt.RayTier;
import dev.icehunter.fornax.voxel.BrickGridUpload;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A real acceleration structure with one solid block in it, plus the machinery to fire caller-built
 * rays at it through {@code rt_ray_query}.
 *
 * <p>Deliberately built through the shipping path, {@code rt_expand} into a primitive structure
 * into an instance structure, rather than from hand-written triangles: a ray query that works
 * against a synthetic quad but not against the geometry the engine actually builds would pass a
 * test and fail in a world.
 *
 * <p>Geometry, which every expected value in the tests is read off: one FULL voxel at local
 * (8, 8, 8). {@code rt_expand} emits a FULL voxel as the axis-aligned box
 * {@code [lx, lx+1] x [ly, ly+1] x [lz, lz+1]} (rt_expand.metal, {@code boxMax = voxelPos +
 * float3(1.0)}), and the single instance is placed at translation (0, 0, 0), so the block occupies
 * exactly <b>[8,9] x [8,9] x [8,9]</b> in the structure's own space, one unit per block.
 *
 * <p>Callers must gate on {@link Objc#isLoaded()} and a non-zero device before calling
 * {@link #open}.
 */
final class RayQueryScene implements AutoCloseable {

    /** The occupied voxel's local coordinate on every axis. */
    static final int VOXEL_LOCAL = 8;

    /** Low corner of the solid block in structure space. */
    static final float BLOCK_MIN = VOXEL_LOCAL;

    /** High corner of the solid block in structure space. */
    static final float BLOCK_MAX = VOXEL_LOCAL + 1.0f;

    /** MTLResourceUsageRead, from Metal.framework's MTLResourceUsage. */
    private static final long READ_USAGE = 1L;

    private final long device;
    private final long queue;
    private final long instanceStructure;
    private final long primitiveStructure;
    private final MetalRtShaders.Compiled compiled;
    private final MetalRtShaders.CompiledKernel rayQuery;
    private final Deque<Long> owned;
    private final long pool;
    private final int triangleCount;

    private RayQueryScene(long device, long queue, long instanceStructure, long primitiveStructure,
            MetalRtShaders.Compiled compiled, MetalRtShaders.CompiledKernel rayQuery,
            Deque<Long> owned, long pool, int triangleCount) {
        this.device = device;
        this.queue = queue;
        this.instanceStructure = instanceStructure;
        this.primitiveStructure = primitiveStructure;
        this.compiled = compiled;
        this.rayQuery = rayQuery;
        this.owned = owned;
        this.pool = pool;
        this.triangleCount = triangleCount;
    }

    /** Triangles rt_expand actually emitted for this scene. */
    int triangleCount() {
        return triangleCount;
    }

    /** One hit record, decoded from the kernel's RayHit struct. */
    record Hit(float distance, int flags, int surface, int atlasUv,
            float normalX, float normalY, float normalZ, int tier) {
        boolean answered() {
            return RayQueryAbi.isAnswered(tier);
        }

        boolean isMiss() {
            return RayQueryAbi.isMiss(distance);
        }

        int frontFacing() {
            return (flags & RayQueryAbi.FLAG_FRONT_FACING) != 0 ? 1 : 0;
        }

        int face() {
            return (flags >>> RayQueryAbi.FLAG_FACE_SHIFT) & RayQueryAbi.FLAG_FACE_MASK;
        }

        boolean uvKnown() {
            return (flags & RayQueryAbi.FLAG_UV_KNOWN) != 0;
        }

        boolean normalUnknown() {
            return RayQueryAbi.isNormalUnknown(normalX, normalY, normalZ);
        }

        String normalText() {
            return "(" + normalX + ", " + normalY + ", " + normalZ + ")";
        }
    }

    /** One solid block at local (8, 8, 8): the exact-geometry scene the round-trip test reads. */
    static RayQueryScene open(long device) {
        // voxelIndex = (y << 8) | (z << 4) | x, exactly as BrickGridUpload writes it, so
        // (8, 8, 8) = (8 << 8) | (8 << 4) | 8 = 2184.
        return open(device, new int[]{2184});
    }

    /**
     * 256 isolated blocks on a lattice spread through the section: x and z every 2 from 0 to 14,
     * y every 2 from 0 to 6. No two touch, so every one emits all 12 triangles, for 3072 in total,
     * against {@code RT_INITIAL_TRIS_PER_SLOT}'s cap of 4096 which the expand kernel will not grow
     * past. Two orders of magnitude more geometry than {@link #open(long)} and, being scattered
     * rather than one solid mass, a deeper BVH for a ray to walk, so a throughput figure taken here
     * is far closer to what a world costs than a twelve-triangle best case.
     *
     * <p>A denser solid fill was tried first and overflowed the cap at 20480 triangles: the
     * harvest's neighbour test does not seal interior faces when the payload and palette are the
     * zeroed stand-ins this fixture uses, so a solid slab emits nearly six faces per voxel rather
     * than only its shell.
     */
    static RayQueryScene openScatteredLattice(long device) {
        int[] voxels = new int[8 * 8 * 4];
        int at = 0;
        for (int y = 0; y < 8; y += 2) {
            for (int z = 0; z < 16; z += 2) {
                for (int x = 0; x < 16; x += 2) {
                    // voxelIndex = (y << 8) | (z << 4) | x, as BrickGridUpload writes it.
                    voxels[at++] = (y << 8) | (z << 4) | x;
                }
            }
        }
        return open(device, voxels);
    }

    static RayQueryScene open(long device, int[] occupiedVoxelIndices) {
        long pool = Objc.autoreleasePoolPush();
        Deque<Long> owned = new ArrayDeque<>();
        long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
        owned.push(queue);
        MetalRtShaders.Compiled compiled = MetalRtShaders.compile(device);
        MetalRtShaders.CompiledKernel rayQuery = MetalRtShaders.compileKernel(
                device, MetalRtShaders.RAY_QUERY_RESOURCE, MetalRtShaders.RAY_QUERY_FUNCTION);

        long occupancy = MetalRtAcceleration.createBuffer(device, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
        owned.push(occupancy);
        long payload = MetalRtAcceleration.createBuffer(device, BrickGridUpload.VOXELS_PER_SECTION);
        owned.push(payload);
        long faceSeal = MetalRtAcceleration.createBuffer(device, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
        owned.push(faceSeal);
        long palette = MetalRtAcceleration.createBuffer(device, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
        owned.push(palette);
        zero(payload, BrickGridUpload.VOXELS_PER_SECTION);
        zero(faceSeal, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
        zero(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
        setOccupied(occupancy, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, occupiedVoxelIndices);

        long vertexBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.VERTEX_BYTES_PER_SLOT);
        owned.push(vertexBuffer);
        long primitiveDataBuffer = MetalRtAcceleration.createBuffer(
                device, MetalRtAcceleration.PRIMITIVE_DATA_BYTES_PER_SLOT);
        owned.push(primitiveDataBuffer);
        long counters = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.COUNTERS_BYTES);
        owned.push(counters);

        long expandCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
        long expandEncoder = Objc.msgSendId(expandCb, Objc.selector("computeCommandEncoder"));
        MetalRtAcceleration.encodeExpand(expandEncoder, compiled.expand(),
                occupancy, 0L, payload, 0L, faceSeal, 0L, palette, 0L,
                vertexBuffer, primitiveDataBuffer, counters);
        Objc.msgSendVoid(expandEncoder, Objc.selector("endEncoding"));
        Objc.msgSendVoid(expandCb, Objc.selector("commit"));
        Objc.msgSendVoid(expandCb, Objc.selector("waitUntilCompleted"));

        int triangleCount = MetalRtAcceleration.readCompleteTriangleCount(counters, -1);
        if (triangleCount <= 0) {
            throw new IllegalStateException(
                    "rt_expand emitted no triangles for " + occupiedVoxelIndices.length
                            + " occupied voxels; the scene would trace as empty");
        }

        long buildCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
        long buildEncoder = Objc.msgSendId(buildCb, Objc.selector("accelerationStructureCommandEncoder"));

        long primDesc = MetalRtAcceleration.buildTrianglePrimitiveDescriptor(
                vertexBuffer, primitiveDataBuffer, triangleCount);
        Objc.AccelerationStructureSizes primSizes = Objc.accelerationStructureSizes(device, primDesc);
        long primitiveStructure = Objc.msgSendId(device,
                Objc.selector("newAccelerationStructureWithSize:"), primSizes.accelerationStructureSize());
        owned.push(primitiveStructure);
        long primScratch = MetalRtAcceleration.createBuffer(
                device, Math.max(primSizes.buildScratchBufferSize(), 4L));
        Objc.msgSendVoidIdIdIdLong(buildEncoder,
                Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                primitiveStructure, primDesc, primScratch, 0L);
        Objc.msgSendVoid(primScratch, Objc.selector("release"));

        long instanceDescriptorBuffer = MetalRtAcceleration.createBuffer(
                device, MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
        owned.push(instanceDescriptorBuffer);
        long instContents = Objc.msgSendId(instanceDescriptorBuffer, Objc.selector("contents"));
        MemorySegment instSeg = MemorySegment.ofAddress(instContents)
                .reinterpret(MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
        MetalRtAcceleration.writeInstanceDescriptor(instSeg, 0, 0.0f, 0.0f, 0.0f, 0);

        long instanceStructure;
        try (Arena local = Arena.ofConfined()) {
            MemorySegment structures = local.allocate(ValueLayout.JAVA_LONG.byteSize());
            structures.set(ValueLayout.JAVA_LONG, 0, primitiveStructure);
            long structuresArray = Objc.msgSendIdPtrLong(Objc.getClass("NSArray"),
                    Objc.selector("arrayWithObjects:count:"), structures.address(), 1L);

            long instDesc = Objc.msgSendId(Objc.getClass("MTLInstanceAccelerationStructureDescriptor"),
                    Objc.selector("descriptor"));
            Objc.msgSendVoid(instDesc, Objc.selector("setInstanceDescriptorBuffer:"), instanceDescriptorBuffer);
            Objc.msgSendVoidLong(instDesc, Objc.selector("setInstanceCount:"), 1L);
            Objc.msgSendVoid(instDesc, Objc.selector("setInstancedAccelerationStructures:"), structuresArray);

            Objc.AccelerationStructureSizes instSizes = Objc.accelerationStructureSizes(device, instDesc);
            instanceStructure = Objc.msgSendId(device,
                    Objc.selector("newAccelerationStructureWithSize:"), instSizes.accelerationStructureSize());
            owned.push(instanceStructure);
            long instScratch = MetalRtAcceleration.createBuffer(
                    device, Math.max(instSizes.buildScratchBufferSize(), 4L));
            Objc.msgSendVoidIdIdIdLong(buildEncoder,
                    Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                    instanceStructure, instDesc, instScratch, 0L);
            Objc.msgSendVoid(instScratch, Objc.selector("release"));
        }
        Objc.msgSendVoid(buildEncoder, Objc.selector("endEncoding"));
        Objc.msgSendVoid(buildCb, Objc.selector("commit"));
        Objc.msgSendVoid(buildCb, Objc.selector("waitUntilCompleted"));

        return new RayQueryScene(device, queue, instanceStructure, primitiveStructure,
                compiled, rayQuery, owned, pool, triangleCount);
    }

    /**
     * Fires {@code rayCount} rays, each 8 floats of origin xyz + tMin then direction xyz + tMax,
     * and returns one {@link Hit} per ray. Dispatches once and waits.
     */
    Hit[] trace(float[] requests, int rayCount) {
        return traceWithAbiVersion(requests, rayCount, RayQueryAbi.ABI_VERSION);
    }

    /**
     * Traces into a hit buffer whose records may already be answered, leaving those alone. This is
     * the discipline the cascade rests on: a lower tier must not overwrite a higher one's answer.
     */
    Hit[] fill(float[] requests, int rayCount, int[] seedTiers) {
        long requestBuffer = MetalRtAcceleration.createBuffer(device, RayQueryAbi.requestByteSize(rayCount));
        long hitBuffer = MetalRtAcceleration.createBuffer(device, RayQueryAbi.hitByteSize(rayCount));
        try {
            writeFloats(requestBuffer, requests);
            MemorySegment seg = MemorySegment
                    .ofAddress(Objc.msgSendId(hitBuffer, Objc.selector("contents")))
                    .reinterpret(RayQueryAbi.hitByteSize(rayCount));
            seg.fill((byte) 0);
            for (int i = 0; i < seedTiers.length; i++) {
                seg.set(ValueLayout.JAVA_INT,
                        (long) i * RayQueryAbi.HIT_WORDS * Float.BYTES + RayQueryAbi.HIT_TIER_WORD * 4L,
                        seedTiers[i]);
            }
            dispatch(requestBuffer, hitBuffer, rayCount, 1, RayQueryAbi.ABI_VERSION, true);
            return readHits(hitBuffer, rayCount);
        } finally {
            Objc.msgSendVoid(hitBuffer, Objc.selector("release"));
            Objc.msgSendVoid(requestBuffer, Objc.selector("release"));
        }
    }

    /** As {@link #trace}, declaring {@code abiVersion} in the constants block, so a test can lie. */
    Hit[] traceWithAbiVersion(float[] requests, int rayCount, int abiVersion) {
        if (requests.length != rayCount * RayQueryAbi.REQUEST_WORDS) {
            throw new IllegalArgumentException("requests must hold " + RayQueryAbi.REQUEST_WORDS
                    + " floats per ray, got " + requests.length + " for " + rayCount + " rays");
        }
        long requestBuffer = MetalRtAcceleration.createBuffer(device, RayQueryAbi.requestByteSize(rayCount));
        long hitBuffer = MetalRtAcceleration.createBuffer(device, RayQueryAbi.hitByteSize(rayCount));
        try {
            writeFloats(requestBuffer, requests);
            dispatch(requestBuffer, hitBuffer, rayCount, 1, abiVersion);
            return readHits(hitBuffer, rayCount);
        } finally {
            Objc.msgSendVoid(hitBuffer, Objc.selector("release"));
            Objc.msgSendVoid(requestBuffer, Objc.selector("release"));
        }
    }

    /**
     * Allocates a hit buffer the size {@code rayCount} needs and reads it back with nothing ever
     * dispatched against it. This is what a caller on a machine with no ray tracing reads, and the
     * reason a record's validity cannot live in the sign of its distance.
     */
    Hit[] readUntracedBuffer(int rayCount) {
        long hitBuffer = MetalRtAcceleration.createBuffer(device, RayQueryAbi.hitByteSize(rayCount));
        try {
            return readHits(hitBuffer, rayCount);
        } finally {
            Objc.msgSendVoid(hitBuffer, Objc.selector("release"));
        }
    }

    /**
     * Encodes {@code iterations} back-to-back dispatches of the same ray set into one command
     * buffer and returns the wall-clock nanoseconds the GPU took to complete all of them. One
     * command buffer rather than {@code iterations} of them so submission overhead is paid once.
     */
    long timeDispatches(long requestBuffer, long hitBuffer, int rayCount, int iterations) {
        long start = System.nanoTime();
        dispatch(requestBuffer, hitBuffer, rayCount, iterations, RayQueryAbi.ABI_VERSION);
        return System.nanoTime() - start;
    }

    private void dispatch(long requestBuffer, long hitBuffer, int rayCount, int iterations,
            int abiVersion) {
        dispatch(requestBuffer, hitBuffer, rayCount, iterations, abiVersion, false);
    }

    private void dispatch(long requestBuffer, long hitBuffer, int rayCount, int iterations,
            int abiVersion, boolean fillMode) {
        long cb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
        long encoder = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
        Objc.msgSendVoid(encoder, Objc.selector("setComputePipelineState:"), rayQuery.pipeline());
        // The instance structure references the primitive structure, which the encoder cannot see
        // through it. Without this the trace reads an unresident structure and returns misses.
        Objc.msgSendVoidIdLong(encoder, Objc.selector("useResource:usage:"), primitiveStructure, READ_USAGE);
        Objc.msgSendVoidIdLong(encoder, Objc.selector("setAccelerationStructure:atBufferIndex:"),
                instanceStructure, 1L);

        long constants = MetalRtAcceleration.createBuffer(device, 16L);
        try {
            long ptr = Objc.msgSendId(constants, Objc.selector("contents"));
            MemorySegment seg = MemorySegment.ofAddress(ptr).reinterpret(16L);
            seg.set(ValueLayout.JAVA_INT, 0L, abiVersion);
            seg.set(ValueLayout.JAVA_INT, 4L, rayCount);
            // This scene is the rt_expand brick-grid structure, so its answers are tier 2. The
            // kernel copies this word into every record it writes.
            seg.set(ValueLayout.JAVA_INT, 8L, RayTier.HARDWARE_VOXEL.ordinal());
            // Fill mode off: this fixture owns its hit buffer and writes every record.
            seg.set(ValueLayout.JAVA_INT, 12L, fillMode ? 1 : 0);
            Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), constants, 0L, 0L);
            Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), requestBuffer, 0L, 2L);
            Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), hitBuffer, 0L, 3L);

            long threadsPerGroup = Math.min(64L,
                    Objc.msgSendLong(rayQuery.pipeline(), Objc.selector("maxTotalThreadsPerThreadgroup")));
            long groups = (rayCount + threadsPerGroup - 1) / threadsPerGroup;
            for (int i = 0; i < iterations; i++) {
                Objc.dispatchThreadgroups(encoder, groups, 1L, 1L, threadsPerGroup, 1L, 1L);
            }
            Objc.msgSendVoid(encoder, Objc.selector("endEncoding"));
            Objc.msgSendVoid(cb, Objc.selector("commit"));
            Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));
        } finally {
            Objc.msgSendVoid(constants, Objc.selector("release"));
        }
    }

    long newRequestBuffer(float[] requests) {
        long buffer = MetalRtAcceleration.createBuffer(device,
                (long) requests.length * Float.BYTES);
        writeFloats(buffer, requests);
        owned.push(buffer);
        return buffer;
    }

    long newHitBuffer(int rayCount) {
        long buffer = MetalRtAcceleration.createBuffer(device, RayQueryAbi.hitByteSize(rayCount));
        owned.push(buffer);
        return buffer;
    }

    private static void writeFloats(long buffer, float[] data) {
        long ptr = Objc.msgSendId(buffer, Objc.selector("contents"));
        MemorySegment seg = MemorySegment.ofAddress(ptr).reinterpret((long) data.length * Float.BYTES);
        MemorySegment.copy(MemorySegment.ofArray(data), ValueLayout.JAVA_FLOAT, 0L,
                seg, ValueLayout.JAVA_FLOAT, 0L, data.length);
    }

    private static Hit[] readHits(long buffer, int rayCount) {
        long ptr = Objc.msgSendId(buffer, Objc.selector("contents"));
        MemorySegment seg = MemorySegment.ofAddress(ptr).reinterpret(RayQueryAbi.hitByteSize(rayCount));
        Hit[] hits = new Hit[rayCount];
        for (int i = 0; i < rayCount; i++) {
            long base = (long) i * RayQueryAbi.HIT_WORDS * Float.BYTES;
            hits[i] = new Hit(
                    seg.get(ValueLayout.JAVA_FLOAT, base + RayQueryAbi.HIT_DISTANCE_WORD * 4L),
                    seg.get(ValueLayout.JAVA_INT, base + RayQueryAbi.HIT_FLAGS_WORD * 4L),
                    seg.get(ValueLayout.JAVA_INT, base + RayQueryAbi.HIT_SURFACE_WORD * 4L),
                    seg.get(ValueLayout.JAVA_INT, base + RayQueryAbi.HIT_ATLAS_UV_WORD * 4L),
                    seg.get(ValueLayout.JAVA_FLOAT, base + RayQueryAbi.HIT_NORMAL_WORD * 4L),
                    seg.get(ValueLayout.JAVA_FLOAT, base + (RayQueryAbi.HIT_NORMAL_WORD + 1) * 4L),
                    seg.get(ValueLayout.JAVA_FLOAT, base + (RayQueryAbi.HIT_NORMAL_WORD + 2) * 4L),
                    seg.get(ValueLayout.JAVA_INT, base + RayQueryAbi.HIT_TIER_WORD * 4L));
        }
        return hits;
    }

    private static void zero(long buffer, long byteSize) {
        long ptr = Objc.msgSendId(buffer, Objc.selector("contents"));
        MemorySegment.ofAddress(ptr).reinterpret(byteSize).fill((byte) 0);
    }

    private static void setOccupied(long buffer, long byteSize, int[] voxelIndices) {
        long ptr = Objc.msgSendId(buffer, Objc.selector("contents"));
        MemorySegment seg = MemorySegment.ofAddress(ptr).reinterpret(byteSize);
        seg.fill((byte) 0);
        for (int voxelIndex : voxelIndices) {
            int byteIndex = voxelIndex >> 3;
            int bitIndex = voxelIndex & 7;
            byte current = seg.get(ValueLayout.JAVA_BYTE, byteIndex);
            seg.set(ValueLayout.JAVA_BYTE, byteIndex, (byte) (current | (1 << bitIndex)));
        }
    }

    @Override
    public void close() {
        rayQuery.release();
        compiled.release();
        while (!owned.isEmpty()) {
            Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
        }
        Objc.autoreleasePoolPop(pool);
    }
}
