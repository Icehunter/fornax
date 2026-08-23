package dev.icehunter.fornax.debug;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.graph.BufferInstance;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pass.compute.VulkanComputeBackend;
import dev.icehunter.fornax.voxel.BrickGridUpload;
import dev.icehunter.fornax.voxel.VoxelWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-snapshot GPU -&gt; CPU readback of the camera slot's {@code voxelLightVolume} words (SOURCE +
 * DIRECT FIELD + INDIRECT segments), for the 2026-07-22 High-tier cell-flicker hunt. One keypress
 * ({@link FornaxDebugKeys}) arms a capture; the next {@link #SNAPSHOTS} client ticks each read back
 * the full slot, then a diff report is logged: WHICH cells changed between snapshots, in WHICH
 * segment, and by how much (10:10:10-unorm max component, expressed in the same 1/15 "bands" the
 * debug-16 heatmap posterizes to).
 *
 * <p>The single question this instrument answers: when the field visibly flickers with a stationary
 * camera, is the SOURCE segment changing (light_inject's inputs -- palette/summary/push-constants --
 * are not frame-stable), or is SOURCE rock-solid while the FIELD segment oscillates
 * (light_propagate's relaxation is not converging)? Every synchronization-level theory was already
 * eliminated live (host fence wait on light_propagate changed nothing), so the answer must be in the
 * values themselves.
 *
 * <p>Readback shape: {@link AnalyticLightListDebug}'s stall-based one-shot staging copy, verbatim --
 * acceptable at tick cadence for a hand-armed diagnostic (a ~48 KiB copy + queue idle, ~8 times).
 */
public final class LightVolumeDebug {
    private LightVolumeDebug() {}

    private static final int SNAPSHOTS = 8;
    private static final int MAX_REPORTED_CELLS = 24;

    private static int remainingSnapshots = 0;
    private static int capturedSlot = -1;
    private static final List<int[]> snapshots = new ArrayList<>();

    /** Arms a capture starting at the next client tick. */
    public static void requestCapture() {
        snapshots.clear();
        capturedSlot = -1;
        remainingSnapshots = SNAPSHOTS;
        FornaxMod.LOGGER.info("[Fornax][lightvol] capture armed: {} snapshots of the camera slot", SNAPSHOTS);
    }

    /** Called every client tick from {@link FornaxDebugKeys}'s existing tick handler. */
    public static void tick() {
        if (remainingSnapshots <= 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) {
            return;
        }
        VoxelWindow.WindowState window = VoxelWindow.currentState();
        TargetRegistry registry = VoxelWindow.attachedRegistry();
        if (registry == null || window.diameter() <= 1) {
            FornaxMod.LOGGER.warn("[Fornax][lightvol] no active voxel window -- capture aborted");
            remainingSnapshots = 0;
            return;
        }
        // Capture the section the CROSSHAIR block lives in, not the camera's (aim-driven since the
        // 2026-07-22 second round: the first capture proved the CAMERA slot static while the visible
        // flicker persisted -- the artifact cells may live in a neighboring section, and pointing the
        // crosshair at the flashing patch targets exactly those). Falls back to the camera section
        // when nothing is in reach (aiming at sky).
        Vec3 target;
        if (mc.hitResult != null && mc.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            target = mc.hitResult.getLocation();
        } else {
            target = mc.gameRenderer.mainCamera().position();
        }
        int d = window.diameter();
        int slot = slotOf(Math.floorDiv((int) Math.floor(target.x), 16),
                Math.floorDiv((int) Math.floor(target.y), 16),
                Math.floorDiv((int) Math.floor(target.z), 16), d);
        if (capturedSlot == -1) {
            capturedSlot = slot;
            FornaxMod.LOGGER.info("[Fornax][lightvol] capturing slot {} (target block {},{},{})",
                    slot, (int) Math.floor(target.x), (int) Math.floor(target.y), (int) Math.floor(target.z));
        } else if (capturedSlot != slot) {
            // Aim crossed a section boundary mid-capture -- the snapshots would compare different
            // world content. Abort loudly rather than log a misleading diff.
            FornaxMod.LOGGER.warn("[Fornax][lightvol] aim changed section mid-capture -- re-arm and hold aim steady");
            remainingSnapshots = 0;
            return;
        }

        int[] words = readSlot(registry, slot);
        if (words == null) {
            remainingSnapshots = 0;
            return;
        }
        snapshots.add(words);
        remainingSnapshots--;
        if (remainingSnapshots == 0) {
            report(slot);
        }
    }

    /** Java mirror of the shaders' slotOf(): ((y mod d) * d + (z mod d)) * d + (x mod d). */
    private static int slotOf(int sectionX, int sectionY, int sectionZ, int d) {
        return (Math.floorMod(sectionY, d) * d + Math.floorMod(sectionZ, d)) * d + Math.floorMod(sectionX, d);
    }

    private static void report(int slot) {
        int cells = BrickGridUpload.lightCellsPerSlot();
        int axis = BrickGridUpload.lightCellsPerSectionAxis();
        String[] segNames = {"SOURCE", "FIELD", "INDIRECT_FIELD", "INDIRECT_SOURCE"};
        FornaxMod.LOGGER.info("[Fornax][lightvol] slot {} ({} cells/axis, {} snapshots) diff report:",
                slot, axis, snapshots.size());
        for (int seg = 0; seg < segNames.length; seg++) {
            int changed = 0;
            StringBuilder examples = new StringBuilder();
            for (int c = 0; c < cells; c++) {
                int w = seg * cells + c;
                boolean cellChanged = false;
                for (int s = 1; s < snapshots.size(); s++) {
                    if (snapshots.get(s)[w] != snapshots.get(0)[w]) {
                        cellChanged = true;
                        break;
                    }
                }
                if (!cellChanged) {
                    continue;
                }
                changed++;
                if (changed <= MAX_REPORTED_CELLS) {
                    // cellIndex = (y * axis + z) * axis + x -- light_inject.comp's own formula, inverted.
                    int x = c % axis;
                    int z = (c / axis) % axis;
                    int y = c / (axis * axis);
                    examples.append(String.format("%n    cell (%d,%d,%d): bands ", x, y, z));
                    for (int s = 0; s < snapshots.size(); s++) {
                        examples.append(band(snapshots.get(s)[w]));
                        if (s < snapshots.size() - 1) {
                            examples.append("->");
                        }
                    }
                }
            }
            FornaxMod.LOGGER.info("[Fornax][lightvol]   {}: {} of {} cells changed across snapshots{}{}",
                    segNames[seg], changed, cells, examples,
                    changed > MAX_REPORTED_CELLS
                            ? String.format("%n    ... and %d more", changed - MAX_REPORTED_CELLS) : "");
        }
        snapshots.clear();
        capturedSlot = -1;
    }

    /** Max 10-bit channel of a packed 10:10:10 cell value, quantized to the heatmap's 0-15 band. */
    private static int band(int packed) {
        int r = packed & 0x3FF;
        int g = (packed >> 10) & 0x3FF;
        int b = (packed >> 20) & 0x3FF;
        return Math.min(15, (Math.max(r, Math.max(g, b)) * 16) / 1024);
    }

    private static int[] readSlot(TargetRegistry registry, int slot) {
        long slotBytes = BrickGridUpload.lightVolumeBytesPerSlot();
        long srcOffset = slot * slotBytes;
        synchronized (VulkanComputeBackend.SHARED_QUEUE_LOCK) {
            BufferInstance buffer = registry.getBuffer(BrickGridUpload.LIGHT_VOLUME_TARGET);
            if (buffer == null) {
                FornaxMod.LOGGER.warn("[Fornax][lightvol] voxelLightVolume not allocated -- capture aborted");
                return null;
            }
            if (srcOffset + slotBytes > buffer.sizeBytes()) {
                FornaxMod.LOGGER.warn("[Fornax][lightvol] slot {} out of bounds ({} + {} > {}) -- capture aborted",
                        slot, srcOffset, slotBytes, buffer.sizeBytes());
                return null;
            }
            try (VulkanComputeBackend backend = VulkanComputeBackend.tryCreate()) {
                if (backend == null) {
                    FornaxMod.LOGGER.warn("[Fornax][lightvol] no compute backend -- capture aborted");
                    return null;
                }
                return copySlot(backend, buffer.vkBuffer(), srcOffset, slotBytes);
            } catch (RuntimeException e) {
                FornaxMod.LOGGER.error("[Fornax][lightvol] readback failed", e);
                return null;
            }
        }
    }

    private static int[] copySlot(VulkanComputeBackend backend, long srcBuffer, long srcOffset, long size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo stagingInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(VK13.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            VmaAllocationCreateInfo allocCreateInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer bufferOut = stack.mallocLong(1);
            PointerBuffer allocationOut = stack.mallocPointer(1);
            VmaAllocationInfo allocInfoOut = VmaAllocationInfo.calloc(stack);
            int result = Vma.vmaCreateBuffer(backend.device().vma(), stagingInfo, allocCreateInfo,
                    bufferOut, allocationOut, allocInfoOut);
            if (result != VK13.VK_SUCCESS) {
                FornaxMod.LOGGER.error("[Fornax][lightvol] vmaCreateBuffer (staging) failed with VkResult {}", result);
                return null;
            }
            long stagingBuffer = bufferOut.get(0);
            long stagingAllocation = allocationOut.get(0);
            try {
                VkCommandBuffer cmd = backend.commandPool().allocateBuffer();
                VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
                VK13.vkBeginCommandBuffer(cmd, beginInfo);
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                        .srcOffset(srcOffset).dstOffset(0).size(size);
                VK13.vkCmdCopyBuffer(cmd, srcBuffer, stagingBuffer, region);
                VK13.vkEndCommandBuffer(cmd);
                try (var submission = backend.computeQueue().beginSubmit()) {
                    submission.executeCommands(cmd);
                }
                backend.computeQueue().waitIdle();
                backend.commandPool().reset();

                Vma.vmaInvalidateAllocation(backend.device().vma(), stagingAllocation, 0, size);
                ByteBuffer mapped = MemoryUtil.memByteBuffer(allocInfoOut.pMappedData(), (int) size);
                int[] words = new int[(int) (size / 4)];
                mapped.asIntBuffer().get(words);
                return words;
            } finally {
                Vma.vmaDestroyBuffer(backend.device().vma(), stagingBuffer, stagingAllocation);
            }
        }
    }
}
