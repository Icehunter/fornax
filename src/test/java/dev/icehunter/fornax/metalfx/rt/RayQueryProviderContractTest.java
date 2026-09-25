package dev.icehunter.fornax.metalfx.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level: the round trip needs a Vulkan device and a Metal one at once, which no test here
 * has. What it pins is the ordering and the pairing that make a batch of rays come back correct,
 * each of which fails as wrong lighting rather than as an error.
 */
class RayQueryProviderContractTest {

    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax");

    private static String read(String path) throws IOException {
        return Files.readString(SOURCE.resolve(path));
    }

    /**
     * The hit buffer crosses to Metal as well as back. It looks redundant and is not: the kernel
     * reads each record's tier to leave a higher tier's answer alone, so it has to see what is
     * already there. Copying only the requests would make every tier overwrite the one above it.
     */
    @Test
    void bothBuffersAreCopiedAcrossAndOnlyTheHitsComeBack() throws IOException {
        String interop = read("metalfx/rt/RayQueryInterop.java");
        assertTrue(interop.contains("copy(cmd, stack, query.requestBuffer(), requests.vkBuffer(), requestBytes);"),
                "the requests must reach Metal");
        assertTrue(interop.contains("copy(cmd, stack, query.hitBuffer(), hits.vkBuffer(), hitBytes);"),
                "so must the hits, or the fill cannot see what a higher tier already answered");
        assertTrue(interop.contains("copy(cmd, stack, hits.vkBuffer(), query.hitBuffer(), hitBytes);"),
                "and the answers must come back");
    }

    /** Vulkan and Metal cannot run together in CI; pin the manifest-to-constant delivery edges. */
    @Test
    void atlasEncodingTravelsFromTheGraphIntoTheUnusedConstantsLaneWithDimensionValidation() throws IOException {
        assertTrue(read("pack/graph/GraphRunner.java").contains("p.name(), raySpec.atlasUvEncoding()"));
        String interop = read("metalfx/rt/RayQueryInterop.java");
        assertTrue(interop.contains("constants.set(ValueLayout.JAVA_INT, 28L, query.atlasUvEncoding().wireValue());"));
        assertTrue(interop.indexOf("validateAtlasDimensions(") < interop.indexOf("long requestBytes ="));
        assertTrue(interop.contains("Objc.selector(\"width\")") && interop.contains("Objc.selector(\"height\")"));
    }

    /** Three values in order: Vulkan input, Metal output, Vulkan copy-back. */
    @Test
    void theRoundTripIsOrderedByOneTimelineInThreeSteps() throws IOException {
        String interop = read("metalfx/rt/RayQueryInterop.java");
        int input = interop.indexOf("signalSemaphore(timeline.vkSemaphore, value,");
        int metalWait = interop.indexOf("encodeWaitForEvent:value:");
        int metalSignal = interop.indexOf("encodeSignalEvent:value:");
        int copyBackWait = interop.indexOf("waitSemaphore(timeline.vkSemaphore, value + 1");
        int done = interop.indexOf("signalSemaphore(timeline.vkSemaphore, value + 2");
        assertTrue(input > 0 && metalWait > input, "Metal waits for the copied-in buffers");
        assertTrue(metalSignal > metalWait, "and signals when its dispatch is encoded");
        assertTrue(copyBackWait > metalSignal, "the copy-back waits for that signal");
        assertTrue(done > copyBackWait, "and signals the whole round trip complete");
    }

    /** Both providers declare their own tier, and fill rather than own the buffer. */
    @Test
    void eachProviderPassesItsOwnTierAndFillsRatherThanOwnsTheBuffer() throws IOException {
        for (String provider : new String[]{"metalfx/rt/MeshMetalProvider.java",
                "metalfx/rt/VoxelMetalProvider.java"}) {
            assertTrue(read(provider).contains("rayQueries.answer(query, tier().ordinal(),"),
                    provider + " must pass its own tier, not a literal");
        }
        assertTrue(read("metalfx/rt/RayQueryInterop.java").contains("constants.set(ValueLayout.JAVA_INT, 12L, 1);"),
                "the round trip always fills: another tier may already have answered");
    }

    /**
     * The clear runs before any tier, from the graph, not from a provider. A declared buffer keeps
     * last frame's tier words, so without it every fill skips every record and the pack reads a
     * frozen answer that looks exactly like a fresh one.
     */
    @Test
    void theHitBufferIsClearedByTheGraphBeforeAnyTierAnswers() throws IOException {
        String runner = read("pack/graph/GraphRunner.java");
        int clear = runner.indexOf("RayQueryInterop.clearHits(");
        int floor = runner.indexOf("RayRouter.tierFloor(raySpec.minTier())");
        int answer = runner.indexOf("RayRouter.answer(");
        assertTrue(clear > 0, "the graph must clear the hit buffer");
        assertTrue(clear < floor && clear < answer, "and must do it before any tier runs");
        assertTrue(read("metalfx/rt/RayQueryInterop.java").contains("vkCmdFillBuffer(cmd, hitBuffer, 0L, bytes, 0)"),
                "the clear writes zero over the whole buffer, which is what tier 0 means");
    }

    /**
     * A tier with no structure this frame answers nothing and says nothing. That is not a failure:
     * the records stay at tier 0 and the tier below gets them, which is the cascade working.
     */
    @Test
    void aTierWithNoStructureLeavesTheRecordsForTheTierBelow() throws IOException {
        assertTrue(read("metalfx/rt/MeshMetalProvider.java").contains("if (debugTlas == 0) {"),
                "the mesh tier must decline when it built nothing this frame");
        assertTrue(read("metalfx/rt/VoxelMetalProvider.java").contains("if (structure == 0) {"),
                "and so must the voxel tier");
    }

    /**
     * Cutout alpha, and the binding it needs. A record with UVs is tested against the block atlas
     * at the same 0.1 cutoff the raster shadow pipeline uses, so a ray query agrees with the
     * rasteriser about which leaf texels are holes. With no atlas bound every such record samples
     * zero and the ray passes through geometry it should have hit: a see-through world, not an
     * error, and one that reads as a lighting bug several layers away.
     */
    @Test
    void cutoutRecordsAreAlphaTestedAndEveryCallerBindsAnAtlas() throws IOException {
        String kernel = Files.readString(Path.of(
                "src/main/resources/assets/fornax/shaders_engine/rt_ray_query.metal"));
        assertTrue(kernel.contains("params.force_opacity(forced_opacity::non_opaque);"),
                "every candidate must reach the loop, or the alpha test never runs");
        assertTrue(kernel.contains("atlasIn.sample(nearestAtlas, uv).a >= 0.1f"),
                "the cutoff must match the raster shadow pipeline's own");
        assertTrue(kernel.contains("if (candidateUvWords == 0u) {"),
                "a record with no UVs is solid by construction and takes no sample");

        String interop = read("metalfx/rt/RayQueryInterop.java");
        assertTrue(interop.contains("setTexture:atIndex:\"), atlas, 0L);"),
                "the round trip must bind the atlas the kernel samples");
        assertTrue(read("metalfx/rt/MeshMetalProvider.java").contains("atlas != null ? atlas.mtlTexture : 0L"),
                "the mesh tier supplies its own exported atlas");
        assertTrue(read("metalfx/rt/VoxelMetalProvider.java").contains("MetalRtShadowPass.atlasTexture()"),
                "and the voxel tier the one its own trace uses");
    }

    /**
     * The trace buffer's GPU timing is polled, never waited on: a poll that found the previous
     * call's buffer still running must not block this one, and a completed buffer's retain must be
     * released so the round trip doesn't leak one Metal object per frame forever.
     */
    @org.junit.jupiter.api.Disabled("Metal trace timing is switched off: retaining the command buffer crashed the render thread")
    @Test
    void metalTimingIsPolledBeforeEachAnswerAndReleasedOnceRead() throws IOException {
        String interop = read("metalfx/rt/RayQueryInterop.java");
        int drain = interop.indexOf("drainPendingMetalTiming();");
        int deviceLookup = interop.indexOf("VulkanMetalInterop.vulkanDevice();", drain);
        assertTrue(drain > 0, "answer() must poll the previous trace before starting a new one");
        assertTrue(drain < deviceLookup,
                "the poll must run before this call does any work of its own");

        int retain = interop.indexOf("Objc.selector(\"retain\")");
        int commit = interop.indexOf("Objc.selector(\"commit\")");
        assertTrue(retain > 0 && commit > 0 && commit < retain,
                "the buffer must be retained only after it is committed, not before");

        int release = interop.indexOf("Objc.selector(\"release\")");
        assertTrue(release > commit, "a completed buffer's retain must be matched by a release");
    }

    /** An errored trace buffer has no meaningful gpuStartTime/gpuEndTime and must not be reported
     * as a real, if suspiciously fast or slow, measurement. */
    @Test
    void anErroredTraceBufferIsReleasedWithoutPublishingATiming() throws IOException {
        String interop = read("metalfx/rt/RayQueryInterop.java");
        int drainMethod = interop.indexOf("private void drainPendingMetalTiming()");
        int unfinishedGuard = interop.indexOf(
                "status != MTL_COMMAND_BUFFER_STATUS_COMPLETED && status != MTL_COMMAND_BUFFER_STATUS_ERROR",
                drainMethod);
        int completedOnly = interop.indexOf("status == MTL_COMMAND_BUFFER_STATUS_COMPLETED", drainMethod);
        int gpuStartRead = interop.indexOf("Objc.selector(\"gpuStartTime\")", drainMethod);
        int releaseCall = interop.indexOf("Objc.selector(\"release\")", drainMethod);

        assertTrue(drainMethod > 0);
        assertTrue(unfinishedGuard > drainMethod,
                "still-running must be distinguished from both completed and errored");
        assertTrue(unfinishedGuard < completedOnly && completedOnly < gpuStartRead,
                "gpuStartTime must only be read once the buffer is confirmed completed, not errored");
        assertTrue(gpuStartRead < releaseCall,
                "the retain must still be released on an errored buffer even though its timing is dropped");
    }
}
