package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class AnalyticLightListBufferTest {
    @Test
    void targetNameIsOurVocabulary() {
        assertEquals("analyticLightList", AnalyticLightListBuffer.TARGET);
    }

    @Test
    void capacityConstantsMatchTheDesignedLayout() {
        assertEquals(256, AnalyticLightListBuffer.MAX_LIGHTS);
        assertEquals(6, AnalyticLightListBuffer.WORDS_PER_LIGHT);
    }

    @Test
    void byteSizeIsCountWordPlusMaxLightsTimesWordsPerLight() {
        // word 0 (count) + 256 lights * 6 words/light = 1537 words * 4 bytes/word = 6148 bytes.
        long expected = (1L + 256L * 6L) * 4L;
        assertEquals(6148L, expected);
        assertEquals(expected, AnalyticLightListBuffer.BYTE_SIZE);
    }

    @Test
    void byteSizeIsFourByteAligned() {
        // ensureBufferSize's own contract (vkCmdFillBuffer requires a multiple of 4) -- a fixed-size
        // buffer target must satisfy this by construction, not by luck.
        assertEquals(0L, AnalyticLightListBuffer.BYTE_SIZE % 4L);
    }
}
