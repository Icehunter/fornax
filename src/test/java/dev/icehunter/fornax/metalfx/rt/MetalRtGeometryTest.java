package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.voxel.BrickGridUpload;
import dev.icehunter.fornax.voxel.VoxelFaceTexture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link MetalRtGeometry}'s pure helpers, the per-target byte stride and offset and the
 * dirty-slot drain, against a live {@code VulkanDevice}-free harness. Anything that needs a real
 * device (buffer/memory allocation, the Metal export, the copy recording) is out of scope here; see
 * the class javadoc on why a GPU-backed test does not belong in this suite.
 */
class MetalRtGeometryTest {
    @AfterEach
    void resetActiveState() {
        MetalRtGeometry.setActive(false);
    }

    @Test
    void bytesPerSlotMatchesBrickGridUploadsRealPerSlotStrides() {
        // Occupancy and face-seal/palette are pinned directly against BrickGridUpload's own public
        // constants: one source of truth, with no re-hardcoded literal to drift silently. Payload is
        // deliberately NOT BrickGridUpload.PAYLOAD_BYTES_PER_SLOT (that totals payload+occupancy for
        // VRAM accounting); it is VOXELS_PER_SECTION, PAYLOAD_TARGET's real per-slot allocation size.
        assertEquals(BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT,
                MetalRtGeometry.bytesPerSlot(BrickGridUpload.OCCUPANCY_TARGET));
        assertEquals(BrickGridUpload.VOXELS_PER_SECTION,
                MetalRtGeometry.bytesPerSlot(BrickGridUpload.PAYLOAD_TARGET));
        assertEquals(BrickGridUpload.FACE_SEAL_BYTES_PER_SLOT,
                MetalRtGeometry.bytesPerSlot(BrickGridUpload.FACE_SEAL_TARGET));
        assertEquals(BrickGridUpload.PALETTE_BYTES_PER_SLOT,
                MetalRtGeometry.bytesPerSlot(BrickGridUpload.PALETTE_TARGET));
        // Face texture's own per-slot stride: WORDS_PER_SLOT words times 4 bytes, pinned against
        // VoxelFaceTexture's own public constant rather than a re-hardcoded literal.
        assertEquals(VoxelFaceTexture.BYTES_PER_SLOT,
                MetalRtGeometry.bytesPerSlot(VoxelFaceTexture.TARGET));
    }

    @Test
    void bytesPerSlotRejectsAnyOtherTargetName() {
        assertThrows(IllegalArgumentException.class, () -> MetalRtGeometry.bytesPerSlot("voxelBrickIndex"));
    }

    @Test
    void slotOffsetIsSlotTimesStride() {
        int slot = 7;
        assertEquals((long) slot * BrickGridUpload.OCCUPANCY_BYTES_PER_SLOT,
                MetalRtGeometry.slotOffset(BrickGridUpload.OCCUPANCY_TARGET, slot));
        assertEquals((long) slot * BrickGridUpload.PALETTE_BYTES_PER_SLOT,
                MetalRtGeometry.slotOffset(BrickGridUpload.PALETTE_TARGET, slot));
        assertEquals((long) slot * VoxelFaceTexture.BYTES_PER_SLOT,
                MetalRtGeometry.slotOffset(VoxelFaceTexture.TARGET, slot));
    }

    @Test
    void markDirtyIsANoOpWhileInactive() {
        MetalRtGeometry.setActive(false);
        MetalRtGeometry.markDirty(5);
        assertEquals(List.of(), MetalRtGeometry.drainDirty(), "inactive markDirty must record nothing");
    }

    @Test
    void drainDirtyReturnsAscendingDeduplicatedSlots() {
        MetalRtGeometry.setActive(true);
        MetalRtGeometry.markDirty(3);
        MetalRtGeometry.markDirty(1);
        MetalRtGeometry.markDirty(3);

        assertEquals(List.of(1, 3), MetalRtGeometry.drainDirty(),
                "drain order is ascending by slot index, each slot appearing once");
    }

    @Test
    void drainDirtyEmptiesTheSetSoASecondDrainIsEmpty() {
        MetalRtGeometry.setActive(true);
        MetalRtGeometry.markDirty(2);
        assertTrue(!MetalRtGeometry.drainDirty().isEmpty());
        assertEquals(List.of(), MetalRtGeometry.drainDirty(), "a second drain sees nothing new");
    }

    @Test
    void deactivatingDropsSlotsMarkedSinceTheLastDrain() {
        MetalRtGeometry.setActive(true);
        MetalRtGeometry.markDirty(9);
        MetalRtGeometry.setActive(false);
        MetalRtGeometry.setActive(true);
        assertEquals(List.of(), MetalRtGeometry.drainDirty(),
                "going inactive clears any slots not yet drained");
    }

    @Test
    void markAllDirtySeedsRegardlessOfActiveState() {
        MetalRtGeometry.setActive(false);
        MetalRtGeometry.markAllDirty(List.of(4, 2, 4));

        assertEquals(List.of(2, 4), MetalRtGeometry.drainDirty(),
                "markAllDirty (the allocation-time seed) is not gated on isActive, unlike markDirty");
    }

    @Test
    void rayTracingWindowCapIsSeventeen() {
        // Radius 8; see the class javadoc's "capped at" paragraph: Metal's per-frame instance
        // acceleration structure rebuild guidance is a few thousand instances.
        assertEquals(17, MetalRtGeometry.MAX_DIAMETER);
    }

    @Test
    void needsReallocationIsTrueBeforeAnythingIsEverAllocated() {
        // No test in this suite calls ensureBuffers (it needs a live VulkanDevice, out of scope
        // here; see the class javadoc), so occupancy stays null and every diameter needs a
        // reallocation, regardless of the registry passed (here null, meaning "no registry
        // attached yet", the same as MetalRtShadowPass sees before a window exists).
        assertTrue(MetalRtGeometry.needsReallocation(null, 0));
        assertTrue(MetalRtGeometry.needsReallocation(null, 9));
        assertTrue(MetalRtGeometry.needsReallocation(null, MetalRtGeometry.MAX_DIAMETER));
    }

    /** Reflection-seeds the private allocation state {@code ensureBuffers} would otherwise set
     * (out of scope here; it needs a live {@code VulkanDevice}) to pin the second, independent
     * half of {@code needsReallocation}'s contract: even with an unchanged diameter, a flip of
     * {@code VoxelFaceTexture.TARGET}'s enablement (a pack reload, or a changed {@code enabled_if})
     * must still report a reallocation is needed, since that decides whether {@link
     * MetalRtGeometry#faceTexture()} should exist at all. */
    @Test
    void needsReallocationIsTrueWhenFaceTextureEnablementChangesEvenAtTheSameDiameter()
            throws NoSuchFieldException, IllegalAccessException {
        Field diameterField = MetalRtGeometry.class.getDeclaredField("allocatedDiameter");
        diameterField.setAccessible(true);
        Field occupancyField = MetalRtGeometry.class.getDeclaredField("occupancy");
        occupancyField.setAccessible(true);
        Field faceTextureEnabledField = MetalRtGeometry.class.getDeclaredField("allocatedFaceTextureEnabled");
        faceTextureEnabledField.setAccessible(true);
        int previousDiameter = diameterField.getInt(null);
        Object previousOccupancy = occupancyField.get(null);
        boolean previousFaceTextureEnabled = faceTextureEnabledField.getBoolean(null);
        try {
            int diameter = 9;
            diameterField.setInt(null, diameter);
            occupancyField.set(null, new MetalRtGeometry.ExportedBuffer(1L, 1L, 1L, 1L));
            faceTextureEnabledField.setBoolean(null, false);

            assertFalse(MetalRtGeometry.needsReallocation(null, diameter),
                    "same diameter, no registry (treated as not enabled): already matches");
            assertTrue(MetalRtGeometry.needsReallocation(enabledFaceTextureRegistry(), diameter),
                    "same diameter, but the registry enables the face texture buffer: must reallocate");

            faceTextureEnabledField.setBoolean(null, true);
            assertFalse(MetalRtGeometry.needsReallocation(enabledFaceTextureRegistry(), diameter),
                    "same diameter, already-enabled state matches: no reallocation needed");
            assertTrue(MetalRtGeometry.needsReallocation(null, diameter),
                    "same diameter, but with no registry (treated as disabled): must reallocate");
        } finally {
            diameterField.setInt(null, previousDiameter);
            occupancyField.set(null, previousOccupancy);
            faceTextureEnabledField.setBoolean(null, previousFaceTextureEnabled);
        }
    }

    /** A minimal {@code TargetRegistry} stand-in is not available without a live pack load, so this
     * test builds a real one from a fixture graph that declares {@code voxelFaceTexture} as an
     * unconditionally-enabled buffer target (null {@code enabledIf}), exercising {@code
     * needsReallocation}'s actual {@code TargetRegistry.isEnabledBufferTarget} call rather than a
     * mock; same construction shape as {@code VoxelFaceTextureTest
     * .optionalTargetHonoursDeclarationAndCompileGate}. */
    private static dev.icehunter.fornax.pack.graph.TargetRegistry enabledFaceTextureRegistry() {
        var targets = new java.util.LinkedHashMap<String, dev.icehunter.fornax.pack.TargetSpec>();
        targets.put(VoxelFaceTexture.TARGET, new dev.icehunter.fornax.pack.TargetSpec(
                VoxelFaceTexture.TARGET, null, 0, false, null,
                dev.icehunter.fornax.pack.graph.TargetBasis.RENDER, dev.icehunter.fornax.pack.graph.TargetKind.BUFFER));
        var graph = new dev.icehunter.fornax.pack.GraphSpec(targets, java.util.List.of());
        return dev.icehunter.fornax.pack.graph.TargetRegistry.create(graph, java.util.Map.of());
    }

    @Test
    void allocatedSlotCountAndSlotInExportedWindowAreZeroAndFalseBeforeAnyAllocation() {
        assertEquals(0L, MetalRtGeometry.allocatedSlotCount());
        assertFalse(MetalRtGeometry.slotInExportedWindow(0));
        assertFalse(MetalRtGeometry.slotInExportedWindow(-1));
    }

    /** Deliberately source-level rather than reflective, following {@code
     * BuiltinResolutionContractTest}'s own precedent: exercising {@code recordSlotCopies} for real
     * needs a live {@code VulkanDevice} (out of scope here; see the class javadoc), and the bug
     * this pins is specifically about allocation COUNT, which no return value exposes.
     *
     * <p>The bug: the original code called {@code VkBufferCopy.calloc(1, stack)} once per slot per
     * target, inside a single {@code MemoryStack} frame pushed once for the whole call and never
     * popped between slots. A world-load or diameter-reallocation dirty burst (thousands of slots,
     * see {@code ensureBuffers}'s {@code markAllDirty} seed) overflowed the LWJGL thread stack (64
     * KB by default) outright, observed live as {@code OutOfMemoryError: Out of stack space} at
     * {@code VkBufferCopy.calloc}. The fix hoists one reusable {@code VkBufferCopy.Buffer} per
     * target above the loop; {@code vkCmdCopyBuffer} reads the struct's fields at call time and
     * keeps no pointer to it afterward, so mutating and resubmitting the same four structs for
     * every slot is correct. */
    @Test
    void recordSlotCopiesAllocatesOneVkBufferCopyPerTargetNotOnePerSlot() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/dev/icehunter/fornax/metalfx/rt/MetalRtGeometry.java"));
        int methodStart = source.indexOf("public static List<Integer> recordSlotCopies(");
        assertTrue(methodStart >= 0, "recordSlotCopies not found: did it move or get renamed?");
        int loopStart = source.indexOf("for (int slot : slots)", methodStart);
        assertTrue(loopStart > methodStart, "the per-slot loop not found inside recordSlotCopies");

        String beforeLoop = source.substring(methodStart, loopStart);
        long callocsBeforeLoop = beforeLoop.lines().filter(line -> line.contains("VkBufferCopy.calloc")).count();
        assertEquals(5, callocsBeforeLoop,
                "expected one VkBufferCopy.calloc per target (occupancy/payload/faceSeal/palette/"
                        + "faceTexture), hoisted above the per-slot loop");

        int methodEnd = source.indexOf("\n    }\n", loopStart);
        String loopBody = source.substring(loopStart, methodEnd);
        assertFalse(loopBody.contains("VkBufferCopy.calloc"),
                "a VkBufferCopy.calloc inside the per-slot loop is exactly the stack-overflow bug "
                        + "this test pins; reuse the structs allocated above the loop instead");
    }


}
