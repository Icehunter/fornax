package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.rt.RayQueryKind;
import dev.icehunter.fornax.rt.RayTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level where it must be: the fill needs a Metal device, and the wiring lives in the graph
 * interpreter. What it pins is the shape that makes two tiers able to share one image safely.
 *
 * <p>The dangerous property is ownership. The acceleration structures, the shared timeline and the
 * interop images have exactly one owner, and the celestial fill is encoded into that owner's
 * command buffer rather than a second one. Two owners on two timelines writing one image in one
 * frame is how a structure gets freed while a dispatch still references it.
 */
class VoxelMetalProviderContractTest {

    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax");

    private static String read(String path) throws IOException {
        return Files.readString(SOURCE.resolve(path));
    }

    /**
     * A trace with no reader is skipped, and each of the three readers keeps it.
     *
     * <ul>
     *   <li>The cost of skipping nothing: on an M5 Pro with ray-traced shadows off in the pack,
     *       the fill still took 15.3 ms of render-thread time per frame out of a 23.2 ms frame.
     *       It runs on a command buffer taken mid-frame under the shared queue lock. The dispatch
     *       behind that submit was 0.047 ms and the radius was zero, so none of it bought
     *       anything.
     *   <li>Gating on the shadow reader alone hands a ray_query pass an unbuilt structure and a
     *       miss for every ray.
     *   <li>Gating it out of the scene debug views leaves the setting reading Voxel RT scene with
     *       a screen that never changes.
     * </ul>
     */
    @Test
    void aFillWithNoReaderIsSkippedAndEveryReaderKeepsIt() {
        assertFalse(VoxelMetalProvider.tracesThisFrame(0f, false, false),
                "a zero radius with no query pass and no scene debug has no reader at all");
        assertTrue(VoxelMetalProvider.tracesThisFrame(16f, false, false), "a subscribed pack reads it");
        assertTrue(VoxelMetalProvider.tracesThisFrame(0f, true, false), "a ray_query pass reads it");
        assertTrue(VoxelMetalProvider.tracesThisFrame(0f, false, true), "a scene debug view reads it");
    }

    /** A pack swap must not leave the previous pack's query demand standing. */
    @Test
    void installingProvidersClearsTheQueryDemand() {
        dev.icehunter.fornax.rt.RayRouter.install(java.util.List.of());
        dev.icehunter.fornax.rt.RayRouter.setQueryDemand(true);
        assertTrue(dev.icehunter.fornax.rt.RayRouter.queryDemand());
        dev.icehunter.fornax.rt.RayRouter.install(java.util.List.of());
        assertFalse(dev.icehunter.fornax.rt.RayRouter.queryDemand(),
                "a pack with no ray_query pass would keep tracing for one that had one");
    }

    @Test
    void theProviderAnswersVisibilityAtTheVoxelTier() {
        VoxelMetalProvider provider = new VoxelMetalProvider();
        assertEquals(RayTier.HARDWARE_VOXEL, provider.tier());
        assertTrue(provider.answers(RayQueryKind.VISIBILITY));
        assertFalse(provider.answers(RayQueryKind.CLOSEST_HIT));
    }

    /**
     * Readiness must not consult the captured G-buffer. The router evaluates it at the top of the
     * frame and the capture happens later, in the graph's finish, so a provider that reports on it
     * here is never ready and never runs. That is exactly how this tier shipped dead for a session:
     * no error, no log, just the mesh tier answering everything and the voxel tier silently absent.
     */
    @Test
    void readinessDoesNotDependOnStateThatArrivesLaterInTheFrame() throws IOException {
        String provider = read("metalfx/rt/VoxelMetalProvider.java");
        int start = provider.indexOf("public RayReadiness readiness()");
        assertTrue(start > 0, "the provider must declare a readiness check");
        String body = provider.substring(start, provider.indexOf("\n    }", start));
        assertFalse(body.contains("screen == null"),
                "readiness is asked before the G-buffer is captured; guard on it in the fill");
        assertTrue(provider.contains("MetalRtShadowPass.runIfEnabled(screen,"),
                "the fill runs before a G-buffer exists and passes whatever it has; only the "
                        + "screen-space scene debug needs one, and it checks for itself");
    }

    /**
     * One owner. The provider holds no native handles of its own and no second timeline; it drives
     * the pass that already owns them. Splitting that before the legacy screen-space path is gone
     * would put two owners on one structure set within a frame.
     */
    @Test
    void theProviderOwnsNoNativeStateOfItsOwn() throws IOException {
        String provider = read("metalfx/rt/VoxelMetalProvider.java");
        for (String owned : new String[]{"InteropImage", "SharedTimeline", "createBuffer(", "commandBuffer"}) {
            assertFalse(provider.contains(owned),
                    "the voxel tier must not take ownership of " + owned + " while the legacy pass "
                            + "still holds it");
        }
        assertTrue(provider.contains("MetalRtShadowPass.runIfEnabled(screen,"),
                "it drives the existing owner rather than duplicating it");
    }

    /**
     * The fill rides the command buffer that already builds the structures, so the build and the
     * trace cannot be reordered or separated by a submission boundary.
     */
    @Test
    void theCelestialFillIsEncodedIntoTheSameCommandBufferThatBuildsTheStructures() throws IOException {
        String pass = read("metalfx/rt/MetalRtShadowPass.java");
        int build = pass.indexOf("MetalRtAcceleration.rebuildInstances(");
        int fill = pass.indexOf("encodeCelestialFill(cb, commandQueue, instanceStructure");
        assertTrue(build > 0 && fill > build,
                "the fill dispatch must follow the structure rebuild inside one command buffer");
        assertTrue(pass.contains("RayTier.HARDWARE_VOXEL.ordinal(), true)"),
                "the fill declares its tier and that it is filling, not owning, the image");
    }

    /**
     * A pack that reads only the cascade's image subscribes to none of the legacy targets, so
     * without this the backend never comes up and the voxel tier silently never answers. That was
     * the state the engine shipped in: the structures existed only while the debug view was on.
     */
    @Test
    void aCelestialRequestIsItselfAReasonToBringTheBackendUp() throws IOException {
        assertTrue(read("metalfx/rt/MetalRtShadowPass.java").contains("|| celestial != null;"),
                "a celestial request must count as a consumer for the availability gate");
    }

    /**
     * Publication follows the copy, and the copy is the previous frame's. A frame that never
     * dispatched owes nothing and certifies nothing, so the pack never reads an image whose
     * contents no tier wrote.
     */
    @Test
    void theTargetIsPublishedOnlyAfterTheCopyThatDeliversIt() throws IOException {
        String pass = read("metalfx/rt/MetalRtShadowPass.java");
        int copy = pass.indexOf("copyCelestialOut(cmd, stack, pendingPublishImage);");
        int published = pass.indexOf("TerrainShadowResult.published();");
        assertTrue(copy > 0 && published > copy,
                "publication must follow the copy that delivers the image, not precede it");
        assertTrue(pass.contains("publishPending = false;"),
                "and the debt must be cleared, or the same image publishes every frame");
    }

    /** Installed only where the bridge links, alongside the mesh tier, and after teardown. */
    @Test
    void theGraphRebuildInstallsBothMetalTiers() throws IOException {
        String runner = read("pack/graph/GraphRunner.java");
        assertTrue(runner.contains("rayProviders.add(new MeshMetalProvider());"));
        assertTrue(runner.contains("rayProviders.add(new dev.icehunter.fornax.metalfx.rt.VoxelMetalProvider());"));
        assertTrue(runner.indexOf("closeCurrent();") < runner.indexOf("RayRouter.install(rayProviders);"));
    }

    /**
     * The voxel tier's geometry must not depend on an unrelated pack feature.
     *
     * <p>The brick grid attaches only while something reads it, and that predicate counted pack
     * passes alone. On a pack whose only voxel readers are its water reflections, turning those off
     * detached the grid and this tier went dark: exact RT out to the mesh radius, raster past it,
     * and nothing anywhere saying why. A tier that can answer has to be able to ask for what it
     * needs.
     */
    @Test
    void theVoxelTierItselfCountsAsAReaderOfTheGridItTraces() throws IOException {
        String runner = read("pack/graph/GraphRunner.java");
        assertTrue(runner.contains("private static boolean voxelRayTierNeedsGrid("),
                "the tier must be able to request the grid");

        int gate = runner.indexOf("if (voxelRayTierNeedsGrid(graph, compileValues)) {");
        int loop = runner.indexOf("for (PassSpec p : graph.passes()) {",
                runner.indexOf("static boolean anyEnabledComputePassReadsVoxelGrid("));
        assertTrue(gate > 0 && gate < loop,
                "it is asked before the pack-pass scan, so no pack declaration is required");

        // Exactly the conditions under which the tier would answer, so the grid is never streamed
        // for a machine or a pack that could not use it.
        assertTrue(runner.contains("Objc.PLATFORM_SUPPORTED"), "only where the bridge links");
        assertTrue(runner.contains("MetalRtSupport.isAvailable()"), "only on a device that can trace");
        assertTrue(runner.contains("p.inputs().contains(TerrainShadowResult.TARGET)"),
                "and only for a pack that actually subscribes to ray-traced shadows");
    }
}
