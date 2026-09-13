package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.voxel.BrickGridUpload;
import dev.icehunter.fornax.voxel.VoxelWindow;
import dev.icehunter.fornax.voxel.SectionHarvester;
import dev.icehunter.fornax.voxel.RtSectionGeometry;
import dev.icehunter.fornax.voxel.VoxelShapeClassifier;
import net.minecraft.core.SectionPos;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Per-slot Metal ray tracing geometry: the {@code rt_expand} dispatch that turns one slot's brick
 * grid into triangles, one primitive acceleration structure per slot built from that output, and
 * one instance acceleration structure per frame that places every slot's primitive structure at
 * its grid-space position.
 *
 * <p>Static state, one instance for the process, matching {@link MetalRtGeometry} and every other
 * class in this package: there is one ray-tracing pass and one Metal device, and every caller
 * (the shadow pass, the smoke test) runs on a single thread at a time.
 *
 * <p>An acceleration structure and its build scratch buffer are always rebuilt from scratch
 * (release the old id, allocate a new one at the exact required size) rather than resized in
 * place: builds only happen for slots the dirty set named, capped at {@link
 * #MAX_DIRTY_PER_FRAME} per frame, so churn is bounded and the simpler code is worth the small
 * extra allocation cost. A scratch buffer is released immediately after the {@code
 * buildAccelerationStructure:} call that references it: Metal retains any resource an encoder
 * references for as long as the command buffer that encoded it is still executing, so releasing
 * this class's own reference right after encoding does not free the memory the GPU is still
 * building into.
 *
 * <p>This class allocates and releases one scratch buffer per build rather than keeping a pool of
 * them: at this milestone's dirty-slot cadence (capped at {@link #MAX_DIRTY_PER_FRAME} per frame,
 * and in practice far less once the window settles), a pool would trade a documented, bounded
 * allocation cost for cache-management code that nothing yet needs.
 */
public final class MetalRtAcceleration {
    /** Initial capacity, retained from the original allocation: 4096 triangles need 160 KiB
     * of vertex/primitive storage. Sparse slots keep this allocation; overflowing slots grow to
     * their measured requirement and retain that storage until eviction. This is never a limit
     * on which geometry may cast a shadow. */
    static final int RT_INITIAL_TRIS_PER_SLOT = 4096;

    /** Shader-derived upper bound: every cell can contain at most MAX_BOXES, each with six
     * faces and two triangles per face. Larger counter values indicate corrupt input or execution,
     * not a section whose real geometry should be discarded. */
    private static final int MAX_REQUIRED_TRIANGLES =
            BrickGridUpload.VOXELS_PER_SECTION * VoxelShapeClassifier.MAX_BOXES * 6 * 2;

    /** One vertex position: 3 packed floats, matching the geometry descriptor's {@code
     * vertexStride} and {@code rt_expand.metal}'s {@code write_triangle}. */
    static final long VERTEX_STRIDE_BYTES = 12;
    static final long VERTEX_BYTES_PER_SLOT = (long) RT_INITIAL_TRIS_PER_SLOT * 3 * VERTEX_STRIDE_BYTES;

    /** One packed {@code uint} of per-triangle data, matching {@code rt_expand.metal}'s {@code
     * primitiveOut}. */
    static final long PRIMITIVE_DATA_ELEMENT_BYTES = 4;
    static final long PRIMITIVE_DATA_BYTES_PER_SLOT = (long) RT_INITIAL_TRIS_PER_SLOT * PRIMITIVE_DATA_ELEMENT_BYTES;

    /** {@code counters[0]} (triangle count) and {@code counters[1]} (overflow count), matching
     * {@code rt_expand.metal}'s {@code counters} buffer. */
    static final long COUNTERS_BYTES = 8;

    /** Threads per {@code rt_expand} threadgroup: one threadgroup covers one slot's 4096
     * voxels; 1024 stays under the common {@code maxTotalThreadsPerThreadgroup} of 1024 on every
     * Metal device this pass targets. The kernel reads this back from the dispatch itself ({@code
     * [[threads_per_threadgroup]]}), so it stays correct for any value here. */
    static final long THREADS_PER_GROUP = 1024;

    /** Apple's own guidance for rebuilding an instance acceleration structure every frame is a few
     * thousand instances (see {@link MetalRtGeometry}'s own "capped at" javadoc); this bounds how
     * much CPU-blocking expand work (a {@code waitUntilCompleted} to read back each slot's
     * triangle count) one frame can be asked to do, independent of how many slots came due at
     * once. Slots past this cap stay marked dirty for the next frame. */
    static final int MAX_DIRTY_PER_FRAME = 64;

    /** {@code MTLAccelerationStructureInstanceDescriptor} (MTLAccelerationStructureTypes.h on this
     * machine): a {@code MTLPackedFloat4x3} transform (4 columns of 3 packed floats, 48 bytes, no
     * padding; column 3 is the translation) followed by four {@code uint32_t} fields (options,
     * mask, intersectionFunctionTableOffset, accelerationStructureIndex), 16 bytes, for 64 bytes
     * total:
     * <pre>
     *   0  transformationMatrix.columns[0]  float3   12 bytes
     *   12 transformationMatrix.columns[1]  float3   12 bytes
     *   24 transformationMatrix.columns[2]  float3   12 bytes
     *   36 transformationMatrix.columns[3]  float3   12 bytes  (translation)
     *   48 options                          uint32    4 bytes
     *   52 mask                             uint32    4 bytes
     *   56 intersectionFunctionTableOffset  uint32    4 bytes
     *   60 accelerationStructureIndex       uint32    4 bytes
     *   64 total
     * </pre>
     */
    static final long INSTANCE_DESCRIPTOR_BYTES = 64;

    /** {@code MTLResourceUsageRead} (MTLCommandEncoder.h on this machine): {@code 1 << 0}. */
    private static final long MTL_RESOURCE_USAGE_READ = 1;

    private static final class SlotAccel {
        long vertexBuffer;
        long primitiveDataBuffer;
        long countersBuffer;
        long accelerationStructure;
        int triangleCapacity;
        SectionPos owner;
        long supplementalVertexBuffer;
        long supplementalPrimitiveBuffer;
        int supplementalTriangles;
        boolean representationExact;
    }

    private static final Map<Integer, SlotAccel> slots = new HashMap<>();

    private static long instanceStructure;
    /** Instance index -> compact exported slot index, one uint per instance, matching {@link #instanceStructure}'s
     * own instance order exactly (rebuilt alongside it, never independently). {@code rt_trace}'s
     * cutout alpha test needs the compact exported slot index to address {@link MetalRtGeometry}'s palette and
     * face-texture buffers, which are laid out by compact slot index across the whole exported window,
     * not by an instance's own position in this frame's (typically much smaller) active list;
     * {@code query.get_candidate_instance_id()} returns the latter. */
    private static long instanceSlotMapBuffer;
    private static VoxelWindow.WindowState lastInstanceWindow;
    private static long geometryStamp;
    private static long instancesBuiltAtGeometryStamp = -1;

    private MetalRtAcceleration() {
    }

    /**
     * For up to {@link #MAX_DIRTY_PER_FRAME} of the slots {@code dirtySlots} names (already
     * drained from {@link MetalRtGeometry} by the caller: this class never drains that set
     * itself, so the caller's Vulkan geometry copy and this class's expand dispatch always agree
     * on exactly which slots changed this frame): dispatches {@code rt_expand} into that slot's
     * own vertex/primitive-data buffers (allocated lazily, released once the slot leaves {@code
     * populatedSlots}), waits for that batch to complete, reads back each slot's required triangle
     * count, grows and re-expands any overflowing slots, then rebuilds that slot's primitive
     * acceleration structure (or drops it, for a slot that came back with zero triangles).
     * Slots past the cap are re-marked dirty on {@link
     * MetalRtGeometry} for the next frame. A no-op when {@code dirtySlots} is empty.
     *
     * <p>{@code waitEvent}/{@code waitValue}: when {@code waitEvent} is nonzero, the expand
     * command buffer, the first Metal work this frame to read the exported geometry buffers,
     * calls {@code encodeWaitForEvent:value:} for it before dispatching, so it can never run ahead
     * of the Vulkan copy that wrote those buffers. Pass 0 when the caller has no cross-API
     * timeline to wait on (a Metal-only caller, e.g. the smoke test).
     */
    public static void rebuildDirty(long commandQueue, MetalRtShaders.Compiled compiled,
            List<Integer> dirtySlots, Map<Integer, SectionPos> populatedSlots,
            long waitEvent, long waitValue) {
        rebuildDirty(commandQueue, compiled, dirtySlots, populatedSlots, waitEvent, waitValue, Map.of());
    }

    /** Captured results must come from the same queue-lock interval as the exported palette copy.
     * Looking them up here could pair a newer CPU model with an older copied palette and BLAS. */
    public static void rebuildDirty(long commandQueue, MetalRtShaders.Compiled compiled,
            List<Integer> dirtySlots, Map<Integer, SectionPos> populatedSlots,
            long waitEvent, long waitValue, Map<Integer, SectionHarvester.Result> capturedSections) {
        if (releaseSlotsNotPopulated(populatedSlots)) {
            geometryStamp++;
        }
        if (dirtySlots.isEmpty()) {
            return;
        }
        List<Integer> capped = dirtySlots;
        if (capped.size() > MAX_DIRTY_PER_FRAME) {
            for (int slot : capped.subList(MAX_DIRTY_PER_FRAME, capped.size())) {
                MetalRtGeometry.markDirty(slot);
            }
            capped = capped.subList(0, MAX_DIRTY_PER_FRAME);
        }
        // Source keys belong to the larger tier-0 window. Only centered-domain members
        // have compact exported offsets; never bind the source index as a destination offset.
        List<Integer> toExpand = new ArrayList<>();
        for (int slot : capped) {
            if (populatedSlots.containsKey(slot) && MetalRtGeometry.slotInExportedWindow(slot)) {
                toExpand.add(slot);
            }
        }
        if (toExpand.isEmpty()) {
            return;
        }

        long device = Objc.msgSendId(commandQueue, Objc.selector("device"));
        long pool = Objc.autoreleasePoolPush();
        try {
            for (int slot : toExpand) {
                ensureSlotBuffers(device, slot);
                SectionHarvester.Result captured = capturedSections.get(slot);
                uploadSupplement(device, slots.get(slot), captured == null
                        ? RtSectionGeometry.UNKNOWN : captured.rtGeometry());
            }

            expandSlots(commandQueue, compiled.expand(), toExpand, waitEvent, waitValue);
            List<Integer> retry = new ArrayList<>();
            for (int slot : toExpand) {
                SlotAccel accel = slots.get(slot);
                int required = readRequiredTriangleCount(accel.countersBuffer, slot);
                if (required > accel.triangleCapacity) {
                    growSlotBuffers(device, accel, required);
                    retry.add(slot);
                }
            }
            // The exported source is unchanged between dispatches. Its first count is therefore
            // the exact allocation required for a complete retry; never build the truncated prefix.
            if (!retry.isEmpty()) {
                expandSlots(commandQueue, compiled.expand(), retry, 0, 0);
            }
            // Validate the entire batch before encoding any replacement acceleration structures.
            Map<Integer, Integer> triangleCounts = new HashMap<>();
            for (int slot : toExpand) {
                SlotAccel accel = slots.get(slot);
                int count = readCompleteTriangleCount(accel.countersBuffer, slot);
                if (count > accel.triangleCapacity) {
                    throw new IllegalStateException("Metal RT slot " + slot + " exceeds its triangle capacity after expansion");
                }
                triangleCounts.put(slot, count);
            }

            long buildCb = Objc.msgSendId(commandQueue, Objc.selector("commandBuffer"));
            if (buildCb == 0) {
                throw new IllegalStateException("Metal command buffer nil (rt acceleration structure build)");
            }
            long buildEncoder = Objc.msgSendId(buildCb, Objc.selector("accelerationStructureCommandEncoder"));
            if (buildEncoder == 0) {
                throw new IllegalStateException("Metal acceleration structure encoder nil");
            }
            try {
                for (int slot : toExpand) {
                    SlotAccel accel = slots.get(slot);
                    int count = triangleCounts.get(slot);
                    rebuildSlotAccelerationStructure(device, buildEncoder, accel, count);
                    accel.owner = populatedSlots.get(slot);
                }
            } finally {
                Objc.msgSendVoid(buildEncoder, Objc.selector("endEncoding"));
            }
            Objc.msgSendVoid(buildCb, Objc.selector("commit"));
            geometryStamp++;
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }

    /** Waits for expansion before its counters are read or its output buffers can be grown. */
    private static void expandSlots(long commandQueue, MetalRtShaders.CompiledKernel expand,
            List<Integer> toExpand, long waitEvent, long waitValue) {
        long expandCb = Objc.msgSendId(commandQueue, Objc.selector("commandBuffer"));
        if (expandCb == 0) {
            throw new IllegalStateException("Metal command buffer nil (rt_expand)");
        }
        if (waitEvent != 0) {
            Objc.msgSendVoidIdLong(expandCb, Objc.selector("encodeWaitForEvent:value:"), waitEvent, waitValue);
        }
        long expandEncoder = Objc.msgSendId(expandCb, Objc.selector("computeCommandEncoder"));
        if (expandEncoder == 0) {
            throw new IllegalStateException("Metal compute encoder nil (rt_expand)");
        }
        // Every encoder-mutating call runs inside this try: a throw here (a bad buffer id, a
        // nil pipeline) must still reach endEncoding, or Metal aborts the process on
        // -[_MTLCommandEncoder dealloc] when the autorelease pool below releases an open
        // encoder.
        try {
            for (int slot : toExpand) {
                SlotAccel accel = slots.get(slot);
                encodeExpand(expandEncoder, expand,
                        MetalRtGeometry.occupancy().mtlBuffer(),
                        MetalRtGeometry.slotOffset(BrickGridUpload.OCCUPANCY_TARGET, MetalRtGeometry.exportedSlot(slot)),
                        MetalRtGeometry.payload().mtlBuffer(),
                        MetalRtGeometry.slotOffset(BrickGridUpload.PAYLOAD_TARGET, MetalRtGeometry.exportedSlot(slot)),
                        MetalRtGeometry.faceSeal().mtlBuffer(),
                        MetalRtGeometry.slotOffset(BrickGridUpload.FACE_SEAL_TARGET, MetalRtGeometry.exportedSlot(slot)),
                        MetalRtGeometry.palette().mtlBuffer(),
                        MetalRtGeometry.slotOffset(BrickGridUpload.PALETTE_TARGET, MetalRtGeometry.exportedSlot(slot)),
                        accel.vertexBuffer, accel.primitiveDataBuffer, accel.countersBuffer, accel.triangleCapacity, true);
            }
        } finally {
            Objc.msgSendVoid(expandEncoder, Objc.selector("endEncoding"));
        }
        Objc.msgSendVoid(expandCb, Objc.selector("commit"));
        Objc.msgSendVoid(expandCb, Objc.selector("waitUntilCompleted"));
        // MTLCommandBufferStatusCompleted = 4 (Metal's command-buffer API). A failed GPU
        // dispatch must not turn zeroed or partially written counters into valid scene geometry.
        if (Objc.msgSendLong(expandCb, Objc.selector("status")) != 4L) {
            throw new IllegalStateException("Metal rt_expand command buffer did not complete successfully");
        }
    }

    /**
     * Rebuilds the frame's instance acceleration structure, one instance per slot that currently
     * has a built primitive structure, when the window moved or {@link #rebuildDirty} changed
     * anything since the last call; otherwise a no-op. Each instance's transform places its slot's
     * geometry at {@code (sectionPos - first) * 16} in grid space, {@code first} being the
     * window's minimum-corner section per axis ({@code center - radius}), matching this engine's
     * grid-space convention.
     */
    public static void rebuildInstances(long commandQueue, Map<Integer, SectionPos> populatedSlots) {
        VoxelWindow.WindowState window = MetalRtGeometry.exportedWindow();
        List<Integer> active = new ArrayList<>();
        for (Map.Entry<Integer, SlotAccel> e : slots.entrySet()) {
            if (e.getValue().accelerationStructure != 0 && populatedSlots.containsKey(e.getKey())) {
                active.add(e.getKey());
            }
        }
        Collections.sort(active);

        boolean unchanged = window.equals(lastInstanceWindow) && geometryStamp == instancesBuiltAtGeometryStamp;
        if (unchanged) {
            return;
        }
        lastInstanceWindow = window;
        instancesBuiltAtGeometryStamp = geometryStamp;

        long device = Objc.msgSendId(commandQueue, Objc.selector("device"));
        long pool = Objc.autoreleasePoolPush();
        try {
            if (instanceStructure != 0) {
                Objc.msgSendVoid(instanceStructure, Objc.selector("release"));
                instanceStructure = 0;
            }
            if (instanceSlotMapBuffer != 0) {
                Objc.msgSendVoid(instanceSlotMapBuffer, Objc.selector("release"));
                instanceSlotMapBuffer = 0;
            }
            if (active.isEmpty()) {
                return;
            }

            int firstX = window.centerX() - window.radius();
            int firstY = window.centerY() - window.radius();
            int firstZ = window.centerZ() - window.radius();

            long descriptorBytes = (long) active.size() * INSTANCE_DESCRIPTOR_BYTES;
            long descriptorBuffer = createBuffer(device, descriptorBytes);
            // Owned from here on: released in the finally below on every exit, not only the
            // success path, so a throw anywhere in this block cannot leak it.
            try {
                long contents = Objc.msgSendId(descriptorBuffer, Objc.selector("contents"));
                MemorySegment seg = MemorySegment.ofAddress(contents).reinterpret(descriptorBytes);
                for (int i = 0; i < active.size(); i++) {
                    SectionPos owner = populatedSlots.get(active.get(i));
                    float tx = (owner.x() - firstX) * 16.0f;
                    float ty = (owner.y() - firstY) * 16.0f;
                    float tz = (owner.z() - firstZ) * 16.0f;
                    writeInstanceDescriptor(seg, i, tx, ty, tz, i);
                }

                instanceSlotMapBuffer = createBuffer(device, (long) active.size() * Integer.BYTES);
                long slotMapContents = Objc.msgSendId(instanceSlotMapBuffer, Objc.selector("contents"));
                MemorySegment slotMapSeg = MemorySegment.ofAddress(slotMapContents)
                        .reinterpret((long) active.size() * Integer.BYTES);
                for (int i = 0; i < active.size(); i++) {
                    slotMapSeg.setAtIndex(ValueLayout.JAVA_INT, i, MetalRtGeometry.exportedSlot(active.get(i)));
                }

                try (Arena local = Arena.ofConfined()) {
                    MemorySegment structures = local.allocate((long) active.size() * ValueLayout.JAVA_LONG.byteSize());
                    for (int i = 0; i < active.size(); i++) {
                        structures.setAtIndex(ValueLayout.JAVA_LONG, i, slots.get(active.get(i)).accelerationStructure);
                    }
                    long structuresArray = Objc.msgSendIdPtrLong(Objc.getClass("NSArray"),
                            Objc.selector("arrayWithObjects:count:"), structures.address(), active.size());

                    long instDesc = Objc.msgSendId(
                            Objc.getClass("MTLInstanceAccelerationStructureDescriptor"), Objc.selector("descriptor"));
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstanceDescriptorBuffer:"), descriptorBuffer);
                    Objc.msgSendVoidLong(instDesc, Objc.selector("setInstanceCount:"), active.size());
                    Objc.msgSendVoid(instDesc, Objc.selector("setInstancedAccelerationStructures:"), structuresArray);

                    Objc.AccelerationStructureSizes sizes = Objc.accelerationStructureSizes(device, instDesc);
                    instanceStructure = Objc.msgSendId(device,
                            Objc.selector("newAccelerationStructureWithSize:"), sizes.accelerationStructureSize());
                    if (instanceStructure == 0) {
                        throw new IllegalStateException("newAccelerationStructureWithSize: (instance) returned nil");
                    }
                    // Scratch is allocated fresh for this one build and released right after the
                    // build is encoded (below); the destination structure is the only thing this
                    // build needs to outlive it.
                    long scratch = createBuffer(device, Math.max(sizes.buildScratchBufferSize(), 4L));

                    long cb = Objc.msgSendId(commandQueue, Objc.selector("commandBuffer"));
                    if (cb == 0) {
                        throw new IllegalStateException("Metal command buffer nil (instance acceleration structure build)");
                    }
                    long encoder = Objc.msgSendId(cb, Objc.selector("accelerationStructureCommandEncoder"));
                    if (encoder == 0) {
                        throw new IllegalStateException("Metal acceleration structure encoder nil (instance)");
                    }
                    try {
                        Objc.msgSendVoidIdIdIdLong(encoder,
                                Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                                instanceStructure, instDesc, scratch, 0L);
                    } finally {
                        Objc.msgSendVoid(encoder, Objc.selector("endEncoding"));
                    }
                    Objc.msgSendVoid(cb, Objc.selector("commit"));
                    Objc.msgSendVoid(scratch, Objc.selector("release"));
                }
            } finally {
                Objc.msgSendVoid(descriptorBuffer, Objc.selector("release"));
            }
        } finally {
            Objc.autoreleasePoolPop(pool);
        }
    }

    /** The frame's current instance acceleration structure, or 0 before the first build. */
    public static long instanceStructure() {
        return instanceStructure;
    }

    /** The frame's instance-index -> compact-slot-index buffer (one uint per instance, see this
     * class's own field javadoc), or 0 before the first build / whenever {@link #instanceStructure}
     * is 0: always rebuilt in lockstep with it. */
    public static long instanceSlotMap() {
        return instanceSlotMapBuffer;
    }

    /** Marks every live primitive structure, its vertex and primitive-data buffers, and the
     * instance structure as used by {@code computeEncoder}, required before a compute dispatch
     * traces against them: an intersector's hardware traversal reads a geometry's vertex and
     * primitive-data buffers directly, outside this kernel's own explicit buffer bindings, so
     * Metal's automatic residency tracking never sees that read on its own. {@code rt_trace} does
     * not read primitive data yet, but marking it here means the first pass that does (an
     * intersection function table payload, say) does not silently read unmapped memory. */
    public static void useResources(long computeEncoder) {
        for (SlotAccel accel : slots.values()) {
            if (accel.accelerationStructure == 0) {
                continue;
            }
            Objc.msgSendVoidIdLong(computeEncoder, Objc.selector("useResource:usage:"),
                    accel.accelerationStructure, MTL_RESOURCE_USAGE_READ);
            Objc.msgSendVoidIdLong(computeEncoder, Objc.selector("useResource:usage:"),
                    accel.vertexBuffer, MTL_RESOURCE_USAGE_READ);
            Objc.msgSendVoidIdLong(computeEncoder, Objc.selector("useResource:usage:"),
                    accel.primitiveDataBuffer, MTL_RESOURCE_USAGE_READ);
            if (accel.supplementalTriangles > 0) {
                Objc.msgSendVoidIdLong(computeEncoder, Objc.selector("useResource:usage:"),
                        accel.supplementalVertexBuffer, MTL_RESOURCE_USAGE_READ);
                Objc.msgSendVoidIdLong(computeEncoder, Objc.selector("useResource:usage:"),
                        accel.supplementalPrimitiveBuffer, MTL_RESOURCE_USAGE_READ);
            }
        }
        if (instanceStructure != 0) {
            Objc.msgSendVoidIdLong(computeEncoder, Objc.selector("useResource:usage:"),
                    instanceStructure, MTL_RESOURCE_USAGE_READ);
        }
    }

    /** Releases every per-slot buffer and structure, the instance structure, and resets the
     * change-tracking stamps. The pack-reload/shutdown teardown path. */
    public static void destroy() {
        for (SlotAccel accel : slots.values()) {
            releaseSlot(accel);
        }
        slots.clear();
        if (instanceStructure != 0) {
            Objc.msgSendVoid(instanceStructure, Objc.selector("release"));
            instanceStructure = 0;
        }
        if (instanceSlotMapBuffer != 0) {
            Objc.msgSendVoid(instanceSlotMapBuffer, Objc.selector("release"));
            instanceSlotMapBuffer = 0;
        }
        geometryStamp = 0;
        instancesBuiltAtGeometryStamp = -1;
        lastInstanceWindow = null;
    }

    /** Releases and drops any slot that is not populated, or that a diameter shrink has
     * moved outside the exported window since it was allocated (see {@link
     * MetalRtGeometry#slotInExportedWindow}: the same bound {@link #rebuildDirty} applies before
     * ever adding a slot here, checked again on the way out in case the window shrank under it). */
    private static boolean releaseSlotsNotPopulated(Map<Integer, SectionPos> populated) {
        boolean removedAny = false;
        Iterator<Map.Entry<Integer, SlotAccel>> it = slots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, SlotAccel> e = it.next();
            if (!java.util.Objects.equals(e.getValue().owner, populated.get(e.getKey()))
                    || !MetalRtGeometry.slotInExportedWindow(e.getKey())) {
                releaseSlot(e.getValue());
                it.remove();
                removedAny = true;
            }
        }
        return removedAny;
    }

    private static void releaseSlot(SlotAccel accel) {
        releaseIfNonzero(accel.accelerationStructure);
        releaseIfNonzero(accel.vertexBuffer);
        releaseIfNonzero(accel.primitiveDataBuffer);
        releaseIfNonzero(accel.countersBuffer);
        releaseIfNonzero(accel.supplementalVertexBuffer);
        releaseIfNonzero(accel.supplementalPrimitiveBuffer);
    }

    private static void releaseIfNonzero(long id) {
        if (id != 0) {
            Objc.msgSendVoid(id, Objc.selector("release"));
        }
    }

    private static void ensureSlotBuffers(long device, int slotIndex) {
        SlotAccel accel = slots.computeIfAbsent(slotIndex, s -> new SlotAccel());
        if (accel.countersBuffer == 0) {
            accel.countersBuffer = createBuffer(device, COUNTERS_BYTES);
            try {
                growSlotBuffers(device, accel, RT_INITIAL_TRIS_PER_SLOT);
            } catch (RuntimeException | Error e) {
                releaseIfNonzero(accel.countersBuffer);
                accel.countersBuffer = 0;
                throw e;
            }
        }
    }

    /** Allocate both replacements before releasing either old buffer, so an allocation failure
     * cannot leave a half-resized slot. Called only before its first dispatch or after expansion
     * completed; no GPU write may still target the old output storage. */
    private static void growSlotBuffers(long device, SlotAccel accel, int required) {
        if (required <= accel.triangleCapacity) {
            return;
        }
        long vertex = createBuffer(device, (long) required * 3 * VERTEX_STRIDE_BYTES);
        long primitive;
        try {
            primitive = createBuffer(device, (long) required * PRIMITIVE_DATA_ELEMENT_BYTES);
        } catch (RuntimeException | Error e) {
            releaseIfNonzero(vertex);
            throw e;
        }
        // An old structure refers to the old output storage; discard it with that storage rather
        // than leave a stale structure available if encoding the replacement later fails.
        releaseIfNonzero(accel.accelerationStructure);
        accel.accelerationStructure = 0;
        releaseIfNonzero(accel.vertexBuffer);
        releaseIfNonzero(accel.primitiveDataBuffer);
        accel.vertexBuffer = vertex;
        accel.primitiveDataBuffer = primitive;
        accel.triangleCapacity = required;
    }

    static long createBuffer(long device, long bytes) {
        long buffer = Objc.msgSendIdLongLong(device, Objc.selector("newBufferWithLength:options:"), bytes, 0L);
        if (buffer == 0) {
            throw new IllegalStateException("newBufferWithLength:options: returned nil (" + bytes + " bytes)");
        }
        return buffer;
    }

    /**
     * Encodes one {@code rt_expand} dispatch into {@code encoder} for a single slot's geometry,
     * whose four source ranges are already offset by the caller within {@code occupancyBuffer}/
     * {@code payloadBuffer}/{@code faceSealBuffer}/{@code paletteBuffer}. Zeros {@code
     * countersBuffer} first: a reused counters buffer otherwise still holds last frame's count.
     *
     * <p>Package-private and framed entirely in raw ids and byte offsets rather than {@link
     * MetalRtGeometry}'s exported buffers: {@link MetalRtSmokeTest} calls this directly against
     * Metal-only buffers with no Vulkan involved at all, exercising the exact same expand path
     * this class uses against the real exported geometry.
     */
    static void encodeExpand(long encoder, MetalRtShaders.CompiledKernel expand,
            long occupancyBuffer, long occupancyOffset,
            long payloadBuffer, long payloadOffset,
            long faceSealBuffer, long faceSealOffset,
            long paletteBuffer, long paletteOffset,
            long vertexBuffer, long primitiveDataBuffer, long countersBuffer) {
        encodeExpand(encoder, expand, occupancyBuffer, occupancyOffset, payloadBuffer, payloadOffset,
                faceSealBuffer, faceSealOffset, paletteBuffer, paletteOffset,
                vertexBuffer, primitiveDataBuffer, countersBuffer, RT_INITIAL_TRIS_PER_SLOT, false);
    }

    private static void encodeExpand(long encoder, MetalRtShaders.CompiledKernel expand,
            long occupancyBuffer, long occupancyOffset,
            long payloadBuffer, long payloadOffset,
            long faceSealBuffer, long faceSealOffset,
            long paletteBuffer, long paletteOffset,
            long vertexBuffer, long primitiveDataBuffer, long countersBuffer, int triangleCapacity, boolean supplementalCross) {
        zeroBuffer(countersBuffer, COUNTERS_BYTES);
        Objc.msgSendVoid(encoder, Objc.selector("setComputePipelineState:"), expand.pipeline());
        Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"),
                occupancyBuffer, occupancyOffset, 0L);
        Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"),
                payloadBuffer, payloadOffset, 1L);
        Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"),
                faceSealBuffer, faceSealOffset, 2L);
        Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"),
                paletteBuffer, paletteOffset, 3L);
        Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), vertexBuffer, 0L, 4L);
        Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), primitiveDataBuffer, 0L, 5L);
        Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), countersBuffer, 0L, 6L);
        try (Arena local = Arena.ofConfined()) {
            MemorySegment maxTris = local.allocate(ValueLayout.JAVA_INT);
            maxTris.set(ValueLayout.JAVA_INT, 0, triangleCapacity);
            Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBytes:length:atIndex:"), maxTris.address(), 4L, 7L);
            maxTris.set(ValueLayout.JAVA_INT, 0, supplementalCross ? 1 : 0);
            Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBytes:length:atIndex:"), maxTris.address(), 4L, 8L);
        }
        Objc.dispatchThreadgroups(encoder, 1, 1, 1, THREADS_PER_GROUP, 1, 1);
    }

    private static void zeroBuffer(long buffer, long byteSize) {
        long ptr = Objc.msgSendId(buffer, Objc.selector("contents"));
        MemorySegment.ofAddress(ptr).reinterpret(byteSize).fill((byte) 0);
    }

    /** Read only after the expansion command completed. The counter includes attempts beyond
     * the allocation, so its full value sizes a retry instead of silently truncating occluders. */
    private static int readRequiredTriangleCount(long countersBuffer, int slot) {
        long ptr = Objc.msgSendId(countersBuffer, Objc.selector("contents"));
        int count = MemorySegment.ofAddress(ptr).reinterpret(COUNTERS_BYTES).get(ValueLayout.JAVA_INT, 0);
        if (count < 0 || count > MAX_REQUIRED_TRIANGLES) {
            throw new IllegalStateException("Metal RT slot " + slot + " reported invalid triangle count " + count);
        }
        return count;
    }

    /** A build may use only a complete expansion. Package-private for the native smoke callers. */
    static int readCompleteTriangleCount(long countersBuffer, int slot) {
        int count = readRequiredTriangleCount(countersBuffer, slot);
        long ptr = Objc.msgSendId(countersBuffer, Objc.selector("contents"));
        int overflow = MemorySegment.ofAddress(ptr).reinterpret(COUNTERS_BYTES).get(ValueLayout.JAVA_INT, 4);
        if (overflow != 0) {
            throw new IllegalStateException("Metal RT slot " + slot + " still has " + overflow
                    + " unwritten triangles after expansion");
        }
        return count;
    }

    /**
     * Rebuilds one slot's primitive acceleration structure from its already-populated vertex/
     * primitive-data buffers, encoding the build into {@code encoder}. A {@code triangleCount} of
     * zero releases any existing structure and leaves the slot without one (skipped by both
     * {@link #rebuildInstances} and {@link #useResources}).
     */
    private static void rebuildSlotAccelerationStructure(
            long device, long encoder, SlotAccel slot, int triangleCount) {
        if (slot.accelerationStructure != 0) {
            Objc.msgSendVoid(slot.accelerationStructure, Objc.selector("release"));
            slot.accelerationStructure = 0;
        }
        if (triangleCount <= 0 && slot.supplementalTriangles <= 0) return;
        List<Long> geometries = new ArrayList<>();
        if (triangleCount > 0) geometries.add(triangleGeometry(slot.vertexBuffer,
                slot.primitiveDataBuffer, triangleCount, PRIMITIVE_DATA_ELEMENT_BYTES));
        if (slot.supplementalTriangles > 0) geometries.add(triangleGeometry(slot.supplementalVertexBuffer,
                slot.supplementalPrimitiveBuffer, slot.supplementalTriangles, RtSectionGeometry.PRIMITIVE_WORDS * 4L));
        long primDesc = primitiveDescriptor(geometries);
        Objc.AccelerationStructureSizes sizes = Objc.accelerationStructureSizes(device, primDesc);
        slot.accelerationStructure = Objc.msgSendId(
                device, Objc.selector("newAccelerationStructureWithSize:"), sizes.accelerationStructureSize());
        if (slot.accelerationStructure == 0) {
            throw new IllegalStateException("newAccelerationStructureWithSize: returned nil");
        }
        // Scratch is allocated fresh for this one build and released right after the build call
        // below encodes it: Metal retains any resource an encoder references for as long as the
        // owning command buffer is still executing, so releasing this class's own reference here
        // does not free the memory the GPU is still building into.
        long scratch = createBuffer(device, Math.max(sizes.buildScratchBufferSize(), 4L));
        try {
            Objc.msgSendVoidIdIdIdLong(encoder,
                    Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                    slot.accelerationStructure, primDesc, scratch, 0L);
        } finally {
            Objc.msgSendVoid(scratch, Objc.selector("release"));
        }
    }

    /** Builds one {@code MTLPrimitiveAccelerationStructureDescriptor} wrapping a single opaque
     * triangle geometry over {@code vertexBuffer}/{@code primitiveDataBuffer}. Package-private:
     * {@link MetalRtSmokeTest} builds its own one-off primitive structure the same way. */
    static long buildTrianglePrimitiveDescriptor(long vertexBuffer, long primitiveDataBuffer, int triangleCount) {
        return primitiveDescriptor(List.of(triangleGeometry(vertexBuffer, primitiveDataBuffer,
                triangleCount, PRIMITIVE_DATA_ELEMENT_BYTES)));
    }

    static long triangleGeometry(long vertexBuffer, long primitiveDataBuffer, int triangleCount, long primitiveStride) {
        long geomDesc = Objc.msgSendId(
                Objc.getClass("MTLAccelerationStructureTriangleGeometryDescriptor"), Objc.selector("descriptor"));
        Objc.msgSendVoid(geomDesc, Objc.selector("setVertexBuffer:"), vertexBuffer);
        Objc.msgSendVoidLong(geomDesc, Objc.selector("setVertexStride:"), VERTEX_STRIDE_BYTES);
        Objc.msgSendVoidLong(geomDesc, Objc.selector("setTriangleCount:"), triangleCount);
        Objc.msgSendVoidBool(geomDesc, Objc.selector("setOpaque:"), true);
        Objc.msgSendVoid(geomDesc, Objc.selector("setPrimitiveDataBuffer:"), primitiveDataBuffer);
        Objc.msgSendVoidLong(geomDesc, Objc.selector("setPrimitiveDataStride:"), primitiveStride);
        Objc.msgSendVoidLong(geomDesc, Objc.selector("setPrimitiveDataElementSize:"), primitiveStride);
        return geomDesc;
    }

    static long primitiveDescriptor(List<Long> geometries) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ids = arena.allocate((long)geometries.size() * Long.BYTES);
            for (int i=0;i<geometries.size();i++) ids.setAtIndex(ValueLayout.JAVA_LONG,i,geometries.get(i));
            long geomArray = Objc.msgSendIdLongLong(Objc.getClass("NSArray"),
                    Objc.selector("arrayWithObjects:count:"), ids.address(), geometries.size());
            long primDesc = Objc.msgSendId(
                    Objc.getClass("MTLPrimitiveAccelerationStructureDescriptor"), Objc.selector("descriptor"));
            Objc.msgSendVoid(primDesc, Objc.selector("setGeometryDescriptors:"), geomArray);
            return primDesc;
        }
    }

    private static void uploadSupplement(long device, SlotAccel slot, RtSectionGeometry geometry) {
        // In-flight Metal command buffers retain the old resources. Replace the complete pair
        // before the new BLAS is published; never mutate a buffer referenced by the old structure.
        RtSectionGeometry.Mesh mesh = geometry.mesh();
        long vertices = 0, primitives = 0;
        try {
            if (mesh.triangleCount() > 0) {
                vertices = createBuffer(device, (long)mesh.vertices().length * Float.BYTES);
                primitives = createBuffer(device, (long)mesh.primitiveWords().length * Integer.BYTES);
                MemorySegment.ofAddress(Objc.msgSendId(vertices, Objc.selector("contents")))
                        .reinterpret((long)mesh.vertices().length * Float.BYTES)
                        .copyFrom(MemorySegment.ofArray(mesh.vertices()));
                MemorySegment.ofAddress(Objc.msgSendId(primitives, Objc.selector("contents")))
                        .reinterpret((long)mesh.primitiveWords().length * Integer.BYTES)
                        .copyFrom(MemorySegment.ofArray(mesh.primitiveWords()));
            }
        } catch (RuntimeException | Error failure) {
            releaseIfNonzero(vertices); releaseIfNonzero(primitives); throw failure;
        }
        releaseIfNonzero(slot.supplementalVertexBuffer);
        releaseIfNonzero(slot.supplementalPrimitiveBuffer);
        slot.supplementalVertexBuffer = vertices;
        slot.supplementalPrimitiveBuffer = primitives;
        slot.supplementalTriangles = mesh.triangleCount();
        slot.representationExact = geometry.exact();
    }

    /** Separate from GPU readiness: a finished approximate selection box is still not exact. */
    public static boolean representationReady(int sourceSlot, SectionPos owner) {
        SlotAccel slot = slots.get(sourceSlot);
        return slot != null && owner.equals(slot.owner) && slot.representationExact;
    }

    /** Writes instance {@code index}'s 64-byte {@code MTLAccelerationStructureInstanceDescriptor}
     * (layout documented on {@link #INSTANCE_DESCRIPTOR_BYTES}): identity rotation/scale, {@code
     * (tx, ty, tz)} translation, no per-instance options (the geometry descriptor's own {@code
     * opaque} flag already covers that), mask {@code 0xFF} (visible to every ray: this milestone
     * has no per-instance ray masking), no intersection function table (implicit-triangle geometry
     * needs none), and {@code accelerationStructureIndex} set to this instance's own position in
     * the {@code instancedAccelerationStructures} array it was built alongside. Package-private:
     * {@link MetalRtSmokeTest} writes its own single instance descriptor the same way. */
    static void writeInstanceDescriptor(MemorySegment seg, int index, float tx, float ty, float tz, int accelIndex) {
        long base = (long) index * INSTANCE_DESCRIPTOR_BYTES;
        seg.set(ValueLayout.JAVA_FLOAT, base, 1.0f);
        seg.set(ValueLayout.JAVA_FLOAT, base + 4, 0.0f);
        seg.set(ValueLayout.JAVA_FLOAT, base + 8, 0.0f);
        seg.set(ValueLayout.JAVA_FLOAT, base + 12, 0.0f);
        seg.set(ValueLayout.JAVA_FLOAT, base + 16, 1.0f);
        seg.set(ValueLayout.JAVA_FLOAT, base + 20, 0.0f);
        seg.set(ValueLayout.JAVA_FLOAT, base + 24, 0.0f);
        seg.set(ValueLayout.JAVA_FLOAT, base + 28, 0.0f);
        seg.set(ValueLayout.JAVA_FLOAT, base + 32, 1.0f);
        seg.set(ValueLayout.JAVA_FLOAT, base + 36, tx);
        seg.set(ValueLayout.JAVA_FLOAT, base + 40, ty);
        seg.set(ValueLayout.JAVA_FLOAT, base + 44, tz);
        seg.set(ValueLayout.JAVA_INT, base + 48, 0);
        seg.set(ValueLayout.JAVA_INT, base + 52, 0xFF);
        seg.set(ValueLayout.JAVA_INT, base + 56, 0);
        seg.set(ValueLayout.JAVA_INT, base + 60, accelIndex);
    }
}
