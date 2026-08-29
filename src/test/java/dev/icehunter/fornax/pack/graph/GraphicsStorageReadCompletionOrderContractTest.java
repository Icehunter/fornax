package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The render-level injection needs a live client to execute, so this source contract pins the
 * cross-queue release relative to every end-of-frame reader that can still touch graph storage.
 */
class GraphicsStorageReadCompletionOrderContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/mixin/vanilla/GameRendererMixin.java");

    @Test
    void graphicsCompletionIsRecordedAfterHistoryAndDebugReadersButBeforeJitterAdvance()
            throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("private void fornax$endFrame(CallbackInfo ci)");
        assertTrue(methodStart >= 0, "the renderLevel RETURN handler must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }", methodStart));

        int history = method.indexOf("this.fornax$copySceneHistory()");
        int waterDebug = method.indexOf("WaterPrepassDebugPass.presentIfEnabled");
        int graphDebug = method.indexOf("GraphTargetDebugPass.presentIfEnabled");
        int release = method.indexOf("GraphRunner.recordGraphicsStorageReadsComplete()");
        int jitter = method.indexOf("CameraJitter.advanceFrame()");

        assertTrue(history >= 0 && waterDebug > history && graphDebug > waterDebug,
                "history and both debug consumers must remain explicitly ordered");
        assertTrue(release > graphDebug,
                "the timeline release must be recorded after every end-of-frame graph reader");
        assertTrue(jitter > release,
                "the release must remain inside the established frame boundary before jitter advances");
    }
}
