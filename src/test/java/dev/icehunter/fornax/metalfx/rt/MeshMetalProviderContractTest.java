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
 * Source-level where it has to be: the wiring this covers lives in a Sodium mixin and in the graph
 * interpreter, neither of which a unit test can execute, and the provider itself needs a Metal
 * device. What is pinned here is the set of silent failures the move to the router introduced.
 *
 * <p>The worst of them is a stale image. The celestial target keeps last frame's contents and its
 * own validity flag, so a frame where this tier declines without invalidating leaves the pack
 * reading an answer for a world that has moved. Every decline path therefore has to invalidate,
 * and counting the call sites is the only check that survives someone adding a sixth.
 */
class MeshMetalProviderContractTest {

    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax");

    private static String read(String path) throws IOException {
        return Files.readString(SOURCE.resolve(path));
    }

    /**
     * Both kinds, at the mesh tier.
     *
     * <ul>
     *   <li>The kernel commits the nearest intersection and reads back its distance, flags,
     *       surface word, atlas UV and normal. That is closest hit.
     *   <li>A tier that declines a kind it can answer is skipped by the router with nothing said,
     *       and every record in the batch stays at tier zero: a pack reads an untraced buffer.
     * </ul>
     */
    @Test
    void theProviderAnswersBothQueryKindsAtTheMeshTier() {
        MeshMetalProvider provider = new MeshMetalProvider();
        assertEquals(RayTier.HARDWARE_MESH, provider.tier());
        assertTrue(provider.answers(RayQueryKind.VISIBILITY));
        assertTrue(provider.answers(RayQueryKind.CLOSEST_HIT),
                "the buffer query IS the atlas UV path, so a bounce can be answered here");
    }

    /**
     * Eight decline paths, each of which must invalidate. Five are plain refusals: unavailable, no
     * device or atlas, an empty caster snapshot, the pack's shadow pass gated off, and the catch.
     * The sixth is a frame where nothing handed over a caster source before the fill, which cannot
     * happen in the shipping call order but is reachable by any future caller of the interface.
     * The seventh is a frame where a build is under way but nothing is promoted yet (the first
     * frame after load, or after a teleport), which has meshes to trace but no structure ready to
     * trace them against. The eighth is a frame with a zero receiving distance: nothing for this
     * fill to trace, even though a ray-query caller may still build its own structure later the
     * same frame.
     */
    @Test
    void everyDeclinePathInvalidatesTheCelestialTarget() throws IOException {
        String provider = read("metalfx/rt/MeshMetalProvider.java");
        assertEquals(8, provider.split("TerrainShadowResult\\.invalidate\\(\\)", -1).length - 1,
                "a decline that leaves the target valid hands the pack last frame's shadows");
        assertTrue(provider.contains("""
                        if (casters == null) {"""),
                "the fill must refuse to trace without this frame's caster source");
    }

    /** The caster source is cleared every frame, so one frame's meshes can never trace the next. */
    @Test
    void theCasterSourceIsClearedAtTheTopOfEveryFrame() throws IOException {
        String provider = read("metalfx/rt/MeshMetalProvider.java");
        int begin = provider.indexOf("public void beginFrame()");
        assertTrue(begin > 0, "the provider must reset per-frame state");
        String body = provider.substring(begin, provider.indexOf('}', provider.indexOf('{', begin)));
        assertTrue(body.contains("casters = null"),
                "a stale caster source would trace a previous frame's uploaded meshes");
    }

    /** Source-level on purpose: the pass this mixin replaced is not in the tree, so a compiled
     * reference to it is impossible and the absence check has to read the source. */
    @Test
    void theOrchestrationMixinDrivesTheRouterRatherThanThePassItReplaced() throws IOException {
        String mixin = read("mixin/sodium/SodiumWorldRendererOrchestrationMixin.java");
        assertTrue(mixin.contains("RayRouter.beginFrame()"),
                "readiness is evaluated once per frame, before any query");
        assertTrue(mixin.contains("RayRouter.phaseOne(new dev.icehunter.fornax.rt.CelestialFill("),
                "the exact-geometry tier runs at the shadow pass site so its trace overlaps the draw");
        assertFalse(mixin.contains("TerrainShadowPass"),
                "the pass this replaced is gone");
        assertTrue(mixin.contains("provider.captureCasters(this.renderSectionManager)"),
                "the renderer-owned caster source can only be read at this point on the render thread");
    }

    /**
     * Installation is per platform, not per setting. A machine with no Metal bridge installs no
     * Metal tier at all, which is why nothing downstream needs an availability flag: to a pack an
     * absent tier and a disabled one are the same answer.
     */
    @Test
    void theGraphRebuildInstallsTheMeshTierOnlyWhereTheMetalBridgeExists() throws IOException {
        String runner = read("pack/graph/GraphRunner.java");
        assertTrue(runner.contains("if (dev.icehunter.fornax.metalfx.objc.Objc.PLATFORM_SUPPORTED) {"),
                "the Metal tier is constructed only where the bridge links");
        assertTrue(runner.contains("rayProviders.add(new MeshMetalProvider());"));
        assertTrue(runner.contains("RayRouter.install(rayProviders);"));
        assertTrue(runner.contains("RayRouter.close();"),
                "pack teardown closes every provider; the old pass was closed here by name");
    }

    /** Rebuild installs after teardown, or the fresh providers are the ones that get closed. */
    @Test
    void installHappensAfterTeardownWithinTheRebuild() throws IOException {
        String runner = read("pack/graph/GraphRunner.java");
        assertTrue(runner.indexOf("closeCurrent();") < runner.indexOf("RayRouter.install(rayProviders);"),
                "installing before the teardown would close the providers just constructed");
    }

    /**
     * Guards against starvation: a build must never start while one is already outstanding, so the
     * diff (and the build it gates) is skipped whenever pendingBuilt is not null. The only place a
     * pending build is ever discarded is close()/teardown, never a fresh mesh change.
     */
    @Test
    void theBuildPathIsGuardedByPendingBuiltAndDiscardPendingRunsOnlyFromClose() throws IOException {
        String provider = read("metalfx/rt/MeshMetalProvider.java");
        assertTrue(provider.contains("if (pendingBuilt == null) {"),
                "a build must never start while one is already outstanding, or a mesh change "
                        + "mid-build discards and restarts it every frame: starvation");
        assertEquals(1, provider.split("discardPending\\(\\);", -1).length - 1,
                "discardPending() must have exactly one call site");
        int close = provider.indexOf("public void close() {");
        assertTrue(close > 0, "close() must exist");
        int call = provider.indexOf("discardPending();");
        assertTrue(call > close,
                "its one call site must be inside close(): a fresh mesh change must never discard "
                        + "a build in flight, only teardown does");
    }

    /**
     * RT shadows off does not mean no ray queries: a pack running GI or lamp queries with no shadow
     * subscription still needs this tier's structure, so a zero receiving distance alone must not
     * tear it down. Query demand is what keeps the celestial fill from destroying resources a buffer
     * query is about to build lazily.
     */
    @Test
    void aZeroReceivingDistanceWithQueryDemandKeepsTheStructureInstead() throws IOException {
        String provider = read("metalfx/rt/MeshMetalProvider.java");
        String trace = method(provider, "private void traceFrame(");
        assertTrue(trace.contains("RayRouter.queryDemand()"),
                "query demand alone must count as subscribed ahead of the availability probe");
        int radiusGate = trace.indexOf("if (radius <= 0) {");
        assertTrue(radiusGate > 0, "a zero receiving distance must be its own branch");
        String radiusBody = trace.substring(radiusGate, trace.indexOf("return;", radiusGate));
        assertFalse(radiusBody.contains("close()"),
                "a zero receiving distance must not tear down a structure a query caller still needs");
    }

    /**
     * A buffer query in a frame with no celestial trace must trigger the structure build, exactly
     * once, from its own camera-window selection rather than the sun's light volume this path has
     * none of.
     */
    @Test
    void aQueryWithNoCelestialFillTriggersTheStructureBuildOncePerFrame() throws IOException {
        String provider = read("metalfx/rt/MeshMetalProvider.java");
        String answer = method(provider, "public void answer(BufferQuery query) {");
        assertTrue(answer.contains("if (debugTlas == 0 && !queryBuildAttempted) {"),
                "an unanswered frame must attempt the lazy build exactly once");
        assertTrue(answer.contains("ensureStructureForQuery();"));

        String ensure = method(provider, "private void ensureStructureForQuery() {");
        assertTrue(ensure.startsWith("\n        queryBuildAttempted = true;"),
                "the attempt must be marked before any early return, or a frame with nothing to "
                        + "build would retry on every ray-query pass");
        assertTrue(ensure.contains("snapshotAroundCamera("),
                "no light matrix exists here, so casters must be selected by camera window");
        assertTrue(ensure.contains("MetalRtSupport.isAvailableFor(true)"),
                "hardware/mode support must still gate this path independent of the shadow radius");
        assertTrue(ensure.contains("failed = true;"),
                "a build that keeps throwing must latch failed the same way traceFrame's catch does");

        assertTrue(provider.contains("queryBuildAttempted = false;"),
                "forgetDebugScene() must reset the attempt at the top of every frame");
    }

    /**
     * A pack with buffer-query demand needs this tier's structure to reach as far as GI and lamp rays
     * do, not only as far as the sun shadow receives into. The fill folds the query-reach window into
     * its own selection instead of leaving it to a second, later build that would only be attempted
     * once this frame's debugTlas is still zero.
     */
    @Test
    void aTraceFrameWithQueryDemandUnionsTheQueryReachWindowIntoItsOwnSelection() throws IOException {
        String provider = read("metalfx/rt/MeshMetalProvider.java");
        String trace = method(provider, "private void traceFrame(");
        assertTrue(trace.contains("boolean includeQueryReach = RayRouter.queryDemand();"),
                "query demand alone decides whether the light-volume selection widens to the query window");
        assertTrue(trace.contains("snapshot(manager, x, y, z, light, includeQueryReach)"),
                "the union must be folded into the same selection the shadow trace itself uses");
        assertTrue(trace.contains("buildStructure(device, atlasSource, x, y, z, sources, phase, includeQueryReach)"));
    }

    /** Both build paths share one named reach so a caller cannot answer past what the other builds. */
    @Test
    void bothStructureBuildPathsShareTheSameQueryReachConstant() throws IOException {
        String provider = read("metalfx/rt/MeshMetalProvider.java");
        assertTrue(provider.contains("private static final float QUERY_REACH_BLOCKS = 96f;"));
        assertTrue(provider.contains("cameraWindowFilter(QUERY_REACH_BLOCKS)"),
                "traceFrame's union must reach exactly as far as the query-only fallback does");
        assertTrue(provider.contains("snapshotAroundCamera(manager, x, y, z, QUERY_REACH_BLOCKS)"),
                "ensureStructureForQuery's camera-only fallback uses the same named reach");
    }

    /**
     * The diff gate's own defence against the one case a plain source diff cannot see: a frame where
     * the light volume already happened to contain the whole query window, so the diffed sources match
     * even though the reach the caller needs just changed. Exercised directly, not through source
     * text, since it is a pure function with no GPU dependency.
     */
    @Test
    void queryReachChangedForcesARebuildOnlyWhenLiveDisagreesWithTheNewDemand() {
        assertFalse(MeshMetalProvider.queryReachChanged(false, false, true),
                "no live structure yet: nothing to disagree with");
        assertFalse(MeshMetalProvider.queryReachChanged(true, true, true));
        assertFalse(MeshMetalProvider.queryReachChanged(true, false, false));
        assertTrue(MeshMetalProvider.queryReachChanged(true, false, true),
                "demand just turned on: live was built without the query window");
        assertTrue(MeshMetalProvider.queryReachChanged(true, true, false),
                "demand just turned off: live still carries the wider selection");
    }

    /** The diff gate must weigh the reach-toggle alongside the source and origin diffs, not instead. */
    @Test
    void theDiffGateWeighsTheReachToggleAlongsideTheExistingChecks() throws IOException {
        String provider = read("metalfx/rt/MeshMetalProvider.java");
        String build = method(provider, "private StructureBuild buildStructure(");
        int needsBuild = build.indexOf("needsBuild = contentDirty");
        assertTrue(needsBuild > 0, "the diff gate must still start from the content diff");
        String gate = build.substring(needsBuild, build.indexOf(';', needsBuild));
        assertTrue(gate.contains("!retained.equals(copies.keySet())"));
        assertTrue(gate.contains("ox != liveOriginX || oy != liveOriginY || oz != liveOriginZ"));
        assertTrue(gate.contains("queryReachChanged(liveBuilt != null, liveIncludesQueryReach, includeQueryReach)"),
                "the reach toggle must widen the same gate, not replace or bypass it");
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing production scope " + signature);
        int open = source.indexOf('{', start);
        int depth = 1;
        for (int i = open + 1; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(open + 1, i);
        }
        throw new AssertionError("unclosed production scope: " + signature);
    }
}
