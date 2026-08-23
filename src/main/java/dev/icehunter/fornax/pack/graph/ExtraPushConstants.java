package dev.icehunter.fornax.pack.graph;

import java.nio.ByteBuffer;

/**
 * Lets ONE specific compute pass push more per-frame data than the fixed 32-byte
 * {@code PassParams.PUSH_CONSTANT_BASE_SIZE} block every compute pass otherwise shares -- without
 * growing that block for every fullscreen/compute pass that doesn't need the extra data. {@code
 * ComputePassRunner} only knows how to size a pipeline's push-constant range to {@code
 * PassParams.PUSH_CONSTANT_BASE_SIZE + byteSize()} and append these bytes after {@code PassParams}'
 * own 32 at dispatch time; it has zero knowledge of what the extra bytes mean -- the caller ({@code
 * GraphRunner}, which knows which specific pass needs what) owns that.
 */
public interface ExtraPushConstants {
    int byteSize();

    /** Writes exactly {@link #byteSize()} bytes into {@code buffer} starting at {@code offset}. */
    void writeInto(ByteBuffer buffer, int offset);
}
