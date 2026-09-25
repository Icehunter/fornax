package dev.icehunter.fornax.rt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtlasUvEncodingTest {
    @Test
    void defaultQueriesKeepTheirLegacyEncodingAndWireZero() {
        assertEquals(AtlasUvEncoding.PACKED_HALF,
                new BufferQuery(RayQueryKind.CLOSEST_HIT, 1, 2, 1, "trace").atlasUvEncoding());
        assertEquals(0, AtlasUvEncoding.PACKED_HALF.wireValue());
        assertEquals(1, AtlasUvEncoding.TEXEL_U16.wireValue());
    }

    @Test
    void exactAddressesRejectEmptyOrUnrepresentableAtlasDimensionsWithoutRestrictingLegacy() {
        // Unsigned 16-bit indices cover 0..65535, hence an extent of 65536 is still representable.
        assertDoesNotThrow(() -> AtlasUvEncoding.TEXEL_U16.validateAtlasDimensions(65536, 65536));
        assertDoesNotThrow(() -> AtlasUvEncoding.TEXEL_U16.validateAtlasDimensions(16384, 8192));
        for (long[] size : new long[][]{{0,1},{1,0},{65537,1},{1,65537},{-1,1}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AtlasUvEncoding.TEXEL_U16.validateAtlasDimensions(size[0], size[1]));
            assertDoesNotThrow(() -> AtlasUvEncoding.PACKED_HALF.validateAtlasDimensions(size[0], size[1]));
        }
    }
}
