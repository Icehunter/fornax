package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.voxel.BrickGridUpload;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the whole Metal ray tracing chain end to end on the real GPU, with no Vulkan involved:
 * a single FULL stone voxel's brick-grid data, expanded to triangles, built into a primitive and
 * then an instance acceleration structure, traced by {@code rt_trace} against two rays.
 *
 * <p>The voxel sits at local (8, 8, 8) with every face exposed (payload 0, face-seal 0, palette
 * entry 0's word 0 left at 0, so {@code boxCount} 0 means FULL). {@code rt_expand} therefore emits
 * all 6 faces, 2 triangles each: 12 total. The instance transform is identity (zero translation),
 * so the built geometry sits at grid coordinates equal to its local voxel coordinates: the voxel
 * occupies grid x, y, z each in [8, 9].
 *
 * <p><b>The hand-picked {@code invProjModelView}.</b> With {@code camAbs} and {@code
 * firstSectionTimes16} both zero, {@code rt_trace.metal} reduces {@code gridPos} to exactly its
 * reconstructed camera-relative position, so the matrix alone decides where each pixel's ray
 * starts. For a 2x1 output, pixel 0's NDC x is -0.5 and pixel 1's is 0.5 (texel-center sampling:
 * {@code (0.5)/2*2-1 = -0.5}, {@code (1.5)/2*2-1 = 0.5}). The matrix maps NDC x to BOTH grid x and
 * grid z the same way (one ray straight down through the point {@code (x, *, x)}), fixes grid y at
 * 20 (well above the voxel), and ignores the depth input entirely (its column is all zero) since
 * this test only cares about the ray's horizontal footprint, not depth-based reconstruction
 * accuracy. Solving {@code f(-0.5) = 8.5} and {@code f(0.5) = 0.5} for a line gives {@code f(ndc.x)
 * = -8*ndc.x + 4.5}: pixel 0 lands exactly on the voxel's horizontal center (8.5, 8.5) and its ray
 * (straight down, {@code sunDir = (0, -1, 0)}) hits the voxel's top face; pixel 1 lands at
 * (0.5, 0.5), nowhere near the voxel, and its ray misses out to {@code maxDistance}. Column-major
 * (matching {@code Matrix4fc.get(float[])} and this bridge's other constant-buffer writers):
 * column 0 = (-8, 0, -8, 0), columns 1 and 2 = zero, column 3 = (4.5, 20, 4.5, 1); the w row
 * (0, 0, 0, 1) keeps the divide a no-op.
 *
 * <p>Depth input: {@code rt_trace.metal} declares its depth parameter as {@code depth2d<float,
 * access::read>}, which requires a genuine depth-format texture (a {@code depth2d} binding over a
 * color-format texture is a type mismatch Metal need not accept). {@code
 * MTLPixelFormatDepth32Float} textures cannot be seeded via {@code replaceRegion:} (Apple's own
 * documentation excludes depth/stencil formats from it), so this test seeds both texels to 0.5
 * with a tiny throwaway compute kernel that writes the SAME texture through a {@code
 * texture2d<float, access::write>} view before {@code rt_trace} reads it through its {@code
 * depth2d} one.
 *
 * <p>Normal input: {@code rt_trace.metal} biases the ray origin along the real surface normal,
 * read per pixel from a texture bound at {@code texture(2)}, and falls back to biasing along the
 * sun direction only when that texture reads back a zero vector: the documented signal for "no
 * real G-buffer normal available". This test has no G-buffer at all, so it seeds a 2x1
 * {@code MTLPixelFormatRGBA16Snorm} texture to all zero (the same throwaway-kernel technique the
 * depth texture uses, kept on one path rather than adding a CPU-side seeding method for this one
 * texture) and deliberately exercises the fallback, not the normal-bias branch: the expected
 * hit/miss pixels above match the fallback math, which does not depend on the normal-bias branch.
 */
class MetalRtSmokeTest {
    // Metal enum values confirmed against Metal.framework headers on this machine.
    private static final long MTL_TEXTURE_TYPE_2D = 2;
    private static final long MTL_PIXEL_FORMAT_R8_UNORM = 10;
    private static final long MTL_PIXEL_FORMAT_DEPTH32_FLOAT = 252;
    private static final long MTL_PIXEL_FORMAT_RGBA16_SNORM = 112;
    private static final long MTL_PIXEL_FORMAT_RGBA32_FLOAT = 125;
    private static final long MTL_TEXTURE_USAGE_SHADER_READ = 1;
    private static final long MTL_TEXTURE_USAGE_SHADER_WRITE = 2;
    private static final long MTL_STORAGE_MODE_SHARED = 0;
    private static final long MTL_RESOURCE_USAGE_READ = 1;

    /** {@code rt_trace.metal}'s own {@code CUTOUT_ATLAS_BIT}/{@code CUTOUT_FACE_TEXTURE_BIT}:
     * the two independent bits of buffer(5)'s {@code cutoutFlags}. A CROSS entry alpha-tests off
     * {@link #CUTOUT_FLAG_ATLAS} alone; a FULL/PARTIAL cutout entry needs both. */
    private static final int CUTOUT_FLAG_ATLAS = 1;
    private static final int CUTOUT_FLAG_FACE_TEXTURE = 2;

    /** {@code RtTraceConstants}' real total size; see rt_trace.metal's own byte-offset table. */
    private static final long TRACE_CONSTANTS_BYTES = 144L;

    /** Synthetic complete finite domain large enough for the existing ray fixtures. */
    private static final int TEST_WINDOW_DIAMETER = 64;
    private static final int TEST_SNAPSHOT_READY = 1;

    private static final String FILL_DEPTH_SOURCE = """
            #include <metal_stdlib>
            using namespace metal;
            kernel void fill_depth(texture2d<float, access::write> d [[texture(0)]],
                    uint2 gid [[thread_position_in_grid]]) {
                d.write(float4(0.5), gid);
            }
            """;

    // rt_trace.metal reads normalIn as the fallback signal (a zero vector) when no real G-buffer
    // normal is available: exactly this test's situation, with no G-buffer at all. Seeding
    // it to all zero exercises that documented fallback path rather than a made-up normal.
    private static final String FILL_ZERO_SOURCE = """
            #include <metal_stdlib>
            using namespace metal;
            kernel void fill_zero(texture2d<float, access::write> d [[texture(0)]],
                    uint2 gid [[thread_position_in_grid]]) {
                d.write(float4(0.0), gid);
            }
            """;

    // Seeds a G-buffer normal texel the way a strongly LabPBR-bump-mapped top-face texel can read
    // back in practice: rgb is a near-unit vector tilted mostly horizontal (vertical component
    // 0.0999, far short of the 1.0 a flat face normal would carry), the kind of value terrain.fsh's
    // worldNormal (terrain.fsh:155) produces on a texel with real per-texel bump detail. Alpha is a
    // real plagueEncodeGeometricNormal(vec3(0,1,0)) code: axis loop picks axis 1 (Y), sign positive,
    // so code = 2 + 1*2 + 1 = 5, alpha = 5.0/32767.0 (see geometric_normal.glsl and
    // rt_trace.metal's plagueDecodeGeometricNormal, which decodes this back to exactly (0,1,0)).
    private static final String FILL_TILTED_NORMAL_SOURCE = """
            #include <metal_stdlib>
            using namespace metal;
            kernel void fill_tilted_normal(texture2d<float, access::write> d [[texture(0)]],
                    uint2 gid [[thread_position_in_grid]]) {
                d.write(float4(0.995, 0.0999, 0.0, 5.0 / 32767.0), gid);
            }
            """;

    // Appended to the real, unmodified rt_trace.metal source (read from its resource, see
    // readRtTraceSource) so this probe calls the exact plagueDecodeGeometricNormal the trace kernel
    // ships, rather than a re-typed copy that could silently drift from it. One thread per texel;
    // alphas[gid.x] is one encoded alpha value, the fallback float3(2.0) is well outside any real
    // decode output (every real branch returns a unit vector or a caller-chosen fallback in
    // [-1, 1]) so its presence in the readback unambiguously means the fallback path fired.
    private static final String DECODE_PROBE_KERNEL_SOURCE = """

            kernel void decode_probe(constant float* alphas [[buffer(0)]],
                    texture2d<float, access::write> out [[texture(0)]],
                    uint2 gid [[thread_position_in_grid]]) {
                float3 n = plagueDecodeGeometricNormal(alphas[gid.x], float3(2.0, 2.0, 2.0));
                out.write(float4(n, 0.0), gid);
            }
            """;

    /** {@code rt_trace}'s cutout-alpha-testing arguments (palette, faceTexture, instanceSlotMap,
     * cutoutFlags, validOut, atlasIn; see rt_trace.metal's own kernel signature) that a
     * test not exercising cutout geometry still must bind something real to. {@code
     * cutoutFlags} is 0, matching a session where no pack has enabled {@code
     * VoxelFaceTexture.TARGET} and no atlas is captured: {@code faceTexture}/{@code atlas}
     * are never read down that path, so a minimal placeholder is enough. {@code instanceSlotMap}
     * maps instance 0 to slot 0, the only instance every test in this file builds. */
    private record InactiveCutoutFixtures(long faceTexture, long instanceSlotMap, long atlas, long validOut) {
    }

    private static InactiveCutoutFixtures createInactiveCutoutFixtures(
            long device, int width, int height, Deque<Long> owned) {
        long faceTexture = MetalRtAcceleration.createBuffer(device, 4);
        owned.push(faceTexture);
        zeroBuffer(faceTexture, 4);

        long instanceSlotMap = MetalRtAcceleration.createBuffer(device, Integer.BYTES);
        owned.push(instanceSlotMap);
        long ptr = Objc.msgSendId(instanceSlotMap, Objc.selector("contents"));
        MemorySegment.ofAddress(ptr).reinterpret(Integer.BYTES).set(ValueLayout.JAVA_INT, 0, 0);

        long atlas = createTexture(device, MTL_PIXEL_FORMAT_RGBA32_FLOAT, 1, 1, MTL_TEXTURE_USAGE_SHADER_READ);
        owned.push(atlas);

        long validOut = createTexture(device, MTL_PIXEL_FORMAT_R8_UNORM, width, height, MTL_TEXTURE_USAGE_SHADER_WRITE);
        owned.push(validOut);

        return new InactiveCutoutFixtures(faceTexture, instanceSlotMap, atlas, validOut);
    }

    /** Binds {@code rt_trace}'s buffer(2..5) cutout-testing arguments onto {@code encoder}: the
     * real {@code palette} buffer (its cutout bit decides everything downstream) plus {@code
     * fixtures}' placeholders for the rest, with {@code cutoutFlags} set to both bits when
     * {@code available} is true, 0 otherwise. */
    private static void bindCutoutArgs(
            long encoder, long palette, InactiveCutoutFixtures fixtures, boolean available) {
        Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), palette, 0L, 2L);
        Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), fixtures.instanceSlotMap(), 0L, 10L);
        Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), fixtures.faceTexture(), 0L, 3L);
        Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), fixtures.instanceSlotMap(), 0L, 4L);
        try (Arena flagArena = Arena.ofConfined()) {
            MemorySegment flag = flagArena.allocate(ValueLayout.JAVA_INT);
            flag.set(ValueLayout.JAVA_INT, 0, available ? (CUTOUT_FLAG_ATLAS | CUTOUT_FLAG_FACE_TEXTURE) : 0);
            Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBytes:length:atIndex:"), flag.address(), 4L, 5L);
        }
        Objc.msgSendVoidIdLong(encoder, Objc.selector("setTexture:atIndex:"), fixtures.validOut(), 3L);
        Objc.msgSendVoidIdLong(encoder, Objc.selector("setTexture:atIndex:"), fixtures.atlas(), 4L);
    }

    @Test
    void oneVoxelExpandsBuildsAndTracesHitAndMiss() {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);

        Deque<Long> owned = new ArrayDeque<>();
        long pool = Objc.autoreleasePoolPush();
        try {
            owned.push(device);
            MetalRtShaders.Compiled compiled = MetalRtShaders.compile(device);
            try {
                long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
                assertNotEquals(0L, queue, "newCommandQueue must not return nil");
                owned.push(queue);

                // ---- one FULL voxel at local (8, 8, 8), every face exposed ----
                long occupancy = MetalRtAcceleration.createBuffer(device, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
                owned.push(occupancy);
                long payload = MetalRtAcceleration.createBuffer(device, BrickGridUpload.VOXELS_PER_SECTION);
                owned.push(payload);
                long faceSeal = MetalRtAcceleration.createBuffer(device, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                owned.push(faceSeal);
                long palette = MetalRtAcceleration.createBuffer(device, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                owned.push(palette);
                zeroBuffer(payload, BrickGridUpload.VOXELS_PER_SECTION);
                zeroBuffer(faceSeal, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                zeroBuffer(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                // voxelIndex = (y << 8) | (z << 4) | x for (8, 8, 8) = 2184; bit 2184 % 8 = 0 of
                // byte 2184 / 8 = 273.
                setOccupiedVoxel(occupancy, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, 273, 0);

                long vertexBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.VERTEX_BYTES_PER_SLOT);
                owned.push(vertexBuffer);
                long primitiveDataBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.PRIMITIVE_DATA_BYTES_PER_SLOT);
                owned.push(primitiveDataBuffer);
                long countersBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.COUNTERS_BYTES);
                owned.push(countersBuffer);

                long expandCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long expandEncoder = Objc.msgSendId(expandCb, Objc.selector("computeCommandEncoder"));
                MetalRtAcceleration.encodeExpand(expandEncoder, compiled.expand(),
                        occupancy, 0L, payload, 0L, faceSeal, 0L, palette, 0L,
                        vertexBuffer, primitiveDataBuffer, countersBuffer);
                Objc.msgSendVoid(expandEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(expandCb, Objc.selector("commit"));
                Objc.msgSendVoid(expandCb, Objc.selector("waitUntilCompleted"));

                int triangleCount = MetalRtAcceleration.readCompleteTriangleCount(countersBuffer, -1);
                assertEquals(12, triangleCount,
                        "a FULL voxel with every face exposed emits 12 triangles (2 per face x 6 faces)");

                // ---- primitive then instance acceleration structure, one build command buffer ----
                long buildCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long buildEncoder = Objc.msgSendId(buildCb, Objc.selector("accelerationStructureCommandEncoder"));

                long primDesc = MetalRtAcceleration.buildTrianglePrimitiveDescriptor(
                        vertexBuffer, primitiveDataBuffer, triangleCount);
                Objc.AccelerationStructureSizes primSizes = Objc.accelerationStructureSizes(device, primDesc);
                long primStructure = Objc.msgSendId(
                        device, Objc.selector("newAccelerationStructureWithSize:"), primSizes.accelerationStructureSize());
                assertNotEquals(0L, primStructure, "primitive acceleration structure must build");
                owned.push(primStructure);
                long primScratch = MetalRtAcceleration.createBuffer(device, Math.max(primSizes.buildScratchBufferSize(), 4L));
                Objc.msgSendVoidIdIdIdLong(buildEncoder,
                        Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                        primStructure, primDesc, primScratch, 0L);
                Objc.msgSendVoid(primScratch, Objc.selector("release"));

                long instanceDescriptorBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                owned.push(instanceDescriptorBuffer);
                long instContents = Objc.msgSendId(instanceDescriptorBuffer, Objc.selector("contents"));
                MemorySegment instSeg = MemorySegment.ofAddress(instContents)
                        .reinterpret(MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                MetalRtAcceleration.writeInstanceDescriptor(instSeg, 0, 0.0f, 0.0f, 0.0f, 0);

                long instanceStructure;
                try (Arena local = Arena.ofConfined()) {
                    MemorySegment structures = local.allocate(ValueLayout.JAVA_LONG.byteSize());
                    structures.set(ValueLayout.JAVA_LONG, 0, primStructure);
                    long structuresArray = Objc.msgSendIdPtrLong(Objc.getClass("NSArray"),
                            Objc.selector("arrayWithObjects:count:"), structures.address(), 1L);

                    long instDesc = Objc.msgSendId(
                            Objc.getClass("MTLInstanceAccelerationStructureDescriptor"), Objc.selector("descriptor"));
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstanceDescriptorBuffer:"), instanceDescriptorBuffer);
                    Objc.msgSendVoidLong(instDesc, Objc.selector("setInstanceCount:"), 1L);
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstancedAccelerationStructures:"), structuresArray);

                    Objc.AccelerationStructureSizes instSizes = Objc.accelerationStructureSizes(device, instDesc);
                    instanceStructure = Objc.msgSendId(device,
                            Objc.selector("newAccelerationStructureWithSize:"), instSizes.accelerationStructureSize());
                    assertNotEquals(0L, instanceStructure, "instance acceleration structure must build");
                    owned.push(instanceStructure);
                    long instScratch =
                            MetalRtAcceleration.createBuffer(device, Math.max(instSizes.buildScratchBufferSize(), 4L));
                    Objc.msgSendVoidIdIdIdLong(buildEncoder,
                            Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                            instanceStructure, instDesc, instScratch, 0L);
                    Objc.msgSendVoid(instScratch, Objc.selector("release"));
                }
                Objc.msgSendVoid(buildEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(buildCb, Objc.selector("commit"));
                Objc.msgSendVoid(buildCb, Objc.selector("waitUntilCompleted"));

                // ---- depth and normal (both seeded via a throwaway fill kernel) and output ----
                long depthTexture = createTexture(device, MTL_PIXEL_FORMAT_DEPTH32_FLOAT, 2, 1,
                        MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(depthTexture);
                long normalTexture = createTexture(device, MTL_PIXEL_FORMAT_RGBA16_SNORM, 2, 1,
                        MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(normalTexture);
                long outputTexture = createTexture(device, MTL_PIXEL_FORMAT_R8_UNORM, 2, 1, MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(outputTexture);

                fillTexture(device, queue, depthTexture, "fill_depth.metal", FILL_DEPTH_SOURCE, "fill_depth", owned);
                fillTexture(device, queue, normalTexture, "fill_zero.metal", FILL_ZERO_SOURCE, "fill_zero", owned);
                InactiveCutoutFixtures cutoutFixtures = createInactiveCutoutFixtures(device, 2, 1, owned);

                // ---- trace ----
                long traceCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long traceEncoder = Objc.msgSendId(traceCb, Objc.selector("computeCommandEncoder"));
                Objc.msgSendVoid(traceEncoder, Objc.selector("setComputePipelineState:"), compiled.trace().pipeline());
                Objc.msgSendVoidIdLong(
                        traceEncoder, Objc.selector("setAccelerationStructure:atBufferIndex:"), instanceStructure, 1L);
                bindCutoutArgs(traceEncoder, palette, cutoutFixtures, false);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), outputTexture, 0L);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), depthTexture, 1L);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), normalTexture, 2L);
                try (Arena local = Arena.ofConfined()) {
                    MemorySegment constants = local.allocate(TRACE_CONSTANTS_BYTES);
                    writeConstants(constants);
                    Objc.msgSendVoidIdLongLong(
                            traceEncoder, Objc.selector("setBytes:length:atIndex:"), constants.address(), TRACE_CONSTANTS_BYTES, 0L);
                }
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("useResource:usage:"), primStructure, MTL_RESOURCE_USAGE_READ);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("useResource:usage:"), vertexBuffer, MTL_RESOURCE_USAGE_READ);
                Objc.msgSendVoidIdLong(
                        traceEncoder, Objc.selector("useResource:usage:"), instanceStructure, MTL_RESOURCE_USAGE_READ);
                Objc.dispatchThreadgroups(traceEncoder, 2, 1, 1, 1, 1, 1);
                Objc.msgSendVoid(traceEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(traceCb, Objc.selector("commit"));
                Objc.msgSendVoid(traceCb, Objc.selector("waitUntilCompleted"));

                try (Arena local = Arena.ofConfined()) {
                    MemorySegment out = local.allocate(2);
                    Objc.getBytesFromRegion(outputTexture, out.address(), 2L, 0, 0, 0, 2, 1, 1, 0L);
                    int pixel0 = out.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
                    int pixel1 = out.get(ValueLayout.JAVA_BYTE, 1) & 0xFF;
                    assertEquals(0, pixel0, "pixel 0's ray passes through the voxel's horizontal center and hits");
                    assertEquals(255, pixel1, "pixel 1's ray passes well outside the voxel's footprint and misses");
                }
            } finally {
                compiled.release();
            }
        } finally {
            while (!owned.isEmpty()) {
                Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            }
            Objc.autoreleasePoolPop(pool);
        }
    }

    @Test
    void sunDepthKernelProjectsNearestDepthCertifiesOnlyIntersectedOwnersAndFillsOnlyUnansweredTexels() {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);

        Deque<Long> owned = new ArrayDeque<>();
        long pool = Objc.autoreleasePoolPush();
        try {
            owned.push(device);
            MetalRtShaders.Compiled compiled = MetalRtShaders.compile(device);
            try {
                long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
                assertNotEquals(0L, queue, "newCommandQueue must not return nil");
                owned.push(queue);

                // ---- one FULL voxel at local (8, 8, 8), every face exposed ----
                long occupancy = MetalRtAcceleration.createBuffer(device, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
                owned.push(occupancy);
                long payload = MetalRtAcceleration.createBuffer(device, BrickGridUpload.VOXELS_PER_SECTION);
                owned.push(payload);
                long faceSeal = MetalRtAcceleration.createBuffer(device, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                owned.push(faceSeal);
                long palette = MetalRtAcceleration.createBuffer(device, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                owned.push(palette);
                zeroBuffer(payload, BrickGridUpload.VOXELS_PER_SECTION);
                zeroBuffer(faceSeal, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                zeroBuffer(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                // voxelIndex = (y << 8) | (z << 4) | x for (8, 8, 8) = 2184; bit 2184 % 8 = 0 of
                // byte 2184 / 8 = 273.
                setOccupiedVoxel(occupancy, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, 273, 0);

                long vertexBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.VERTEX_BYTES_PER_SLOT);
                owned.push(vertexBuffer);
                long primitiveDataBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.PRIMITIVE_DATA_BYTES_PER_SLOT);
                owned.push(primitiveDataBuffer);
                long countersBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.COUNTERS_BYTES);
                owned.push(countersBuffer);

                long expandCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long expandEncoder = Objc.msgSendId(expandCb, Objc.selector("computeCommandEncoder"));
                MetalRtAcceleration.encodeExpand(expandEncoder, compiled.expand(),
                        occupancy, 0L, payload, 0L, faceSeal, 0L, palette, 0L,
                        vertexBuffer, primitiveDataBuffer, countersBuffer);
                Objc.msgSendVoid(expandEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(expandCb, Objc.selector("commit"));
                Objc.msgSendVoid(expandCb, Objc.selector("waitUntilCompleted"));

                int triangleCount = MetalRtAcceleration.readCompleteTriangleCount(countersBuffer, -1);
                assertEquals(12, triangleCount,
                        "a FULL voxel with every face exposed emits 12 triangles (2 per face x 6 faces)");

                // ---- primitive then instance acceleration structure, one build command buffer ----
                long buildCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long buildEncoder = Objc.msgSendId(buildCb, Objc.selector("accelerationStructureCommandEncoder"));

                long primDesc = MetalRtAcceleration.buildTrianglePrimitiveDescriptor(
                        vertexBuffer, primitiveDataBuffer, triangleCount);
                Objc.AccelerationStructureSizes primSizes = Objc.accelerationStructureSizes(device, primDesc);
                long primStructure = Objc.msgSendId(
                        device, Objc.selector("newAccelerationStructureWithSize:"), primSizes.accelerationStructureSize());
                assertNotEquals(0L, primStructure, "primitive acceleration structure must build");
                owned.push(primStructure);
                long primScratch = MetalRtAcceleration.createBuffer(device, Math.max(primSizes.buildScratchBufferSize(), 4L));
                Objc.msgSendVoidIdIdIdLong(buildEncoder,
                        Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                        primStructure, primDesc, primScratch, 0L);
                Objc.msgSendVoid(primScratch, Objc.selector("release"));

                long instanceDescriptorBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                owned.push(instanceDescriptorBuffer);
                long instContents = Objc.msgSendId(instanceDescriptorBuffer, Objc.selector("contents"));
                MemorySegment instSeg = MemorySegment.ofAddress(instContents)
                        .reinterpret(MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                MetalRtAcceleration.writeInstanceDescriptor(instSeg, 0, 0.0f, 0.0f, 0.0f, 0);

                long instanceStructure;
                try (Arena local = Arena.ofConfined()) {
                    MemorySegment structures = local.allocate(ValueLayout.JAVA_LONG.byteSize());
                    structures.set(ValueLayout.JAVA_LONG, 0, primStructure);
                    long structuresArray = Objc.msgSendIdPtrLong(Objc.getClass("NSArray"),
                            Objc.selector("arrayWithObjects:count:"), structures.address(), 1L);

                    long instDesc = Objc.msgSendId(
                            Objc.getClass("MTLInstanceAccelerationStructureDescriptor"), Objc.selector("descriptor"));
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstanceDescriptorBuffer:"), instanceDescriptorBuffer);
                    Objc.msgSendVoidLong(instDesc, Objc.selector("setInstanceCount:"), 1L);
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstancedAccelerationStructures:"), structuresArray);

                    Objc.AccelerationStructureSizes instSizes = Objc.accelerationStructureSizes(device, instDesc);
                    instanceStructure = Objc.msgSendId(device,
                            Objc.selector("newAccelerationStructureWithSize:"), instSizes.accelerationStructureSize());
                    assertNotEquals(0L, instanceStructure, "instance acceleration structure must build");
                    owned.push(instanceStructure);
                    long instScratch =
                            MetalRtAcceleration.createBuffer(device, Math.max(instSizes.buildScratchBufferSize(), 4L));
                    Objc.msgSendVoidIdIdIdLong(buildEncoder,
                            Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                            instanceStructure, instDesc, instScratch, 0L);
                    Objc.msgSendVoid(instScratch, Objc.selector("release"));
                }
                Objc.msgSendVoid(buildEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(buildCb, Objc.selector("commit"));
                Objc.msgSendVoid(buildCb, Objc.selector("waitUntilCompleted"));

                // Both rays cross the finite 48-block cube. One hits the voxel, the other misses.
                // Read as well as write: the kernel's output is a read_write texture, because in fill
                // mode it has to see whether a higher tier already answered a texel.
                long output = createTexture(device, MTL_PIXEL_FORMAT_RGBA32_FLOAT, 2, 1,
                        MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(output);
                InactiveCutoutFixtures fixtures = createInactiveCutoutFixtures(device, 2, 1, owned);
                long readiness = MetalRtAcceleration.createBuffer(device, 27 * 4);
                owned.push(readiness);
                MemorySegment ready = MemorySegment.ofAddress(Objc.msgSendId(readiness, Objc.selector("contents"))).reinterpret(27 * 4);
                for (int scenario = 0; scenario < 6; ++scenario) {
                    for (int i = 0; i < 27; ++i) ready.setAtIndex(ValueLayout.JAVA_INT, i, 1);
                    // Toroidal source indexing is (y*D+z)*D+x. The ray footprints are x=8.5
                    // and x=24.5, z=8.5. Unknown (2,2,2) is unrelated; (0,1,0) is before the
                    // downward hit and behind the upward hit; (1,1,0) lies on only the miss ray.
                    if (scenario == 1) ready.setAtIndex(ValueLayout.JAVA_INT, 26, 0);
                    if (scenario == 2 || scenario == 3) ready.setAtIndex(ValueLayout.JAVA_INT, 9, 0);
                    if (scenario == 4) ready.setAtIndex(ValueLayout.JAVA_INT, 10, 0);
                    if (scenario == 5) ready.setAtIndex(ValueLayout.JAVA_INT, 0, 0);
                    long cb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                    long enc = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
                    Objc.msgSendVoid(enc, Objc.selector("setComputePipelineState:"), compiled.sunDepth().pipeline());
                    Objc.msgSendVoidIdLong(enc, Objc.selector("setAccelerationStructure:atBufferIndex:"), instanceStructure, 1L);
                    bindCutoutArgs(enc, palette, fixtures, false);
                    Objc.msgSendVoidIdLongLong(enc, Objc.selector("setBuffer:offset:atIndex:"), readiness, 0L, 10L);
                    Objc.msgSendVoidIdLong(enc, Objc.selector("setTexture:atIndex:"), output, 0L);
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment constants = arena.allocate(192);
                        constants.fill((byte)0);
                        // x=16*ndc.x+16.5 -> 8.5 and 24.5. Depth runs down from48 to0,
                        // except scenario3 where it runs up from0 to48 to place unknown behind hit.
                        constants.set(ValueLayout.JAVA_FLOAT, 0, 16.0f);
                        constants.set(ValueLayout.JAVA_FLOAT, 36, scenario == 3 ? 48.0f : -48.0f);
                        constants.set(ValueLayout.JAVA_FLOAT, 48, 16.5f);
                        constants.set(ValueLayout.JAVA_FLOAT, 52, scenario == 3 ? 0.0f : 48.0f);
                        constants.set(ValueLayout.JAVA_FLOAT, 56, 8.5f);
                        constants.set(ValueLayout.JAVA_FLOAT, 60, 1.0f);
                        constants.set(ValueLayout.JAVA_INT, 148, 3);
                        constants.set(ValueLayout.JAVA_INT, 152, 2);
                        constants.set(ValueLayout.JAVA_INT, 156, 1);
                        // Byte 176 is the tier this dispatch reports; 2 is RayTier.HARDWARE_VOXEL.
                        // Byte 180 stays zero: this dispatch owns the image and clears it.
                        constants.set(ValueLayout.JAVA_INT, 176, 2);
                        Objc.msgSendVoidIdLongLong(enc, Objc.selector("setBytes:length:atIndex:"), constants.address(), 192, 0L);
                    }
                    Objc.msgSendVoidIdLong(enc, Objc.selector("useResource:usage:"), primStructure, MTL_RESOURCE_USAGE_READ);
                    Objc.msgSendVoidIdLong(enc, Objc.selector("useResource:usage:"), vertexBuffer, MTL_RESOURCE_USAGE_READ);
                    Objc.msgSendVoidIdLong(enc, Objc.selector("useResource:usage:"), instanceStructure, MTL_RESOURCE_USAGE_READ);
                    Objc.dispatchThreadgroups(enc, 2, 1, 1, 1, 1, 1);
                    Objc.msgSendVoid(enc, Objc.selector("endEncoding"));
                    Objc.msgSendVoid(cb, Objc.selector("commit"));
                    Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment out = arena.allocate(32);
                        Objc.getBytesFromRegion(output, out.address(), 32, 0, 0, 0, 2, 1, 1, 0);
                        boolean firstValid = scenario != 2 && scenario != 5;
                        assertEquals(firstValid ? 1.0f : 0.0f, out.get(ValueLayout.JAVA_FLOAT, 12), "hit validity scenario " + scenario);
                        assertEquals(scenario == 4 ? 0.0f : 1.0f, out.get(ValueLayout.JAVA_FLOAT, 28), "miss validity scenario " + scenario);
                        if (firstValid) {
                            assertEquals((scenario == 3 ? 8.0f : 39.0f) / 48.0f, out.get(ValueLayout.JAVA_FLOAT, 0), 1e-6f);
                            // G is the answering tier, not a second depth: 2 is the value byte 176
                            // carries above. B is reserved and stays zero. The entry and exit
                            // pair holds no second depth: nothing read one.
                            assertEquals(2.0f, out.get(ValueLayout.JAVA_FLOAT, 4), 0f);
                            assertEquals(0.0f, out.get(ValueLayout.JAVA_FLOAT, 8), 0f);
                        }
                        if (scenario != 4) assertEquals(1.0f, out.get(ValueLayout.JAVA_FLOAT, 16), "forward-Z miss clears to1");
                    }
                }

                // Fill mode, against the image the cascade actually shares. The loop above left
                // scenario 5 behind, which is not a controlled starting point, so this stages its
                // own: scenario 4 answers the hit texel and leaves the miss texel unanswered, then
                // one fill dispatch with everything ready must answer the second without disturbing
                // the first. That is the whole contract a lower tier has to honour.
                for (int i = 0; i < 27; ++i) ready.setAtIndex(ValueLayout.JAVA_INT, i, 1);
                ready.setAtIndex(ValueLayout.JAVA_INT, 10, 0);
                // Staged as tier 3 on purpose. The two dispatches would otherwise compute the same
                // depth for the hit texel, and overwriting it would be indistinguishable from
                // skipping it; a different tier in G is what makes an overwrite visible.
                dispatchSunDepth(queue, compiled, instanceStructure, palette, fixtures, readiness, output,
                        primStructure, vertexBuffer, 3, false);
                float[] staged = readSunDepth(output);
                assertEquals(1.0f, staged[3], "the hit texel starts answered");
                assertEquals(3.0f, staged[1], "staged as the tier above the one that fills below");
                assertEquals(0.0f, staged[7], "the miss texel starts unanswered");

                for (int i = 0; i < 27; ++i) ready.setAtIndex(ValueLayout.JAVA_INT, i, 1);
                dispatchSunDepth(queue, compiled, instanceStructure, palette, fixtures, readiness, output,
                        primStructure, vertexBuffer, 2, true);
                float[] filled = readSunDepth(output);

                for (int word = 0; word < 4; word++) {
                    assertEquals(staged[word], filled[word], 0f,
                            "an answered texel must survive a lower tier's fill, word " + word);
                }
                assertEquals(1.0f, filled[4], 1e-6f, "the filled texel is a traced miss, depth 1");
                // 2 is RayTier.HARDWARE_VOXEL.ordinal(), the tier this dispatch declares.
                assertEquals(2.0f, filled[5], 0f, "the filled texel names the tier that answered it");
                assertEquals(0.0f, filled[6], 0f, "B is reserved");
                assertEquals(1.0f, filled[7], 0f, "and it is certified");
            } finally {
                compiled.release();
            }
        } finally {
            while (!owned.isEmpty()) {
                Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            }
            Objc.autoreleasePoolPop(pool);
        }
    }

    @Test void exactTriangleUvsRetainMirroredCroppedAlphaAndMissingAtlasIsUnknown() {
        for (int mapping = 0; mapping < 6; ++mapping) runExactSunAlpha(mapping);
    }

    private void runExactSunAlpha(int mapping) {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);

        Deque<Long> owned = new ArrayDeque<>();
        long pool = Objc.autoreleasePoolPush();
        try {
            owned.push(device);
            MetalRtShaders.Compiled compiled = MetalRtShaders.compile(device);
            try {
                long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
                assertNotEquals(0L, queue, "newCommandQueue must not return nil");
                owned.push(queue);

                // ---- one FULL voxel at local (8, 8, 8), every face exposed ----
                long occupancy = MetalRtAcceleration.createBuffer(device, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
                owned.push(occupancy);
                long payload = MetalRtAcceleration.createBuffer(device, BrickGridUpload.VOXELS_PER_SECTION);
                owned.push(payload);
                long faceSeal = MetalRtAcceleration.createBuffer(device, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                owned.push(faceSeal);
                long palette = MetalRtAcceleration.createBuffer(device, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                owned.push(palette);
                zeroBuffer(payload, BrickGridUpload.VOXELS_PER_SECTION);
                zeroBuffer(faceSeal, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                zeroBuffer(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                // voxelIndex = (y << 8) | (z << 4) | x for (8, 8, 8) = 2184; bit 2184 % 8 = 0 of
                // byte 2184 / 8 = 273.
                setOccupiedVoxel(occupancy, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, 273, 0);

                long vertexBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.VERTEX_BYTES_PER_SLOT);
                owned.push(vertexBuffer);
                long primitiveDataBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.PRIMITIVE_DATA_BYTES_PER_SLOT);
                owned.push(primitiveDataBuffer);
                long countersBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.COUNTERS_BYTES);
                owned.push(countersBuffer);

                long expandCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long expandEncoder = Objc.msgSendId(expandCb, Objc.selector("computeCommandEncoder"));
                MetalRtAcceleration.encodeExpand(expandEncoder, compiled.expand(),
                        occupancy, 0L, payload, 0L, faceSeal, 0L, palette, 0L,
                        vertexBuffer, primitiveDataBuffer, countersBuffer);
                Objc.msgSendVoid(expandEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(expandCb, Objc.selector("commit"));
                Objc.msgSendVoid(expandCb, Objc.selector("waitUntilCompleted"));

                int triangleCount = MetalRtAcceleration.readCompleteTriangleCount(countersBuffer, -1);
                assertEquals(12, triangleCount,
                        "a FULL voxel with every face exposed emits 12 triangles (2 per face x 6 faces)");

                // Two exact triangles occupy a horizontal unit quad; their UVs are attached to
                // vertices, so mirrored/cropped asymmetric alpha cannot collapse to a sprite rect.
                float[] positions = {8,9,8, 9,9,8, 8,9,9, 9,9,8, 9,9,9, 8,9,9};
                MemorySegment vertices = MemorySegment.ofAddress(Objc.msgSendId(vertexBuffer, Objc.selector("contents"))).reinterpret(positions.length * 4L);
                MemorySegment primitive = MemorySegment.ofAddress(Objc.msgSendId(primitiveDataBuffer, Objc.selector("contents"))).reinterpret(64);
                primitive.fill((byte)0);
                for (int i = 0; i < positions.length; ++i) vertices.setAtIndex(ValueLayout.JAVA_FLOAT, i, positions[i]);
                for (int triangle = 0; triangle < 2; ++triangle) {
                    primitive.set(ValueLayout.JAVA_INT, triangle * 32L, 1 << 31);
                    for (int vertex = 0; vertex < 3; ++vertex) {
                        float x = positions[(triangle * 3 + vertex) * 3];
                        float u = mapping == 0 ? 9 - x : mapping == 2 ? (x - 8) * 0.25f : x - 8;
                        primitive.set(ValueLayout.JAVA_FLOAT, triangle * 32L + 4 + vertex * 8L, u);
                        primitive.set(ValueLayout.JAVA_FLOAT, triangle * 32L + 8 + vertex * 8L, 0.5f);
                    }
                }
                if (mapping >= 4) {
                    primitive.set(ValueLayout.JAVA_INT, 28, 1);
                    primitive.set(ValueLayout.JAVA_INT, 60, 1);
                    if (mapping == 5) {
                        for (int triangle = 0; triangle < 2; ++triangle) for (int component = 0; component < 3; ++component) {
                            long first = (triangle * 9L + component) * 4;
                            long second = (triangle * 9L + 3 + component) * 4;
                            float swap = vertices.get(ValueLayout.JAVA_FLOAT, first);
                            vertices.set(ValueLayout.JAVA_FLOAT, first, vertices.get(ValueLayout.JAVA_FLOAT, second));
                            vertices.set(ValueLayout.JAVA_FLOAT, second, swap);
                        }
                    }
                }
                triangleCount = 2;
                // ---- primitive then instance acceleration structure, one build command buffer ----
                long buildCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long buildEncoder = Objc.msgSendId(buildCb, Objc.selector("accelerationStructureCommandEncoder"));

                long primDesc = MetalRtAcceleration.primitiveDescriptor(java.util.List.of(
                        MetalRtAcceleration.triangleGeometry(vertexBuffer, primitiveDataBuffer, triangleCount, 32L)));
                Objc.AccelerationStructureSizes primSizes = Objc.accelerationStructureSizes(device, primDesc);
                long primStructure = Objc.msgSendId(
                        device, Objc.selector("newAccelerationStructureWithSize:"), primSizes.accelerationStructureSize());
                assertNotEquals(0L, primStructure, "primitive acceleration structure must build");
                owned.push(primStructure);
                long primScratch = MetalRtAcceleration.createBuffer(device, Math.max(primSizes.buildScratchBufferSize(), 4L));
                Objc.msgSendVoidIdIdIdLong(buildEncoder,
                        Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                        primStructure, primDesc, primScratch, 0L);
                Objc.msgSendVoid(primScratch, Objc.selector("release"));

                long instanceDescriptorBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                owned.push(instanceDescriptorBuffer);
                long instContents = Objc.msgSendId(instanceDescriptorBuffer, Objc.selector("contents"));
                MemorySegment instSeg = MemorySegment.ofAddress(instContents)
                        .reinterpret(MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                MetalRtAcceleration.writeInstanceDescriptor(instSeg, 0, 0.0f, 0.0f, 0.0f, 0);

                long instanceStructure;
                try (Arena local = Arena.ofConfined()) {
                    MemorySegment structures = local.allocate(ValueLayout.JAVA_LONG.byteSize());
                    structures.set(ValueLayout.JAVA_LONG, 0, primStructure);
                    long structuresArray = Objc.msgSendIdPtrLong(Objc.getClass("NSArray"),
                            Objc.selector("arrayWithObjects:count:"), structures.address(), 1L);

                    long instDesc = Objc.msgSendId(
                            Objc.getClass("MTLInstanceAccelerationStructureDescriptor"), Objc.selector("descriptor"));
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstanceDescriptorBuffer:"), instanceDescriptorBuffer);
                    Objc.msgSendVoidLong(instDesc, Objc.selector("setInstanceCount:"), 1L);
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstancedAccelerationStructures:"), structuresArray);

                    Objc.AccelerationStructureSizes instSizes = Objc.accelerationStructureSizes(device, instDesc);
                    instanceStructure = Objc.msgSendId(device,
                            Objc.selector("newAccelerationStructureWithSize:"), instSizes.accelerationStructureSize());
                    assertNotEquals(0L, instanceStructure, "instance acceleration structure must build");
                    owned.push(instanceStructure);
                    long instScratch =
                            MetalRtAcceleration.createBuffer(device, Math.max(instSizes.buildScratchBufferSize(), 4L));
                    Objc.msgSendVoidIdIdIdLong(buildEncoder,
                            Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                            instanceStructure, instDesc, instScratch, 0L);
                    Objc.msgSendVoid(instScratch, Objc.selector("release"));
                }
                Objc.msgSendVoid(buildEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(buildCb, Objc.selector("commit"));
                Objc.msgSendVoid(buildCb, Objc.selector("waitUntilCompleted"));

                // Both rays cross the finite 48-block cube. One hits the voxel, the other misses.
                long output = createTexture(device, MTL_PIXEL_FORMAT_RGBA32_FLOAT, 2, 1, MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(output);
                InactiveCutoutFixtures fixtures = createInactiveCutoutFixtures(device, 2, 1, owned);
                long readiness = MetalRtAcceleration.createBuffer(device, 27 * 4);
                owned.push(readiness);
                MemorySegment ready = MemorySegment.ofAddress(Objc.msgSendId(readiness, Objc.selector("contents"))).reinterpret(27 * 4);
                long atlas = createTexture(device, MTL_PIXEL_FORMAT_RGBA32_FLOAT, 2, 1,
                        MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(atlas);
                fillTexture(device, queue, atlas, "fill_atlas.metal", FILL_ATLAS_SOURCE, "fill_atlas", owned);
                for (int scenario = 0; scenario < 1; ++scenario) {
                    for (int i = 0; i < 27; ++i) ready.setAtIndex(ValueLayout.JAVA_INT, i, 1);
                    // Toroidal source indexing is (y*D+z)*D+x. The ray footprints are x=8.5
                    // and x=24.5, z=8.5. Unknown (2,2,2) is unrelated; (0,1,0) is before the
                    // downward hit and behind the upward hit; (1,1,0) lies on only the miss ray.
                    if (scenario == 1) ready.setAtIndex(ValueLayout.JAVA_INT, 26, 0);
                    if (scenario == 2 || scenario == 3) ready.setAtIndex(ValueLayout.JAVA_INT, 9, 0);
                    if (scenario == 4) ready.setAtIndex(ValueLayout.JAVA_INT, 10, 0);
                    if (scenario == 5) ready.setAtIndex(ValueLayout.JAVA_INT, 0, 0);
                    long cb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                    long enc = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
                    Objc.msgSendVoid(enc, Objc.selector("setComputePipelineState:"), compiled.sunDepth().pipeline());
                    Objc.msgSendVoidIdLong(enc, Objc.selector("setAccelerationStructure:atBufferIndex:"), instanceStructure, 1L);
                    bindCutoutArgs(enc, palette, fixtures, mapping != 3);
                    Objc.msgSendVoidIdLong(enc, Objc.selector("setTexture:atIndex:"), atlas, 4L);
                    Objc.msgSendVoidIdLongLong(enc, Objc.selector("setBuffer:offset:atIndex:"), readiness, 0L, 10L);
                    Objc.msgSendVoidIdLong(enc, Objc.selector("setTexture:atIndex:"), output, 0L);
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment constants = arena.allocate(176);
                        constants.fill((byte)0);
                        // x=16*ndc.x+16.5 -> 8.5 and 24.5. Depth runs down from48 to0,
                        // except scenario3 where it runs up from0 to48 to place unknown behind hit.
                        constants.set(ValueLayout.JAVA_FLOAT, 0, 0.5f);
                        constants.set(ValueLayout.JAVA_FLOAT, 36, scenario == 3 ? 48.0f : -48.0f);
                        constants.set(ValueLayout.JAVA_FLOAT, 48, 8.5f);
                        constants.set(ValueLayout.JAVA_FLOAT, 52, scenario == 3 ? 0.0f : 48.0f);
                        constants.set(ValueLayout.JAVA_FLOAT, 56, 8.5f);
                        constants.set(ValueLayout.JAVA_FLOAT, 60, 1.0f);
                        constants.set(ValueLayout.JAVA_INT, 148, 3);
                        constants.set(ValueLayout.JAVA_INT, 152, 2);
                        constants.set(ValueLayout.JAVA_INT, 156, 1);
                        Objc.msgSendVoidIdLongLong(enc, Objc.selector("setBytes:length:atIndex:"), constants.address(), 176, 0L);
                    }
                    Objc.msgSendVoidIdLong(enc, Objc.selector("useResource:usage:"), primStructure, MTL_RESOURCE_USAGE_READ);
                    Objc.msgSendVoidIdLong(enc, Objc.selector("useResource:usage:"), vertexBuffer, MTL_RESOURCE_USAGE_READ);
                    Objc.msgSendVoidIdLong(enc, Objc.selector("useResource:usage:"), instanceStructure, MTL_RESOURCE_USAGE_READ);
                    Objc.dispatchThreadgroups(enc, 2, 1, 1, 1, 1, 1);
                    Objc.msgSendVoid(enc, Objc.selector("endEncoding"));
                    Objc.msgSendVoid(cb, Objc.selector("commit"));
                    Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment out = arena.allocate(32);
                        Objc.getBytesFromRegion(output, out.address(), 32, 0, 0, 0, 2, 1, 1, 0);
                        assertEquals(mapping == 3 ? 0.0f : 1.0f, out.get(ValueLayout.JAVA_FLOAT, 12), "exact alpha validity");
                        assertEquals(mapping == 3 ? 0.0f : 1.0f, out.get(ValueLayout.JAVA_FLOAT, 28), "exact alpha validity");
                        if (mapping >= 4) {
                            assertEquals(mapping == 4 ? 1.0f : 39.0f / 48.0f, out.get(ValueLayout.JAVA_FLOAT, 0), 1e-6f, "cull facing mapping " + mapping);
                        } else if (mapping != 3) {
                            assertEquals(mapping == 0 ? 1.0f : 39.0f / 48.0f, out.get(ValueLayout.JAVA_FLOAT, 0), 1e-6f, "first pixel mapping " + mapping);
                            assertEquals(mapping == 1 ? 1.0f : 39.0f / 48.0f, out.get(ValueLayout.JAVA_FLOAT, 16), 1e-6f, "second pixel mapping " + mapping);
                        }

                    }
                }
            } finally {
                compiled.release();
            }
        } finally {
            while (!owned.isEmpty()) {
                Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            }
            Objc.autoreleasePoolPop(pool);
        }
    }

    /**
     * Pins the ray-origin bias behavior that avoids a camera-correlated false shadow on flat
     * ground: the bias in {@code rt_trace.metal} must come from the FLAT face normal decoded from
     * gNormalOut's alpha channel through {@code plagueDecodeGeometricNormal} (the pack's own
     * SNORM16 code from {@code geometric_normal.glsl}: codes 2..7 are the six exact axes, code 5
     * is UP), never from gNormalOut's rgb, the LabPBR bump-mapped shading normal, which a strongly
     * textured top face can tilt far enough off vertical to shrink the bias below the
     * reconstruction's own floating-point slack.
     *
     * <p>Same single FULL voxel fixture as {@link #oneVoxelExpandsBuildsAndTracesHitAndMiss}: local
     * (8, 8, 8), top face at grid y = 9. The hand-picked {@code invProjModelView} is translation-only
     * (ignores NDC and depth entirely, like that test's own matrix), placing both output pixels'
     * reconstructed surface at (8.5, 8.995, 8.5), 0.005 below the true top face, standing in for
     * the small floating-point slack a real reconstruction always carries. {@code sunDir} points
     * straight up, so the ray must clear this same voxel's own top face to report sun-visible.
     *
     * <p>The normal texture is seeded with {@link #FILL_TILTED_NORMAL_SOURCE}: rgb tilted mostly
     * horizontal, alpha a real UP-face code (5.0/32767.0). Biasing along rgb only lifts the origin
     * by {@code 0.0999 * 0.01 = 0.000999}, well short of the 0.005 deficit, so a kernel that trusts
     * the raw G-buffer normal self-intersects its own voxel (mask 0, a false shadow with no caster).
     * Biasing along the flat UP normal decoded from alpha lifts it by the full {@code 0.01},
     * clearing the top face with room to spare (mask 255).
     */
    @Test
    void groundedRayNearTopFaceIsNotSelfShadowedByATiltedBumpNormal() {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);

        Deque<Long> owned = new ArrayDeque<>();
        long pool = Objc.autoreleasePoolPush();
        try {
            owned.push(device);
            MetalRtShaders.Compiled compiled = MetalRtShaders.compile(device);
            try {
                long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
                assertNotEquals(0L, queue, "newCommandQueue must not return nil");
                owned.push(queue);

                long occupancy = MetalRtAcceleration.createBuffer(device, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
                owned.push(occupancy);
                long payload = MetalRtAcceleration.createBuffer(device, BrickGridUpload.VOXELS_PER_SECTION);
                owned.push(payload);
                long faceSeal = MetalRtAcceleration.createBuffer(device, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                owned.push(faceSeal);
                long palette = MetalRtAcceleration.createBuffer(device, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                owned.push(palette);
                zeroBuffer(payload, BrickGridUpload.VOXELS_PER_SECTION);
                zeroBuffer(faceSeal, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                zeroBuffer(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                setOccupiedVoxel(occupancy, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, 273, 0);

                long vertexBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.VERTEX_BYTES_PER_SLOT);
                owned.push(vertexBuffer);
                long primitiveDataBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.PRIMITIVE_DATA_BYTES_PER_SLOT);
                owned.push(primitiveDataBuffer);
                long countersBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.COUNTERS_BYTES);
                owned.push(countersBuffer);

                long expandCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long expandEncoder = Objc.msgSendId(expandCb, Objc.selector("computeCommandEncoder"));
                MetalRtAcceleration.encodeExpand(expandEncoder, compiled.expand(),
                        occupancy, 0L, payload, 0L, faceSeal, 0L, palette, 0L,
                        vertexBuffer, primitiveDataBuffer, countersBuffer);
                Objc.msgSendVoid(expandEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(expandCb, Objc.selector("commit"));
                Objc.msgSendVoid(expandCb, Objc.selector("waitUntilCompleted"));

                int triangleCount = MetalRtAcceleration.readCompleteTriangleCount(countersBuffer, -1);
                assertEquals(12, triangleCount,
                        "a FULL voxel with every face exposed emits 12 triangles (2 per face x 6 faces)");

                long buildCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long buildEncoder = Objc.msgSendId(buildCb, Objc.selector("accelerationStructureCommandEncoder"));

                long primDesc = MetalRtAcceleration.buildTrianglePrimitiveDescriptor(
                        vertexBuffer, primitiveDataBuffer, triangleCount);
                Objc.AccelerationStructureSizes primSizes = Objc.accelerationStructureSizes(device, primDesc);
                long primStructure = Objc.msgSendId(
                        device, Objc.selector("newAccelerationStructureWithSize:"), primSizes.accelerationStructureSize());
                assertNotEquals(0L, primStructure, "primitive acceleration structure must build");
                owned.push(primStructure);
                long primScratch = MetalRtAcceleration.createBuffer(device, Math.max(primSizes.buildScratchBufferSize(), 4L));
                Objc.msgSendVoidIdIdIdLong(buildEncoder,
                        Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                        primStructure, primDesc, primScratch, 0L);
                Objc.msgSendVoid(primScratch, Objc.selector("release"));

                long instanceDescriptorBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                owned.push(instanceDescriptorBuffer);
                long instContents = Objc.msgSendId(instanceDescriptorBuffer, Objc.selector("contents"));
                MemorySegment instSeg = MemorySegment.ofAddress(instContents)
                        .reinterpret(MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                MetalRtAcceleration.writeInstanceDescriptor(instSeg, 0, 0.0f, 0.0f, 0.0f, 0);

                long instanceStructure;
                try (Arena local = Arena.ofConfined()) {
                    MemorySegment structures = local.allocate(ValueLayout.JAVA_LONG.byteSize());
                    structures.set(ValueLayout.JAVA_LONG, 0, primStructure);
                    long structuresArray = Objc.msgSendIdPtrLong(Objc.getClass("NSArray"),
                            Objc.selector("arrayWithObjects:count:"), structures.address(), 1L);

                    long instDesc = Objc.msgSendId(
                            Objc.getClass("MTLInstanceAccelerationStructureDescriptor"), Objc.selector("descriptor"));
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstanceDescriptorBuffer:"), instanceDescriptorBuffer);
                    Objc.msgSendVoidLong(instDesc, Objc.selector("setInstanceCount:"), 1L);
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstancedAccelerationStructures:"), structuresArray);

                    Objc.AccelerationStructureSizes instSizes = Objc.accelerationStructureSizes(device, instDesc);
                    instanceStructure = Objc.msgSendId(device,
                            Objc.selector("newAccelerationStructureWithSize:"), instSizes.accelerationStructureSize());
                    assertNotEquals(0L, instanceStructure, "instance acceleration structure must build");
                    owned.push(instanceStructure);
                    long instScratch =
                            MetalRtAcceleration.createBuffer(device, Math.max(instSizes.buildScratchBufferSize(), 4L));
                    Objc.msgSendVoidIdIdIdLong(buildEncoder,
                            Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                            instanceStructure, instDesc, instScratch, 0L);
                    Objc.msgSendVoid(instScratch, Objc.selector("release"));
                }
                Objc.msgSendVoid(buildEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(buildCb, Objc.selector("commit"));
                Objc.msgSendVoid(buildCb, Objc.selector("waitUntilCompleted"));

                long depthTexture = createTexture(device, MTL_PIXEL_FORMAT_DEPTH32_FLOAT, 2, 1,
                        MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(depthTexture);
                long normalTexture = createTexture(device, MTL_PIXEL_FORMAT_RGBA16_SNORM, 2, 1,
                        MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(normalTexture);
                long outputTexture = createTexture(device, MTL_PIXEL_FORMAT_R8_UNORM, 2, 1, MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(outputTexture);

                fillTexture(device, queue, depthTexture, "fill_depth.metal", FILL_DEPTH_SOURCE, "fill_depth", owned);
                fillTexture(device, queue, normalTexture, "fill_tilted_normal.metal", FILL_TILTED_NORMAL_SOURCE,
                        "fill_tilted_normal", owned);
                InactiveCutoutFixtures cutoutFixtures = createInactiveCutoutFixtures(device, 2, 1, owned);

                long traceCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long traceEncoder = Objc.msgSendId(traceCb, Objc.selector("computeCommandEncoder"));
                Objc.msgSendVoid(traceEncoder, Objc.selector("setComputePipelineState:"), compiled.trace().pipeline());
                Objc.msgSendVoidIdLong(
                        traceEncoder, Objc.selector("setAccelerationStructure:atBufferIndex:"), instanceStructure, 1L);
                bindCutoutArgs(traceEncoder, palette, cutoutFixtures, false);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), outputTexture, 0L);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), depthTexture, 1L);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), normalTexture, 2L);
                try (Arena local = Arena.ofConfined()) {
                    MemorySegment constants = local.allocate(TRACE_CONSTANTS_BYTES);
                    writeGroundedConstants(constants);
                    Objc.msgSendVoidIdLongLong(
                            traceEncoder, Objc.selector("setBytes:length:atIndex:"), constants.address(), TRACE_CONSTANTS_BYTES, 0L);
                }
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("useResource:usage:"), primStructure, MTL_RESOURCE_USAGE_READ);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("useResource:usage:"), vertexBuffer, MTL_RESOURCE_USAGE_READ);
                Objc.msgSendVoidIdLong(
                        traceEncoder, Objc.selector("useResource:usage:"), instanceStructure, MTL_RESOURCE_USAGE_READ);
                Objc.dispatchThreadgroups(traceEncoder, 2, 1, 1, 1, 1, 1);
                Objc.msgSendVoid(traceEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(traceCb, Objc.selector("commit"));
                Objc.msgSendVoid(traceCb, Objc.selector("waitUntilCompleted"));

                try (Arena local = Arena.ofConfined()) {
                    MemorySegment out = local.allocate(2);
                    Objc.getBytesFromRegion(outputTexture, out.address(), 2L, 0, 0, 0, 2, 1, 1, 0L);
                    int pixel0 = out.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
                    int pixel1 = out.get(ValueLayout.JAVA_BYTE, 1) & 0xFF;
                    String reason = "the ray must clear its own voxel's top face using the flat UP normal "
                            + "decoded from alpha, not self-shadow from the tilted bump normal in rgb";
                    assertEquals(255, pixel0, reason);
                    // The matrix's NDC-dependent columns are zero (see writeGroundedConstants), so
                    // pixel 1's ray originates from the same reconstructed position as pixel 0's.
                    assertEquals(255, pixel1, reason);
                }
            } finally {
                compiled.release();
            }
        } finally {
            while (!owned.isEmpty()) {
                Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            }
            Objc.autoreleasePoolPop(pool);
        }
    }

    /**
     * Pins {@code plagueDecodeGeometricNormal} itself (the real function shipped in {@code
     * rt_trace.metal}, called through {@link #DECODE_PROBE_KERNEL_SOURCE}, not a re-typed copy)
     * against the pack's own {@code plagueEncodeGeometricNormal} codes from {@code
     * geometric_normal.glsl}: the axis loop returns {@code 2 + axis*2 + (positive ? 1 : 0)}, so UP
     * (0,1,0) is axis 1 positive, code 5, and WEST (-1,0,0) is axis 0 negative, code 2. Every other
     * probed code (0, 1, 16392, and the maximum 32767) falls outside both the 2..7 axis range and
     * the 8..16391 octahedral range, so each must return the fallback unchanged. A real encoded
     * alpha is one of these small fractions (2/32767 .. 7/32767 for an exact axis), a value any
     * decode scheme that assumes a coarser 0-5 face index divided into alpha would misread.
     */
    @Test
    void decodeGeometricNormalMatchesTheRealPackEncodingForAxisAlignedAndSentinelCodes() {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);

        // index -> (alpha, expected). Sentinel entries expect the probe's own fallback, (2, 2, 2).
        float up = 5.0f / 32767.0f;
        float west = 2.0f / 32767.0f;
        float[] alphas = {up, west, 0.0f, 1.0f / 32767.0f, 16392.0f / 32767.0f, 32767.0f / 32767.0f};
        float[][] expected = {
                {0.0f, 1.0f, 0.0f},
                {-1.0f, 0.0f, 0.0f},
                {2.0f, 2.0f, 2.0f},
                {2.0f, 2.0f, 2.0f},
                {2.0f, 2.0f, 2.0f},
                {2.0f, 2.0f, 2.0f},
        };

        Deque<Long> owned = new ArrayDeque<>();
        long pool = Objc.autoreleasePoolPush();
        try {
            owned.push(device);
            float[][] decoded = runDecodeProbe(device, alphas, owned);
            for (int i = 0; i < alphas.length; i++) {
                assertEquals(expected[i][0], decoded[i][0], 1e-5f, "alpha " + alphas[i] + " x component");
                assertEquals(expected[i][1], decoded[i][1], 1e-5f, "alpha " + alphas[i] + " y component");
                assertEquals(expected[i][2], decoded[i][2], 1e-5f, "alpha " + alphas[i] + " z component");
            }
        } finally {
            while (!owned.isEmpty()) {
                Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            }
            Objc.autoreleasePoolPop(pool);
        }
    }

    /**
     * Pins {@code plagueDecodeGeometricNormal}'s general (non-axis-aligned) octahedral branch
     * against a hand-derived encoding of {@code normalize(vec3(0.5, 0.5, 0.707))} using the pack's
     * own {@code plagueEncodeGeometricNormal} formula from {@code geometric_normal.glsl}:
     *
     * <p>The input normalizes to (0.500038, 0.500038, 0.707053) (length 0.9999245). No component
     * exceeds {@code 1.0 - 1e-6}, so the axis loop does not fire and the octahedral path runs:
     * dividing by the L1 norm ({@code 0.500038+0.500038+0.707053 = 1.707129}) gives
     * (0.293001, 0.293001, 0.414258); since z is positive, {@code p = n.xy = (0.293001, 0.293001)}
     * unchanged. {@code q = round(clamp(p*0.5+0.5, 0, 1) * 127) = round(0.646501 * 127) = round
     * (82.106) = (82, 82)}. The final code is {@code 8 + q.x + 128*q.y = 8 + 82 + 10496 = 10586},
     * so {@code alpha = 10586.0 / 32767.0}.
     *
     * <p>Decoding is not expected to reproduce the input exactly: the 7+7 bit octahedral grid only
     * has 128 steps per axis, so this pins closeness (dot product near 1, i.e. a small angular
     * error) rather than equality, the same tolerance the convergence check in
     * {@code plagueDecodeGeometricNormal}'s header comment describes.
     */
    @Test
    void decodeGeometricNormalIsCloseToOriginalForANonAxisAlignedOctahedralCode() {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);

        float alpha = 10586.0f / 32767.0f;
        float[] original = normalize(new float[] {0.5f, 0.5f, 0.707f});

        Deque<Long> owned = new ArrayDeque<>();
        long pool = Objc.autoreleasePoolPush();
        try {
            owned.push(device);
            float[][] decoded = runDecodeProbe(device, new float[] {alpha}, owned);
            float dot = original[0] * decoded[0][0] + original[1] * decoded[0][1] + original[2] * decoded[0][2];
            assertTrue(dot > 0.999, "decoded direction " + java.util.Arrays.toString(decoded[0])
                    + " must be close to the original " + java.util.Arrays.toString(original)
                    + ", dot was " + dot);
        } finally {
            while (!owned.isEmpty()) {
                Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            }
            Objc.autoreleasePoolPop(pool);
        }
    }

    /**
     * A cutout FULL voxel (a leaf block: a full cube with a see-through texture) does NOT seal a
     * shared face against another cutout FULL voxel, in {@code rt_expand.metal}'s {@code
     * face_exposed}: {@code rt_trace}'s alpha test needs every cutout face's own triangle to test
     * the right point, rather than skip through to whichever face a ray reaches next.
     *
     * <p>The slot holds one center voxel at local (8, 8, 8) plus all six of its face neighbors,
     * every one of them a FULL voxel (payload 0, so {@code boxCount} 0) pointing at palette entry
     * 0, which this test flags cutout with no cross bit, and every one with face-seal 0x3F (a FULL
     * cube seals all six of its own faces). The center voxel is therefore completely enclosed by
     * leaf-like neighbors on every side, the inside-a-tree-canopy case. Since every one of the
     * seven voxels is cutout, {@code face_exposed} never seals any of their shared faces: all seven
     * emit all six of their own faces.
     */
    @Test
    void fullyEnclosedCutoutVoxelStillEmitsEveryFaceForAlphaTesting() {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);

        // voxelIndex = (y << 8) | (z << 4) | x. Center (8, 8, 8) and its six face neighbors, one
        // step away along each axis.
        int center = (8 << 8) | (8 << 4) | 8;
        int down = (7 << 8) | (8 << 4) | 8;
        int up = (9 << 8) | (8 << 4) | 8;
        int north = (8 << 8) | (7 << 4) | 8;
        int south = (8 << 8) | (9 << 4) | 8;
        int west = (8 << 8) | (8 << 4) | 7;
        int east = (8 << 8) | (8 << 4) | 9;
        int[] occupiedVoxels = {center, down, up, north, south, west, east};

        Deque<Long> owned = new ArrayDeque<>();
        long pool = Objc.autoreleasePoolPush();
        try {
            owned.push(device);
            MetalRtShaders.Compiled compiled = MetalRtShaders.compile(device);
            try {
                long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
                assertNotEquals(0L, queue, "newCommandQueue must not return nil");
                owned.push(queue);

                long occupancy = MetalRtAcceleration.createBuffer(device, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
                owned.push(occupancy);
                long payload = MetalRtAcceleration.createBuffer(device, BrickGridUpload.VOXELS_PER_SECTION);
                owned.push(payload);
                long faceSeal = MetalRtAcceleration.createBuffer(device, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                owned.push(faceSeal);
                long palette = MetalRtAcceleration.createBuffer(device, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                owned.push(palette);

                setOccupiedVoxels(occupancy, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, occupiedVoxels);
                // Every occupied voxel's payload byte stays 0 (its zeroed default), pointing at
                // palette entry 0, so boxCount is 0 for all seven: they are all FULL voxels.
                zeroBuffer(payload, BrickGridUpload.VOXELS_PER_SECTION);
                zeroBuffer(faceSeal, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                setFaceSealBytes(faceSeal, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT, (byte) 0x3F, occupiedVoxels);
                zeroBuffer(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                // Palette entry 0, word 0: boxCount 0, CUTOUT_BIT (1 << 30) set, no CROSS_BIT,
                // matching rt_expand.metal's own packing.
                setPaletteEntryZeroFlagsWord(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT, 1 << 30);

                long vertexBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.VERTEX_BYTES_PER_SLOT);
                owned.push(vertexBuffer);
                long primitiveDataBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.PRIMITIVE_DATA_BYTES_PER_SLOT);
                owned.push(primitiveDataBuffer);
                long countersBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.COUNTERS_BYTES);
                owned.push(countersBuffer);

                long expandCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long expandEncoder = Objc.msgSendId(expandCb, Objc.selector("computeCommandEncoder"));
                MetalRtAcceleration.encodeExpand(expandEncoder, compiled.expand(),
                        occupancy, 0L, payload, 0L, faceSeal, 0L, palette, 0L,
                        vertexBuffer, primitiveDataBuffer, countersBuffer);
                Objc.msgSendVoid(expandEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(expandCb, Objc.selector("commit"));
                Objc.msgSendVoid(expandCb, Objc.selector("waitUntilCompleted"));

                int triangleCount = MetalRtAcceleration.readCompleteTriangleCount(countersBuffer, -1);
                // Every one of the seven voxels is cutout, so face_exposed never seals any of their
                // shared faces (ownCutout is true for whichever voxel's own face is being tested):
                // 7 voxels x 6 faces x 2 triangles = 84, including the center's own six.
                assertEquals(84, triangleCount,
                        "every voxel is cutout, so none of the seven's faces are sealed, including the "
                                + "fully-enclosed center's own six");

                long primitiveContents = Objc.msgSendId(primitiveDataBuffer, Objc.selector("contents"));
                MemorySegment primitiveSeg = MemorySegment.ofAddress(primitiveContents)
                        .reinterpret(MetalRtAcceleration.PRIMITIVE_DATA_BYTES_PER_SLOT);
                int centerTriangles = 0;
                for (int i = 0; i < triangleCount; i++) {
                    int packed = primitiveSeg.get(ValueLayout.JAVA_INT, (long) i * 4);
                    if ((packed & 0xFFF) == center) {
                        centerTriangles++;
                    }
                }
                assertEquals(12, centerTriangles,
                        "the fully-enclosed center voxel is cutout, so it emits all 6 of its own faces "
                                + "(2 triangles each) for rt_trace to alpha-test");
            } finally {
                compiled.release();
            }
        } finally {
            while (!owned.isEmpty()) {
                Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            }
            Objc.autoreleasePoolPop(pool);
        }
    }

    /**
     * A CROSS-shaped voxel (grass, a fern, a flower, a crop, a sapling) emits its two real
     * diagonal "X" planes from {@code rt_expand}, 2 triangles each for 4 total, reconstructed from
     * the one bounding box its palette entry carries in box slot 0 ({@code emit_cross}), not that
     * box itself as a solid cube: 12 triangles (2 per face x 6 faces) would be the wrong,
     * solid-silhouette answer that ignores CROSS alpha testing. {@code
     * rt_trace} alpha-tests each plane against the same palette entry's own embedded UV rect (see
     * {@link #crossCutoutAlphaTestBlocksTheOpaqueHalfAndPassesTheTransparentHalf} for the full
     * trace-level proof); this test only pins the expand-stage triangle count.
     */
    @Test
    void crossShapedVoxelEmitsItsDiagonalPlanesNotItsBoundingBox() {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);

        int center = (8 << 8) | (8 << 4) | 8;

        Deque<Long> owned = new ArrayDeque<>();
        long pool = Objc.autoreleasePoolPush();
        try {
            owned.push(device);
            MetalRtShaders.Compiled compiled = MetalRtShaders.compile(device);
            try {
                long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
                assertNotEquals(0L, queue, "newCommandQueue must not return nil");
                owned.push(queue);

                long occupancy = MetalRtAcceleration.createBuffer(device, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
                owned.push(occupancy);
                long payload = MetalRtAcceleration.createBuffer(device, BrickGridUpload.VOXELS_PER_SECTION);
                owned.push(payload);
                long faceSeal = MetalRtAcceleration.createBuffer(device, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                owned.push(faceSeal);
                long palette = MetalRtAcceleration.createBuffer(device, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                owned.push(palette);

                setOccupiedVoxels(occupancy, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, center);
                // The center voxel's payload byte stays 0 (its zeroed default), pointing at palette
                // entry 0.
                zeroBuffer(payload, BrickGridUpload.VOXELS_PER_SECTION);
                zeroBuffer(faceSeal, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                zeroBuffer(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                // Palette entry 0, word 0: boxCount 1, CUTOUT_BIT (1 << 30) and CROSS_BIT (1 << 31)
                // both set, matching a real harvested plant entry (BrickGridUpload's own packing).
                setPaletteEntryZeroFlagsWord(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT,
                        1 | (1 << 30) | (1 << 31));
                // Box slot 0 (word 7): a real bounding box, minX=2 minY=0 minZ=2 maxX=14 maxY=16
                // maxZ=14 in 1/16-block units, packed exactly as rt_expand.metal's own comment
                // documents; the same box crossCutoutAlphaTestBlocksTheOpaqueHalfAndPassesTheTransparentHalf
                // reuses for its own trace-level proof.
                int boxWord = 2 | (0 << 5) | (2 << 10) | (14 << 15) | (16 << 20) | (14 << 25);
                setPaletteEntryZeroBoxWord(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT, 0, boxWord);

                long vertexBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.VERTEX_BYTES_PER_SLOT);
                owned.push(vertexBuffer);
                long primitiveDataBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.PRIMITIVE_DATA_BYTES_PER_SLOT);
                owned.push(primitiveDataBuffer);
                long countersBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.COUNTERS_BYTES);
                owned.push(countersBuffer);

                long expandCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long expandEncoder = Objc.msgSendId(expandCb, Objc.selector("computeCommandEncoder"));
                MetalRtAcceleration.encodeExpand(expandEncoder, compiled.expand(),
                        occupancy, 0L, payload, 0L, faceSeal, 0L, palette, 0L,
                        vertexBuffer, primitiveDataBuffer, countersBuffer);
                Objc.msgSendVoid(expandEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(expandCb, Objc.selector("commit"));
                Objc.msgSendVoid(expandCb, Objc.selector("waitUntilCompleted"));

                int triangleCount = MetalRtAcceleration.readCompleteTriangleCount(countersBuffer, -1);
                assertEquals(4, triangleCount,
                        "a CROSS-shaped voxel emits its two real diagonal planes (2 triangles each), "
                                + "not its bounding box as a solid cube (12)");
            } finally {
                compiled.release();
            }
        } finally {
            while (!owned.isEmpty()) {
                Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            }
            Objc.autoreleasePoolPop(pool);
        }
    }

    /**
     * The manual {@code intersection_query} loop in {@code rt_trace} alpha-tests a cutout-flagged
     * voxel's top face against a real, synthetic block atlas, splitting the same face into an
     * opaque half and a transparent half: a ray through the opaque half is blocked, and one
     * through the transparent half is not.
     *
     * <p>One FULL voxel at local (8, 8, 8) (same fixture shape as {@link
     * #oneVoxelExpandsBuildsAndTracesHitAndMiss}), palette entry 0 flagged cutout (word 0 bit 30,
     * {@code BrickGridUpload.packPaletteFlagsWord}'s own packing) with boxCount 0 (FULL). The
     * voxel's top and bottom faces (face indices 1 and 0, the only two faces the straight-down ray
     * below crosses, in {@code Direction.get3DDataValue()} order) each get a real
     * {@code voxelFaceTexture} entry: header word bit 24 set (a usable UV mapping,
     * {@code VoxelFaceTexture.mapping()}'s own {@code flags = 1 | ...}) and a trivial identity
     * mapping {@code u = s, v = t} (u0=v0=0, du/ds=1, dv/ds=0, du/dt=0, dv/dt=1), where (s, t) for
     * a Y-axis face is (local.x, local.z) per that class's own doc. Both faces need a real mapping
     * (not only the entry face) because this kernel's documented fallback for an UNMAPPED face is
     * to commit as opaque; a real FULL cutout block gets exactly this from {@code
     * VoxelFaceTexture.pack()}'s own per-face loop, so mapping every face the ray can reach is the
     * realistic fixture, not a special case for this test.
     *
     * <p>The synthetic atlas is 2x1 RGBA32Float: texel 0 (u in [0, 0.5)) alpha 1.0 (opaque), texel
     * 1 (u in [0.5, 1)) alpha 0.0 (transparent), nearest-filtered, so u alone selects the texel.
     * With the identity mapping, u = s = local.x, so a ray landing at local x = 0.25 samples the
     * opaque texel and one at local x = 0.75 samples the transparent one.
     *
     * <p>The hand-picked {@code invProjModelView} depends on NDC.x only (unlike the shared matrix
     * {@link #oneVoxelExpandsBuildsAndTracesHitAndMiss} uses, which ties x and z to the same NDC.x):
     * column 0 is (0.5, 0, 0, 0), translation is (8.5, 20, 8.5, 1), so pixel 0 (NDC.x = -0.5) lands
     * at grid x = 8.25 and pixel 1 (NDC.x = 0.5) at grid x = 8.75, both at fixed z = 8.5 and y = 20,
     * well above the voxel. {@code sunDir} is straight down, so both rays cross the same top face
     * (its two triangles split along the x = z diagonal, but both x = 0.25 and x = 0.75 sample the
     * SAME face/voxel/palette entry regardless of which triangle they land in, since {@code
     * emit_face} writes identical face/voxelIndex/paletteIndex for both of a face's triangles).
     *
     * <p>The real alpha threshold (0.1) is the same one {@code shaders/blocks/shadow.fsh} uses for
     * every rasterized shadow caster in this engine (see rt_trace.metal's own derivation comment);
     * this test's opaque/transparent alphas (1.0 and 0.0) sit unambiguously on either side of it.
     */
    @Test
    void cutoutAlphaTestBlocksTheOpaqueHalfOfAFaceAndPassesTheTransparentHalf() {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);

        Deque<Long> owned = new ArrayDeque<>();
        long pool = Objc.autoreleasePoolPush();
        try {
            owned.push(device);
            MetalRtShaders.Compiled compiled = MetalRtShaders.compile(device);
            try {
                long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
                assertNotEquals(0L, queue, "newCommandQueue must not return nil");
                owned.push(queue);

                long occupancy = MetalRtAcceleration.createBuffer(device, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
                owned.push(occupancy);
                long payload = MetalRtAcceleration.createBuffer(device, BrickGridUpload.VOXELS_PER_SECTION);
                owned.push(payload);
                long faceSeal = MetalRtAcceleration.createBuffer(device, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                owned.push(faceSeal);
                long palette = MetalRtAcceleration.createBuffer(device, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                owned.push(palette);
                zeroBuffer(payload, BrickGridUpload.VOXELS_PER_SECTION);
                zeroBuffer(faceSeal, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                zeroBuffer(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                setOccupiedVoxel(occupancy, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, 273, 0);
                // Palette entry 0, word 0: boxCount 0 (FULL), CUTOUT_BIT (1 << 30) set, no CROSS_BIT.
                setPaletteEntryZeroFlagsWord(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT, 1 << 30);

                long faceTextureBuffer =
                        MetalRtAcceleration.createBuffer(device, dev.icehunter.fornax.voxel.VoxelFaceTexture.BYTES_PER_SLOT);
                owned.push(faceTextureBuffer);
                zeroBuffer(faceTextureBuffer, dev.icehunter.fornax.voxel.VoxelFaceTexture.BYTES_PER_SLOT);
                // Palette index 0, faces 0 and 1 (down and up, the two faces this test's straight-
                // down ray crosses: x=8.25/8.75 and z=8.5 are strictly interior, so the
                // ray never reaches a side face): header valid bit (1 << 24) set, identity UV
                // mapping on both. Only mapping the entry face (up) would leave the exit face
                // (down) with no usable UV, and this kernel's own documented fallback for that
                // (commit as opaque) would then block a ray this test means to pass clean through
                // the transparent half. A real FULL cutout block (leaves) gets a real mapping on
                // every one of its six faces from VoxelFaceTexture.pack()'s own per-face loop, so
                // mapping both is the realistic fixture, not a workaround.
                setFaceTextureEntry(faceTextureBuffer, 0, 0, 1 << 24, 0f, 0f, 1f, 0f, 0f, 1f);
                setFaceTextureEntry(faceTextureBuffer, 0, 1, 1 << 24, 0f, 0f, 1f, 0f, 0f, 1f);

                long instanceSlotMap = MetalRtAcceleration.createBuffer(device, Integer.BYTES);
                owned.push(instanceSlotMap);
                long slotMapPtr = Objc.msgSendId(instanceSlotMap, Objc.selector("contents"));
                MemorySegment.ofAddress(slotMapPtr).reinterpret(Integer.BYTES).set(ValueLayout.JAVA_INT, 0, 0);

                // 2x1 atlas: texel 0 opaque (alpha 1), texel 1 transparent (alpha 0).
                long atlasTexture = createTexture(
                        device, MTL_PIXEL_FORMAT_RGBA32_FLOAT, 2, 1, MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(atlasTexture);
                fillTexture(device, queue, atlasTexture, "fill_atlas.metal", FILL_ATLAS_SOURCE, "fill_atlas", owned);

                long vertexBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.VERTEX_BYTES_PER_SLOT);
                owned.push(vertexBuffer);
                long primitiveDataBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.PRIMITIVE_DATA_BYTES_PER_SLOT);
                owned.push(primitiveDataBuffer);
                long countersBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.COUNTERS_BYTES);
                owned.push(countersBuffer);

                long expandCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long expandEncoder = Objc.msgSendId(expandCb, Objc.selector("computeCommandEncoder"));
                MetalRtAcceleration.encodeExpand(expandEncoder, compiled.expand(),
                        occupancy, 0L, payload, 0L, faceSeal, 0L, palette, 0L,
                        vertexBuffer, primitiveDataBuffer, countersBuffer);
                Objc.msgSendVoid(expandEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(expandCb, Objc.selector("commit"));
                Objc.msgSendVoid(expandCb, Objc.selector("waitUntilCompleted"));

                int triangleCount = MetalRtAcceleration.readCompleteTriangleCount(countersBuffer, -1);
                assertEquals(12, triangleCount,
                        "a FULL voxel with every face exposed emits 12 triangles (2 per face x 6 faces)");

                long buildCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long buildEncoder = Objc.msgSendId(buildCb, Objc.selector("accelerationStructureCommandEncoder"));

                long primDesc = MetalRtAcceleration.buildTrianglePrimitiveDescriptor(
                        vertexBuffer, primitiveDataBuffer, triangleCount);
                Objc.AccelerationStructureSizes primSizes = Objc.accelerationStructureSizes(device, primDesc);
                long primStructure = Objc.msgSendId(
                        device, Objc.selector("newAccelerationStructureWithSize:"), primSizes.accelerationStructureSize());
                assertNotEquals(0L, primStructure, "primitive acceleration structure must build");
                owned.push(primStructure);
                long primScratch = MetalRtAcceleration.createBuffer(device, Math.max(primSizes.buildScratchBufferSize(), 4L));
                Objc.msgSendVoidIdIdIdLong(buildEncoder,
                        Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                        primStructure, primDesc, primScratch, 0L);
                Objc.msgSendVoid(primScratch, Objc.selector("release"));

                long instanceDescriptorBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                owned.push(instanceDescriptorBuffer);
                long instContents = Objc.msgSendId(instanceDescriptorBuffer, Objc.selector("contents"));
                MemorySegment instSeg = MemorySegment.ofAddress(instContents)
                        .reinterpret(MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                MetalRtAcceleration.writeInstanceDescriptor(instSeg, 0, 0.0f, 0.0f, 0.0f, 0);

                long instanceStructure;
                try (Arena local = Arena.ofConfined()) {
                    MemorySegment structures = local.allocate(ValueLayout.JAVA_LONG.byteSize());
                    structures.set(ValueLayout.JAVA_LONG, 0, primStructure);
                    long structuresArray = Objc.msgSendIdPtrLong(Objc.getClass("NSArray"),
                            Objc.selector("arrayWithObjects:count:"), structures.address(), 1L);

                    long instDesc = Objc.msgSendId(
                            Objc.getClass("MTLInstanceAccelerationStructureDescriptor"), Objc.selector("descriptor"));
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstanceDescriptorBuffer:"), instanceDescriptorBuffer);
                    Objc.msgSendVoidLong(instDesc, Objc.selector("setInstanceCount:"), 1L);
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstancedAccelerationStructures:"), structuresArray);

                    Objc.AccelerationStructureSizes instSizes = Objc.accelerationStructureSizes(device, instDesc);
                    instanceStructure = Objc.msgSendId(device,
                            Objc.selector("newAccelerationStructureWithSize:"), instSizes.accelerationStructureSize());
                    assertNotEquals(0L, instanceStructure, "instance acceleration structure must build");
                    owned.push(instanceStructure);
                    long instScratch =
                            MetalRtAcceleration.createBuffer(device, Math.max(instSizes.buildScratchBufferSize(), 4L));
                    Objc.msgSendVoidIdIdIdLong(buildEncoder,
                            Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                            instanceStructure, instDesc, instScratch, 0L);
                    Objc.msgSendVoid(instScratch, Objc.selector("release"));
                }
                Objc.msgSendVoid(buildEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(buildCb, Objc.selector("commit"));
                Objc.msgSendVoid(buildCb, Objc.selector("waitUntilCompleted"));

                long depthTexture = createTexture(device, MTL_PIXEL_FORMAT_DEPTH32_FLOAT, 2, 1,
                        MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(depthTexture);
                long normalTexture = createTexture(device, MTL_PIXEL_FORMAT_RGBA16_SNORM, 2, 1,
                        MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(normalTexture);
                long outputTexture = createTexture(device, MTL_PIXEL_FORMAT_R8_UNORM, 2, 1, MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(outputTexture);
                long validOutTexture = createTexture(device, MTL_PIXEL_FORMAT_R8_UNORM, 2, 1, MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(validOutTexture);

                fillTexture(device, queue, depthTexture, "fill_depth.metal", FILL_DEPTH_SOURCE, "fill_depth", owned);
                fillTexture(device, queue, normalTexture, "fill_zero.metal", FILL_ZERO_SOURCE, "fill_zero", owned);

                long traceCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long traceEncoder = Objc.msgSendId(traceCb, Objc.selector("computeCommandEncoder"));
                Objc.msgSendVoid(traceEncoder, Objc.selector("setComputePipelineState:"), compiled.trace().pipeline());
                Objc.msgSendVoidIdLong(
                        traceEncoder, Objc.selector("setAccelerationStructure:atBufferIndex:"), instanceStructure, 1L);
                Objc.msgSendVoidIdLongLong(traceEncoder, Objc.selector("setBuffer:offset:atIndex:"), palette, 0L, 2L);
                Objc.msgSendVoidIdLongLong(
                        traceEncoder, Objc.selector("setBuffer:offset:atIndex:"), faceTextureBuffer, 0L, 3L);
                Objc.msgSendVoidIdLongLong(
                        traceEncoder, Objc.selector("setBuffer:offset:atIndex:"), instanceSlotMap, 0L, 4L);
                try (Arena flagArena = Arena.ofConfined()) {
                    // A FULL cutout entry needs both bits: it reads the real voxelFaceTexture
                    // entries set up above AND the atlas.
                    MemorySegment flag = flagArena.allocate(ValueLayout.JAVA_INT);
                    flag.set(ValueLayout.JAVA_INT, 0, CUTOUT_FLAG_ATLAS | CUTOUT_FLAG_FACE_TEXTURE);
                    Objc.msgSendVoidIdLongLong(
                            traceEncoder, Objc.selector("setBytes:length:atIndex:"), flag.address(), 4L, 5L);
                }
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), outputTexture, 0L);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), depthTexture, 1L);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), normalTexture, 2L);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), validOutTexture, 3L);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), atlasTexture, 4L);
                try (Arena local = Arena.ofConfined()) {
                    MemorySegment constants = local.allocate(TRACE_CONSTANTS_BYTES);
                    writeCutoutConstants(constants);
                    Objc.msgSendVoidIdLongLong(
                            traceEncoder, Objc.selector("setBytes:length:atIndex:"), constants.address(), TRACE_CONSTANTS_BYTES, 0L);
                }
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("useResource:usage:"), primStructure, MTL_RESOURCE_USAGE_READ);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("useResource:usage:"), vertexBuffer, MTL_RESOURCE_USAGE_READ);
                Objc.msgSendVoidIdLong(
                        traceEncoder, Objc.selector("useResource:usage:"), instanceStructure, MTL_RESOURCE_USAGE_READ);
                Objc.dispatchThreadgroups(traceEncoder, 2, 1, 1, 1, 1, 1);
                Objc.msgSendVoid(traceEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(traceCb, Objc.selector("commit"));
                Objc.msgSendVoid(traceCb, Objc.selector("waitUntilCompleted"));

                try (Arena local = Arena.ofConfined()) {
                    MemorySegment out = local.allocate(2);
                    Objc.getBytesFromRegion(outputTexture, out.address(), 2L, 0, 0, 0, 2, 1, 1, 0L);
                    int pixel0 = out.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
                    int pixel1 = out.get(ValueLayout.JAVA_BYTE, 1) & 0xFF;
                    assertEquals(0, pixel0,
                            "pixel 0 samples the atlas's opaque half (u=0.25) and must be blocked");
                    assertEquals(255, pixel1,
                            "pixel 1 samples the atlas's transparent half (u=0.75) and must pass through");
                }
            } finally {
                compiled.release();
            }
        } finally {
            while (!owned.isEmpty()) {
                Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            }
            Objc.autoreleasePoolPop(pool);
        }
    }

    /**
     * A CROSS-shaped voxel (a plant) alpha-tests against the palette's own embedded UV rect (words
     * 13/14) rather than {@code voxelFaceTexture}, which has no per-face concept for a diagonal
     * plane; see {@code rt_trace.metal}'s own header comment. This proves the real chain end to
     * end: {@code rt_expand} reconstructs the actual diagonal "X" from the one bounding box the
     * palette carries (box slot 0, {@code emit_cross}), the four triangles {@link
     * #crossShapedVoxelEmitsItsDiagonalPlanesNotItsBoundingBox} pins, and {@code rt_trace} samples the
     * same kind of synthetic half-opaque/half-transparent atlas {@link
     * #cutoutAlphaTestBlocksTheOpaqueHalfOfAFaceAndPassesTheTransparentHalf} uses, blocking a ray
     * through the opaque half and passing one through the transparent half.
     *
     * <p>Same box as {@link #crossShapedVoxelEmitsItsDiagonalPlanesNotItsBoundingBox}: minX=2, minY=0, minZ=2, maxX=14,
     * maxY=16, maxZ=14 in 1/16-block units -&gt; boxMin=(0.125, 0, 0.125), boxMax=(0.875, 1,
     * 0.875) relative to the voxel's own local origin. UV rect words 13/14 (box slots 6/7, the same
     * reuse {@code BrickGridUpload.packPaletteEntries} makes for a real cutout entry) are set to
     * the full unit rect (u0=v0=0, u1=v1=1), so this test's {@code u = s} and {@code v = 1 - t}
     * directly (rt_trace.metal's own {@code mix(rectU0, rectU1, s)}/{@code mix(rectV1, rectV0, t)}
     * with those bounds), matching {@link
     * #cutoutAlphaTestBlocksTheOpaqueHalfOfAFaceAndPassesTheTransparentHalf}'s own 2x1 atlas: texel
     * 0 (u &lt; 0.5) opaque, texel 1 (u &gt;= 0.5) transparent.
     *
     * <p>Diagonal plane A ({@code emit_cross}'s face id 6, corners a0..a3) is the line x = z within
     * the voxel's local cell here, since minX = minZ and maxX = maxZ. A ray along {@code sunDir =
     * normalize(1, 0, -1)} is exactly that plane's own normal direction (crosses it transversally
     * at one point) and exactly plane B's own diagonal direction (tangent to it, never a real
     * intersection), so every candidate this test's rays can commit against is unambiguously plane
     * A. Solving the intersection algebra (direction has no y component, so the hit height is the
     * ray's own origin height unchanged, and the hit x = z point is exactly the average of the
     * origin's own x and z) shows {@code rt_trace}'s own fallback bias ({@code sunDir * 0.05}, no
     * G-buffer normal supplied) shifts x and z by equal and opposite amounts along this particular
     * direction, which cancels out of that average exactly: the reconstructed grid position need
     * not itself sit on the plane for the algebra below to hold.
     *
     * <p>Both rays share height t = 0.5 (grid y = 8.5, inside the box's full [0, 1] height range)
     * and a fixed 0.5-unit x/z straddle around their own intersection point (an arbitrary
     * non-degenerate spread; it cancels out of the result the same way the bias does). Pixel 0
     * targets s = 0.25 (grid x = z = 8.3125): u = 0.25, the atlas's opaque half, must be blocked.
     * Pixel 1 targets s = 0.75 (grid x = z = 8.6875): u = 0.75, the transparent half, must pass
     * through. Solving {@code gridPos = origin_target - sunDir * 0.05} for each pixel's origin
     * gives the hand-picked {@code invProjModelView} in {@link #writeCrossCutoutConstants}: column
     * 0 = (0.375, 0, 0.375, 0) (both x and z move by 0.375 per unit NDC.x, matching the two
     * targets' 0.375 spacing), translation = (8.21464466, 8.5, 8.78535534, 1).
     *
     * <p>The buffer(5) flag below carries {@code CUTOUT_FLAG_ATLAS} alone, {@code
     * CUTOUT_FLAG_FACE_TEXTURE} deliberately clear: a pack that captures the block atlas but never
     * enables {@code VoxelFaceTexture.TARGET} must still alpha-test CROSS geometry, since this
     * branch never reads {@code voxelFaceTexture}. Gating it on both bits would silently drop
     * plant alpha testing back to a solid silhouette for a reason unrelated to the data it reads.
     */
    @Test
    void crossCutoutAlphaTestBlocksTheOpaqueHalfAndPassesTheTransparentHalf() {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);

        Deque<Long> owned = new ArrayDeque<>();
        long pool = Objc.autoreleasePoolPush();
        try {
            owned.push(device);
            MetalRtShaders.Compiled compiled = MetalRtShaders.compile(device);
            try {
                long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
                assertNotEquals(0L, queue, "newCommandQueue must not return nil");
                owned.push(queue);

                long occupancy = MetalRtAcceleration.createBuffer(device, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
                owned.push(occupancy);
                long payload = MetalRtAcceleration.createBuffer(device, BrickGridUpload.VOXELS_PER_SECTION);
                owned.push(payload);
                long faceSeal = MetalRtAcceleration.createBuffer(device, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                owned.push(faceSeal);
                long palette = MetalRtAcceleration.createBuffer(device, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                owned.push(palette);
                zeroBuffer(payload, BrickGridUpload.VOXELS_PER_SECTION);
                zeroBuffer(faceSeal, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                zeroBuffer(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                setOccupiedVoxel(occupancy, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, 273, 0);
                // Palette entry 0, word 0: boxCount 1, CUTOUT_BIT (1 << 30) and CROSS_BIT (1 << 31)
                // both set, a real harvested plant entry (BrickGridUpload's own packing).
                setPaletteEntryZeroFlagsWord(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT,
                        1 | (1 << 30) | (1 << 31));
                // Box slot 0 (word 7): the same real bounding box
                // crossShapedVoxelEmitsItsDiagonalPlanesNotItsBoundingBox uses (minX=2 minY=0
                // minZ=2 maxX=14 maxY=16 maxZ=14, 1/16-block units).
                int boxWord = 2 | (0 << 5) | (2 << 10) | (14 << 15) | (16 << 20) | (14 << 25);
                setPaletteEntryZeroBoxWord(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT, 0, boxWord);
                // Box slots 6/7 (words 13/14) reused as the packed UV rect, exactly the reuse
                // BrickGridUpload.packPaletteEntries makes for a real cutout entry: the full unit
                // rect (u0=v0=0 as 16-bit unorm 0, u1=v1=1 as 16-bit unorm 65535).
                setPaletteEntryZeroBoxWord(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT, 6, 0);
                setPaletteEntryZeroBoxWord(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT, 7, 0xFFFFFFFF);

                // rt_trace never reads voxelFaceTexture for a CROSS entry (see this test's own
                // javadoc), so this only needs to be a real bound buffer, never a real mapping,
                // and CUTOUT_FLAG_FACE_TEXTURE is deliberately left unset below, standing in for a
                // pack that never enabled VoxelFaceTexture.TARGET, to prove that omission does not
                // block CROSS alpha testing.
                long faceTextureBuffer = MetalRtAcceleration.createBuffer(device, 4);
                owned.push(faceTextureBuffer);
                zeroBuffer(faceTextureBuffer, 4);

                long instanceSlotMap = MetalRtAcceleration.createBuffer(device, Integer.BYTES);
                owned.push(instanceSlotMap);
                long slotMapPtr = Objc.msgSendId(instanceSlotMap, Objc.selector("contents"));
                MemorySegment.ofAddress(slotMapPtr).reinterpret(Integer.BYTES).set(ValueLayout.JAVA_INT, 0, 0);

                // Same 2x1 atlas as cutoutAlphaTestBlocksTheOpaqueHalfOfAFaceAndPassesTheTransparentHalf:
                // texel 0 opaque (alpha 1), texel 1 transparent (alpha 0).
                long atlasTexture = createTexture(
                        device, MTL_PIXEL_FORMAT_RGBA32_FLOAT, 2, 1, MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(atlasTexture);
                fillTexture(device, queue, atlasTexture, "fill_atlas.metal", FILL_ATLAS_SOURCE, "fill_atlas", owned);

                long vertexBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.VERTEX_BYTES_PER_SLOT);
                owned.push(vertexBuffer);
                long primitiveDataBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.PRIMITIVE_DATA_BYTES_PER_SLOT);
                owned.push(primitiveDataBuffer);
                long countersBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.COUNTERS_BYTES);
                owned.push(countersBuffer);

                long expandCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long expandEncoder = Objc.msgSendId(expandCb, Objc.selector("computeCommandEncoder"));
                MetalRtAcceleration.encodeExpand(expandEncoder, compiled.expand(),
                        occupancy, 0L, payload, 0L, faceSeal, 0L, palette, 0L,
                        vertexBuffer, primitiveDataBuffer, countersBuffer);
                Objc.msgSendVoid(expandEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(expandCb, Objc.selector("commit"));
                Objc.msgSendVoid(expandCb, Objc.selector("waitUntilCompleted"));

                int triangleCount = MetalRtAcceleration.readCompleteTriangleCount(countersBuffer, -1);
                assertEquals(4, triangleCount,
                        "a CROSS voxel emits its two real diagonal planes, 2 triangles each, not its "
                                + "bounding box");

                long buildCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long buildEncoder = Objc.msgSendId(buildCb, Objc.selector("accelerationStructureCommandEncoder"));

                long primDesc = MetalRtAcceleration.buildTrianglePrimitiveDescriptor(
                        vertexBuffer, primitiveDataBuffer, triangleCount);
                Objc.AccelerationStructureSizes primSizes = Objc.accelerationStructureSizes(device, primDesc);
                long primStructure = Objc.msgSendId(
                        device, Objc.selector("newAccelerationStructureWithSize:"), primSizes.accelerationStructureSize());
                assertNotEquals(0L, primStructure, "primitive acceleration structure must build");
                owned.push(primStructure);
                long primScratch = MetalRtAcceleration.createBuffer(device, Math.max(primSizes.buildScratchBufferSize(), 4L));
                Objc.msgSendVoidIdIdIdLong(buildEncoder,
                        Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                        primStructure, primDesc, primScratch, 0L);
                Objc.msgSendVoid(primScratch, Objc.selector("release"));

                long instanceDescriptorBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                owned.push(instanceDescriptorBuffer);
                long instContents = Objc.msgSendId(instanceDescriptorBuffer, Objc.selector("contents"));
                MemorySegment instSeg = MemorySegment.ofAddress(instContents)
                        .reinterpret(MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                MetalRtAcceleration.writeInstanceDescriptor(instSeg, 0, 0.0f, 0.0f, 0.0f, 0);

                long instanceStructure;
                try (Arena local = Arena.ofConfined()) {
                    MemorySegment structures = local.allocate(ValueLayout.JAVA_LONG.byteSize());
                    structures.set(ValueLayout.JAVA_LONG, 0, primStructure);
                    long structuresArray = Objc.msgSendIdPtrLong(Objc.getClass("NSArray"),
                            Objc.selector("arrayWithObjects:count:"), structures.address(), 1L);

                    long instDesc = Objc.msgSendId(
                            Objc.getClass("MTLInstanceAccelerationStructureDescriptor"), Objc.selector("descriptor"));
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstanceDescriptorBuffer:"), instanceDescriptorBuffer);
                    Objc.msgSendVoidLong(instDesc, Objc.selector("setInstanceCount:"), 1L);
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstancedAccelerationStructures:"), structuresArray);

                    Objc.AccelerationStructureSizes instSizes = Objc.accelerationStructureSizes(device, instDesc);
                    instanceStructure = Objc.msgSendId(device,
                            Objc.selector("newAccelerationStructureWithSize:"), instSizes.accelerationStructureSize());
                    assertNotEquals(0L, instanceStructure, "instance acceleration structure must build");
                    owned.push(instanceStructure);
                    long instScratch =
                            MetalRtAcceleration.createBuffer(device, Math.max(instSizes.buildScratchBufferSize(), 4L));
                    Objc.msgSendVoidIdIdIdLong(buildEncoder,
                            Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                            instanceStructure, instDesc, instScratch, 0L);
                    Objc.msgSendVoid(instScratch, Objc.selector("release"));
                }
                Objc.msgSendVoid(buildEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(buildCb, Objc.selector("commit"));
                Objc.msgSendVoid(buildCb, Objc.selector("waitUntilCompleted"));

                long depthTexture = createTexture(device, MTL_PIXEL_FORMAT_DEPTH32_FLOAT, 2, 1,
                        MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(depthTexture);
                long normalTexture = createTexture(device, MTL_PIXEL_FORMAT_RGBA16_SNORM, 2, 1,
                        MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(normalTexture);
                long outputTexture = createTexture(device, MTL_PIXEL_FORMAT_R8_UNORM, 2, 1, MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(outputTexture);
                long validOutTexture = createTexture(device, MTL_PIXEL_FORMAT_R8_UNORM, 2, 1, MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(validOutTexture);

                fillTexture(device, queue, depthTexture, "fill_depth.metal", FILL_DEPTH_SOURCE, "fill_depth", owned);
                fillTexture(device, queue, normalTexture, "fill_zero.metal", FILL_ZERO_SOURCE, "fill_zero", owned);

                long traceCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long traceEncoder = Objc.msgSendId(traceCb, Objc.selector("computeCommandEncoder"));
                Objc.msgSendVoid(traceEncoder, Objc.selector("setComputePipelineState:"), compiled.trace().pipeline());
                Objc.msgSendVoidIdLong(
                        traceEncoder, Objc.selector("setAccelerationStructure:atBufferIndex:"), instanceStructure, 1L);
                Objc.msgSendVoidIdLongLong(traceEncoder, Objc.selector("setBuffer:offset:atIndex:"), palette, 0L, 2L);
                Objc.msgSendVoidIdLongLong(
                        traceEncoder, Objc.selector("setBuffer:offset:atIndex:"), faceTextureBuffer, 0L, 3L);
                Objc.msgSendVoidIdLongLong(
                        traceEncoder, Objc.selector("setBuffer:offset:atIndex:"), instanceSlotMap, 0L, 4L);
                try (Arena flagArena = Arena.ofConfined()) {
                    // CUTOUT_FLAG_ATLAS alone, CUTOUT_FLAG_FACE_TEXTURE deliberately clear: a
                    // CROSS entry alpha-tests off the atlas alone and must not be gated on
                    // voxelFaceTexture, which it never reads (see this test's own javadoc).
                    MemorySegment flag = flagArena.allocate(ValueLayout.JAVA_INT);
                    flag.set(ValueLayout.JAVA_INT, 0, CUTOUT_FLAG_ATLAS);
                    Objc.msgSendVoidIdLongLong(
                            traceEncoder, Objc.selector("setBytes:length:atIndex:"), flag.address(), 4L, 5L);
                }
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), outputTexture, 0L);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), depthTexture, 1L);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), normalTexture, 2L);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), validOutTexture, 3L);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), atlasTexture, 4L);
                try (Arena local = Arena.ofConfined()) {
                    MemorySegment constants = local.allocate(TRACE_CONSTANTS_BYTES);
                    writeCrossCutoutConstants(constants);
                    Objc.msgSendVoidIdLongLong(
                            traceEncoder, Objc.selector("setBytes:length:atIndex:"), constants.address(), TRACE_CONSTANTS_BYTES, 0L);
                }
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("useResource:usage:"), primStructure, MTL_RESOURCE_USAGE_READ);
                Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("useResource:usage:"), vertexBuffer, MTL_RESOURCE_USAGE_READ);
                Objc.msgSendVoidIdLong(
                        traceEncoder, Objc.selector("useResource:usage:"), instanceStructure, MTL_RESOURCE_USAGE_READ);
                Objc.dispatchThreadgroups(traceEncoder, 2, 1, 1, 1, 1, 1);
                Objc.msgSendVoid(traceEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(traceCb, Objc.selector("commit"));
                Objc.msgSendVoid(traceCb, Objc.selector("waitUntilCompleted"));

                try (Arena local = Arena.ofConfined()) {
                    MemorySegment out = local.allocate(2);
                    Objc.getBytesFromRegion(outputTexture, out.address(), 2L, 0, 0, 0, 2, 1, 1, 0L);
                    int pixel0 = out.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
                    int pixel1 = out.get(ValueLayout.JAVA_BYTE, 1) & 0xFF;
                    assertEquals(0, pixel0,
                            "pixel 0 samples the atlas's opaque half (u=0.25) through the CROSS plane "
                                    + "and must be blocked");
                    assertEquals(255, pixel1,
                            "pixel 1 samples the atlas's transparent half (u=0.75) through the CROSS "
                                    + "plane and must pass through");
                }
            } finally {
                compiled.release();
            }
        } finally {
            while (!owned.isEmpty()) {
                Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            }
            Objc.autoreleasePoolPop(pool);
        }
    }

    /** Native coverage test over a finite two-section domain. Pixel zero reconstructs to
     * (0.5,8,0.5), inside; pixel one to (0.5,40,0.5), outside. Runs both incomplete and complete
     * snapshot flags against the same real acceleration structure. Only the complete inside
     * receiver may publish valid=1. A CPU allocation count is deliberately no part of this gate. */
    @Test
    void windowValidityAcceptsASlotInsideTheExportAndRejectsOneOutside() {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);

        Deque<Long> owned = new ArrayDeque<>();
        long pool = Objc.autoreleasePoolPush();
        try {
            owned.push(device);
            MetalRtShaders.Compiled compiled = MetalRtShaders.compile(device);
            try {
                long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
                assertNotEquals(0L, queue, "newCommandQueue must not return nil");
                owned.push(queue);

                long occupancy = MetalRtAcceleration.createBuffer(device, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
                owned.push(occupancy);
                long payload = MetalRtAcceleration.createBuffer(device, BrickGridUpload.VOXELS_PER_SECTION);
                owned.push(payload);
                long faceSeal = MetalRtAcceleration.createBuffer(device, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                owned.push(faceSeal);
                long palette = MetalRtAcceleration.createBuffer(device, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                owned.push(palette);
                zeroBuffer(payload, BrickGridUpload.VOXELS_PER_SECTION);
                zeroBuffer(faceSeal, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                zeroBuffer(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                setOccupiedVoxel(occupancy, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, 273, 0);

                long vertexBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.VERTEX_BYTES_PER_SLOT);
                owned.push(vertexBuffer);
                long primitiveDataBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.PRIMITIVE_DATA_BYTES_PER_SLOT);
                owned.push(primitiveDataBuffer);
                long countersBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.COUNTERS_BYTES);
                owned.push(countersBuffer);

                long expandCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long expandEncoder = Objc.msgSendId(expandCb, Objc.selector("computeCommandEncoder"));
                MetalRtAcceleration.encodeExpand(expandEncoder, compiled.expand(),
                        occupancy, 0L, payload, 0L, faceSeal, 0L, palette, 0L,
                        vertexBuffer, primitiveDataBuffer, countersBuffer);
                Objc.msgSendVoid(expandEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(expandCb, Objc.selector("commit"));
                Objc.msgSendVoid(expandCb, Objc.selector("waitUntilCompleted"));

                int triangleCount = MetalRtAcceleration.readCompleteTriangleCount(countersBuffer, -1);
                assertEquals(12, triangleCount,
                        "a FULL voxel with every face exposed emits 12 triangles (2 per face x 6 faces)");

                long buildCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long buildEncoder = Objc.msgSendId(buildCb, Objc.selector("accelerationStructureCommandEncoder"));

                long primDesc = MetalRtAcceleration.buildTrianglePrimitiveDescriptor(
                        vertexBuffer, primitiveDataBuffer, triangleCount);
                Objc.AccelerationStructureSizes primSizes = Objc.accelerationStructureSizes(device, primDesc);
                long primStructure = Objc.msgSendId(
                        device, Objc.selector("newAccelerationStructureWithSize:"), primSizes.accelerationStructureSize());
                assertNotEquals(0L, primStructure, "primitive acceleration structure must build");
                owned.push(primStructure);
                long primScratch = MetalRtAcceleration.createBuffer(device, Math.max(primSizes.buildScratchBufferSize(), 4L));
                Objc.msgSendVoidIdIdIdLong(buildEncoder,
                        Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                        primStructure, primDesc, primScratch, 0L);
                Objc.msgSendVoid(primScratch, Objc.selector("release"));

                long instanceDescriptorBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                owned.push(instanceDescriptorBuffer);
                long instContents = Objc.msgSendId(instanceDescriptorBuffer, Objc.selector("contents"));
                MemorySegment instSeg = MemorySegment.ofAddress(instContents)
                        .reinterpret(MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                MetalRtAcceleration.writeInstanceDescriptor(instSeg, 0, 0.0f, 0.0f, 0.0f, 0);

                long instanceStructure;
                try (Arena local = Arena.ofConfined()) {
                    MemorySegment structures = local.allocate(ValueLayout.JAVA_LONG.byteSize());
                    structures.set(ValueLayout.JAVA_LONG, 0, primStructure);
                    long structuresArray = Objc.msgSendIdPtrLong(Objc.getClass("NSArray"),
                            Objc.selector("arrayWithObjects:count:"), structures.address(), 1L);

                    long instDesc = Objc.msgSendId(
                            Objc.getClass("MTLInstanceAccelerationStructureDescriptor"), Objc.selector("descriptor"));
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstanceDescriptorBuffer:"), instanceDescriptorBuffer);
                    Objc.msgSendVoidLong(instDesc, Objc.selector("setInstanceCount:"), 1L);
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstancedAccelerationStructures:"), structuresArray);

                    Objc.AccelerationStructureSizes instSizes = Objc.accelerationStructureSizes(device, instDesc);
                    instanceStructure = Objc.msgSendId(device,
                            Objc.selector("newAccelerationStructureWithSize:"), instSizes.accelerationStructureSize());
                    assertNotEquals(0L, instanceStructure, "instance acceleration structure must build");
                    owned.push(instanceStructure);
                    long instScratch =
                            MetalRtAcceleration.createBuffer(device, Math.max(instSizes.buildScratchBufferSize(), 4L));
                    Objc.msgSendVoidIdIdIdLong(buildEncoder,
                            Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                            instanceStructure, instDesc, instScratch, 0L);
                    Objc.msgSendVoid(instScratch, Objc.selector("release"));
                }
                Objc.msgSendVoid(buildEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(buildCb, Objc.selector("commit"));
                Objc.msgSendVoid(buildCb, Objc.selector("waitUntilCompleted"));

                long depthTexture = createTexture(device, MTL_PIXEL_FORMAT_DEPTH32_FLOAT, 2, 1,
                        MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(depthTexture);
                long normalTexture = createTexture(device, MTL_PIXEL_FORMAT_RGBA16_SNORM, 2, 1,
                        MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(normalTexture);
                long outputTexture = createTexture(device, MTL_PIXEL_FORMAT_R8_UNORM, 2, 1, MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(outputTexture);

                fillTexture(device, queue, depthTexture, "fill_depth.metal", FILL_DEPTH_SOURCE, "fill_depth", owned);
                fillTexture(device, queue, normalTexture, "fill_zero.metal", FILL_ZERO_SOURCE, "fill_zero", owned);
                InactiveCutoutFixtures cutoutFixtures = createInactiveCutoutFixtures(device, 2, 1, owned);

                for (int snapshotReady = 0; snapshotReady <= 1; snapshotReady++) {
                    long traceCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                    long traceEncoder = Objc.msgSendId(traceCb, Objc.selector("computeCommandEncoder"));
                    Objc.msgSendVoid(traceEncoder, Objc.selector("setComputePipelineState:"), compiled.trace().pipeline());
                    Objc.msgSendVoidIdLong(
                            traceEncoder, Objc.selector("setAccelerationStructure:atBufferIndex:"), instanceStructure, 1L);
                    bindCutoutArgs(traceEncoder, palette, cutoutFixtures, false);
                    Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), outputTexture, 0L);
                    Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), depthTexture, 1L);
                    Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), normalTexture, 2L);
                    try (Arena local = Arena.ofConfined()) {
                        MemorySegment constants = local.allocate(TRACE_CONSTANTS_BYTES);
                        writeWindowValidityConstants(constants, snapshotReady);
                        Objc.msgSendVoidIdLongLong(traceEncoder, Objc.selector("setBytes:length:atIndex:"),
                                constants.address(), TRACE_CONSTANTS_BYTES, 0L);
                    }
                    Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("useResource:usage:"), primStructure, MTL_RESOURCE_USAGE_READ);
                    Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("useResource:usage:"), vertexBuffer, MTL_RESOURCE_USAGE_READ);
                    Objc.msgSendVoidIdLong(
                            traceEncoder, Objc.selector("useResource:usage:"), instanceStructure, MTL_RESOURCE_USAGE_READ);
                    Objc.dispatchThreadgroups(traceEncoder, 2, 1, 1, 1, 1, 1);
                    Objc.msgSendVoid(traceEncoder, Objc.selector("endEncoding"));
                    Objc.msgSendVoid(traceCb, Objc.selector("commit"));
                    Objc.msgSendVoid(traceCb, Objc.selector("waitUntilCompleted"));

                    try (Arena local = Arena.ofConfined()) {
                        MemorySegment out = local.allocate(2);
                        Objc.getBytesFromRegion(cutoutFixtures.validOut(), out.address(), 2L, 0, 0, 0, 2, 1, 1, 0L);
                        int valid0 = out.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
                        int valid1 = out.get(ValueLayout.JAVA_BYTE, 1) & 0xFF;
                        assertEquals(snapshotReady == 0 ? 0 : 255, valid0,
                                "inside-domain receiver is valid only for a complete geometry snapshot");
                        assertEquals(0, valid1,
                                "pixel 1 lies outside the two-section finite domain and must not be traced");
                    }
                }
            } finally {
                compiled.release();
            }
        } finally {
            while (!owned.isEmpty()) {
                Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            }
            Objc.autoreleasePoolPop(pool);
        }
    }

    @Test
    void legacyPerRayValidityUsesTheCertifiedInnerCubeAndKeepsModeOne() {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);

        Deque<Long> owned = new ArrayDeque<>();
        long pool = Objc.autoreleasePoolPush();
        try {
            owned.push(device);
            MetalRtShaders.Compiled compiled = MetalRtShaders.compile(device);
            try {
                long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
                assertNotEquals(0L, queue, "newCommandQueue must not return nil");
                owned.push(queue);

                long occupancy = MetalRtAcceleration.createBuffer(device, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
                owned.push(occupancy);
                long payload = MetalRtAcceleration.createBuffer(device, BrickGridUpload.VOXELS_PER_SECTION);
                owned.push(payload);
                long faceSeal = MetalRtAcceleration.createBuffer(device, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                owned.push(faceSeal);
                long palette = MetalRtAcceleration.createBuffer(device, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                owned.push(palette);
                zeroBuffer(payload, BrickGridUpload.VOXELS_PER_SECTION);
                zeroBuffer(faceSeal, BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
                zeroBuffer(palette, BrickGridUpload.PALETTE_BYTES_PER_SLOT);
                setOccupiedVoxels(occupancy, BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, 2184, 2191);

                long vertexBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.VERTEX_BYTES_PER_SLOT);
                owned.push(vertexBuffer);
                long primitiveDataBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.PRIMITIVE_DATA_BYTES_PER_SLOT);
                owned.push(primitiveDataBuffer);
                long countersBuffer = MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.COUNTERS_BYTES);
                owned.push(countersBuffer);

                long expandCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long expandEncoder = Objc.msgSendId(expandCb, Objc.selector("computeCommandEncoder"));
                MetalRtAcceleration.encodeExpand(expandEncoder, compiled.expand(),
                        occupancy, 0L, payload, 0L, faceSeal, 0L, palette, 0L,
                        vertexBuffer, primitiveDataBuffer, countersBuffer);
                Objc.msgSendVoid(expandEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(expandCb, Objc.selector("commit"));
                Objc.msgSendVoid(expandCb, Objc.selector("waitUntilCompleted"));

                int triangleCount = MetalRtAcceleration.readCompleteTriangleCount(countersBuffer, -1);
                assertEquals(24, triangleCount, "two disjoint FULL voxels emit twelve triangles each");

                long buildCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long buildEncoder = Objc.msgSendId(buildCb, Objc.selector("accelerationStructureCommandEncoder"));

                long primDesc = MetalRtAcceleration.buildTrianglePrimitiveDescriptor(
                        vertexBuffer, primitiveDataBuffer, triangleCount);
                Objc.AccelerationStructureSizes primSizes = Objc.accelerationStructureSizes(device, primDesc);
                long primStructure = Objc.msgSendId(
                        device, Objc.selector("newAccelerationStructureWithSize:"), primSizes.accelerationStructureSize());
                assertNotEquals(0L, primStructure, "primitive acceleration structure must build");
                owned.push(primStructure);
                long primScratch = MetalRtAcceleration.createBuffer(device, Math.max(primSizes.buildScratchBufferSize(), 4L));
                Objc.msgSendVoidIdIdIdLong(buildEncoder,
                        Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                        primStructure, primDesc, primScratch, 0L);
                Objc.msgSendVoid(primScratch, Objc.selector("release"));

                long instanceDescriptorBuffer =
                        MetalRtAcceleration.createBuffer(device, MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                owned.push(instanceDescriptorBuffer);
                long instContents = Objc.msgSendId(instanceDescriptorBuffer, Objc.selector("contents"));
                MemorySegment instSeg = MemorySegment.ofAddress(instContents)
                        .reinterpret(MetalRtAcceleration.INSTANCE_DESCRIPTOR_BYTES);
                MetalRtAcceleration.writeInstanceDescriptor(instSeg, 0, 0.0f, 0.0f, 0.0f, 0);

                long instanceStructure;
                try (Arena local = Arena.ofConfined()) {
                    MemorySegment structures = local.allocate(ValueLayout.JAVA_LONG.byteSize());
                    structures.set(ValueLayout.JAVA_LONG, 0, primStructure);
                    long structuresArray = Objc.msgSendIdPtrLong(Objc.getClass("NSArray"),
                            Objc.selector("arrayWithObjects:count:"), structures.address(), 1L);

                    long instDesc = Objc.msgSendId(
                            Objc.getClass("MTLInstanceAccelerationStructureDescriptor"), Objc.selector("descriptor"));
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstanceDescriptorBuffer:"), instanceDescriptorBuffer);
                    Objc.msgSendVoidLong(instDesc, Objc.selector("setInstanceCount:"), 1L);
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstancedAccelerationStructures:"), structuresArray);

                    Objc.AccelerationStructureSizes instSizes = Objc.accelerationStructureSizes(device, instDesc);
                    instanceStructure = Objc.msgSendId(device,
                            Objc.selector("newAccelerationStructureWithSize:"), instSizes.accelerationStructureSize());
                    assertNotEquals(0L, instanceStructure, "instance acceleration structure must build");
                    owned.push(instanceStructure);
                    long instScratch =
                            MetalRtAcceleration.createBuffer(device, Math.max(instSizes.buildScratchBufferSize(), 4L));
                    Objc.msgSendVoidIdIdIdLong(buildEncoder,
                            Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                            instanceStructure, instDesc, instScratch, 0L);
                    Objc.msgSendVoid(instScratch, Objc.selector("release"));
                }
                Objc.msgSendVoid(buildEncoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(buildCb, Objc.selector("commit"));
                Objc.msgSendVoid(buildCb, Objc.selector("waitUntilCompleted"));

                long depthTexture = createTexture(device, MTL_PIXEL_FORMAT_DEPTH32_FLOAT, 2, 1,
                        MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(depthTexture);
                long normalTexture = createTexture(device, MTL_PIXEL_FORMAT_RGBA16_SNORM, 2, 1,
                        MTL_TEXTURE_USAGE_SHADER_READ | MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(normalTexture);
                long outputTexture = createTexture(device, MTL_PIXEL_FORMAT_R8_UNORM, 2, 1, MTL_TEXTURE_USAGE_SHADER_WRITE);
                owned.push(outputTexture);

                fillTexture(device, queue, depthTexture, "fill_depth.metal", FILL_DEPTH_SOURCE, "fill_depth", owned);
                fillTexture(device, queue, normalTexture, "fill_zero.metal", FILL_ZERO_SOURCE, "fill_zero", owned);
                InactiveCutoutFixtures cutoutFixtures = createInactiveCutoutFixtures(device, 2, 1, owned);

                long readiness = MetalRtAcceleration.createBuffer(device, 27 * Integer.BYTES);
                owned.push(readiness);
                MemorySegment ready = MemorySegment.ofAddress(Objc.msgSendId(readiness, Objc.selector("contents")))
                        .reinterpret(27 * Integer.BYTES);
                int[][] expectedValidity = {{255,255},{0,255},{0,255},{255,255},{0,255},{255,255},{0,255}};
                for (int scenario = 0; scenario < expectedValidity.length; scenario++) {
                    for (int i = 0; i < 27; i++) ready.setAtIndex(ValueLayout.JAVA_INT, i, 1);
                    // Unknown owner (0,1,0) precedes only pixel0's hit; x=24.5 misses it.
                    if (scenario == 2) ready.setAtIndex(ValueLayout.JAVA_INT, 9, 0);
                    // Unknown owner (1,0,0) begins at x=15 after the supported spill bound.
                    // Pixel0's +X ray hits the source-owner voxel there, at an interior endpoint.
                    if (scenario == 6) ready.setAtIndex(ValueLayout.JAVA_INT, 1, 0);
                    long traceCb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                    long traceEncoder = Objc.msgSendId(traceCb, Objc.selector("computeCommandEncoder"));
                    Objc.msgSendVoid(traceEncoder, Objc.selector("setComputePipelineState:"), compiled.trace().pipeline());
                    Objc.msgSendVoidIdLong(
                            traceEncoder, Objc.selector("setAccelerationStructure:atBufferIndex:"), instanceStructure, 1L);
                    bindCutoutArgs(traceEncoder, palette, cutoutFixtures, false);
                    Objc.msgSendVoidIdLongLong(traceEncoder, Objc.selector("setBuffer:offset:atIndex:"), readiness, 0L, 10L);
                    Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), outputTexture, 0L);
                    Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), depthTexture, 1L);
                    Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("setTexture:atIndex:"), normalTexture, 2L);
                    try (Arena local = Arena.ofConfined()) {
                        MemorySegment constants = local.allocate(TRACE_CONSTANTS_BYTES);
                        writeLegacyInnerCubeConstants(constants, scenario);
                        Objc.msgSendVoidIdLongLong(traceEncoder, Objc.selector("setBytes:length:atIndex:"),
                                constants.address(), TRACE_CONSTANTS_BYTES, 0L);
                    }
                    Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("useResource:usage:"), primStructure, MTL_RESOURCE_USAGE_READ);
                    Objc.msgSendVoidIdLong(traceEncoder, Objc.selector("useResource:usage:"), vertexBuffer, MTL_RESOURCE_USAGE_READ);
                    Objc.msgSendVoidIdLong(
                            traceEncoder, Objc.selector("useResource:usage:"), instanceStructure, MTL_RESOURCE_USAGE_READ);
                    Objc.dispatchThreadgroups(traceEncoder, 2, 1, 1, 1, 1, 1);
                    Objc.msgSendVoid(traceEncoder, Objc.selector("endEncoding"));
                    Objc.msgSendVoid(traceCb, Objc.selector("commit"));
                    Objc.msgSendVoid(traceCb, Objc.selector("waitUntilCompleted"));

                    try (Arena local = Arena.ofConfined()) {
                        MemorySegment out = local.allocate(2);
                        Objc.getBytesFromRegion(cutoutFixtures.validOut(), out.address(), 2L, 0, 0, 0, 2, 1, 1, 0L);
                        int valid0 = out.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
                        int valid1 = out.get(ValueLayout.JAVA_BYTE, 1) & 0xFF;
                        assertEquals(expectedValidity[scenario][0], valid0, "first ray validity scenario " + scenario);
                        assertEquals(expectedValidity[scenario][1], valid1, "second ray validity scenario " + scenario);
                        Objc.getBytesFromRegion(outputTexture, out.address(), 2L, 0, 0, 0, 2, 1, 1, 0L);
                        if (scenario == 2 || scenario == 3 || scenario == 6)
                            assertEquals(0, out.get(ValueLayout.JAVA_BYTE, 0) & 255, "known hit remains present scenario " + scenario);
                        assertEquals(255, out.get(ValueLayout.JAVA_BYTE, 1) & 255, "second ray is unobstructed");

                    }
                }
            } finally {
                compiled.release();
            }
        } finally {
            while (!owned.isEmpty()) {
                Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            }
            Objc.autoreleasePoolPop(pool);
        }
    }

    /** Mode2 rays: interior misses; shell and biased-origin rejection; unknown before hit;
     * known hit; and an unknown owner's expanded bound beginning at a confirmed hit.
     * Mode1 retains the full-cube fixture ABI. */
    private static void writeLegacyInnerCubeConstants(MemorySegment seg, int scenario) {
        seg.fill((byte) 0);
        float xScale = scenario == 1 || scenario == 5 ? 1.0f : scenario == 4 || scenario == 6 ? 0.0f : 16.0f;
        float xCenter = scenario == 0 ? 32.5f : scenario == 1 || scenario == 5 ? 1.0f
                : scenario == 4 ? 24.5f : scenario == 6 ? 12.0f : 16.5f;
        float y = scenario == 2 ? 40.0f : scenario == 3 ? 20.0f : scenario == 4 ? 1.05f
                : scenario == 6 ? 16.5f : 24.0f;
        seg.set(ValueLayout.JAVA_FLOAT, 0, xScale);
        seg.set(ValueLayout.JAVA_FLOAT, 4, scenario == 4 ? 0.05f : scenario == 6 ? 16.0f : 0.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 48, xCenter);
        seg.set(ValueLayout.JAVA_FLOAT, 52, y);
        seg.set(ValueLayout.JAVA_FLOAT, 56, 8.5f);
        seg.set(ValueLayout.JAVA_FLOAT, 60, 1.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 96, scenario == 6 ? 1.0f : 0.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 100, scenario == 6 ? 0.0f : -1.0f);
        seg.set(ValueLayout.JAVA_FLOAT, 112, 64.0f);
        seg.set(ValueLayout.JAVA_INT, 116, 3);
        seg.set(ValueLayout.JAVA_INT, 120, 2);
        seg.set(ValueLayout.JAVA_INT, 124, 1);
        seg.set(ValueLayout.JAVA_INT, 128, scenario == 5 ? 1 : 2);
    }

    /** See {@link #windowValidityAcceptsASlotInsideTheExportAndRejectsOneOutside}'s javadoc for the
     * derivation: column 0 depends on NDC.x alone, affecting only grid y (Y_STEP = 32), translation
     * (0.5, 24, 0.5, 1); windowDiameter = 2, explicit snapshot readiness, firstSectionX/Y/Z = 0 (all
     * written directly here rather than through {@link #writeTestWindowFields}, which uses the
     * generous {@link #TEST_WINDOW_DIAMETER}/{@link #TEST_SNAPSHOT_READY} every OTHER test
     * needs specifically so the window-validity check never interferes with them). */
    private static void writeWindowValidityConstants(MemorySegment seg, int snapshotReady) {
        float[] columnMajor = {
                0f, 32f, 0f, 0f,
                0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f,
                0.5f, 24f, 0.5f, 1f,
        };
        for (int i = 0; i < 16; i++) {
            seg.set(ValueLayout.JAVA_FLOAT, (long) i * 4, columnMajor[i]);
        }
        seg.set(ValueLayout.JAVA_FLOAT, 64, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 68, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 72, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 76, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 80, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 84, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 88, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 92, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 96, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 100, -1f);
        seg.set(ValueLayout.JAVA_FLOAT, 104, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 108, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 112, 64f);
        seg.set(ValueLayout.JAVA_INT, 116, 2);
        seg.set(ValueLayout.JAVA_INT, 120, 2);
        seg.set(ValueLayout.JAVA_INT, 124, 1);
        seg.set(ValueLayout.JAVA_INT, 128, snapshotReady);
        seg.set(ValueLayout.JAVA_INT, 132, 0);
        seg.set(ValueLayout.JAVA_INT, 136, 0);
        seg.set(ValueLayout.JAVA_INT, 140, 0);
    }

    // Writes atlas texel (gid.x, 0): x == 0 opaque (alpha 1), x == 1 transparent (alpha 0). The
    // real header/UV layout this test derives is exercised through the actual rt_trace.metal
    // sampling code, not re-implemented here; this kernel only seeds the two-texel source image.
    private static final String FILL_ATLAS_SOURCE = """
            #include <metal_stdlib>
            using namespace metal;
            kernel void fill_atlas(texture2d<float, access::write> d [[texture(0)]],
                    uint2 gid [[thread_position_in_grid]]) {
                d.write(gid.x == 0 ? float4(1.0, 1.0, 1.0, 1.0) : float4(0.0, 0.0, 0.0, 0.0), gid);
            }
            """;

    /** Writes one {@code voxelFaceTexture} face entry (7 words, see rt_trace.metal's own layout
     * derivation) for {@code paletteIndex}/{@code face} in a single-slot buffer: header word then
     * u0, v0, du/ds, dv/ds, du/dt, dv/dt as raw float bits, matching {@code
     * VoxelFaceTexture.mapping()}'s own word order exactly. */
    private static void setFaceTextureEntry(long buffer, int paletteIndex, int face, int header,
            float u0, float v0, float duds, float dvds, float dudt, float dvdt) {
        long ptr = Objc.msgSendId(buffer, Objc.selector("contents"));
        MemorySegment seg = MemorySegment.ofAddress(ptr)
                .reinterpret(dev.icehunter.fornax.voxel.VoxelFaceTexture.BYTES_PER_SLOT);
        long faceBase = (long) (paletteIndex * 6 + face) * 7 * 4;
        seg.set(ValueLayout.JAVA_INT, faceBase, header);
        seg.set(ValueLayout.JAVA_FLOAT, faceBase + 4, u0);
        seg.set(ValueLayout.JAVA_FLOAT, faceBase + 8, v0);
        seg.set(ValueLayout.JAVA_FLOAT, faceBase + 12, duds);
        seg.set(ValueLayout.JAVA_FLOAT, faceBase + 16, dvds);
        seg.set(ValueLayout.JAVA_FLOAT, faceBase + 20, dudt);
        seg.set(ValueLayout.JAVA_FLOAT, faceBase + 24, dvdt);
    }

    /** See {@link #cutoutAlphaTestBlocksTheOpaqueHalfOfAFaceAndPassesTheTransparentHalf}'s javadoc
     * for the derivation: column 0 depends on NDC.x alone (unlike {@link #writeConstants}'s shared
     * x/z column), placing pixel 0 at grid x = 8.25 and pixel 1 at grid x = 8.75, both at fixed
     * z = 8.5, y = 20; sunDir straight down. */
    private static void writeCutoutConstants(MemorySegment seg) {
        float[] columnMajor = {
                0.5f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f,
                8.5f, 20f, 8.5f, 1f,
        };
        for (int i = 0; i < 16; i++) {
            seg.set(ValueLayout.JAVA_FLOAT, (long) i * 4, columnMajor[i]);
        }
        seg.set(ValueLayout.JAVA_FLOAT, 64, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 68, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 72, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 76, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 80, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 84, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 88, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 92, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 96, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 100, -1f);
        seg.set(ValueLayout.JAVA_FLOAT, 104, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 108, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 112, 64f);
        writeTestWindowFields(seg);
    }

    /** See {@link #crossCutoutAlphaTestBlocksTheOpaqueHalfAndPassesTheTransparentHalf}'s javadoc
     * for the derivation: column 0 = (0.375, 0, 0.375, 0) moves both grid x and grid z together
     * with NDC.x, translation = (8.21464466, 8.5, 8.78535534, 1) places pixel 0 (NDC.x = -0.5) at
     * the pre-bias grid position (8.02714466, 8.5, 8.59785534) and pixel 1 (NDC.x = 0.5) at
     * (8.40214466, 8.5, 8.97285534). {@code sunDir = normalize(1, 0, -1)}, not straight down like
     * every other trace test in this file: this ray needs a horizontal component to cross the
     * CROSS entry's diagonal plane transversally rather than travelling within it. */
    private static void writeCrossCutoutConstants(MemorySegment seg) {
        float[] columnMajor = {
                0.375f, 0f, 0.375f, 0f,
                0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f,
                8.21464466f, 8.5f, 8.78535534f, 1f,
        };
        for (int i = 0; i < 16; i++) {
            seg.set(ValueLayout.JAVA_FLOAT, (long) i * 4, columnMajor[i]);
        }
        seg.set(ValueLayout.JAVA_FLOAT, 64, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 68, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 72, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 76, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 80, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 84, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 88, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 92, 0f);
        float diag = (float) (Math.sqrt(2.0) / 2.0);
        seg.set(ValueLayout.JAVA_FLOAT, 96, diag);
        seg.set(ValueLayout.JAVA_FLOAT, 100, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 104, -diag);
        seg.set(ValueLayout.JAVA_FLOAT, 108, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 112, 64f);
        writeTestWindowFields(seg);
    }

    /** Zeros {@code buffer} then sets the occupancy bit for every voxel index in {@code
     * voxelIndices}. Extends {@link #setOccupiedVoxel} (which only ever sets one bit) to seed the
     * several-neighbor slots {@link #fullyEnclosedCutoutVoxelStillEmitsEveryFaceForAlphaTesting}
     * needs. */
    private static void setOccupiedVoxels(long buffer, long byteSize, int... voxelIndices) {
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

    /** Sets {@code faceSeal[voxelIndex]} to {@code value} for every voxel index in {@code
     * voxelIndices}, leaving every other byte at whatever the caller already wrote (a prior {@link
     * #zeroBuffer} call, in practice). */
    private static void setFaceSealBytes(long buffer, long byteSize, byte value, int... voxelIndices) {
        long ptr = Objc.msgSendId(buffer, Objc.selector("contents"));
        MemorySegment seg = MemorySegment.ofAddress(ptr).reinterpret(byteSize);
        for (int voxelIndex : voxelIndices) {
            seg.set(ValueLayout.JAVA_BYTE, voxelIndex, value);
        }
    }

    /** Sets palette entry 0's word 0 (boxCount and the cutout/cross flags, see {@code
     * rt_expand.metal}'s header comment) to {@code flagsWord}. */
    private static void setPaletteEntryZeroFlagsWord(long buffer, long byteSize, int flagsWord) {
        long ptr = Objc.msgSendId(buffer, Objc.selector("contents"));
        MemorySegment seg = MemorySegment.ofAddress(ptr).reinterpret(byteSize);
        seg.set(ValueLayout.JAVA_INT, 0, flagsWord);
    }

    /** Sets palette entry 0's box word at box slot {@code boxSlot} (word index 7 + {@code
     * boxSlot}, the packed min/max corner {@code rt_expand.metal}'s header comment documents) to
     * {@code boxWord}. */
    private static void setPaletteEntryZeroBoxWord(long buffer, long byteSize, int boxSlot, int boxWord) {
        long ptr = Objc.msgSendId(buffer, Objc.selector("contents"));
        MemorySegment seg = MemorySegment.ofAddress(ptr).reinterpret(byteSize);
        seg.set(ValueLayout.JAVA_INT, (long) (7 + boxSlot) * 4, boxWord);
    }

    /** Compiles {@code kernelSource}'s single {@code texture2d<float, access::write>} kernel and
     * dispatches it once over the whole 2x1 {@code texture}: the throwaway seeding step both
     * {@code depthTexture} and {@code normalTexture} need, since neither {@code
     * MTLPixelFormatDepth32Float} nor a texture bound at kernel-dispatch time can be seeded from
     * the CPU side (depth/stencil formats reject {@code replaceRegion:} outright; this test keeps
     * the normal texture's seeding on the same path for one less special case). Every id this
     * creates is pushed onto {@code owned} for the caller's existing teardown loop to release. */
    private static void fillTexture(long device, long queue, long texture, String resourceName,
            String kernelSource, String functionName, Deque<Long> owned) {
        long library = MetalRtShaders.compileSource(device, resourceName, kernelSource);
        owned.push(library);
        long function = Objc.msgSendId(library, Objc.selector("newFunctionWithName:"), Objc.nsString(functionName));
        owned.push(function);
        Objc.Result pipelineResult = Objc.msgSendIdIdErr(
                device, Objc.selector("newComputePipelineStateWithFunction:error:"), function);
        assertNotEquals(0L, pipelineResult.id(), functionName + " pipeline must build: " + pipelineResult.error());
        owned.push(pipelineResult.id());

        long cb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
        long encoder = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
        Objc.msgSendVoid(encoder, Objc.selector("setComputePipelineState:"), pipelineResult.id());
        Objc.msgSendVoidIdLong(encoder, Objc.selector("setTexture:atIndex:"), texture, 0L);
        Objc.dispatchThreadgroups(encoder, 2, 1, 1, 1, 1, 1);
        Objc.msgSendVoid(encoder, Objc.selector("endEncoding"));
        Objc.msgSendVoid(cb, Objc.selector("commit"));
        Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));
    }

    /** Reads the real, shipped {@code rt_trace.metal} source text so a decode probe kernel can be
     * appended to it and compiled together; the probe then calls the exact {@code
     * plagueDecodeGeometricNormal} the trace kernel uses, not a copy that could drift from it. */
    private static String readRtTraceSource() {
        String resourcePath = "/assets/fornax/shaders_engine/rt_trace.metal";
        try (InputStream in = MetalRtSmokeTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("missing engine shader resource " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read engine shader resource " + resourcePath, e);
        }
    }

    /** Compiles the real {@code rt_trace.metal} source plus {@link #DECODE_PROBE_KERNEL_SOURCE},
     * dispatches one thread per entry in {@code alphas} over a 1-row {@code RGBA32Float} texture,
     * and reads back each texel's rgb as the decoded direction (w is unused). Every id this creates
     * is pushed onto {@code owned} for the caller's existing teardown loop to release. */
    private static float[][] runDecodeProbe(long device, float[] alphas, Deque<Long> owned) {
        long library = MetalRtShaders.compileSource(
                device, "rt_trace_decode_probe.metal", readRtTraceSource() + DECODE_PROBE_KERNEL_SOURCE);
        owned.push(library);
        long function = Objc.msgSendId(library, Objc.selector("newFunctionWithName:"), Objc.nsString("decode_probe"));
        assertNotEquals(0L, function, "decode_probe function must be found in the combined source");
        owned.push(function);
        Objc.Result pipelineResult = Objc.msgSendIdIdErr(
                device, Objc.selector("newComputePipelineStateWithFunction:error:"), function);
        assertNotEquals(0L, pipelineResult.id(), "decode_probe pipeline must build: " + pipelineResult.error());
        owned.push(pipelineResult.id());

        long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
        assertNotEquals(0L, queue, "newCommandQueue must not return nil");
        owned.push(queue);

        int width = alphas.length;
        long outputTexture = createTexture(device, MTL_PIXEL_FORMAT_RGBA32_FLOAT, width, 1, MTL_TEXTURE_USAGE_SHADER_WRITE);
        owned.push(outputTexture);

        long cb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
        long encoder = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
        Objc.msgSendVoid(encoder, Objc.selector("setComputePipelineState:"), pipelineResult.id());
        Objc.msgSendVoidIdLong(encoder, Objc.selector("setTexture:atIndex:"), outputTexture, 0L);
        try (Arena local = Arena.ofConfined()) {
            MemorySegment alphaSeg = local.allocate((long) width * Float.BYTES);
            for (int i = 0; i < width; i++) {
                alphaSeg.set(ValueLayout.JAVA_FLOAT, (long) i * Float.BYTES, alphas[i]);
            }
            Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBytes:length:atIndex:"),
                    alphaSeg.address(), (long) width * Float.BYTES, 0L);
        }
        Objc.dispatchThreadgroups(encoder, width, 1, 1, 1, 1, 1);
        Objc.msgSendVoid(encoder, Objc.selector("endEncoding"));
        Objc.msgSendVoid(cb, Objc.selector("commit"));
        Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));

        float[][] decoded = new float[width][3];
        try (Arena local = Arena.ofConfined()) {
            long bytesPerRow = (long) width * 4 * Float.BYTES;
            MemorySegment out = local.allocate(bytesPerRow);
            Objc.getBytesFromRegion(outputTexture, out.address(), bytesPerRow, 0, 0, 0, width, 1, 1, 0L);
            for (int i = 0; i < width; i++) {
                long base = (long) i * 4 * Float.BYTES;
                decoded[i][0] = out.get(ValueLayout.JAVA_FLOAT, base);
                decoded[i][1] = out.get(ValueLayout.JAVA_FLOAT, base + Float.BYTES);
                decoded[i][2] = out.get(ValueLayout.JAVA_FLOAT, base + 2L * Float.BYTES);
            }
        }
        return decoded;
    }

    @Test void nativeSunProjectionRoundTripsTheLiveWarpAndDiagonalDdaChecksDisplacedOwners() throws Exception {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);
        Deque<Long> owned = new ArrayDeque<>();
        long pool = Objc.autoreleasePoolPush();
        try {
            owned.push(device);
            String sunSource;
            try (InputStream in = getClass().getResourceAsStream("/assets/fornax/shaders_engine/rt_sun_depth.metal")) {
                sunSource = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String probe = """
                    kernel void sun_probe(constant RtSunDepthConstants& c [[buffer(0)]],
                            device const uint* readiness [[buffer(10)]],
                            texture2d<float, access::write> out [[texture(0)]], uint2 gid [[thread_position_in_grid]]) {
                        if (gid.x < 5u) {
                            const float2 samples[5] = {float2(0),float2(0.5,0),float2(0.7),float2(0.8),float2(1)};
                            float2 p;
                            if (!rtUnwarp(samples[gid.x], c.bias, p)) {out.write(float4(0),gid);return;}
                            float4 world = c.inverseSunViewProj * float4(p,0.37,1);
                            float4 projected = c.sunViewProj * float4(world.xyz/world.w,1);
                            projected /= projected.w;
                            projected.xy /= length(projected.xy)*c.bias + (1-c.bias);
                            out.write(float4(projected.xyz,1),gid);
                        } else if (gid.x < 9u) {
                            float3 origin = gid.x < 7u ? float3(1) : float3(gid.x == 7u ? 15.5 : 14.0,1,8);
                            float3 direction = gid.x < 7u ? normalize(float3(1)) : float3(0,1,0);
                            bool valid = rtRaySectionsReady(origin,direction,0.0,40.0,true,3u,int3(0),readiness + (gid.x-5u)*27u);
                            out.write(float4(valid ? 1 : 0),gid);
                        } else {
                            // Owner (1,0,0) can emit a triangle at x=15 through its allowed
                            // one-block spill. A closest-hit endpoint checks that owner; a finite
                            // miss limit excludes its endpoint. Both sides of each boundary are pinned.
                            const float stops[3] = {13.999,14.0,14.001};
                            bool valid = rtRaySectionsReady(float3(1,8,8),float3(1,0,0),0.0,
                                    stops[(gid.x-9u)%3u],gid.x<12u,3u,int3(0),readiness + (gid.x-5u)*27u);
                            out.write(float4(valid ? 1 : 0),gid);
                        }
                    }
                    """;
            long library = MetalRtShaders.compileSource(device, "sun_projection_probe", readRtTraceSource() + sunSource + probe);
            owned.push(library);
            long function = Objc.msgSendId(library, Objc.selector("newFunctionWithName:"), Objc.nsString("sun_probe"));
            owned.push(function);
            Objc.Result pipeline = Objc.msgSendIdIdErr(device, Objc.selector("newComputePipelineStateWithFunction:error:"), function);
            assertNotEquals(0L,pipeline.id(),pipeline.error());
            owned.push(pipeline.id());
            long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue")); owned.push(queue);
            long output = createTexture(device,MTL_PIXEL_FORMAT_RGBA32_FLOAT,15,1,MTL_TEXTURE_USAGE_SHADER_WRITE); owned.push(output);
            long readiness = MetalRtAcceleration.createBuffer(device,10*27*4); owned.push(readiness);
            MemorySegment ready = MemorySegment.ofAddress(Objc.msgSendId(readiness,Objc.selector("contents"))).reinterpret(10*27*4);
            for(int i=0;i<10*27;++i) ready.setAtIndex(ValueLayout.JAVA_INT,i,1);
            ready.setAtIndex(ValueLayout.JAVA_INT,2,0); // unrelated (2,0,0) off the diagonal
            ready.setAtIndex(ValueLayout.JAVA_INT,27+13,0); // (1,1,1) on the diagonal
            ready.setAtIndex(ValueLayout.JAVA_INT,54+10,0); // neighbor (1,1,0) reaches x=15.5
            ready.setAtIndex(ValueLayout.JAVA_INT,81+10,0); // same neighbor cannot reach x=14
            for (int i=4;i<10;++i) ready.setAtIndex(ValueLayout.JAVA_INT,i*27+1,0);
            long cb=Objc.msgSendId(queue,Objc.selector("commandBuffer"));
            long enc=Objc.msgSendId(cb,Objc.selector("computeCommandEncoder"));
            Objc.msgSendVoid(enc,Objc.selector("setComputePipelineState:"),pipeline.id());
            Objc.msgSendVoidIdLong(enc,Objc.selector("setTexture:atIndex:"),output,0);
            Objc.msgSendVoidIdLongLong(enc,Objc.selector("setBuffer:offset:atIndex:"),readiness,0,10);
            try(Arena arena=Arena.ofConfined()) {
                MemorySegment constants=arena.allocate(176); constants.fill((byte)0);
                // Live distance128/resolution2048 gives bias0.8. The actual production matrix
                // builder supplies a grazing, non-axis aligned light with texel-snapped camera.
                var matrix=dev.icehunter.fornax.pass.shadow.ShadowCamera.compute(new org.joml.Vector3f(0.4f,0.75f,0.2f),
                        -132.25,80.125,33.75,128,2048).viewProj();
                float[] inverse=new org.joml.Matrix4f(matrix).invert().get(new float[16]);
                float[] forward=matrix.get(new float[16]);
                for(int i=0;i<16;++i){constants.setAtIndex(ValueLayout.JAVA_FLOAT,i,inverse[i]);constants.set(ValueLayout.JAVA_FLOAT,80+i*4L,forward[i]);}
                constants.set(ValueLayout.JAVA_FLOAT,144,dev.icehunter.fornax.pass.shadow.ShadowCamera.shadowMapBias(128,2048));
                Objc.msgSendVoidIdLongLong(enc,Objc.selector("setBytes:length:atIndex:"),constants.address(),176,0);
            }
            Objc.dispatchThreadgroups(enc,15,1,1,1,1,1);
            Objc.msgSendVoid(enc,Objc.selector("endEncoding"));Objc.msgSendVoid(cb,Objc.selector("commit"));Objc.msgSendVoid(cb,Objc.selector("waitUntilCompleted"));
            try(Arena arena=Arena.ofConfined()) {
                MemorySegment out=arena.allocate(15*16);Objc.getBytesFromRegion(output,out.address(),15*16,0,0,0,15,1,1,0);
                float[][] expected={{0,0},{0.5f,0},{0.7f,0.7f},{0.8f,0.8f}};
                for(int i=0;i<4;++i){
                    assertEquals(expected[i][0],out.get(ValueLayout.JAVA_FLOAT,i*16L),2e-5f);
                    assertEquals(expected[i][1],out.get(ValueLayout.JAVA_FLOAT,i*16L+4),2e-5f);
                    assertEquals(0.37f,out.get(ValueLayout.JAVA_FLOAT,i*16L+8),2e-6f);
                    assertEquals(1f,out.get(ValueLayout.JAVA_FLOAT,i*16L+12));
                }
                assertEquals(0f,out.get(ValueLayout.JAVA_FLOAT,4*16L+12),"outside invertible warp domain");
                float[] valid={1,0,0,1,1,0,0,1,1,0};for(int i=0;i<10;++i)assertEquals(valid[i],out.get(ValueLayout.JAVA_FLOAT,(5+i)*16L),"diagonal/displaced case "+i);
            }
        } finally {while(!owned.isEmpty())Objc.msgSendVoid(owned.pop(),Objc.selector("release"));Objc.autoreleasePoolPop(pool);}
    }

    private static float[] normalize(float[] v) {
        float length = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new float[] {v[0] / length, v[1] / length, v[2] / length};
    }

    private static void zeroBuffer(long buffer, long byteSize) {
        long ptr = Objc.msgSendId(buffer, Objc.selector("contents"));
        MemorySegment.ofAddress(ptr).reinterpret(byteSize).fill((byte) 0);
    }

    private static void setOccupiedVoxel(long buffer, long byteSize, int byteIndex, int bitIndex) {
        long ptr = Objc.msgSendId(buffer, Objc.selector("contents"));
        MemorySegment seg = MemorySegment.ofAddress(ptr).reinterpret(byteSize);
        seg.fill((byte) 0);
        byte current = seg.get(ValueLayout.JAVA_BYTE, byteIndex);
        seg.set(ValueLayout.JAVA_BYTE, byteIndex, (byte) (current | (1 << bitIndex)));
    }

    /** Builds an {@code MTLTextureDescriptor} through its plain property setters (each an
     * existing NSUInteger-arg shape) rather than the {@code texture2DDescriptorWithPixelFormat:
     * width:height:mipmapped:} convenience constructor, so no new multi-argument Objc shape is
     * needed for this test's two textures. */
    private static long createTexture(long device, long pixelFormat, long width, long height, long usage) {
        long alloc = Objc.msgSendId(Objc.getClass("MTLTextureDescriptor"), Objc.selector("alloc"));
        long desc = Objc.msgSendId(alloc, Objc.selector("init"));
        Objc.msgSendVoidLong(desc, Objc.selector("setTextureType:"), MTL_TEXTURE_TYPE_2D);
        Objc.msgSendVoidLong(desc, Objc.selector("setPixelFormat:"), pixelFormat);
        Objc.msgSendVoidLong(desc, Objc.selector("setWidth:"), width);
        Objc.msgSendVoidLong(desc, Objc.selector("setHeight:"), height);
        Objc.msgSendVoidLong(desc, Objc.selector("setUsage:"), usage);
        Objc.msgSendVoidLong(desc, Objc.selector("setStorageMode:"), MTL_STORAGE_MODE_SHARED);
        long texture = Objc.msgSendId(device, Objc.selector("newTextureWithDescriptor:"), desc);
        Objc.msgSendVoid(desc, Objc.selector("release"));
        if (texture == 0) {
            throw new IllegalStateException("newTextureWithDescriptor: returned nil");
        }
        return texture;
    }

    /** See the class javadoc's "hand-picked invProjModelView" paragraph for the derivation. */
    private static void writeConstants(MemorySegment seg) {
        float[] columnMajor = {
                -8f, 0f, -8f, 0f,
                0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f,
                4.5f, 20f, 4.5f, 1f,
        };
        for (int i = 0; i < 16; i++) {
            seg.set(ValueLayout.JAVA_FLOAT, (long) i * 4, columnMajor[i]);
        }
        seg.set(ValueLayout.JAVA_FLOAT, 64, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 68, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 72, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 76, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 80, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 84, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 88, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 92, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 96, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 100, -1f);
        seg.set(ValueLayout.JAVA_FLOAT, 104, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 108, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 112, 64f);
        writeTestWindowFields(seg);
    }

    /** See {@link #groundedRayNearTopFaceIsNotSelfShadowedByATiltedBumpNormal}'s javadoc for the
     * derivation: a translation-only matrix (ignores NDC and depth like {@link #writeConstants}'s
     * own matrix) placing both output pixels' reconstructed position at (8.5, 8.995, 8.5), and
     * sunDir straight up so the ray must clear the voxel's own top face (grid y = 9) to escape. */
    private static void writeGroundedConstants(MemorySegment seg) {
        float[] columnMajor = {
                0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f,
                8.5f, 8.995f, 8.5f, 1f,
        };
        for (int i = 0; i < 16; i++) {
            seg.set(ValueLayout.JAVA_FLOAT, (long) i * 4, columnMajor[i]);
        }
        seg.set(ValueLayout.JAVA_FLOAT, 64, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 68, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 72, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 76, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 80, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 84, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 88, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 92, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 96, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 100, 1f);
        seg.set(ValueLayout.JAVA_FLOAT, 104, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 108, 0f);
        seg.set(ValueLayout.JAVA_FLOAT, 112, 64f);
        writeTestWindowFields(seg);
    }

    /** Writes the shared window/output-size fields (offsets 116-143) every trace-dispatch test in
     * this file uses, except {@link #windowValidityAcceptsASlotInsideTheExportAndRejectsOneOutside}
     * which needs its own smaller values to exercise the boundary: {@link
     * #TEST_WINDOW_DIAMETER} for {@code windowDiameter} and {@link #TEST_SNAPSHOT_READY} for
     * {@code allocatedSlotCount} (see those fields' own doc for why they are generous enough that
     * every OTHER test's hand-picked grid position reads as inside both bounds), a 2x1 output
     * size, and {@code firstSectionX/Y/Z = 0} so a test's grid position is directly its absolute
     * section position. */
    private static void writeTestWindowFields(MemorySegment seg) {
        seg.set(ValueLayout.JAVA_INT, 116, TEST_WINDOW_DIAMETER);
        seg.set(ValueLayout.JAVA_INT, 120, 2);
        seg.set(ValueLayout.JAVA_INT, 124, 1);
        seg.set(ValueLayout.JAVA_INT, 128, TEST_SNAPSHOT_READY);
        seg.set(ValueLayout.JAVA_INT, 132, 0);
        seg.set(ValueLayout.JAVA_INT, 136, 0);
        seg.set(ValueLayout.JAVA_INT, 140, 0);
    }

    /**
     * One rt_sun_depth dispatch over the 2x1 fixture above. Extracted only so the fill-mode
     * assertions can run the same encode twice with one flag changed; every binding here mirrors
     * what the loop above does inline.
     */
    private static void dispatchSunDepth(long queue, MetalRtShaders.Compiled compiled, long instanceStructure,
            long palette, InactiveCutoutFixtures fixtures, long readiness, long output,
            long primStructure, long vertexBuffer, int tier, boolean fillMode) {
        long cb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
        long enc = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
        Objc.msgSendVoid(enc, Objc.selector("setComputePipelineState:"), compiled.sunDepth().pipeline());
        Objc.msgSendVoidIdLong(enc, Objc.selector("setAccelerationStructure:atBufferIndex:"), instanceStructure, 1L);
        bindCutoutArgs(enc, palette, fixtures, false);
        Objc.msgSendVoidIdLongLong(enc, Objc.selector("setBuffer:offset:atIndex:"), readiness, 0L, 10L);
        Objc.msgSendVoidIdLong(enc, Objc.selector("setTexture:atIndex:"), output, 0L);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment constants = arena.allocate(192);
            constants.fill((byte) 0);
            constants.set(ValueLayout.JAVA_FLOAT, 0, 16.0f);
            constants.set(ValueLayout.JAVA_FLOAT, 36, -48.0f);
            constants.set(ValueLayout.JAVA_FLOAT, 48, 16.5f);
            constants.set(ValueLayout.JAVA_FLOAT, 52, 48.0f);
            constants.set(ValueLayout.JAVA_FLOAT, 56, 8.5f);
            constants.set(ValueLayout.JAVA_FLOAT, 60, 1.0f);
            constants.set(ValueLayout.JAVA_INT, 148, 3);
            constants.set(ValueLayout.JAVA_INT, 152, 2);
            constants.set(ValueLayout.JAVA_INT, 156, 1);
            constants.set(ValueLayout.JAVA_INT, 176, tier);
            constants.set(ValueLayout.JAVA_INT, 180, fillMode ? 1 : 0);
            Objc.msgSendVoidIdLongLong(enc, Objc.selector("setBytes:length:atIndex:"), constants.address(), 192, 0L);
        }
        Objc.msgSendVoidIdLong(enc, Objc.selector("useResource:usage:"), primStructure, MTL_RESOURCE_USAGE_READ);
        Objc.msgSendVoidIdLong(enc, Objc.selector("useResource:usage:"), vertexBuffer, MTL_RESOURCE_USAGE_READ);
        Objc.msgSendVoidIdLong(enc, Objc.selector("useResource:usage:"), instanceStructure, MTL_RESOURCE_USAGE_READ);
        Objc.dispatchThreadgroups(enc, 2, 1, 1, 1, 1, 1);
        Objc.msgSendVoid(enc, Objc.selector("endEncoding"));
        Objc.msgSendVoid(cb, Objc.selector("commit"));
        Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));
    }

    /** The 2x1 RGBA32F output as eight floats: texel 0's RGBA then texel 1's. */
    private static float[] readSunDepth(long output) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(32);
            Objc.getBytesFromRegion(output, out.address(), 32, 0, 0, 0, 2, 1, 1, 0);
            float[] words = new float[8];
            for (int i = 0; i < words.length; i++) {
                words[i] = out.get(ValueLayout.JAVA_FLOAT, i * 4L);
            }
            return words;
        }
    }
}
