package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.pipeline.FornaxChunkVertex;

import java.util.Optional;

/** Rules for which part of the uploaded terrain mesh to copy for rendering. */
public final class TerrainMeshSelection {
    // The public terrain metadata format has seven facing slices and unsigned 32-bit words.
    private static final int FACING_SLICES = 7;
    private static final long MAX_UNSIGNED_WORD = 0xffff_ffffL;
    // Each shared-index primitive is a quad: local triangles (0,1,2) and (2,3,0).
    private static final int VERTICES_PER_QUAD = 4;
    public record VertexRange(long byteOffset, long vertexCount, long byteLength) { }

    private TerrainMeshSelection() { }

    /** Validates unsigned metadata before turning the vertex offset into a byte offset. A nonzero
     * metadata pointer is not evidence of vertices: an empty sum is rejected. Local index layouts
     * need their actual indices, so they cannot use the fixed shared-quad reconstruction here. */
    public static Optional<VertexRange> validRange(long baseVertex, long[] facingVertexCounts,
                                                   long bufferBytes, boolean localIndices) {
        if (localIndices || baseVertex < 0 || baseVertex > MAX_UNSIGNED_WORD || bufferBytes < 0
                || facingVertexCounts == null || facingVertexCounts.length != FACING_SLICES) return Optional.empty();
        try {
            long vertexCount = 0;
            for (long count : facingVertexCounts) {
                if (count < 0 || count > MAX_UNSIGNED_WORD || count % VERTICES_PER_QUAD != 0) return Optional.empty();
                vertexCount = Math.addExact(vertexCount, count);
            }
            if (vertexCount == 0) return Optional.empty();
            long offset = Math.multiplyExact(baseVertex, FornaxChunkVertex.STRIDE);
            long length = Math.multiplyExact(vertexCount, FornaxChunkVertex.STRIDE);
            if (Math.addExact(offset, length) > bufferBytes) return Optional.empty();
            return Optional.of(new VertexRange(offset, vertexCount, length));
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
    }

}
