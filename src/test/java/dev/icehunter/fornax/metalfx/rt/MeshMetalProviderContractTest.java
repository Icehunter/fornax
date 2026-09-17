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

    @Test
    void theProviderAnswersVisibilityAtTheMeshTierAndNotClosestHit() {
        MeshMetalProvider provider = new MeshMetalProvider();
        assertEquals(RayTier.HARDWARE_MESH, provider.tier());
        assertTrue(provider.answers(RayQueryKind.VISIBILITY));
        assertFalse(provider.answers(RayQueryKind.CLOSEST_HIT),
                "closest hit needs the atlas UV path the buffer query carries; claiming it would "
                        + "make the router skip a lower tier that could actually answer");
    }

    /**
     * Six, not five. The deleted pass had five: unavailable, no device or atlas, an empty caster
     * snapshot, the pack's shadow pass gated off, and the catch. The sixth is new with the split:
     * a frame where nothing handed over a caster source before the fill, which cannot happen in the
     * shipping call order but is reachable by any future caller of the interface.
     */
    @Test
    void everyDeclinePathInvalidatesTheCelestialTarget() throws IOException {
        String provider = read("metalfx/rt/MeshMetalProvider.java");
        assertEquals(6, provider.split("TerrainShadowResult\\.invalidate\\(\\)", -1).length - 1,
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

    /** The frame hook and the phase-one call both moved; naming the deleted pass would not compile. */
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
}
