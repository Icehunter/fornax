package dev.icehunter.fornax.metalfx.objc;

import dev.icehunter.fornax.metalfx.MetalFxSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the message-send shapes Task 2 onward needs: a buffer alloc, a library compile that fails,
 * a library compile that succeeds plus function lookup, and a full dispatch through a real command
 * buffer. Skips off this machine; only runs where {@link Objc#isLoaded()} and Metal are present.
 *
 * <p>Every {@code new*}-named selector returns an object with a retain count the caller owns (not
 * autoreleased), so each one this class creates is released after use, matching how the per-frame
 * code in later tasks must call the same selectors.
 */
class ObjcShapesTest {
    private static final int MTL_COMMAND_BUFFER_STATUS_COMPLETED = 4;

    @Test
    void newBufferWithLengthReturnsAnIdWhoseLengthReadsBack() {
        Assumptions.assumeTrue(Objc.isLoaded());
        Assumptions.assumeTrue(MetalFxSupport.isAvailable());
        long device = MetalFxSupport.metalDevice();

        long pool = Objc.autoreleasePoolPush();
        try {
            long buffer = Objc.msgSendIdLongLong(device,
                    Objc.selector("newBufferWithLength:options:"), 256L, 0L);
            assertNotEquals(0L, buffer, "newBufferWithLength:options: returned nil");
            try {
                long length = Objc.msgSendLong(buffer, Objc.selector("length"));
                assertEquals(256L, length);
            } finally {
                Objc.msgSendVoid(buffer, Objc.selector("release"));
            }
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }

    @Test
    void newLibraryWithSourceOnABrokenKernelReturnsNoLibraryAndAnErrorString() {
        Assumptions.assumeTrue(Objc.isLoaded());
        Assumptions.assumeTrue(MetalFxSupport.isAvailable());
        long device = MetalFxSupport.metalDevice();

        long pool = Objc.autoreleasePoolPush();
        try {
            String brokenSource = "kernel void k() { int x = ; }";
            Objc.Result result = Objc.msgSendIdIdIdErr(device,
                    Objc.selector("newLibraryWithSource:options:error:"),
                    Objc.nsString(brokenSource), 0L);
            assertEquals(0L, result.id(), "a syntax error must not yield a library");
            assertTrue(result.error() != null && !result.error().isEmpty(),
                    "a syntax error must surface a non-empty error string");
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }

    @Test
    void newLibraryWithSourceOnAValidKernelReturnsALibraryAndFindsTheFunction() {
        Assumptions.assumeTrue(Objc.isLoaded());
        Assumptions.assumeTrue(MetalFxSupport.isAvailable());
        long device = MetalFxSupport.metalDevice();

        long pool = Objc.autoreleasePoolPush();
        try {
            Objc.Result result = Objc.msgSendIdIdIdErr(device,
                    Objc.selector("newLibraryWithSource:options:error:"),
                    Objc.nsString("kernel void k() {}"), 0L);
            assertNotEquals(0L, result.id(), "a valid kernel must yield a library");
            try {
                long function = Objc.msgSendId(result.id(),
                        Objc.selector("newFunctionWithName:"), Objc.nsString("k"));
                assertNotEquals(0L, function, "newFunctionWithName: must find the compiled kernel");
                Objc.msgSendVoid(function, Objc.selector("release"));
            } finally {
                Objc.msgSendVoid(result.id(), Objc.selector("release"));
            }
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }

    @Test
    void dispatchThreadgroupsRunsToCompletionOnARealCommandBuffer() {
        Assumptions.assumeTrue(Objc.isLoaded());
        Assumptions.assumeTrue(MetalFxSupport.isAvailable());
        long device = MetalFxSupport.metalDevice();

        long pool = Objc.autoreleasePoolPush();
        try {
            Objc.Result library = Objc.msgSendIdIdIdErr(device,
                    Objc.selector("newLibraryWithSource:options:error:"),
                    Objc.nsString("kernel void k() {}"), 0L);
            assertNotEquals(0L, library.id(), "kernel must compile");
            try {
                long function = Objc.msgSendId(library.id(),
                        Objc.selector("newFunctionWithName:"), Objc.nsString("k"));
                assertNotEquals(0L, function);
                try {
                    Objc.Result pipeline = Objc.msgSendIdIdErr(device,
                            Objc.selector("newComputePipelineStateWithFunction:error:"), function);
                    assertNotEquals(0L, pipeline.id(), "pipeline state must build: " + pipeline.error());
                    try {
                        long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
                        assertNotEquals(0L, queue);
                        try {
                            long commandBuffer = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                            assertNotEquals(0L, commandBuffer);
                            long encoder = Objc.msgSendId(commandBuffer, Objc.selector("computeCommandEncoder"));
                            assertNotEquals(0L, encoder);

                            Objc.msgSendVoid(encoder, Objc.selector("setComputePipelineState:"), pipeline.id());
                            Objc.dispatchThreadgroups(encoder, 1, 1, 1, 1, 1, 1);
                            Objc.msgSendVoid(encoder, Objc.selector("endEncoding"));
                            Objc.msgSendVoid(commandBuffer, Objc.selector("commit"));
                            Objc.msgSendVoid(commandBuffer, Objc.selector("waitUntilCompleted"));

                            long status = Objc.msgSendLong(commandBuffer, Objc.selector("status"));
                            assertEquals(MTL_COMMAND_BUFFER_STATUS_COMPLETED, status);
                        } finally {
                            Objc.msgSendVoid(queue, Objc.selector("release"));
                        }
                    } finally {
                        Objc.msgSendVoid(pipeline.id(), Objc.selector("release"));
                    }
                } finally {
                    Objc.msgSendVoid(function, Objc.selector("release"));
                }
            } finally {
                Objc.msgSendVoid(library.id(), Objc.selector("release"));
            }
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }

    @Test
    void supportsRaytracingAndSupportsFamilyReturnWithoutThrowing() {
        Assumptions.assumeTrue(Objc.isLoaded());
        Assumptions.assumeTrue(MetalFxSupport.isAvailable());
        long device = MetalFxSupport.metalDevice();

        long pool = Objc.autoreleasePoolPush();
        try {
            // Either answer is a pass here: this pins the no-arg BOOL shape itself, not the
            // hardware capability (that belongs to MetalRtSupport's own probe test).
            assertDoesNotThrow(() -> Objc.msgSendBool(device, Objc.selector("supportsRaytracing")));

            // MTLGPUFamilyApple9, the family this spike targets; exercises the existing one-arg
            // msgSendBool shape with a real selector rather than a made-up one.
            assertDoesNotThrow(
                    () -> Objc.msgSendBool(device, Objc.selector("supportsFamily:"), 1009L));
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }

    @Test
    void arrayWithObjectsCountBuildsAnNSArrayOfTheGivenObjects() {
        Assumptions.assumeTrue(Objc.isLoaded());
        Assumptions.assumeTrue(MetalFxSupport.isAvailable());

        long pool = Objc.autoreleasePoolPush();
        try {
            long numberClass = Objc.getClass("NSNumber");
            // numberWithInteger: and arrayWithObjects:count: are both convenience ("numberWith*"/
            // "arrayWith*") constructors, so both return autoreleased objects this test does not
            // own and must not release.
            long a = Objc.msgSendId(numberClass, Objc.selector("numberWithInteger:"), 11L);
            long b = Objc.msgSendId(numberClass, Objc.selector("numberWithInteger:"), 22L);
            try (Arena local = Arena.ofConfined()) {
                MemorySegment elements = local.allocate(2 * ValueLayout.JAVA_LONG.byteSize());
                elements.setAtIndex(ValueLayout.JAVA_LONG, 0, a);
                elements.setAtIndex(ValueLayout.JAVA_LONG, 1, b);
                long array = Objc.msgSendIdPtrLong(Objc.getClass("NSArray"),
                        Objc.selector("arrayWithObjects:count:"), elements.address(), 2L);
                assertNotEquals(0L, array, "arrayWithObjects:count: must not return nil");
                assertEquals(2L, Objc.msgSendLong(array, Objc.selector("count")));
                long first = Objc.msgSendId(array, Objc.selector("objectAtIndex:"), 0L);
                long second = Objc.msgSendId(array, Objc.selector("objectAtIndex:"), 1L);
                assertEquals(11L, Objc.msgSendLong(first, Objc.selector("integerValue")));
                assertEquals(22L, Objc.msgSendLong(second, Objc.selector("integerValue")));
            }
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }

    @Test
    void accelerationStructureSizesAndBuildAccelerationStructureWorkOnARealTriangle() {
        Assumptions.assumeTrue(Objc.isLoaded());
        Assumptions.assumeTrue(MetalFxSupport.isAvailable());
        long device = MetalFxSupport.metalDevice();

        long pool = Objc.autoreleasePoolPush();
        try {
            // One triangle: 3 vertices x 3 floats x 4 bytes = 36 bytes.
            long vertexBuffer = Objc.msgSendIdLongLong(device, Objc.selector("newBufferWithLength:options:"), 36L, 0L);
            try {
                long geomDesc = Objc.msgSendId(
                        Objc.getClass("MTLAccelerationStructureTriangleGeometryDescriptor"), Objc.selector("descriptor"));
                Objc.msgSendVoid(geomDesc, Objc.selector("setVertexBuffer:"), vertexBuffer);
                Objc.msgSendVoidLong(geomDesc, Objc.selector("setVertexStride:"), 12L);
                Objc.msgSendVoidLong(geomDesc, Objc.selector("setTriangleCount:"), 1L);
                Objc.msgSendVoidBool(geomDesc, Objc.selector("setOpaque:"), true);
                long geomArray = Objc.msgSendId(Objc.getClass("NSArray"), Objc.selector("arrayWithObject:"), geomDesc);
                long primDesc = Objc.msgSendId(
                        Objc.getClass("MTLPrimitiveAccelerationStructureDescriptor"), Objc.selector("descriptor"));
                Objc.msgSendVoid(primDesc, Objc.selector("setGeometryDescriptors:"), geomArray);

                Objc.AccelerationStructureSizes sizes = Objc.accelerationStructureSizes(device, primDesc);
                assertTrue(sizes.accelerationStructureSize() > 0,
                        "a real geometry descriptor must need nonzero acceleration structure storage");

                long structure = Objc.msgSendId(
                        device, Objc.selector("newAccelerationStructureWithSize:"), sizes.accelerationStructureSize());
                assertNotEquals(0L, structure, "newAccelerationStructureWithSize: must not return nil");
                try {
                    long scratch = Objc.msgSendIdLongLong(device, Objc.selector("newBufferWithLength:options:"),
                            Math.max(sizes.buildScratchBufferSize(), 4L), 0L);
                    try {
                        long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
                        try {
                            long cb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                            long encoder = Objc.msgSendId(cb, Objc.selector("accelerationStructureCommandEncoder"));
                            assertNotEquals(0L, encoder, "accelerationStructureCommandEncoder must not return nil");
                            Objc.msgSendVoidIdIdIdLong(encoder,
                                    Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                                    structure, primDesc, scratch, 0L);
                            Objc.msgSendVoid(encoder, Objc.selector("endEncoding"));
                            Objc.msgSendVoid(cb, Objc.selector("commit"));
                            Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));
                            assertEquals(MTL_COMMAND_BUFFER_STATUS_COMPLETED, Objc.msgSendLong(cb, Objc.selector("status")));
                        } finally {
                            Objc.msgSendVoid(queue, Objc.selector("release"));
                        }
                    } finally {
                        Objc.msgSendVoid(scratch, Objc.selector("release"));
                    }
                } finally {
                    Objc.msgSendVoid(structure, Objc.selector("release"));
                }
            } finally {
                Objc.msgSendVoid(vertexBuffer, Objc.selector("release"));
            }
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }

    @Test
    void getBytesFromRegionReadsBackAKnownTextureValue() {
        Assumptions.assumeTrue(Objc.isLoaded());
        Assumptions.assumeTrue(MetalFxSupport.isAvailable());
        long device = MetalFxSupport.metalDevice();

        long pool = Objc.autoreleasePoolPush();
        try {
            long alloc = Objc.msgSendId(Objc.getClass("MTLTextureDescriptor"), Objc.selector("alloc"));
            long desc = Objc.msgSendId(alloc, Objc.selector("init"));
            Objc.msgSendVoidLong(desc, Objc.selector("setTextureType:"), 2L); // MTLTextureType2D
            Objc.msgSendVoidLong(desc, Objc.selector("setPixelFormat:"), 10L); // MTLPixelFormatR8Unorm
            Objc.msgSendVoidLong(desc, Objc.selector("setWidth:"), 1L);
            Objc.msgSendVoidLong(desc, Objc.selector("setHeight:"), 1L);
            Objc.msgSendVoidLong(desc, Objc.selector("setUsage:"), 2L); // MTLTextureUsageShaderWrite
            Objc.msgSendVoidLong(desc, Objc.selector("setStorageMode:"), 0L); // MTLStorageModeShared
            long texture = Objc.msgSendId(device, Objc.selector("newTextureWithDescriptor:"), desc);
            Objc.msgSendVoid(desc, Objc.selector("release"));
            assertNotEquals(0L, texture, "newTextureWithDescriptor: must not return nil");
            try {
                Objc.Result library = Objc.msgSendIdIdIdErr(device,
                        Objc.selector("newLibraryWithSource:options:error:"),
                        Objc.nsString("#include <metal_stdlib>\nusing namespace metal;\n"
                                + "kernel void fill(texture2d<float, access::write> t [[texture(0)]], "
                                + "uint2 gid [[thread_position_in_grid]]) { t.write(float4(1.0), gid); }"),
                        0L);
                assertNotEquals(0L, library.id(), "fill kernel must compile: " + library.error());
                try {
                    long function = Objc.msgSendId(library.id(), Objc.selector("newFunctionWithName:"), Objc.nsString("fill"));
                    try {
                        Objc.Result pipeline = Objc.msgSendIdIdErr(
                                device, Objc.selector("newComputePipelineStateWithFunction:error:"), function);
                        assertNotEquals(0L, pipeline.id());
                        try {
                            long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
                            try {
                                long cb = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                                long encoder = Objc.msgSendId(cb, Objc.selector("computeCommandEncoder"));
                                Objc.msgSendVoid(encoder, Objc.selector("setComputePipelineState:"), pipeline.id());
                                Objc.msgSendVoidIdLong(encoder, Objc.selector("setTexture:atIndex:"), texture, 0L);
                                Objc.dispatchThreadgroups(encoder, 1, 1, 1, 1, 1, 1);
                                Objc.msgSendVoid(encoder, Objc.selector("endEncoding"));
                                Objc.msgSendVoid(cb, Objc.selector("commit"));
                                Objc.msgSendVoid(cb, Objc.selector("waitUntilCompleted"));
                            } finally {
                                Objc.msgSendVoid(queue, Objc.selector("release"));
                            }
                        } finally {
                            Objc.msgSendVoid(pipeline.id(), Objc.selector("release"));
                        }
                    } finally {
                        Objc.msgSendVoid(function, Objc.selector("release"));
                    }
                } finally {
                    Objc.msgSendVoid(library.id(), Objc.selector("release"));
                }

                try (Arena local = Arena.ofConfined()) {
                    MemorySegment out = local.allocate(1);
                    Objc.getBytesFromRegion(texture, out.address(), 1L, 0, 0, 0, 1, 1, 1, 0L);
                    assertEquals(255, out.get(ValueLayout.JAVA_BYTE, 0) & 0xFF,
                            "the fill kernel wrote 1.0, which R8Unorm stores as byte 255");
                }
            } finally {
                Objc.msgSendVoid(texture, Objc.selector("release"));
            }
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }
}
