package dev.icehunter.fornax.metalfx.rt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code runIfEnabled} needs a live Metal device transitioning from active to inactive mid-session
 * to exercise this directly, the same constraint that makes {@code
 * MetalFxUpscalePassDeviceLossContractTest} a source-level test.
 *
 * <p>Pins that {@code clearRtShadowResultTargetsToInvalid()} runs on every path where the pass stops
 * running: the {@code wasActive && !available} transition inside the {@code !available} branch
 * (setting off, {@code wanted} false, the probe going unavailable, or a FORCE-&gt;AUTO flip landing
 * on non-apple9 hardware), and the session-disabling {@code catch (Throwable t)} block. Without
 * either call, {@code RtShadowResult}'s {@code rtSunValid}/{@code rtSunVisibility} targets keep
 * whatever the last successful trace wrote, and a pack keeps compositing that frozen screen-space
 * imprint as the camera moves on. {@code rtSunValid} is documented as "traced THIS frame", so a
 * frame the pass does not run must zero it, not skip it.
 *
 * <p>Also pins the session-disabling block's internal order: the ERROR log and {@code
 * releaseAllGpuState()} must run before {@code clearRtShadowResultTargetsToInvalid()}, and that
 * clear call must sit in its own try/catch. The clear itself issues a GPU command on a device that
 * just failed a GPU operation; if it throws first, the original failure is never logged and every
 * resource this pass owns leaks for the rest of the session, and if its own failure is not caught
 * it escapes uncaught into the {@code GameRendererMixin} render path.
 *
 * <p>Also pins that the clear's own catch checks {@link
 * dev.icehunter.fornax.util.GpuFatalErrors#rethrowIfFatal} first, before logging or swallowing it.
 * A device loss from the clear must still surface as unrecoverable, not be logged and continued
 * past onto a dead device. Also pins that an ordinary
 * (non-fatal) failure there sets {@code pendingResultClear} so the next frame's {@code
 * !available} branch retries the clear instead of leaving {@code RtShadowResult} stale for the
 * rest of the session.
 */
class MetalRtShadowPassStaleResultContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/metalfx/rt/MetalRtShadowPass.java");

    @Test
    void unavailableTransitionClearsRtShadowResult() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public static void runIfEnabled(");
        assertTrue(methodStart >= 0, "runIfEnabled must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int notAvailableBranch = method.indexOf("if (!available) {");
        int tryBlock = method.indexOf("try {\n            run(gbuffer);");
        assertTrue(notAvailableBranch >= 0, "the !available early-return branch must still exist");
        assertTrue(tryBlock >= 0, "the run(gbuffer) try block must still exist");
        assertTrue(notAvailableBranch < tryBlock, "the !available branch must run ahead of run(gbuffer)");

        String notAvailableBlock = method.substring(notAvailableBranch, tryBlock);
        assertTrue(notAvailableBlock.contains("if (wasActive || pendingResultClear) {"),
                "the clear must be conditioned on the wasActive transition or a pending retry, not "
                        + "run every frame RT is off");
        int wasActiveGuard = notAvailableBlock.indexOf("if (wasActive || pendingResultClear) {");
        int wasActiveGuardEnd = notAvailableBlock.indexOf("return;", wasActiveGuard);
        assertTrue(wasActiveGuardEnd >= 0, "the !available branch must still return early");
        String wasActiveBlock = notAvailableBlock.substring(wasActiveGuard, wasActiveGuardEnd);
        assertTrue(wasActiveBlock.contains("clearRtShadowResultTargetsToInvalid();"),
                "the wasActive->!available transition must clear RtShadowResult's targets, or "
                        + "rtSunValid keeps reading 1 for a frozen trace after the pass stops running");
    }

    @Test
    void sessionDisablingFailureClearsRtShadowResult() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public static void runIfEnabled(");
        assertTrue(methodStart >= 0, "runIfEnabled must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int broadCatch = method.indexOf("catch (Throwable t) {");
        int releaseCall = method.indexOf("releaseAllGpuState();", broadCatch);
        int clearCall = method.indexOf("clearRtShadowResultTargetsToInvalid();", broadCatch);
        assertTrue(broadCatch >= 0, "the session-disabling broad Throwable catch must still exist");
        assertTrue(releaseCall >= 0, "the broad catch must still tear down GPU state via releaseAllGpuState()");
        assertTrue(clearCall >= 0,
                "a session-disabling failure must clear RtShadowResult's targets, or rtSunValid keeps "
                        + "reading 1 for a trace that will never run again this session");

        String catchBlock = method.substring(broadCatch, clearCall);
        assertTrue(catchBlock.contains("LOGGER.error("),
                "the ERROR log must run before the clear, so a failure in the clear itself cannot "
                        + "swallow the record of the original failure");
        assertTrue(releaseCall < clearCall,
                "releaseAllGpuState() must run before the clear, so a failure in the clear itself "
                        + "cannot skip releasing this pass's GPU resources");

        String betweenReleaseAndClear = method.substring(releaseCall, clearCall);
        assertTrue(betweenReleaseAndClear.contains("try {"),
                "the clear must sit inside its own try block, separate from the outer session-disabling catch");

        int clearTryStart = betweenReleaseAndClear.lastIndexOf("try {");
        String afterClear = method.substring(clearCall);
        int clearOwnCatch = afterClear.indexOf("} catch (Throwable");
        assertTrue(clearTryStart >= 0 && clearOwnCatch >= 0,
                "the clear's own try/catch must wrap only the clear call");
        assertTrue(!afterClear.substring(0, clearOwnCatch).contains("releaseAllGpuState()"),
                "releaseAllGpuState() must not be inside the clear's own try block, or a failure "
                        + "in the clear would re-run teardown logic that already ran");
    }

    @Test
    void sessionDisablingClearFailureRethrowsFatalBeforeSwallowing() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public static void runIfEnabled(");
        assertTrue(methodStart >= 0, "runIfEnabled must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int broadCatch = method.indexOf("catch (Throwable t) {");
        int clearCall = method.indexOf("clearRtShadowResultTargetsToInvalid();", broadCatch);
        int innerCatchStart = method.indexOf("} catch (Throwable clearFailure) {", clearCall);
        assertTrue(innerCatchStart >= 0, "the clear must still have its own catch (Throwable clearFailure)");

        int innerCatchBodyStart = method.indexOf("{", innerCatchStart + "} catch (Throwable clearFailure) ".length());
        int innerCatchEnd = method.indexOf("}", innerCatchBodyStart);
        String innerCatchBody = method.substring(innerCatchBodyStart + 1, innerCatchEnd);

        int rethrowCall = innerCatchBody.indexOf("GpuFatalErrors.rethrowIfFatal(clearFailure);");
        int pendingSet = innerCatchBody.indexOf("pendingResultClear = true;");
        int logCall = innerCatchBody.indexOf("LOGGER.error(");
        assertTrue(rethrowCall >= 0,
                "a device loss from the clear must still be checked for via GpuFatalErrors.rethrowIfFatal, "
                        + "matching every other GPU-work catch in this file, or it is logged and the frame "
                        + "continues on a dead device instead of surfacing as unrecoverable");
        assertTrue(rethrowCall < pendingSet && rethrowCall < logCall,
                "rethrowIfFatal must run before pendingResultClear is set or the failure is logged, so a "
                        + "fatal failure is never mistaken for an ordinary one to retry");
    }

    @Test
    void pendingResultClearIsRetriedFromTheUnavailableBranch() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public static void runIfEnabled(");
        assertTrue(methodStart >= 0, "runIfEnabled must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int notAvailableBranch = method.indexOf("if (!available) {");
        int tryBlock = method.indexOf("try {\n            run(gbuffer);");
        String notAvailableBlock = method.substring(notAvailableBranch, tryBlock);

        assertTrue(notAvailableBlock.contains("pendingResultClear = false;"),
                "a clear that succeeds must clear the pending-retry flag, or every later frame "
                        + "keeps re-attempting a clear that already worked");
        assertTrue(notAvailableBlock.contains("pendingResultClear = true;"),
                "a clear that fails ordinarily inside the !available branch must set pendingResultClear "
                        + "so the next frame retries it, since wasActive is already false by then and "
                        + "would otherwise never trigger another attempt");
    }
}
