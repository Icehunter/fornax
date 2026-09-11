package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract for the entity occluder set byte layout, the part that needs no Vulkan. */
class EntityOccluderBufferTest {

    @Test
    void byteSizeIsHeaderPlusMaxOccludersTimesFloatsPerOccluder() {
        // (4 header floats + 64 occluders * 12 floats each) * 4 bytes per float = 3088 bytes.
        long expected = (long) (EntityOccluderBuffer.HEADER_FLOATS
                + EntityOccluderBuffer.MAX_OCCLUDERS * EntityOccluderBuffer.FLOATS_PER_OCCLUDER)
                * Float.BYTES;
        assertEquals(expected, EntityOccluderBuffer.BYTE_SIZE);
        assertEquals(3088L, EntityOccluderBuffer.BYTE_SIZE);
    }

    @Test
    void byteSizeIsAFourByteMultiple() {
        // vkCmdFillBuffer requires a size that is a multiple of 4.
        assertEquals(0, EntityOccluderBuffer.BYTE_SIZE % 4);
    }

    @Test
    void byteSizeFitsOneInlineUpdateRange() {
        assertTrue(EntityOccluderBuffer.BYTE_SIZE <= EngineBufferUploadQueue.MAX_RANGE_BYTES,
                "one send to the GPU must fit in a single vkCmdUpdateBuffer range");
    }

    @Test
    void kindNoneIsZeroAndKindsAreDistinct() {
        assertEquals(0, EntityOccluderBuffer.KIND_NONE);
        assertEquals(6, Set.of(
                EntityOccluderBuffer.KIND_NONE, EntityOccluderBuffer.KIND_PLAYER,
                EntityOccluderBuffer.KIND_OTHER_PLAYER, EntityOccluderBuffer.KIND_LIVING,
                EntityOccluderBuffer.KIND_ITEM, EntityOccluderBuffer.KIND_OTHER).size());
    }

    @Test
    void targetNameIsOurVocabulary() {
        assertEquals("entityOccluders", EntityOccluderBuffer.TARGET);
    }
}
