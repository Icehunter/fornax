package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import dev.icehunter.fornax.util.GpuFatalException;
import java.util.function.Supplier;

/** Completes graphics-recorded allocation transitions and clears before raw compute can use them. */
final class StorageTextureInitialization {
    // Matches the graphics submit deadline and Volume3DTexture's allocation-only completion wait.
    private static final long FENCE_TIMEOUT_NANOS = 5_000_000_000L;
    private boolean pending;

    void allocated(boolean storage) {
        pending |= storage;
    }

    void complete(Supplier<CommandEncoder> encoderFactory) {
        if (!pending) return;
        CommandEncoder encoder = encoderFactory.get();
        // The fence must name the batch containing the clears; creating it after submit names the next batch.
        try (GpuFence fence = encoder.createFence()) {
            encoder.submit();
            if (!fence.awaitCompletion(FENCE_TIMEOUT_NANOS)) {
                throw new GpuFatalException("storage texture initialization fence timeout ("
                        + FENCE_TIMEOUT_NANOS + "ns)");
            }
        }
        pending = false;
    }

    void requireComplete(String passName) {
        if (pending) {
            // A late flush can split an active render pass or wait on this very compute submission.
            throw new GpuFatalException("compute pass '" + passName
                    + "' reached pending storage texture initialization; complete it during prepare sizing");
        }
    }
}
