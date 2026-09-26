package dev.icehunter.fornax.rt.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two rules a buffer here cannot break: it is addressable, and it can be cleared with one fill. The
 * driver reports neither failure: an unaddressable buffer returns address 0, and a misaligned fill
 * is a validation error the shipped game never sees.
 */
class RtBufferTest {

    @Test
    void everyBufferCarriesTheDeviceAddressUsageWhateverTheCallerAsked() {
        assertEquals(VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, RtBuffer.BASE_USAGE);
        int usage = RtBuffer.usageFor(VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        assertTrue((usage & VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0);
        assertTrue((usage & VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) != 0, "the caller's own usage survives");
        assertEquals(RtBuffer.BASE_USAGE, RtBuffer.usageFor(0));
    }

    @Test
    void sizesArePositiveMultiplesOfFourSoTheWholeBufferCanBeFilled() {
        // vkCmdFillBuffer requires size to be a multiple of 4.
        assertEquals(4, RtBuffer.checkedSize(4));
        assertEquals(4096, RtBuffer.checkedSize(4096));
        assertThrows(IllegalArgumentException.class, () -> RtBuffer.checkedSize(0));
        assertThrows(IllegalArgumentException.class, () -> RtBuffer.checkedSize(-4));
        assertThrows(IllegalArgumentException.class, () -> RtBuffer.checkedSize(6));
        assertThrows(IllegalArgumentException.class, () -> RtBuffer.checkedSize(4097));
    }

    @Test
    void theRecordRefusesDeadHandlesAndAZeroAddress() {
        assertThrows(IllegalArgumentException.class, () -> new RtBuffer(0, 1, 4, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new RtBuffer(1, 0, 4, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new RtBuffer(1, 1, 4, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RtBuffer(1, 1, 6, 1, 0));
    }

    @Test
    void aDeviceLocalBufferHasNoHostWindow() {
        RtBuffer deviceLocal = new RtBuffer(1, 1, 4, 1, 0);
        assertThrows(IllegalStateException.class, deviceLocal::mappedBytes);
    }
}
