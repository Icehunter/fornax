package dev.icehunter.fornax.pack.graph;

import org.jspecify.annotations.Nullable;

/**
 * Allocates monotonically increasing timeline values for a graphics-read to compute-write image
 * reuse edge. One write may be outstanding at a time: compute waits the ticket's prior graphics
 * completion value, then the graphics queue publishes its release value after every reader of that
 * write has been recorded.
 *
 * <p>This class deliberately owns no Vulkan handles. Keeping the state transition pure makes the
 * otherwise silent cross-queue ordering contract directly testable without a device.
 */
final class CrossQueueImageReuseSequence {
    record Ticket(long waitValue, long releaseValue) {}

    private long publishedValue;
    @Nullable
    private Ticket pending;

    Ticket beginWrite() {
        if (pending != null) {
            throw new IllegalStateException("cross-queue image reuse already has an unpublished write");
        }
        pending = new Ticket(publishedValue, publishedValue + 1);
        return pending;
    }

    void cancel(Ticket ticket) {
        requirePending(ticket);
        pending = null;
    }

    void publishGraphicsCompletion(Ticket ticket) {
        requirePending(ticket);
        publishedValue = ticket.releaseValue();
        pending = null;
    }

    private void requirePending(Ticket ticket) {
        if (pending != ticket) {
            throw new IllegalArgumentException("ticket is not the pending cross-queue image reuse write");
        }
    }
}
