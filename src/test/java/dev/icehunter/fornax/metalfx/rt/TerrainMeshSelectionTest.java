package dev.icehunter.fornax.metalfx.rt;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hand-derived byte ranges; no GPU, unsafe metadata, or world required. */
class TerrainMeshSelectionTest {
    private Optional<TerrainMeshSelection.VertexRange> range(long base, long[] counts, long bytes, boolean local) {
        return TerrainMeshSelection.validRange(base, counts, bytes, local);
    }

    @Test void sumsAllFacingSlicesAndConvertsVertexOffsetsToBytesExactlyOnce() {
        // Seven slices total 32 vertices: 7*24 byte offset, 32*24 bytes, ending at byte 936.
        var selected = range(7, new long[]{4, 8, 0, 12, 4, 0, 4}, 936, false).orElseThrow();
        assertEquals(168, selected.byteOffset());
        assertEquals(32, selected.vertexCount());
        assertEquals(768, selected.byteLength());
        assertTrue(range(7, new long[]{4, 8, 0, 12, 4, 0, 4}, 935, false).isEmpty());
    }

    @Test void anArenaBaseNeedNotBeQuadAlignedWhenEachLocalSliceIsQuadAligned() {
        // The arena can begin this allocation at vertex three; its first local quad still has four vertices.
        var selected = range(3, new long[]{4, 0, 0, 0, 0, 0, 0}, 168, false).orElseThrow();
        assertEquals(72, selected.byteOffset());
        assertEquals(4, selected.vertexCount());
    }

    @Test void unsignedMetadataKeepsHighBitsWithoutSignedNarrowing() {
        // 0xffffffff vertices * 24 bytes + one four-vertex quad = 103079215176 bytes.
        var highBase = range(0xffff_ffffL, new long[]{4, 0, 0, 0, 0, 0, 0}, 103079215176L, false).orElseThrow();
        assertEquals(103079215080L, highBase.byteOffset());
        // Seven maximum quad-aligned uint32 counts sum beyond uint32; do not wrap the sum.
        var many = range(0, new long[]{0xffff_fffcL, 0xffff_fffcL, 0xffff_fffcL, 0xffff_fffcL,
                0xffff_fffcL, 0xffff_fffcL, 0xffff_fffcL}, 721554505056L, false).orElseThrow();
        assertEquals(30064771044L, many.vertexCount());
        assertEquals(721554505056L, many.byteLength());
    }

    @Test void metadataPresenceWithoutVerticesAndLocalIndexLayoutsAreNotQuadGeometry() {
        assertTrue(range(10, new long[7], 1000, false).isEmpty());
        assertTrue(range(0, new long[]{4, 0, 0, 0, 0, 0, 0}, 96, true).isEmpty());
        assertTrue(range(0, new long[]{1, 3, 0, 0, 0, 0, 0}, 96, false).isEmpty(),
                "a quad cannot cross the boundary between two facing slices");
    }

    @Test void malformedUnsignedRangesAndBufferExtentsAreRejectedWithoutOverflow() {
        var quad = new long[]{4, 0, 0, 0, 0, 0, 0};
        assertTrue(range(-1, quad, Long.MAX_VALUE, false).isEmpty());
        assertTrue(range(0x1_0000_0000L, quad, Long.MAX_VALUE, false).isEmpty());
        assertTrue(range(Long.MAX_VALUE, quad, Long.MAX_VALUE, false).isEmpty());
        assertTrue(range(0, new long[]{-4, 0, 0, 0, 0, 0, 0}, Long.MAX_VALUE, false).isEmpty());
        assertTrue(range(0, new long[]{0x1_0000_0000L, 0, 0, 0, 0, 0, 0}, Long.MAX_VALUE, false).isEmpty());
        assertTrue(range(0, quad, -1, false).isEmpty());
        assertTrue(range(4, quad, 95, false).isEmpty());
        assertTrue(range(0, new long[6], 1000, false).isEmpty());
        assertTrue(range(0, null, 1000, false).isEmpty());
    }

}
