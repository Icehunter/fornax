package dev.icehunter.fornax.profile;

import java.util.OptionalLong;

/**
 * Minimal seam over one GPU timestamp query pool, existing so {@link PassTimer}'s ring/drain
 * ordering is unit-testable against a fake -- the real adapter (inside {@link PassTimer}) wraps
 * {@code GpuQueryPool} + {@code CommandEncoder.writeTimestamp}.
 *
 * <p>Implementations MUST mirror the real API's semantics exactly, because PassTimer's correctness
 * hinges on them: {@link #write} host-resets {@code index} <em>immediately</em> (the Vulkan backend
 * calls {@code vkResetQueryPool} on the host before queuing the GPU-side write), destroying any
 * still-unread value at that index; the new value only becomes readable once the GPU catches up,
 * some frames later. {@link #tryRead} is non-blocking: empty while the index is reset/pending.
 */
interface TimestampPool extends AutoCloseable {
    /** Host-resets {@code index} (any unread value there is lost NOW), then queues the GPU timestamp write. */
    void write(int index);

    /** Non-blocking read: the raw tick value if the GPU has resolved it, empty while reset/pending. */
    OptionalLong tryRead(int index);

    @Override
    void close();
}
