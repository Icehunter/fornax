package dev.icehunter.fornax.metalfx.rt;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.metalfx.VulkanMetalInterop;
import dev.icehunter.fornax.pack.graph.BufferInstance;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import dev.icehunter.fornax.voxel.BrickGridUpload;
import dev.icehunter.fornax.voxel.VoxelFaceTexture;
import dev.icehunter.fornax.voxel.VoxelWindow;
import net.minecraft.core.SectionPos;
import java.util.Map;
import java.util.HashMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMetalObjects;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkExportMetalBufferInfoEXT;
import org.lwjgl.vulkan.VkExportMetalObjectCreateInfoEXT;
import org.lwjgl.vulkan.VkExportMetalObjectsInfoEXT;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metal-visible copies of up to five brick-grid buffers (occupancy, payload, face seal, palette,
 * face texture), sized for the capped ray-tracing window. The registry's own brick-grid buffers
 * ({@code TargetRegistry}, allocated through VMA) are never exportable to Metal, so this class owns
 * up to five separate Vulkan buffers, created with raw {@code vkCreateBuffer}/{@code
 * vkAllocateMemory} rather than VMA so their backing {@code VkDeviceMemory} can be declared
 * exportable and handed to Metal as an {@code MTLBuffer} through {@code VK_EXT_metal_objects}.
 * Whichever slots changed since the last drain are copied from the registry's buffers into these
 * buffers each frame instead. The face texture buffer is the exception: it exists only when the
 * active pack's graph enabled it (see {@link #ensureBuffers}), so exactly four of the five are
 * guaranteed present whenever this class is active.
 *
 * <p>The window is capped at {@link #MAX_DIAMETER} slots across: Metal's guidance for rebuilding an
 * instance acceleration structure every frame is a few thousand instances, and a diameter beyond
 * this cap would need more slots than that in the worst case. Source slots are selected by their
 * section coordinates inside a centered cube, then copied to compact toroidal destinations.
 * Excluded source slots do not consume the copy budget or keep the readiness gate pending.
 *
 * <p>Static state, one instance for the process, matching {@code MetalFxUpscalePass}: there is one
 * ray-tracing pass and one Metal device.
 */
public final class MetalRtGeometry {
    /** See the class javadoc's "capped at" paragraph for why 17 (radius 8). */
    static final int MAX_DIAMETER = 17;

    /** One Fornax-created, exported Vulkan buffer: its own {@code VkDeviceMemory} (not VMA) and
     * the {@code MTLBuffer} Metal reads the same memory as. */
    public record ExportedBuffer(long vkBuffer, long memory, long mtlBuffer, long sizeBytes) {
    }

    private static volatile boolean active;
    private static int allocatedDiameter = -1;
    /** Whether the current allocation includes {@link #faceTexture}: tracks {@code
     * TargetRegistry.isEnabledBufferTarget(VoxelFaceTexture.TARGET)} at the last (re)allocation.
     * That buffer is pack-conditional; the other four are always required (see {@link
     * #ensureBuffers}'s own doc). */
    private static boolean allocatedFaceTextureEnabled = false;
    /** One-shot warning flag for the case where the pack never enabled {@link
     * VoxelFaceTexture#TARGET}: see {@link #ensureBuffers}. Cleared as soon as the buffer becomes
     * available again, so a pack that later declares it and then drops it again gets warned
     * instead of staying silent forever. */
    private static boolean faceTextureMissingWarned = false;
    private static ExportedBuffer occupancy;
    private static ExportedBuffer payload;
    private static ExportedBuffer faceSeal;
    private static ExportedBuffer palette;
    /** Only allocated when the active pack's graph enabled {@link VoxelFaceTexture#TARGET} (see
     * {@link #ensureBuffers}). At {@link #MAX_DIAMETER} this is the largest of the five buffers
     * by far (16128 bytes/slot against 512+4096+4096+6144 for the other four combined), so
     * allocating it when no pack writes into it would more than double this class's VRAM use for
     * nothing. {@code null} exactly when the pack has not enabled it. */
    private static ExportedBuffer faceTexture;

    private static final Object DIRTY_LOCK = new Object();
    private static final Set<Integer> dirtySlots = new TreeSet<>();
    // Published owner is recorded only after both exported metadata and its BLAS build are queued.
    private static final Map<Integer, SectionPos> publishedOwners = new HashMap<>();
    private static int publishedSourceDiameter = -1;
    private static int publishedExportDiameter = -1;

    private MetalRtGeometry() {
    }

    /** Set by the ray-tracing pass once it knows whether Metal RT is available and enabled.
     * {@link #markDirty} is a no-op while this is false. Turning it off drops any slots marked
     * dirty since the last drain, since there is nothing to copy them into while inactive. */
    public static void setActive(boolean value) {
        active = value;
        if (!value) {
            synchronized (DIRTY_LOCK) {
                dirtySlots.clear();
            }
        }
    }

    public static boolean isActive() {
        return active;
    }

    /** Called from {@code BrickGridUpload.uploadSlots}/{@code clearOccupancySlots} after a tier-0
     * slot's geometry buffers change in the registry. A no-op unless the pass is active, so the
     * hook costs nothing when Metal RT is off or unavailable. */
    public static void markDirty(int slot) {
        if (!active) {
            return;
        }
        synchronized (DIRTY_LOCK) {
            dirtySlots.add(slot);
        }
    }

    /** Seeds the dirty set with every slot in {@code slots}, unconditionally: unlike {@link
     * #markDirty}, this is not gated on {@link #isActive()}. Called once from {@link
     * #ensureBuffers} right after a (re)allocation, when every populated slot's data still needs
     * copying into the fresh buffers on the first {@link #recordSlotCopies} call, regardless
     * of whether {@link #setActive} has run yet. */
    static void markAllDirty(Collection<Integer> slots) {
        synchronized (DIRTY_LOCK) {
            dirtySlots.addAll(slots);
        }
    }

    /** Drains every slot marked dirty since the last drain: ascending by slot index, each slot
     * appearing once even if it was marked more than once in between. Empty when nothing changed. */
    public static List<Integer> drainDirty() {
        synchronized (DIRTY_LOCK) {
            if (dirtySlots.isEmpty()) {
                return List.of();
            }
            List<Integer> drained = new ArrayList<>(dirtySlots);
            dirtySlots.clear();
            return drained;
        }
    }

    /** This class's per-slot byte stride for one of the five brick-grid targets it copies.
     * Throws for any other target name: this class holds no exported copy of it. */
    public static long bytesPerSlot(String target) {
        if (target.equals(BrickGridUpload.OCCUPANCY_TARGET)) {
            return BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT;
        }
        if (target.equals(BrickGridUpload.PAYLOAD_TARGET)) {
            // BrickGridUpload's real per-slot payload stride. Deliberately not
            // BrickGridUpload.PAYLOAD_BYTES_PER_SLOT: that public constant totals payload plus
            // occupancy for a different, VRAM-accounting purpose. It is not this buffer's actual
            // stride (see BrickGridUpload.ensureAllocated's own sizing of PAYLOAD_TARGET at
            // slotCount * VOXELS_PER_SECTION).
            return BrickGridUpload.VOXELS_PER_SECTION;
        }
        if (target.equals(BrickGridUpload.FACE_SEAL_TARGET)) {
            return BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT;
        }
        if (target.equals(BrickGridUpload.PALETTE_TARGET)) {
            return BrickGridUpload.PALETTE_BYTES_PER_SLOT;
        }
        if (target.equals(VoxelFaceTexture.TARGET)) {
            return VoxelFaceTexture.BYTES_PER_SLOT;
        }
        throw new IllegalArgumentException("MetalRtGeometry has no exported copy of target '" + target + "'");
    }

    /** This class's byte offset of {@code slot} within one of the five exported buffers. */
    public static long slotOffset(String target, int slot) {
        return (long) slot * bytesPerSlot(target);
    }

    /** Allocated compact buffer capacity; never the tier-0 source slot-index limit. */
    public static long allocatedSlotCount() {
        return occupancy == null ? 0 : (long) allocatedDiameter * allocatedDiameter * allocatedDiameter;
    }

    /** The actual finite RT domain, centered on the source window and capped at radius eight. */
    public static VoxelWindow.WindowState exportedWindow() {
        return exportedWindow(VoxelWindow.currentState());
    }

    static VoxelWindow.WindowState exportedWindow(VoxelWindow.WindowState source) {
        int radius = Math.min(source.radius(), (MAX_DIAMETER - 1) / 2);
        return new VoxelWindow.WindowState(source.centerX(), source.centerY(), source.centerZ(), radius, radius * 2 + 1);
    }

    /** Converts a tier-0 toroidal slot into its compact, absolute-coordinate toroidal RT slot.
     * The absolute modulo keeps overlapping sections at stable destinations when the camera moves.
     * A raw source-index prefix is not a centered spatial window and must never be used here. */
    static int exportedSlot(VoxelWindow.WindowState source, int slot) {
        int d = source.diameter();
        if (slot < 0 || slot >= (long) d * d * d) return -1;
        int firstX = source.centerX() - source.radius();
        int firstY = source.centerY() - source.radius();
        int firstZ = source.centerZ() - source.radius();
        int x = firstX + Math.floorMod(slot % d - firstX, d);
        int z = firstZ + Math.floorMod((slot / d) % d - firstZ, d);
        int y = firstY + Math.floorMod(slot / (d * d) - firstY, d);
        var domain = exportedWindow(source);
        if (Math.abs(x - domain.centerX()) > domain.radius()
                || Math.abs(y - domain.centerY()) > domain.radius()
                || Math.abs(z - domain.centerZ()) > domain.radius()) return -1;
        int e = domain.diameter();
        return (Math.floorMod(y, e) * e + Math.floorMod(z, e)) * e + Math.floorMod(x, e);
    }

    static int exportedSlot(int slot) {
        return exportedSlot(VoxelWindow.currentState(), slot);
    }

    public static boolean slotInExportedWindow(int slot) {
        int destination = exportedSlot(slot);
        return destination >= 0 && destination < allocatedSlotCount();
    }

    /** Drops excluded slots before budgeting, and seeds new/reowned sections even when their source
     * upload happened while RT was off or while the section lay outside the smaller RT domain. */
    static Map<Integer, SectionPos> prepareSnapshot(Map<Integer, SectionPos> populated) {
        var source = VoxelWindow.currentState();
        var domain = exportedWindow(source);
        Map<Integer, SectionPos> eligible = new HashMap<>();
        for (var entry : populated.entrySet()) {
            if (slotInExportedWindow(entry.getKey())) eligible.put(entry.getKey(), entry.getValue());
        }
        synchronized (DIRTY_LOCK) {
            if (source.diameter() != publishedSourceDiameter || domain.diameter() != publishedExportDiameter) {
                publishedOwners.clear();
                publishedSourceDiameter = source.diameter();
                publishedExportDiameter = domain.diameter();
            }
            publishedOwners.entrySet().removeIf(e -> !e.getValue().equals(eligible.get(e.getKey())));
            dirtySlots.retainAll(eligible.keySet());
            for (var entry : eligible.entrySet()) {
                if (!entry.getValue().equals(publishedOwners.get(entry.getKey()))) dirtySlots.add(entry.getKey());
            }
        }
        return eligible;
    }

    /** One common copy/build batch: metadata must not be published ahead of its triangle indices. */
    static List<Integer> selectDirtyBatch() {
        synchronized (DIRTY_LOCK) {
            List<Integer> batch = new ArrayList<>(MAX_SLOT_COPIES_PER_CALL);
            var iterator = dirtySlots.iterator();
            while (iterator.hasNext() && batch.size() < MAX_SLOT_COPIES_PER_CALL) {
                int slot = iterator.next();
                batch.add(slot);
                iterator.remove();
                // Selected is still unpublished, including a rebuild of the same owner. Once its
                // dirty mark is drained only a completed copy/build may restore readiness.
                publishedOwners.remove(slot);
            }
            return batch;
        }
    }

    static void markPublished(List<Integer> slots, Map<Integer, SectionPos> owners) {
        synchronized (DIRTY_LOCK) {
            for (int slot : slots) publishedOwners.put(slot, owners.get(slot));
        }
    }

    static boolean snapshotReady(Map<Integer, SectionPos> owners) {
        synchronized (DIRTY_LOCK) {
            if (owners.size() != allocatedSlotCount()) return false;
            for (var entry : owners.entrySet()) {
                if (dirtySlots.contains(entry.getKey()) || !entry.getValue().equals(publishedOwners.get(entry.getKey()))) return false;
            }
            return true;
        }
    }

    /** Per-section publication readiness in compact exported toroidal order: one uint-compatible
     * int per allocated slot, with one meaning current and zero meaning unknown. Input keys are
     * tier-0 source slots, never compact destinations. Unrelated missing or dirty sections leave
     * other entries unchanged; even zero-triangle sections require a completed publication.
     *
     * <p>The queue lock binds CPU mesh/read/upload revisions to the metadata/BLAS owner snapshot.
     * Callers retain that lock across their matching metadata capture and readiness upload. These
     * flags describe owning sections; rays near section boundaries must also validate any neighbor
     * whose displaced geometry can reach the ray, using that geometry's supported bounds. */
    public static int[] sectionReadiness(Map<Integer, SectionPos> owners) {
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            int[] readiness = new int[Math.toIntExact(allocatedSlotCount())];
            var source = VoxelWindow.currentState();
            var domain = exportedWindow(source);
            synchronized (DIRTY_LOCK) {
                if (allocatedDiameter != domain.diameter() || publishedSourceDiameter != source.diameter()
                        || publishedExportDiameter != domain.diameter()) return readiness;
                for (var entry : owners.entrySet()) {
                    int sourceSlot = entry.getKey();
                    SectionPos owner = entry.getValue();
                    int destination = exportedSlot(source, sourceSlot);
                    if (destination < 0 || destination >= readiness.length
                            || VoxelWindow.slotFor(owner.x(), owner.y(), owner.z()) != sourceSlot
                            || dirtySlots.contains(sourceSlot) || !owner.equals(publishedOwners.get(sourceSlot))
                            || !VoxelWindow.isGeometryReady(owner)) continue;
                    readiness[destination] = 1; // Binary data-valid ABI; zero is conservative unknown.
                }
            }
            return readiness;
        }
    }

    /** Whether calling {@link #ensureBuffers} with {@code registry} and {@code diameter} would
     * (re)allocate. Lets the caller decide before calling it whether a reallocation is coming, so
     * it can host-wait its own shared timeline's last-signaled value first. See {@link
     * #ensureBuffers}'s own contract. Also true on an {@code enabled_if}/pack-reload flip of {@link
     * VoxelFaceTexture#TARGET}'s enablement even when {@code diameter} is unchanged, since that
     * flip changes whether {@link #faceTexture} should exist at all. */
    public static boolean needsReallocation(TargetRegistry registry, int diameter) {
        int cappedDiameter = Math.min(Math.max(diameter, 0), MAX_DIAMETER);
        boolean faceTextureEnabled = registry != null && registry.isEnabledBufferTarget(VoxelFaceTexture.TARGET);
        return occupancy == null || cappedDiameter != allocatedDiameter
                || faceTextureEnabled != allocatedFaceTextureEnabled;
    }

    /**
     * Allocates (or reallocates, on a diameter or face-texture-enablement change) the exported
     * buffers for a window of {@code diameter} slots across, capped at {@link #MAX_DIAMETER}. A
     * no-op when the buffers are already sized for this (capped) diameter and {@link #faceTexture}'s
     * allocation already matches {@code registry}'s current enablement of {@link
     * VoxelFaceTexture#TARGET} (see {@link #needsReallocation}).
     *
     * <p>{@link #faceTexture} is allocated only when {@code registry} currently has {@link
     * VoxelFaceTexture#TARGET} enabled. Unlike occupancy/payload/faceSeal/palette, which {@code
     * BrickGridUpload.ensureAllocated} always sizes for tier 0, that buffer is a pack-declared
     * engine buffer the active pack's graph may never enable. At {@link #MAX_DIAMETER} it is by
     * far the largest of the five (16128 bytes/slot against the other four's 14848 combined), so
     * allocating it unconditionally would more than double this class's VRAM use for a pack that
     * never writes into it. When it is not enabled this method logs one warning (see {@link
     * #faceTextureMissingWarned}) instead of allocating and silently zero-filling it, since a
     * ray-tracing feature quietly missing its face-texture data for the rest of the session is
     * exactly the failure this codebase's fail-loudly rule exists to catch.
     *
     * <p>Every freshly allocated buffer is zero-filled before this method returns. Some Vulkan
     * backends do not zero-fill new VRAM, and Metal would otherwise read leftover memory for every
     * slot not yet copied by {@link #recordSlotCopies} (this class only copies dirty slots, never
     * the whole buffer, so "allocated" does not mean "populated"). The dirty set is then seeded
     * with every currently populated slot ({@link VoxelWindow#populatedSlotSections()}) so the next
     * {@link #recordSlotCopies} call copies real data for all of them, regardless of whether {@link
     * #setActive} has run yet.
     *
     * <p><b>Caller's responsibility before a reallocation:</b> when {@link #needsReallocation} would
     * return {@code true} for this call, the caller must already have host-waited its own Metal or
     * Vulkan shared timeline up to the last value it signaled after the final Vulkan or Metal use of
     * the current buffers (mirroring {@code MetalFxUpscalePass.ensureResources}'s own resize-teardown
     * wait) before calling this. {@link #destroy}, which this calls internally on a reallocation,
     * frees the old buffers unconditionally and keeps no fence of its own, so a reallocation racing
     * a still-executing Metal read or Vulkan copy against the old buffers is a use-after-free.
     */
    public static void ensureBuffers(VulkanDevice device, TargetRegistry registry, int diameter) {
        int cappedDiameter = Math.min(Math.max(diameter, 0), MAX_DIAMETER);
        boolean faceTextureEnabled = registry != null && registry.isEnabledBufferTarget(VoxelFaceTexture.TARGET);
        if (occupancy != null && cappedDiameter == allocatedDiameter
                && faceTextureEnabled == allocatedFaceTextureEnabled) {
            return;
        }
        destroy(device);
        allocatedDiameter = cappedDiameter;
        allocatedFaceTextureEnabled = faceTextureEnabled;
        if (cappedDiameter <= 0) {
            return;
        }
        long slotCount = (long) cappedDiameter * cappedDiameter * cappedDiameter;
        occupancy = createExportedBuffer(device, slotCount * BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT);
        payload = createExportedBuffer(device, slotCount * BrickGridUpload.VOXELS_PER_SECTION);
        faceSeal = createExportedBuffer(device, slotCount * BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT);
        palette = createExportedBuffer(device, slotCount * BrickGridUpload.PALETTE_BYTES_PER_SLOT);
        if (faceTextureEnabled) {
            faceTexture = createExportedBuffer(device, slotCount * VoxelFaceTexture.BYTES_PER_SLOT);
            faceTextureMissingWarned = false;
        } else if (!faceTextureMissingWarned) {
            faceTextureMissingWarned = true;
            FornaxMod.LOGGER.warn(
                    "[Fornax] MetalRtGeometry: the active pack has not enabled {}. Ray-traced shadows "
                            + "will run without per-face texture data (flat material response) until the "
                            + "pack's graph declares this buffer.",
                    VoxelFaceTexture.TARGET);
        }
        zeroFillOnAllocation(device);
        markAllDirty(VoxelWindow.populatedSlotSections().keySet());
    }

    /** VRAM is not zero-filled on every Vulkan backend. Clears every freshly allocated buffer
     * (four, or five when {@link #faceTexture} is enabled) on the graphics stream, host-waited,
     * before this method returns, so no Metal read can ever see leftover VRAM. One batched submit
     * rather than one per buffer, matching {@code
     * VulkanMetalInterop.recordAndFlush}'s existing shape for other allocation-time GPU work in this
     * package family. */
    private static void zeroFillOnAllocation(VulkanDevice device) {
        VulkanCommandEncoder encoder = device.createCommandEncoder();
        VulkanMetalInterop.recordAndFlush(encoder, cmd -> {
            VK13.vkCmdFillBuffer(cmd, occupancy.vkBuffer(), 0, occupancy.sizeBytes(), 0);
            VK13.vkCmdFillBuffer(cmd, payload.vkBuffer(), 0, payload.sizeBytes(), 0);
            VK13.vkCmdFillBuffer(cmd, faceSeal.vkBuffer(), 0, faceSeal.sizeBytes(), 0);
            VK13.vkCmdFillBuffer(cmd, palette.vkBuffer(), 0, palette.sizeBytes(), 0);
            if (faceTexture != null) {
                VK13.vkCmdFillBuffer(cmd, faceTexture.vkBuffer(), 0, faceTexture.sizeBytes(), 0);
            }
        });
    }

    public static ExportedBuffer occupancy() {
        return occupancy;
    }

    public static ExportedBuffer payload() {
        return payload;
    }

    public static ExportedBuffer faceSeal() {
        return faceSeal;
    }

    public static ExportedBuffer palette() {
        return palette;
    }

    public static ExportedBuffer faceTexture() {
        return faceTexture;
    }

    /**
     * Records four {@code vkCmdCopyBuffer} regions per slot, one per required target, plus a
     * fifth for {@link VoxelFaceTexture#TARGET} when the pack has enabled that buffer, from the
     * registry's brick-grid buffers into these exported buffers. Follows with a transfer-to-
     * all-commands barrier so any later Vulkan use of the exported buffers is ordered after the
     * copy (Metal needs no such barrier; the shared timeline in the ray-tracing pass supplies its
     * own cross-API wait).
     *
     * <p>Unlike occupancy/payload/faceSeal/palette, which {@code BrickGridUpload.ensureAllocated}
     * always sizes for tier 0, {@link VoxelFaceTexture#TARGET} is a pack-declared engine buffer
     * (see {@code GraphValidator.ENGINE_BUFFERS}): the registry has no buffer for it at all when
     * the active pack's graph never enabled it, which is a normal steady state, not an error.
     * When that is the case this method still copies the other four targets normally; only the
     * face-texture copy for that slot is skipped.
     *
     * <p><b>Caller holds {@code VulkanComputeBackend.SHARED_QUEUE_LOCK}.</b> The registry buffer
     * handles read here are only stable under that lock, the same rule every other brick-grid
     * reader in this codebase follows: a resize can otherwise retire and replace a handle between
     * reads.
     *
     * <p>A slot whose byte range overruns either the source registry buffer (a live window shrink
     * raced the dirty mark, see {@code BrickGridUpload}'s own "Out-of-bounds write guard" section)
     * or the destination exported buffer (a changed compact mapping or allocation) is skipped,
     * with a one-shot warning per distinct condition. Source indices and compact destination
     * indices have separate byte offsets by design.
     *
     * @return the slots from {@code slots} that were copied on every target this call requires for
     *     them (the four required targets, plus face texture whenever the registry has that
     *     buffer). A slot missing from this list had at least one required target dropped (out of
     *     window, or the registry has no buffer at all yet), or was past {@link
     *     #MAX_SLOT_COPIES_PER_CALL} this call, and so holds stale or incomplete exported
     *     data. The caller must not expand it this frame and must re-mark it dirty for the next one.
     */
    public static List<Integer> recordSlotCopies(VkCommandBuffer cmd, TargetRegistry registry, List<Integer> slots) {
        if (occupancy == null || slots.isEmpty()) {
            return List.of();
        }
        BufferInstance srcOccupancy = registry.getBuffer(BrickGridUpload.OCCUPANCY_TARGET);
        BufferInstance srcPayload = registry.getBuffer(BrickGridUpload.PAYLOAD_TARGET);
        BufferInstance srcFaceSeal = registry.getBuffer(BrickGridUpload.FACE_SEAL_TARGET);
        BufferInstance srcPalette = registry.getBuffer(BrickGridUpload.PALETTE_TARGET);
        if (srcOccupancy == null || srcPayload == null || srcFaceSeal == null || srcPalette == null) {
            return List.of();
        }
        // Optional: null exactly when the active pack's graph never enabled this buffer, in which
        // case the exported faceTexture field is also null (ensureBuffers does not allocate it and
        // has already logged the one-shot warning, see its own doc). Not part of the
        // all-or-nothing check above.
        BufferInstance srcFaceTexture = registry.getBuffer(VoxelFaceTexture.TARGET);
        List<Integer> copied = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // One reusable VkBufferCopy struct per target, mutated and re-submitted for every slot,
            // rather than one calloc per slot per target: vkCmdCopyBuffer reads the struct's fields
            // at call time and does not keep the pointer afterward, so reusing it is correct. It is
            // the difference between one allocation total here and thousands: a first-frame or
            // world-load dirty burst that never popped a per-slot stack frame would otherwise
            // overflow the (64 KB) LWJGL thread-local MemoryStack.
            VkBufferCopy.Buffer occupancyRegion = VkBufferCopy.calloc(1, stack);
            VkBufferCopy.Buffer payloadRegion = VkBufferCopy.calloc(1, stack);
            VkBufferCopy.Buffer faceSealRegion = VkBufferCopy.calloc(1, stack);
            VkBufferCopy.Buffer paletteRegion = VkBufferCopy.calloc(1, stack);
            VkBufferCopy.Buffer faceTextureRegion = VkBufferCopy.calloc(1, stack);
            int attempted = 0;
            for (int slot : slots) {
                if (!slotInExportedWindow(slot)) {
                    continue;
                }
                // A world load or a diameter reallocation can seed thousands of slots dirty at
                // once (see MetalRtGeometry.ensureBuffers's markAllDirty seed). Copying all of them
                // in one Vulkan command buffer recording would make the first frame's recording
                // cost scale with the whole window instead of with what changed. Capping
                // here spreads that burst over as many frames as it takes, at a bounded per-frame
                // cost. Slots past the cap are left off `copied`, and the caller already
                // re-marks any input slot missing from the returned list dirty for the next frame.
                if (attempted >= MAX_SLOT_COPIES_PER_CALL) {
                    break;
                }
                attempted++;
                boolean ok = copySlot(cmd, occupancyRegion, BrickGridUpload.OCCUPANCY_TARGET, srcOccupancy, occupancy,
                        BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT, slot);
                ok &= copySlot(cmd, payloadRegion, BrickGridUpload.PAYLOAD_TARGET, srcPayload, payload,
                        BrickGridUpload.VOXELS_PER_SECTION, slot);
                ok &= copySlot(cmd, faceSealRegion, BrickGridUpload.FACE_SEAL_TARGET, srcFaceSeal, faceSeal,
                        BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT, slot);
                ok &= copySlot(cmd, paletteRegion, BrickGridUpload.PALETTE_TARGET, srcPalette, palette,
                        BrickGridUpload.PALETTE_BYTES_PER_SLOT, slot);
                if (srcFaceTexture != null && faceTexture != null) {
                    ok &= copySlot(cmd, faceTextureRegion, VoxelFaceTexture.TARGET, srcFaceTexture, faceTexture,
                            VoxelFaceTexture.BYTES_PER_SLOT, slot);
                }
                if (ok) {
                    copied.add(slot);
                }
            }
            recordTransferToAllCommandsBarrier(cmd, stack);
        }
        return copied;
    }

    /** Bounds how many slots one {@link #recordSlotCopies} call processes: see that method's
     * cap-check comment for why. This is exactly the BLAS rebuild budget, since a larger copy budget
     * would expose a new palette beside old primitive palette indices. */
    private static final int MAX_SLOT_COPIES_PER_CALL = MetalRtAcceleration.MAX_DIRTY_PER_FRAME;

    /** Distinct (target, source size, exported size) triples already warned about, the same
     * log-once-per-condition dedup {@code BrickGridUpload.OOB_DROP_LOGGED} uses, for the same
     * reason: a single window shrink or exported-window cap can leave many dirty slots all rejected
     * by the same current sizes in one frame. */
    private static final Set<String> OOB_DROP_LOGGED = ConcurrentHashMap.newKeySet();

    /** {@code region} is caller-owned and reused across every slot/target this call processes (see
     * {@link #recordSlotCopies}'s own comment on why). This method only ever mutates its fields,
     * never allocates one. */
    private static boolean copySlot(VkCommandBuffer cmd, VkBufferCopy.Buffer region, String targetName,
            BufferInstance src, ExportedBuffer dst, long bytesPerSlot, int slot) {
        long offset = (long) slot * bytesPerSlot;
        long destinationOffset = (long) exportedSlot(slot) * bytesPerSlot;
        long srcSize = src.sizeBytes();
        long dstSize = dst.sizeBytes();
        // The registry's brick-grid buffer can shrink out from under an already-queued dirty slot
        // the same way BrickGridUpload's upload guard documents. Destination indexing is compact
        // and independent: a high source slot can correctly map to a low exported slot.
        if (offset + bytesPerSlot > srcSize || destinationOffset < 0 || destinationOffset + bytesPerSlot > dstSize) {
            if (OOB_DROP_LOGGED.add(targetName + '@' + srcSize + '@' + dstSize)) {
                FornaxMod.LOGGER.warn(
                        "[Fornax] MetalRtGeometry: dropping slot {} copy for {} (offset={}, size={}), past "
                                + "the current source ({} bytes) or exported ({} bytes) buffer's capacity.",
                        slot, targetName, offset, bytesPerSlot, srcSize, dstSize);
            }
            return false;
        }
        region.srcOffset(offset).dstOffset(destinationOffset).size(bytesPerSlot);
        VK13.vkCmdCopyBuffer(cmd, src.vkBuffer(), dst.vkBuffer(), region);
        return true;
    }

    private static void recordTransferToAllCommandsBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        // faceTexture is only present when the pack enabled it (see ensureBuffers); the barrier
        // count must match whichever buffers exist for this allocation.
        int count = faceTexture != null ? 5 : 4;
        VkBufferMemoryBarrier.Buffer barriers = VkBufferMemoryBarrier.calloc(count, stack);
        setBarrier(barriers.get(0), occupancy);
        setBarrier(barriers.get(1), payload);
        setBarrier(barriers.get(2), faceSeal);
        setBarrier(barriers.get(3), palette);
        if (faceTexture != null) {
            setBarrier(barriers.get(4), faceTexture);
        }
        VK13.vkCmdPipelineBarrier(cmd, VK13.VK_PIPELINE_STAGE_TRANSFER_BIT, VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, null, barriers, null);
    }

    private static void setBarrier(VkBufferMemoryBarrier barrier, ExportedBuffer buffer) {
        barrier.sType$Default()
                .srcAccessMask(VK13.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK13.VK_ACCESS_MEMORY_READ_BIT | VK13.VK_ACCESS_MEMORY_WRITE_BIT)
                .srcQueueFamilyIndex(VK13.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK13.VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer.vkBuffer())
                .offset(0)
                .size(buffer.sizeBytes());
    }

    /** Frees all five exported buffers and their memory, if allocated. Safe to call when nothing
     * is allocated. Caller must have already waited for any GPU work reading these buffers to
     * finish: this class keeps no fence of its own, matching {@code VulkanMetalInterop
     * .destroyImage}'s "already-quiesced" contract. */
    public static void destroy(VulkanDevice device) {
        destroyOne(device, occupancy);
        destroyOne(device, payload);
        destroyOne(device, faceSeal);
        destroyOne(device, palette);
        destroyOne(device, faceTexture);
        occupancy = null;
        payload = null;
        faceSeal = null;
        palette = null;
        faceTexture = null;
        allocatedDiameter = -1;
        allocatedFaceTextureEnabled = false;
        synchronized (DIRTY_LOCK) {
            publishedOwners.clear();
            publishedSourceDiameter = -1;
            publishedExportDiameter = -1;
        }
    }

    private static void destroyOne(VulkanDevice device, ExportedBuffer buffer) {
        if (buffer == null) {
            return;
        }
        VK13.vkDestroyBuffer(device.vkDevice(), buffer.vkBuffer(), null);
        VK13.vkFreeMemory(device.vkDevice(), buffer.memory(), null);
    }

    /** Creates one Fornax-owned buffer with its own exportable {@code VkDeviceMemory} and exports
     * its {@code MTLBuffer}. Memory is device-local only (not host-visible): Vulkan writes it
     * through {@code vkCmdCopyBuffer} and Metal reads it as a GPU-resident buffer; nothing on
     * either side needs CPU access to it. */
    private static ExportedBuffer createExportedBuffer(VulkanDevice device, long sizeBytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(sizeBytes)
                    .usage(VK13.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                    .sharingMode(VK13.VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer bufferOut = stack.mallocLong(1);
            int result = VK13.vkCreateBuffer(device.vkDevice(), bufferInfo, null, bufferOut);
            if (result != VK13.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateBuffer (exported RT geometry buffer) failed: " + result);
            }
            long vkBuffer = bufferOut.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            VK13.vkGetBufferMemoryRequirements(device.vkDevice(), vkBuffer, requirements);
            int memoryTypeIndex = findMemoryType(device.vkDevice().getPhysicalDevice(),
                    requirements.memoryTypeBits(), VK13.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            VkExportMetalObjectCreateInfoEXT exportDecl = VkExportMetalObjectCreateInfoEXT.calloc(stack)
                    .sType$Default()
                    .exportObjectType(EXTMetalObjects.VK_EXPORT_METAL_OBJECT_TYPE_METAL_BUFFER_BIT_EXT);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(exportDecl)
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(memoryTypeIndex);
            LongBuffer memoryOut = stack.mallocLong(1);
            result = VK13.vkAllocateMemory(device.vkDevice(), allocInfo, null, memoryOut);
            if (result != VK13.VK_SUCCESS) {
                VK13.vkDestroyBuffer(device.vkDevice(), vkBuffer, null);
                throw new IllegalStateException("vkAllocateMemory (exported RT geometry buffer) failed: " + result);
            }
            long memory = memoryOut.get(0);

            result = VK13.vkBindBufferMemory(device.vkDevice(), vkBuffer, memory, 0);
            if (result != VK13.VK_SUCCESS) {
                VK13.vkFreeMemory(device.vkDevice(), memory, null);
                VK13.vkDestroyBuffer(device.vkDevice(), vkBuffer, null);
                throw new IllegalStateException("vkBindBufferMemory (exported RT geometry buffer) failed: " + result);
            }

            VkExportMetalBufferInfoEXT bufferExport = VkExportMetalBufferInfoEXT.calloc(stack)
                    .sType$Default()
                    .memory(memory);
            VkExportMetalObjectsInfoEXT exportInfo = VkExportMetalObjectsInfoEXT.calloc(stack)
                    .sType$Default()
                    .pNext(bufferExport);
            EXTMetalObjects.vkExportMetalObjectsEXT(device.vkDevice(), exportInfo);
            long mtlBuffer = bufferExport.mtlBuffer();
            if (mtlBuffer == 0) {
                VK13.vkFreeMemory(device.vkDevice(), memory, null);
                VK13.vkDestroyBuffer(device.vkDevice(), vkBuffer, null);
                throw new IllegalStateException("vkExportMetalObjectsEXT returned nil MTLBuffer");
            }
            return new ExportedBuffer(vkBuffer, memory, mtlBuffer, sizeBytes);
        }
    }

    /** First memory type allowed by {@code typeBits} (the buffer's own {@code
     * memoryRequirements.memoryTypeBits}) whose property flags include every bit in {@code
     * requiredProperties}. Throws rather than silently picking an unsuitable type, matching this
     * codebase's fail-loudly rule. */
    private static int findMemoryType(VkPhysicalDevice physicalDevice, int typeBits, int requiredProperties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK13.vkGetPhysicalDeviceMemoryProperties(physicalDevice, props);
            for (int i = 0; i < props.memoryTypeCount(); i++) {
                boolean typeAllowed = (typeBits & (1 << i)) != 0;
                boolean hasProperties = (props.memoryTypes(i).propertyFlags() & requiredProperties) == requiredProperties;
                if (typeAllowed && hasProperties) {
                    return i;
                }
            }
            throw new IllegalStateException("No Vulkan memory type with properties " + requiredProperties
                    + " matches requirement bitmask " + typeBits);
        }
    }
}
