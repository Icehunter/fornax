package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link GraphRunner#finish}'s publication order by reading its source, because exercising the
 * orchestration method directly requires a live GPU device. Pack temporal passes consume
 * {@code SkyReprojection.current()} while the graph loop runs, so the current frame's factual
 * camera transform must be published before that loop. History and previous-camera commits retain
 * their separate end-of-frame phase after every graph consumer has completed.
 */
class GraphRunnerSkyReprojectionOrderingTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java");

    @Test
    void skyReprojectionIsPublishedBeforeItsFirstGraphConsumer() throws IOException {
        String source = Files.readString(SOURCE);
        String finish = source.substring(
                source.indexOf("public static void finish(ChunkRenderMatrices"),
                source.indexOf("private static void ensureRunnersBuilt()"));

        String skyCommit = "SkyReprojection.commit(CameraJitter.currentUnjitteredProjection(), matrices.modelView());";
        String graphLoop = "for (PassSpec p : pack.graph().passes()) {";
        String historySwap = "r.swapHistory();";
        String previousCameraCommit = "PreviousFrameCameraTransform.commit(";

        assertEquals(1, occurrences(source, skyCommit),
                "GraphRunner must publish the sky reprojection at exactly one orchestration site");

        int skyCommitIndex = finish.indexOf(skyCommit);
        int graphLoopIndex = finish.indexOf(graphLoop);
        int historySwapIndex = finish.indexOf(historySwap);
        int previousCameraCommitIndex = finish.indexOf(previousCameraCommit);

        assertTrue(skyCommitIndex >= 0,
                "the sole sky reprojection publication must belong to finish's frame orchestration");
        assertTrue(skyCommitIndex < graphLoopIndex,
                "sky reprojection must be current before temporal passes run in the graph loop");
        assertTrue(graphLoopIndex < historySwapIndex,
                "history targets must remain unswapped until every graph pass has finished");
        assertTrue(historySwapIndex < previousCameraCommitIndex,
                "the previous-camera snapshot must remain after the history swap");
    }

    private static int occurrences(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
