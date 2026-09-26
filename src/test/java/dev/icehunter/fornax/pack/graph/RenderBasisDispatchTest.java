package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackTomlLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A seed that writes one element per pixel into a {@code count = "render"} buffer needs a dispatch
 * that follows the buffer, or the rows past its literal group count are never written: the
 * ray_query after it then answers those zero requests as misses, painting the tail of the screen
 * lit. {@code local_size} on such a pass sizes the dispatch from the live buffer.
 */
class RenderBasisDispatchTest {

    private static String graph(String count, String localSize) {
        return """
                [targets.rayRequests]
                kind = "buffer"
                stride_bytes = 32
                count = %s

                [[pass]]
                name = "seed_rays"
                type = "compute"
                shader = "compute/seed_rays.comp"
                outputs = ["rayRequests"]
                dispatch = [1, 1, 1]
                %s
                """.formatted(count, localSize);
    }

    private static void validate(String toml) {
        GraphSpec g = PackTomlLoader.loadGraph(new StringReader(toml), "graph.toml");
        GraphValidator.validate(g, Map.of(), 1920, 1080);
    }

    @Test
    void groupsCoverEveryElementAndNeverDropToZero() {
        // 2073600 pixels at 256 a group: 8100 groups exactly.
        assertEquals(8100, ComputePassRunner.groupsForElements(2073600, 256));
        // 2560 x 1369 = 3504640 pixels, exactly 13690 groups of 256; one more element needs one more group.
        assertEquals(13690, ComputePassRunner.groupsForElements(3504640, 256));
        assertEquals(13691, ComputePassRunner.groupsForElements(3504641, 256));
        assertEquals(1, ComputePassRunner.groupsForElements(0, 256), "an empty buffer still dispatches a group, never zero");
        assertEquals(1, ComputePassRunner.groupsForElements(1, 256));
    }

    @Test
    void localSizeOverARenderBufferValidates() {
        assertDoesNotThrow(() -> validate(graph("\"render\"", "local_size = [256, 1]")));
    }

    @Test
    void localSizeOverAFixedBufferIsRefusedAtLoadNotAtRunnerBuild() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> validate(graph("2097152", "local_size = [256, 1]")));
        assertTrue(e.getMessage().contains("pass.seed_rays.local_size"), e.getMessage());
        assertTrue(e.getMessage().contains("count = \"render\""), e.getMessage());
    }

    @Test
    void aTwoDimensionalLocalSizeOverABufferIsRefused() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> validate(graph("\"render\"", "local_size = [16, 16]")));
        assertTrue(e.getMessage().contains("[x, 1]"), e.getMessage());
    }

    @Test
    void aFixedBufferWithALiteralDispatchStillValidates() {
        assertDoesNotThrow(() -> validate(graph("2097152", "")));
    }

    @Test
    void theRunnerSizesABufferOutputFromTheLiveBufferNotTheDeclaration() throws IOException {
        String runner = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/ComputePassRunner.java"));
        int at = runner.indexOf("private int[] resolveDispatchGroups(");
        String body = runner.substring(at, runner.indexOf("static int groupsForElements(", at));
        assertTrue(body.contains("BufferSize size = registry.bufferSizeOf(first);"));
        assertTrue(body.contains("groupsX = groupsForElements(buffer.sizeBytes() / size.strideBytes(), localSize.get(0));"),
                "elements come from the buffer's bytes this frame, which is what has grown after a resize");
        assertTrue(body.contains("groupsY = 1;"));
    }
}
