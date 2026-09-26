package dev.icehunter.fornax.rt.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRAccelerationStructure;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The 64-byte instance record is read by the driver, not by any code here, so a wrong offset is a
 * structure that places meshes at the wrong spot or references the wrong BLAS, with no error. The
 * layout is pinned word by word against the Vulkan specification's struct.
 */
class InstanceRecordTest {

    private static ByteBuffer record(float x, float y, float z, int customIndex, int flags, long reference) {
        ByteBuffer out = ByteBuffer.allocate(InstanceRecord.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        InstanceRecord.write(out, 0, x, y, z, customIndex, flags, reference);
        return out;
    }

    @Test
    void theRecordIsSixtyFourBytesWithTheSpecOffsets() {
        // VkTransformMatrixKHR is float[3][4] = 48 bytes; two packed uint32s; one uint64.
        assertEquals(64, InstanceRecord.BYTES);
        assertEquals(0, InstanceRecord.TRANSFORM_OFFSET);
        assertEquals(48, InstanceRecord.CUSTOM_INDEX_AND_MASK_OFFSET);
        assertEquals(52, InstanceRecord.SBT_OFFSET_AND_FLAGS_OFFSET);
        assertEquals(56, InstanceRecord.REFERENCE_OFFSET);
    }

    @Test
    void theFlagConstantsAreTheBindingsOwn() {
        assertEquals(KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR,
                InstanceRecord.FLAG_TRIANGLE_FACING_CULL_DISABLE);
        assertEquals(KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_FORCE_OPAQUE_BIT_KHR,
                InstanceRecord.FLAG_FORCE_OPAQUE);
    }

    @Test
    void translationLandsInTheFourthColumnOfEachRowMajorRow() {
        ByteBuffer r = record(5f, -6f, 7.5f, 0, 0, 0x1000L);
        // Row-major 3x4: row i occupies floats 4i..4i+3, translation is the last of each row.
        assertEquals(1f, r.getFloat(0));
        assertEquals(5f, r.getFloat(12));
        assertEquals(1f, r.getFloat(20));
        assertEquals(-6f, r.getFloat(28));
        assertEquals(1f, r.getFloat(40));
        assertEquals(7.5f, r.getFloat(44));
        // Off-diagonal rotation is zero.
        assertEquals(0f, r.getFloat(4));
        assertEquals(0f, r.getFloat(8));
        assertEquals(0f, r.getFloat(16));
        assertEquals(0f, r.getFloat(24));
        assertEquals(0f, r.getFloat(32));
        assertEquals(0f, r.getFloat(36));
    }

    @Test
    void customIndexTakesTheLowTwentyFourBitsAndTheMaskTheTopByte() {
        ByteBuffer r = record(0, 0, 0, 0xABCDEF, 0, 0x1000L);
        assertEquals(0xFFABCDEF, r.getInt(48));
    }

    @Test
    void flagsTakeTheTopByteOfTheSecondWordWithAZeroShaderBindingTableOffset() {
        ByteBuffer r = record(0, 0, 0, 1, InstanceRecord.FLAG_TRIANGLE_FACING_CULL_DISABLE, 0x1000L);
        assertEquals(0x01000000, r.getInt(52));
    }

    @Test
    void theReferenceIsTheBlasAddressAtByteFiftySix() {
        ByteBuffer r = record(0, 0, 0, 1, 0, 0x0000_1234_5678_9ABCL);
        assertEquals(0x0000_1234_5678_9ABCL, r.getLong(56));
    }

    @Test
    void anOffsetPlacesTheWholeRecordLater() {
        ByteBuffer out = ByteBuffer.allocate(3 * InstanceRecord.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        InstanceRecord.write(out, 128, 1f, 2f, 3f, 7, 0, 0x2000L);
        assertEquals(3f, out.getFloat(128 + 44));
        assertEquals(0x2000L, out.getLong(128 + 56));
        assertEquals(0, out.getLong(56), "the earlier slots are untouched");
    }

    @Test
    void anOpaqueInstanceIsRefusedBecauseItSkipsTheAlphaTest() {
        assertThrows(IllegalArgumentException.class,
                () -> record(0, 0, 0, 0, InstanceRecord.FLAG_FORCE_OPAQUE, 0x1000L));
    }

    @Test
    void aCustomIndexPastTwentyFourBitsIsRefusedRatherThanAliased() {
        assertEquals((1 << 24) - 1, InstanceRecord.MAX_CUSTOM_INDEX);
        assertThrows(IllegalArgumentException.class, () -> record(0, 0, 0, 1 << 24, 0, 0x1000L));
        assertThrows(IllegalArgumentException.class, () -> record(0, 0, 0, -1, 0, 0x1000L));
    }

    @Test
    void aNullReferenceAndABigEndianBufferAreBothRefused() {
        assertThrows(IllegalArgumentException.class, () -> record(0, 0, 0, 0, 0, 0L));
        ByteBuffer bigEndian = ByteBuffer.allocate(InstanceRecord.BYTES).order(ByteOrder.BIG_ENDIAN);
        assertThrows(IllegalArgumentException.class,
                () -> InstanceRecord.write(bigEndian, 0, 0, 0, 0, 0, 0, 0x1000L));
    }
}
