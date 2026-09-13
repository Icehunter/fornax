package dev.icehunter.fornax.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BlockAtlasViewTest {
    @Test
    void captureAdvancesGenerationEveryTimeSoAConsumerCanDetectAReload() {
        BlockAtlasView.clear();
        int base = BlockAtlasView.generation();
        BlockAtlasView.capture(null, null);
        int afterFirstCapture = BlockAtlasView.generation();
        assertNotEquals(base, afterFirstCapture, "a capture must move the generation counter");
        BlockAtlasView.capture(null, null);
        assertNotEquals(afterFirstCapture, BlockAtlasView.generation(),
                "a second capture must move the generation counter again, even with the same arguments");
    }

    @Test
    void clearAlsoAdvancesGenerationSinceItReplacesTheTextureReferenceWithNull() {
        BlockAtlasView.clear();
        BlockAtlasView.capture(null, null);
        int afterCapture = BlockAtlasView.generation();
        BlockAtlasView.clear();
        assertNotEquals(afterCapture, BlockAtlasView.generation(),
                "clearing the atlas replaces the texture reference just like a real capture, so a "
                        + "consumer holding a copy made before the clear must see the generation move");
        assertNull(BlockAtlasView.texture());
        assertNull(BlockAtlasView.view());
    }
}
