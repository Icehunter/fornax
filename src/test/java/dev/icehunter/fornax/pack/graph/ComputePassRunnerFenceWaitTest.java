package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK13;

/**
 * {@code fenceWaitSucceeded} is the pure decision behind every {@code vkWaitForFences} call in
 * {@code ComputePassRunner}: whether the caller may treat the waited slot as drained. Pinned as a
 * plain {@code int -> boolean} function so the three real call sites (which need a live GPU) don't
 * have to be exercised to prove the decision itself is right.
 */
class ComputePassRunnerFenceWaitTest {
    @Test
    void vkSuccessMeansTheSlotIsDrained() {
        assertTrue(ComputePassRunner.fenceWaitSucceeded(VK13.VK_SUCCESS, "test"));
    }

    @Test
    void vkTimeoutMeansTheSlotIsNotDrained() {
        // FENCE_WAIT_TIMEOUT is UINT64_MAX, so a real timeout is not the realistic outcome -- but the
        // helper must still refuse to treat it as success if one ever does happen.
        assertFalse(ComputePassRunner.fenceWaitSucceeded(VK13.VK_TIMEOUT, "test"));
    }

    @Test
    void vkErrorDeviceLostMeansTheSlotIsNotDrained() {
        // The realistic non-timeout outcome given an effectively infinite wait: the wait returning
        // early with a lost device, not the buffer actually completing.
        assertFalse(ComputePassRunner.fenceWaitSucceeded(VK13.VK_ERROR_DEVICE_LOST, "test"));
    }

    @Test
    void vkErrorOutOfDeviceMemoryMeansTheSlotIsNotDrained() {
        assertFalse(ComputePassRunner.fenceWaitSucceeded(VK13.VK_ERROR_OUT_OF_DEVICE_MEMORY, "test"));
    }
}
