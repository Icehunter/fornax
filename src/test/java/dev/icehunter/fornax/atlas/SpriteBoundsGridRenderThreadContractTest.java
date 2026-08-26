package dev.icehunter.fornax.atlas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins that {@code SpriteBoundsTexture.useGridSize} -- which closes a live {@code GpuTexture} -- is
 * never called from {@code BlockAtlasPagedStitch.takeover} or {@code SpriteLoaderPagedStitchMixin},
 * both of which run on the resource-reload's background executor, not the render thread (confirmed
 * via the reload pipeline's own {@code CompletableFuture} chain and the live log's {@code
 * Worker-Main-*} lines at exactly this call). {@code BlockAtlasOverflow.rebuild}, render-thread-only,
 * is the one place that applies it now, on the layout's behalf.
 *
 * <p>Deliberately source-level: these classes' relevant methods run off the render thread by
 * design, so there is no live-device test that could exercise the failure this pins without a real
 * background executor and a real GPU device racing each other.
 */
class SpriteBoundsGridRenderThreadContractTest {
    private static final Path PAGED_STITCH =
            Path.of("src/main/java/dev/icehunter/fornax/atlas/BlockAtlasPagedStitch.java");
    private static final Path STITCH_MIXIN = Path.of(
            "src/main/java/dev/icehunter/fornax/mixin/vanilla/SpriteLoaderPagedStitchMixin.java");
    private static final Path BLOCK_ATLAS_OVERFLOW =
            Path.of("src/main/java/dev/icehunter/fornax/atlas/BlockAtlasOverflow.java");

    @Test
    void pagedStitchTakeoverNeverCallsUseGridSize() throws IOException {
        String source = Files.readString(PAGED_STITCH);
        int methodStart = source.indexOf("public static Takeover takeover(");
        assertTrue(methodStart >= 0, "takeover must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        // Checks for an actual invocation (a real argument in the parens), not just the method
        // name appearing in a comment explaining why it is deliberately absent here.
        assertFalse(method.contains(".useGridSize(gridSize)"),
                "takeover() runs on the stitch's background executor, not the render thread; it"
                        + " must only decide the grid size (carried on BlockAtlasPagedLayout), never"
                        + " apply it -- useGridSize() closes a live GPU texture");
        assertTrue(method.contains("gridSize"),
                "the grid-size decision itself must still happen here");
    }

    @Test
    void stitchMixinFitsOnePageBranchNeverCallsUseGridSize() throws IOException {
        String source = Files.readString(STITCH_MIXIN);
        int methodStart = source.indexOf("private void fornax$pagedStitch(");
        assertTrue(methodStart >= 0, "fornax$pagedStitch must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertFalse(method.contains(".useGridSize("),
                "this HEAD hook on SpriteLoader.stitch runs on the same background executor as"
                        + " takeover() -- see this test class's own doc. BlockAtlasOverflow.rebuild"
                        + "(null), reached later on the render thread once BlockAtlasPagedLayout"
                        + ".current() reads null, is what resets the grid now");
    }

    @Test
    void blockAtlasOverflowRebuildAppliesTheGridSizeItself() throws IOException {
        String source = Files.readString(BLOCK_ATLAS_OVERFLOW);
        int methodStart = source.indexOf("public static void rebuild(");
        assertTrue(methodStart >= 0, "rebuild() must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("SpriteBoundsTexture.useGridSize("),
                "rebuild() is render-thread-only (called from TextureAtlasBlockHookMixin's RETURN"
                        + " hook and AtlasGenerationSchedule.tick, never off-thread), so it is the"
                        + " one place the grid-size decision the stitch made can safely be applied");
    }
}
