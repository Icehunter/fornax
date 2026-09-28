package dev.icehunter.fornax.voxel;

/** Values may be waited only after the corresponding graphics signal was recorded. */
final class VoxelFrameOrder {
    private long published;
    private boolean open;

    long begin() {
        if (open) throw new IllegalStateException("Voxel read frame has not ended");
        open = true;
        return published;
    }

    long nextRelease() {
        if (!open) throw new IllegalStateException("No voxel read frame is open");
        return Math.incrementExact(published);
    }

    void published(long value) {
        if (value != nextRelease()) throw new IllegalArgumentException("Wrong voxel read completion value");
        published = value;
        open = false;
    }

    boolean isOpen() { return open; }
}
