package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code GraphRunner.closeCurrent()} needs a live GPU device and a real pack teardown to exercise
 * directly, the same constraint that makes {@code BuiltinResolutionContractTest} a source-level
 * test. Pins that pack teardown deactivates frame generation, and that the settings screen shares
 * that same implementation rather than a second copy that can drift.
 */
class FrameGenerationTeardownContractTest {
    private static final Path GRAPH_RUNNER = Path.of(
            "src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java");
    private static final Path PRESENTER = Path.of(
            "src/main/java/dev/icehunter/fornax/pass/FrameGenPresenter.java");
    private static final Path SETTINGS_SCREEN = Path.of(
            "src/main/java/dev/icehunter/fornax/screen/FornaxSettingsScreen.java");

    @Test
    void closeCurrentDeactivatesFrameGeneration() throws IOException {
        String source = Files.readString(GRAPH_RUNNER);
        int methodStart = source.indexOf("private static void closeCurrent()");
        assertTrue(methodStart >= 0, "GraphRunner.closeCurrent must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("FrameGenPresenter.deactivateAll();"),
                "pack teardown must deactivate frame generation, or it keeps presenting every frame"
                        + " against GPU state closeCurrent() is about to free");
    }

    @Test
    void deactivateAllReleasesAllThreeFrameGenerationOwners() throws IOException {
        String source = Files.readString(PRESENTER);
        int methodStart = source.indexOf("public static void deactivateAll()");
        assertTrue(methodStart >= 0, "FrameGenPresenter.deactivateAll must still exist");
        String method = source.substring(methodStart, source.indexOf('}', methodStart) + 1);

        assertTrue(method.contains("FrameGenPass.deactivate();"),
                "must release FrameGenPass's interpolator and interop images");
        assertTrue(method.contains("UiLayerCapture.deactivate();"),
                "must release UiLayerCapture's staging target");
        assertTrue(method.contains("deactivate();"),
                "must release its own staging target too");
    }

    @Test
    void settingsScreenSharesTheSameDeactivationImplementation() throws IOException {
        String source = Files.readString(SETTINGS_SCREEN);
        int methodStart = source.indexOf("private static void deactivateFrameGeneration()");
        assertTrue(methodStart >= 0, "FornaxSettingsScreen.deactivateFrameGeneration must still exist");
        String method = source.substring(methodStart, source.indexOf('}', methodStart) + 1);

        assertTrue(method.contains("FrameGenPresenter.deactivateAll();"),
                "must call the shared deactivateAll(), not an independent copy that could drift");
    }
}
