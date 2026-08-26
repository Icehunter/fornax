package dev.icehunter.fornax.util;

import com.mojang.blaze3d.GpuDeviceLossException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link GpuFatalErrors#rethrowIfFatal} is the one place a broad catch around GPU work decides
 * whether the failure is unrecoverable (rethrow) or ordinary (return, let the caller degrade). No
 * GPU device needed: the predicate is pure type-checking.
 */
class GpuFatalErrorsTest {
    @Test
    void aGpuDeviceLossExceptionIsRethrown() {
        GpuDeviceLossException failure = new GpuDeviceLossException("device lost");

        assertThrows(GpuDeviceLossException.class, () -> GpuFatalErrors.rethrowIfFatal(failure));
    }

    @Test
    void aGpuFatalExceptionIsRethrown() {
        GpuFatalException failure = new GpuFatalException("interop timeline wait failed");

        assertThrows(GpuFatalException.class, () -> GpuFatalErrors.rethrowIfFatal(failure));
    }

    @Test
    void anOrdinaryIllegalStateExceptionReturnsNormally() {
        // The load-bearing negative case: this engine raises IllegalStateException for dozens of
        // unrelated reasons (validation failures, malformed layouts, missing builtins). None of
        // those are a dead device, and rethrowIfFatal must not be widened to string-match VK error
        // text -- see BlockAtlasOverflow's own compositor catch, where a Vulkan OOM specifically
        // surfaces as a plain IllegalStateException (VulkanUtils.crashIfFailure only wraps
        // VK_ERROR_DEVICE_LOST as GpuDeviceLossException) and is deliberately left on the soft
        // degrade path rather than being reclassified as fatal here.
        assertDoesNotThrow(() -> GpuFatalErrors.rethrowIfFatal(new IllegalStateException("ordinary bug")));
    }

    @Test
    void anOrdinaryRuntimeExceptionReturnsNormally() {
        assertDoesNotThrow(() -> GpuFatalErrors.rethrowIfFatal(new RuntimeException("ordinary bug")));
    }
}
