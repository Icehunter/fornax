package dev.icehunter.fornax.profile;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Checking hook order needs a running client. This pins where coverage starts and ends, not the
 * real GPU timestamps. */
class RenderProfileBoundaryContractTest {
    @Test
    void worldSpanStartsBeforeScaleSetupAndEndsAfterHistoryCopy() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/mixin/vanilla/GameRendererMixin.java"));
        String begin = source.substring(source.indexOf("private void fornax$ssaaBeginFrame("));
        assertTrue(begin.contains("GraphRunner.beginProfileFrame()"));
        assertTrue(begin.indexOf("GraphRunner.beginProfileFrame()") < begin.indexOf("SsaaManager.applyCurrentScale()"));
        String end = source.substring(source.indexOf("private void fornax$endFrame("));
        assertTrue(end.indexOf("GraphRunner.endProfileFrame()") > end.indexOf("this.fornax$copySceneHistory()"));
    }

    @Test
    void graphLoopCannotResetOrCloseTheOuterTimestampRing() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java"));
        String finish = source.substring(source.indexOf("public static void finish("), source.indexOf("private static void ensureRunnersBuilt("));
        assertFalse(finish.contains("timer.beginFrame()"));
        assertFalse(finish.contains("timer.endFrame()"));
        assertTrue(finish.contains("timer.bracketBegin(FrameProfiler.LABEL_GRAPH)"));
        assertTrue(finish.contains("timer.bracketEnd(FrameProfiler.LABEL_GRAPH)"));
    }
}
