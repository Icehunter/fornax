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
        backend.reset();
        record.run();
        backend.submit();
        pending = true;
        complete();
    }

    private void complete() {
        if (pending) {
            backend.await();
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
