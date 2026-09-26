package dev.icehunter.fornax.rt.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

import java.nio.LongBuffer;

/**
 * Bottom- and top-level acceleration structures over the geometry the Vulkan ray tiers decode:
 * sizing, creation, the build commands and the barriers around them.
 *
 * <p>Two geometry shapes only. A bottom-level structure is a triangle list of {@code
 * VK_FORMAT_R32G32B32_SFLOAT} positions at a 12-byte stride with no index buffer, which is exactly
 * what the mesh decode kernel writes (three vertices per triangle, in order). A top-level
 * structure is a flat array of {@link InstanceRecord}s. Nothing here knows what a mesh is.
 *
 * <p>Triangle geometry is {@code VK_GEOMETRY_OPAQUE_BIT_KHR} only for a mesh with no cutout
 * faces. An opaque geometry ends the traversal at the first triangle without ever surfacing it as
 * a candidate, so the kernel's alpha test never runs: for a solid mesh that is the fast path and
 * the right answer, for a leaf or a pane it would block light like stone. A cutout mesh is
 * non-opaque, reaches the query loop and is confirmed or not by the shader, which is where this
 * engine keeps that decision.
 *
 * <p>Render-thread confined. Handles are plain {@code long}s; a {@link Structure} owns its storage
 * buffer and nothing else, and the scratch buffer belongs to whoever records the build.
 */
public final class AccelerationStructures {

    /** {@code VK_FORMAT_R32G32B32_SFLOAT}: three floats a vertex, which every driver accepts. */
    public static final int VERTEX_FORMAT = VK13.VK_FORMAT_R32G32B32_SFLOAT;
    public static final long VERTEX_STRIDE_BYTES = 12;
    public static final int VERTICES_PER_TRIANGLE = 3;

    /** {@code OPAQUE} for a solid mesh, nothing for a cutout one; never {@code NO_DUPLICATE_ANY_HIT}.
     * See the class doc. */
    public static int triangleGeometryFlags(boolean opaque) {
        return opaque ? KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR : 0;
    }

    /** Built once per mesh revision and traced many frames: trace speed over build speed. */
    public static final int BUILD_FLAGS = KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;

    public record Sizes(long structureBytes, long buildScratchBytes) {
        public Sizes {
            if (structureBytes <= 0 || buildScratchBytes < 0) {
                throw new IllegalArgumentException("a structure has size; scratch may be zero");
            }
        }
    }

    /** One structure and the buffer it lives in. {@code deviceAddress} is what an instance record
     * references (for a BLAS) or what nothing references (for a TLAS, which is bound instead). */
    public record Structure(long handle, RtBuffer storage, long deviceAddress, int type) {
        public Structure {
            if (handle == 0 || deviceAddress == 0) {
                throw new IllegalArgumentException("a Structure carries a live handle and address");
            }
            java.util.Objects.requireNonNull(storage, "storage");
        }
    }

    private AccelerationStructures() {
    }

    /** The highest vertex index a triangle list of {@code triangleCount} references. */
    static int maxVertex(int triangleCount) {
        if (triangleCount <= 0) {
            throw new IllegalArgumentException("a triangle geometry has at least one triangle, got " + triangleCount);
        }
        return Math.multiplyExact(triangleCount, VERTICES_PER_TRIANGLE) - 1;
    }

    /** Whether {@code address} satisfies {@code alignment}, a power of two. */
    static boolean aligned(long address, int alignment) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("alignment must be a power of two, got " + alignment);
        }
        return (address & (alignment - 1)) == 0;
    }

    /** {@code minAccelerationStructureScratchOffsetAlignment}: a scratch address off this alignment
     * is undefined behaviour, not an error the driver reports. Query once and check every build. */
    public static int scratchAlignment(VulkanDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceAccelerationStructurePropertiesKHR props =
                    VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType$Default()
                    .pNext(props);
            VK11.vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(), props2);
            int alignment = props.minAccelerationStructureScratchOffsetAlignment();
            if (alignment <= 0) {
                throw new IllegalStateException("driver reports scratch alignment " + alignment);
            }
            return alignment;
        }
    }

    /** {@code opaque} must match the build: the flag is part of what is sized. */
    public static Sizes blasSizes(VulkanDevice device, int triangleCount, boolean opaque) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = triangleGeometry(stack, 0L, triangleCount, opaque);
            VkAccelerationStructureBuildGeometryInfoKHR info = buildInfo(stack,
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, geometry);
            return querySizes(device, stack, info, triangleCount);
        }
    }

    public static Sizes tlasSizes(VulkanDevice device, int instanceCount) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = instanceGeometry(stack, 0L);
            VkAccelerationStructureBuildGeometryInfoKHR info = buildInfo(stack,
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, geometry);
            return querySizes(device, stack, info, instanceCount);
        }
    }

    /** Allocates storage and creates the structure object over it. Nothing is built yet. */
    public static Structure create(RtAllocator allocator, int type, Sizes sizes, String what) {
        VulkanDevice device = allocator.device();
        RtBuffer storage = RtBuffer.create(allocator, roundUp4(sizes.structureBytes()),
                KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, what + " storage");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureCreateInfoKHR createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .buffer(storage.buffer())
                    .offset(0)
                    .size(sizes.structureBytes())
                    .type(type);
            LongBuffer out = stack.mallocLong(1);
            int result = KHRAccelerationStructure.vkCreateAccelerationStructureKHR(device.vkDevice(), createInfo, null, out);
            if (result != VK13.VK_SUCCESS) {
                storage.destroy(allocator);
                throw new IllegalStateException("vkCreateAccelerationStructureKHR (" + what + ") failed: " + result);
            }
            long handle = out.get(0);
            VkAccelerationStructureDeviceAddressInfoKHR addressInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType$Default()
                    .accelerationStructure(handle);
            long address = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(device.vkDevice(), addressInfo);
            if (address == 0) {
                KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device.vkDevice(), handle, null);
                storage.destroy(allocator);
                throw new IllegalStateException("vkGetAccelerationStructureDeviceAddressKHR (" + what + ") returned 0");
            }
            return new Structure(handle, storage, address, type);
        }
    }

    /**
     * Records a full build of a bottom-level structure over {@code triangleCount} triangles whose
     * positions start at {@code positionsAddress}. The positions must be written and visible to the
     * build stage before this command: see {@link #barrierComputeToBuild}.
     */
    public static void recordBlasBuild(VkCommandBuffer cmd, Structure blas, long positionsAddress,
            int triangleCount, long scratchAddress, boolean opaque) {
        requireType(blas, KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, "BLAS build");
        requireAddress(positionsAddress, "positions");
        requireAddress(scratchAddress, "scratch");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = triangleGeometry(stack, positionsAddress, triangleCount, opaque);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            info.get(0).set(buildInfo(stack, blas.type(), geometry))
                    .dstAccelerationStructure(blas.handle())
                    .scratchData(it -> it.deviceAddress(scratchAddress));
            recordBuild(cmd, stack, info, triangleCount);
        }
    }

    /**
     * Records a full build of a top-level structure over {@code instanceCount} {@link
     * InstanceRecord}s at {@code instancesAddress}. The records must be uploaded and the bottom-level
     * structures they reference built before this command: {@link #barrierTransferToBuild} and
     * {@link #barrierBuildToBuild}.
     */
    public static void recordTlasBuild(VkCommandBuffer cmd, Structure tlas, long instancesAddress,
            int instanceCount, long scratchAddress) {
        requireType(tlas, KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, "TLAS build");
        requireAddress(instancesAddress, "instances");
        requireAddress(scratchAddress, "scratch");
        if (instanceCount <= 0) {
            throw new IllegalArgumentException("a top-level build places at least one instance, got " + instanceCount);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = instanceGeometry(stack, instancesAddress);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            info.get(0).set(buildInfo(stack, tlas.type(), geometry))
                    .dstAccelerationStructure(tlas.handle())
                    .scratchData(it -> it.deviceAddress(scratchAddress));
            recordBuild(cmd, stack, info, instanceCount);
        }
    }

    /** Compute-shader writes (decoded positions) become readable by a structure build. */
    public static void barrierComputeToBuild(VkCommandBuffer cmd) {
        memoryBarrier(cmd, VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_SHADER_WRITE_BIT,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
    }

    /** Transfer writes (uploaded instance records, copied vertices) become readable by a build. */
    public static void barrierTransferToBuild(VkCommandBuffer cmd) {
        memoryBarrier(cmd, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, VK13.VK_ACCESS_TRANSFER_WRITE_BIT,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
    }

    /** Bottom-level builds become readable by the top-level build that references them. */
    public static void barrierBuildToBuild(VkCommandBuffer cmd) {
        memoryBarrier(cmd, KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
    }

    /** A finished top-level build becomes traversable by a compute shader's ray queries. */
    public static void barrierBuildToCompute(VkCommandBuffer cmd) {
        memoryBarrier(cmd, KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
    }

    /** Destroys the structure and frees its storage. No submitted work may still reference either. */
    public static void destroy(RtAllocator allocator, Structure structure) {
        KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(allocator.device().vkDevice(), structure.handle(), null);
        structure.storage().destroy(allocator);
    }

    // --- private -----------------------------------------------------------------------------------

    private static VkAccelerationStructureGeometryKHR.Buffer triangleGeometry(MemoryStack stack,
            long positionsAddress, int triangleCount, boolean opaque) {
        int maxVertex = maxVertex(triangleCount);
        VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.get(0)
                .sType$Default()
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(triangleGeometryFlags(opaque))
                .geometry(data -> data.triangles(triangles -> triangles
                        .sType$Default()
                        .vertexFormat(VERTEX_FORMAT)
                        .vertexData(it -> it.deviceAddress(positionsAddress))
                        .vertexStride(VERTEX_STRIDE_BYTES)
                        .maxVertex(maxVertex)
                        .indexType(KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR)));
        return geometry;
    }

    private static VkAccelerationStructureGeometryKHR.Buffer instanceGeometry(MemoryStack stack, long instancesAddress) {
        VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.get(0)
                .sType$Default()
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(0)
                .geometry(data -> data.instances(instances -> instances
                        .sType$Default()
                        .arrayOfPointers(false)
                        .data(it -> it.deviceAddress(instancesAddress))));
        return geometry;
    }

    private static VkAccelerationStructureBuildGeometryInfoKHR buildInfo(MemoryStack stack, int type,
            VkAccelerationStructureGeometryKHR.Buffer geometry) {
        return VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                .sType$Default()
                .type(type)
                .flags(BUILD_FLAGS)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1)
                .pGeometries(geometry);
    }

    private static Sizes querySizes(VulkanDevice device, MemoryStack stack,
            VkAccelerationStructureBuildGeometryInfoKHR info, int primitiveCount) {
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(device.vkDevice(),
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                info, stack.ints(primitiveCount), sizes);
        return new Sizes(sizes.accelerationStructureSize(), sizes.buildScratchSize());
    }

    private static void recordBuild(VkCommandBuffer cmd, MemoryStack stack,
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info, int primitiveCount) {
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(primitiveCount).primitiveOffset(0).firstVertex(0).transformOffset(0);
        PointerBuffer ranges = stack.pointers(range.address());
        KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(cmd, info, ranges);
    }

    private static void memoryBarrier(VkCommandBuffer cmd, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            VK13.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, barrier, null, null);
        }
    }

    private static void requireType(Structure structure, int type, String during) {
        if (structure.type() != type) {
            throw new IllegalArgumentException(during + " given a structure of type " + structure.type());
        }
    }

    private static void requireAddress(long address, String what) {
        if (address == 0) {
            throw new IllegalArgumentException(what + " address is 0");
        }
    }

    /** Structure sizes come from the driver and need not be 4-byte multiples; the storage buffer must. */
    static long roundUp4(long bytes) {
        return (bytes + 3) & ~3L;
    }
}
