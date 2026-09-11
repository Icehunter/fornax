package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins where {@link GraphRunner#finish} records graphics-queue engine buffer uploads, by reading
 * its source, because calling that method directly needs a live Vulkan device. The recording must
 * land after the current frame's sky transform is sent, since data sent for a graphics reader
 * is frame data too. It must land before the pass loop, since a pass in that loop may bind the
 * buffer the recording writes this frame.
 */
class GraphRunnerGraphicsUploadOrderingTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java");

    @Test
    void graphicsBufferUploadsAreRecordedAfterSkyReprojectionAndBeforeThePassLoop() throws IOException {
        String source = Files.readString(SOURCE);
        String finish = source.substring(
                source.indexOf("public static void finish(ChunkRenderMatrices"),
                source.indexOf("private static void ensureRunnersBuilt()"));

        String recordCall = "GraphicsBufferUploads.record(";
        String skyCommit = "SkyReprojection.commit(";
        String graphLoop = "for (PassSpec p : pack.graph().passes()) {";

        assertEquals(1, occurrences(source, recordCall),
                "GraphRunner must record graphics-queue engine buffer uploads in one place only");

        int recordIndex = finish.indexOf(recordCall);
        int skyCommitIndex = finish.indexOf(skyCommit);
        int graphLoopIndex = finish.indexOf(graphLoop);

        assertTrue(recordIndex >= 0,
                "the one graphics buffer upload recording must sit inside finish");
        assertTrue(skyCommitIndex >= 0, "finish must send the sky transform");
        assertTrue(skyCommitIndex < recordIndex,
                "the sky transform must be current before the graphics upload recording runs");
        assertTrue(recordIndex < graphLoopIndex,
                "graphics buffer uploads must be recorded before any pass in the loop can bind them");
    }

    private static int occurrences(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
