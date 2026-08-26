package dev.icehunter.fornax.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code gridSize} carries {@code BlockAtlasPagedStitch.takeover}'s grid-resolution decision (made
 * on the stitch's background executor) to {@code BlockAtlasOverflow.rebuild} (render-thread-only,
 * the one place that may actually call {@code SpriteBoundsTexture.useGridSize}, which closes a live
 * GPU texture) -- see {@code SpriteBoundsGridRenderThreadContractTest}. This pins that it survives
 * the {@link BlockAtlasPagedLayout#install}/{@link BlockAtlasPagedLayout#current} round trip
 * unchanged, same as every other field on the record.
 */
class BlockAtlasPagedLayoutTest {
    @Test
    void gridSizeRoundTripsThroughInstallAndCurrent() {
        BlockAtlasPagedLayout layout = new BlockAtlasPagedLayout(8192, 3, 2, List.of(), 2048);

        BlockAtlasPagedLayout.install(layout);

        assertEquals(2048, BlockAtlasPagedLayout.current().gridSize());
    }

    @Test
    void clearDropsTheGridSizeAlongWithEverythingElse() {
        BlockAtlasPagedLayout.install(new BlockAtlasPagedLayout(8192, 3, 2, List.of(), 2048));

        BlockAtlasPagedLayout.clear();

        assertNull(BlockAtlasPagedLayout.current());
    }
}
