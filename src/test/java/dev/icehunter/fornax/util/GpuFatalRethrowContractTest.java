package dev.icehunter.fornax.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the five crash-path catch sites this session's device-loss fix touched call
 * {@link GpuFatalErrors#rethrowIfFatal} BEFORE logging/degrading, not after -- calling it after the
 * log line would still execute the rethrow, but every intervening line (marking a pass invalid,
 * closing a resource, mutating shared state) would run first, on a device already known to be dead.
 *
 * <p>Deliberately source-level, matching {@code BuiltinResolutionContractTest}'s own reasoning: each
 * site lives inside a live render-thread GPU call this suite has no device to exercise, so reading
 * the source is what actually answers "does the rethrow run first here".
 */
class GpuFatalRethrowContractTest {
    private static final Path GRAPH_RUNNER =
            Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphRunner.java");
    private static final Path FRAME_GEN_PRESENTER =
            Path.of("src/main/java/dev/icehunter/fornax/pass/FrameGenPresenter.java");
    private static final Path FRAME_GEN_PASS =
            Path.of("src/main/java/dev/icehunter/fornax/metalfx/FrameGenPass.java");
    private static final Path BLOCK_ATLAS_OVERFLOW =
            Path.of("src/main/java/dev/icehunter/fornax/atlas/BlockAtlasOverflow.java");

    @Test
    void graphRunnerMipchainDispatchRethrowsBeforeItsOncePerPassLogGate() throws IOException {
        // logPassRunFailureOnce gates by pass name, so a recurring device loss after the first
        // failure would otherwise go completely unlogged -- the rethrow has to run first.
        assertRethrowsBeforeMarker(GRAPH_RUNNER, "logPassRunFailureOnce(p.name(), e);", 0);
    }

    @Test
    void graphRunnerCopyDispatchRethrowsBeforeItsOncePerPassLogGate() throws IOException {
        // The second occurrence of the same log call -- the COPY case's own catch, not MIPCHAIN's.
        assertRethrowsBeforeMarker(GRAPH_RUNNER, "logPassRunFailureOnce(p.name(), e);", 1);
    }

    @Test
    void presentSeamBlitRethrowsBeforeMarkingFrameGenerationFailed() throws IOException {
        assertRethrowsBeforeMarker(FRAME_GEN_PRESENTER, "\"present seam (blit G)\"", 0);
    }

    @Test
    void presentSeamPresentRethrowsBeforeMarkingFrameGenerationFailed() throws IOException {
        assertRethrowsBeforeMarker(FRAME_GEN_PRESENTER, "\"present seam (present G)\"", 0);
    }

    @Test
    void presentSeamReacquireRethrowsBeforeMarkingFrameGenerationFailed() throws IOException {
        // The site with no `return` after it: on an ordinary failure the method falls through to
        // vanilla's own blit/present with no image acquired, which is exactly why a FATAL failure
        // here must propagate out rather than be logged and continued past.
        assertRethrowsBeforeMarker(FRAME_GEN_PRESENTER, "\"present seam (reacquire)\"", 0);
    }

    @Test
    void frameGenPassCrossTeardownWaitRethrowsBeforeDowngradingToAWarning() throws IOException {
        // waitForOwnGpuWork's own doc: this wait exists specifically so deactivate() does not free
        // the interpolator/images while GPU work still reads them. A swallowed fatal failure here
        // would let deactivate() proceed to free them anyway.
        assertRethrowsBeforeMarker(FRAME_GEN_PASS,
                "\"[Fornax] frame generation cross-teardown wait failed\"", 0);
    }

    @Test
    void frameGenPassRunIfEnabledRethrowsBeforeMarkingFailed() throws IOException {
        // run() records and submits real GPU work (the Vulkan copy-in plus the Metal interpolator
        // encode); this catch must not swallow a fatal failure from either into the same soft
        // failed=true fallback as an ordinary bug.
        assertRethrowsBeforeMarker(FRAME_GEN_PASS, "\"runIfEnabled\"", 0);
    }

    @Test
    void frameGenPassCopyGeneratedIntoRethrowsBeforeMarkingFailed() throws IOException {
        // Also a real command-recording-and-submission path, same reasoning as runIfEnabled's own.
        assertRethrowsBeforeMarker(FRAME_GEN_PASS, "\"copyGeneratedInto\"", 0);
    }

    @Test
    void presentSeamPrepareRethrowsBeforeMarkingFrameGenerationFailed() throws IOException {
        // Wraps copyGeneratedInto plus the sky-fill/UI composites, all real GPU work on the shared
        // render-thread queue -- the fourth present-seam catch in this file.
        assertRethrowsBeforeMarker(FRAME_GEN_PRESENTER, "\"present seam (prepare G)\"", 0);
    }

    @Test
    void blockAtlasOverflowBuildRethrowsBeforeFallingBackToUnpaged() throws IOException {
        // The multi-gigabyte array-texture allocation itself -- a Vulkan OOM or device loss here
        // logged as a soft warning is what let rendering continue for several more frames on an
        // already-failed device, surfacing as an unattributed native crash deeper in.
        assertRethrowsBeforeMarker(BLOCK_ATLAS_OVERFLOW,
                "\"[Fornax] Paged block atlas: overflow compositor failed;\"", 0);
    }

    @Test
    void blockAtlasOverflowBuildWrapsItsAllocationCallAsFatal() throws IOException {
        // Distinct from the test above: this pins that the allocation call itself is wrapped so
        // even a plain IllegalStateException (a Vulkan OOM, per VulkanUtils.crashIfFailure only
        // mapping VK_ERROR_DEVICE_LOST to GpuDeviceLossException) becomes fatal, closing the gap
        // that made a real OOM here still take the soft degrade path despite the rethrow above.
        String source = Files.readString(BLOCK_ATLAS_OVERFLOW);
        int methodStart = source.indexOf("private static Published build(");
        assertTrue(methodStart >= 0, "build must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        int allocationCallIndex = method.indexOf("ArrayTextures.create(\"Fornax Paged Block Atlas Albedo\"");
        int fatalThrowIndex = method.indexOf("throw new GpuFatalException(");
        assertTrue(allocationCallIndex >= 0, "the array-texture allocation call must still exist");
        assertTrue(fatalThrowIndex >= 0,
                "the allocation call must be wrapped to convert any exception into a GpuFatalException");
        assertTrue(allocationCallIndex < fatalThrowIndex,
                "the fatal-wrapping catch must sit right after the allocation call, not elsewhere"
                        + " in this method");
    }

    /**
     * Finds the {@code occurrence}-th (0-indexed) instance of {@code logMarker} in {@code path},
     * then asserts {@code GpuFatalErrors.rethrowIfFatal} appears between that instance's nearest
     * enclosing {@code catch (} and the marker itself.
     */
    private static void assertRethrowsBeforeMarker(Path path, String logMarker, int occurrence)
            throws IOException {
        String source = Files.readString(path);

        int markerIndex = -1;
        for (int i = 0; i <= occurrence; i++) {
            markerIndex = source.indexOf(logMarker, markerIndex + 1);
            assertTrue(markerIndex >= 0,
                    "occurrence " + i + " of " + logMarker + " not found in " + path);
        }

        int catchIndex = source.lastIndexOf("catch (", markerIndex);
        assertTrue(catchIndex >= 0, "no enclosing catch found before " + logMarker + " in " + path);

        int rethrowIndex = source.indexOf("GpuFatalErrors.rethrowIfFatal", catchIndex);
        assertTrue(rethrowIndex >= 0 && rethrowIndex < markerIndex,
                "rethrowIfFatal must run before " + logMarker + " in " + path
                        + ", so a fatal failure surfaces immediately instead of after this catch's"
                        + " own degrade-and-continue logic runs");
    }
}
