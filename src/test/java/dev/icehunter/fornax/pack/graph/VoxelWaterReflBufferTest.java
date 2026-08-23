package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class VoxelWaterReflBufferTest {
    @Test
    void byteSizeIsTwoUintWordsPerPixel() {
        // 2 words/pixel * 4 bytes/word => 8 bytes/pixel.
        assertEquals(8L, VoxelWaterReflBuffer.byteSize(1, 1));
        assertEquals(1920L * 1080L * 8L, VoxelWaterReflBuffer.byteSize(1920, 1080));
    }

    @Test
    void targetNameIsOurVocabulary() {
        assertEquals("voxelWaterRefl", VoxelWaterReflBuffer.TARGET);
        assertEquals(2, VoxelWaterReflBuffer.WORDS_PER_PIXEL);
    }

    @Test
    void byteSizeDoesNotOverflowIntAtRetina() {
        // 3456x2168 * 8 must stay a long (would overflow a 32-bit int product).
        assertEquals(3456L * 2168L * 8L, VoxelWaterReflBuffer.byteSize(3456, 2168));
    }
}
