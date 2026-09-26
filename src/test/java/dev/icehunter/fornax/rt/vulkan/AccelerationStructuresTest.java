package dev.icehunter.fornax.rt.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK13;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Building needs a device. Pinned here: the geometry contract the decode kernel and the builder
 * share (three floats a vertex, 12-byte stride, no index buffer), and which geometry is opaque. An
 * opaque triangle geometry ends traversal before the kernel's alpha test, so cutout foliage would
 * block light like stone, and every ray would still report a hit.
 */
class AccelerationStructuresTest {

    private static final Path SOURCE =
            Path.of("src/main/java/dev/icehunter/fornax/rt/vulkan/AccelerationStructures.java");

    @Test
    void vertexLayoutIsThreeFloatsAtATwelveByteStride() {
        assertEquals(VK13.VK_FORMAT_R32G32B32_SFLOAT, AccelerationStructures.VERTEX_FORMAT);
        assertEquals(12, AccelerationStructures.VERTEX_STRIDE_BYTES);
        assertEquals(3, AccelerationStructures.VERTICES_PER_TRIANGLE);
    }

    @Test
    void solidGeometryIsOpaqueAndCutoutGeometryIsNot() throws IOException {
        assertEquals(KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR, AccelerationStructures.triangleGeometryFlags(true));
        assertEquals(0, AccelerationStructures.triangleGeometryFlags(false));
        // The flag reaches a geometry only through that one decision, and the sizes query takes
        // the same decision as the build: a flag that differs between the two is undefined behaviour.
        String source = Files.readString(SOURCE);
        assertEquals(1, source.split("KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR", -1).length - 1,
                "the opaque flag is spelled once, in triangleGeometryFlags");
        assertTrue(source.contains(".flags(triangleGeometryFlags(opaque))"));
        assertTrue(source.contains("blasSizes(VulkanDevice device, int triangleCount, boolean opaque)"));
        assertTrue(source.contains("int triangleCount, long scratchAddress, boolean opaque)"));
        String tracer = Files.readString(SOURCE.resolveSibling("MeshVulkanTracer.java"));
        assertEquals(2, tracer.split(java.util.regex.Pattern.quote("!source.key().cutout()"), -1).length - 1,
                "a mesh is opaque exactly when it is not the cutout pass, for sizing and for building");
    }

    @Test
    void buildsPreferFastTraceBecauseAStructureIsBuiltOnceAndTracedManyFrames() {
        assertEquals(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR,
                AccelerationStructures.BUILD_FLAGS);
    }

    @Test
    void maxVertexIsTheLastIndexOfAnUnindexedTriangleList() {
        assertEquals(2, AccelerationStructures.maxVertex(1));
        assertEquals(29, AccelerationStructures.maxVertex(10));
        assertThrows(IllegalArgumentException.class, () -> AccelerationStructures.maxVertex(0));
        assertThrows(ArithmeticException.class, () -> AccelerationStructures.maxVertex(Integer.MAX_VALUE));
    }

    @Test
    void scratchAlignmentIsAPowerOfTwoCheck() {
        assertTrue(AccelerationStructures.aligned(0, 128));
        assertTrue(AccelerationStructures.aligned(256, 128));
        assertFalse(AccelerationStructures.aligned(64, 128));
        assertThrows(IllegalArgumentException.class, () -> AccelerationStructures.aligned(0, 96));
        assertThrows(IllegalArgumentException.class, () -> AccelerationStructures.aligned(0, 0));
    }

    @Test
    void structureStorageIsRoundedUpToAFillableSize() {
        assertEquals(4, AccelerationStructures.roundUp4(1));
        assertEquals(4, AccelerationStructures.roundUp4(4));
        assertEquals(8, AccelerationStructures.roundUp4(5));
        assertEquals(0, AccelerationStructures.roundUp4(0));
    }

    @Test
    void aSizesRecordNeedsAStructureButMayNeedNoScratch() {
        assertEquals(0, new AccelerationStructures.Sizes(64, 0).buildScratchBytes());
        assertThrows(IllegalArgumentException.class, () -> new AccelerationStructures.Sizes(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AccelerationStructures.Sizes(64, -1));
    }

    @Test
    void theInstanceGeometryIsAFlatArrayNotAnArrayOfPointers() throws IOException {
        // InstanceRecord writes contiguous 64-byte records. Read as a pointer array, each
        // record's first 8 bytes are an address, and nothing lands where the meshes are.
        assertTrue(Files.readString(SOURCE).contains(".arrayOfPointers(false)"));
    }
}
