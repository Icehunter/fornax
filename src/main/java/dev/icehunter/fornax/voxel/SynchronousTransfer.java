package dev.icehunter.fornax.voxel;

/** A reusable submission whose storage cannot be reset or freed before confirmed completion. */
final class SynchronousTransfer implements AutoCloseable {
    interface Backend extends AutoCloseable {
        void reset();
        void submit();
        void await();
        @Override void close();
    }

    private final Backend backend;
    private boolean pending;
    private boolean closed;

    SynchronousTransfer(Backend backend) { this.backend = backend; }

    void execute(Runnable record) {
        if (closed) throw new IllegalStateException("Upload resources are closed");
        complete();
        long start = VoxelRefillTelemetry.start();
        try { backend.reset(); }
        finally { VoxelRefillTelemetry.finish(VoxelRefillTelemetry.Phase.RESET, start); }
        start = VoxelRefillTelemetry.start();
        try { record.run(); }
        finally { VoxelRefillTelemetry.finish(VoxelRefillTelemetry.Phase.RECORD, start); }
        start = VoxelRefillTelemetry.start();
        try { backend.submit(); }
        finally { VoxelRefillTelemetry.finish(VoxelRefillTelemetry.Phase.SUBMIT, start); }
        pending = true;
        complete();
    }

    private void complete() {
        if (pending) {
            long start = VoxelRefillTelemetry.start();
            try { backend.await(); }
            finally { VoxelRefillTelemetry.finish(VoxelRefillTelemetry.Phase.WAIT, start); }
            pending = false;
        }
    }

    @Override public void close() {
        if (closed) return;
        // A failed wait leaves pending true. Closing retries the wait.
        complete();
        backend.close();
        closed = true;
    }
}
