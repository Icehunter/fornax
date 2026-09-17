package dev.icehunter.fornax.metalfx.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One image, filled by both tiers, published once.
 *
 * <p>The shape this replaces had each tier copy the celestial image in and its own result back out:
 * four Vulkan submits a frame for two tiers. Measured on an M5 Pro, a mid-frame submit costs about
 * 2.3 ms of render-thread time and the one that waits on the Metal trace costs about 6.5, against
 * 0.03 ms for the acceleration structure rebuild and 0.55 for the dispatch encode. The copying, not
 * the ray tracing, was the cost.
 */
class CascadeHandoverContractTest {

    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax");

    private static String read(String path) throws IOException {
        return Files.readString(SOURCE.resolve(path));
    }

    /** The tier above publishes its image rather than a copy of it. */
    @Test
    void theMeshTierHandsOverItsImageInsteadOfCopyingItBack() throws IOException {
        String mesh = read("metalfx/rt/MeshMetalProvider.java");
        assertTrue(mesh.contains("cascade = new CascadeImage(depth, timeline.mtlSharedEvent, value + 1, resolution);"),
                "the traced image, the event that says it is done and its resolution travel together");
        assertTrue(mesh.contains("public boolean publishCelestialVisibility()"),
                "the tier above delivers its own image only when no lower tier did, which is the "
                        + "path for a frame where the tier below sat out or failed");
        assertTrue(mesh.contains("cascade = null;"),
                "and the handover is dropped with the frame, like every other per-frame handle");
    }

    /** The tier below fills that image in place, and only allocates its own when there is none. */
    @Test
    void theVoxelTierFillsTheHandedOverImageAndSkipsTheCopyIn() throws IOException {
        String pass = read("metalfx/rt/MetalRtShadowPass.java");
        assertTrue(pass.contains("if (celestialFill && handover == null) copyCelestialIn(cmd, stack);"),
                "a handed-over image is already Metal-side and already carries this frame's answers");
        assertTrue(pass.contains("if (celestialFill && handover == null) ensureCelestialImage("),
                "and needs no image of this tier's own");
        assertTrue(pass.contains("VulkanMetalInterop.InteropImage target = handover != null ? handover.image() : celestialIo;"),
                "the dispatch writes into whichever image this frame is using");
    }

    /**
     * Ordering across the two tiers' command buffers. Metal starts them in order but does not hold
     * a later one until an earlier finishes, so reading the image without this wait samples a
     * half-written trace: geometry lands, per-texel content is whatever was there.
     */
    @Test
    void theFillWaitsForTheTierAboveToFinishWritingTheImage() throws IOException {
        String pass = read("metalfx/rt/MetalRtShadowPass.java");
        assertTrue(pass.contains("handover.readyEvent(), handover.readyValue()"),
                "the fill must wait on the event the tier above signals");
        int wait = pass.indexOf("if (handover != null && handover.readyEvent() != 0) {");
        int encoder = pass.indexOf("long encoder = Objc.msgSendId(cb, Objc.selector(\"computeCommandEncoder\"));",
                pass.indexOf("private static void encodeCelestialFill("));
        assertTrue(wait > 0 && wait < encoder,
                "and must encode the wait before the dispatch that reads");
    }

    /**
     * Tracing and delivery sit at different frame points on purpose, and they want opposite ones.
     *
     * <p>The trace wants to be early, before the terrain draw, so the GPU has that whole draw to
     * finish it. The delivery wants to be late, just before a pack can read it, so its wait is on
     * work already done. Put the delivery next to the trace and the render thread blocks inside
     * {@code vkQueueSubmit}: measured at 6 to 7.8 ms a frame, against 0.008 ms to record the same
     * copy. Defer it a whole frame instead and the image is a frame behind the camera, which is
     * visible as shadows shifting when the player moves.
     */
    @Test
    void theTraceAndTheDeliveryAreSeparateStepsAtDifferentFramePoints() throws IOException {
        String pass = read("metalfx/rt/MetalRtShadowPass.java");
        assertTrue(pass.contains("static boolean publishPendingCelestial() {"),
                "delivery is its own step, not the tail of the trace");
        assertTrue(pass.contains("publishPending = true;"),
                "the trace records a debt rather than paying it");
        assertTrue(pass.contains("encoder.waitSemaphore(timeline.vkSemaphore, pendingPublishValue,"),
                "and the delivery waits on the value that trace signals");

        String provider = read("metalfx/rt/VoxelMetalProvider.java");
        assertTrue(provider.contains("public boolean publishCelestialVisibility() {"),
                "the tier that wrote the image last delivers it, and says whether it did");

        String router = read("rt/RayRouter.java");
        assertTrue(router.contains("for (int i = providers.size() - 1; i >= 0; i--)"),
                "delivery walks tiers from the lowest up: the last to write the image holds it");
        assertTrue(router.contains("TerrainShadowResult.invalidate();"),
                "and a frame nobody delivered must clear the target, or the stale image it still "
                        + "holds reads as valid and shows as a shadow cast by nothing");

        String runner = read("pack/graph/GraphRunner.java");
        assertTrue(runner.contains("RayRouter.publish();"),
                "and the graph asks for it where a pack can first read the result");
        assertFalse(runner.contains("RayRouter.phaseTwo("),
                "there is no second trace phase: every tier traces in one");
    }
}
