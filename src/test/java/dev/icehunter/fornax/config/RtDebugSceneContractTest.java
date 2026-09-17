package dev.icehunter.fornax.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scene selector for the RT debug view, and the one thing about it that fails silently.
 *
 * <p>The two tiers build their geometry in different coordinate frames: the voxel structure is
 * addressed relative to its window's first section, the mesh structure relative to a coarse grid
 * origin the mesh tracer rebases onto every frame. A dispatch that binds one scene's structure with
 * the other's origin still traces, still shades, and still tracks the camera. It looks at the
 * wrong part of the world. Nothing errors, so the pairing is pinned here.
 */
class RtDebugSceneContractTest {

    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax");

    private static String read(String path) throws IOException {
        return Files.readString(SOURCE.resolve(path));
    }

    @Test
    void thereAreExactlyTwoScenesAndVoxelIsTheDefault() {
        assertEquals(2, RtDebugScene.values().length);
        assertEquals(RtDebugScene.VOXEL, RtDebugScene.values()[0],
                "the default is the scene that exists on any pack; the mesh scene needs a shadow "
                        + "subscription to have been built at all");
    }

    /** Gson leaves an absent enum field null, so every config written before this existed needs it. */
    @Test
    void anOlderConfigWithoutTheSceneMigratesToVoxel() throws IOException {
        String settings = read("config/FornaxSettings.java");
        assertTrue(settings.contains("if (settings.rtDebugScene == null) {"),
                "an absent rtDebugScene must migrate rather than stay null and throw at first use");
        assertTrue(settings.contains("settings.rtDebugScene = RtDebugScene.VOXEL;"));
    }

    /** An option that is never added to a group is fully working and completely invisible. */
    @Test
    void theSceneOptionIsBothDefinedAndPlacedOnTheScreen() throws IOException {
        String screen = read("screen/FornaxSettingsScreen.java");
        assertTrue(screen.contains("private static Option<RtDebugScene> buildRtDebugSceneOption()"),
                "the option must be defined");
        assertTrue(screen.contains(".option(buildRtDebugSceneOption())"),
                "defining it is not enough: it must be added to a group, or it never renders");

        String lang = Files.readString(
                Path.of("src/main/resources/assets/fornax/lang/en_us.json"));
        assertTrue(lang.contains("\"gui.fornax.option.rt_debug_scene\""),
                "an unlocalised option renders its raw translation key");
        assertTrue(lang.contains("\"gui.fornax.option.rt_debug_scene.tooltip\""));
    }

    /**
     * The structure and the origin travel together. Binding the mesh structure while writing the
     * voxel window's origin traces a real scene from the wrong place in the world, which looks
     * like a rendering bug rather than a wiring one.
     */
    @Test
    void theTracedStructureAndTheOriginItIsAddressedAgainstAreChosenTogether() throws IOException {
        String pass = read("metalfx/rt/MetalRtShadowPass.java");
        assertTrue(pass.contains("meshScene.originX(), meshScene.originY(), meshScene.originZ()"),
                "the mesh scene supplies its own origin");
        assertTrue(pass.contains("meshScene != null ? meshScene.structure() : instanceStructure"),
                "and its own structure, selected on the same condition");
    }

    /**
     * An encoder cannot see through an acceleration structure to the buffers beneath it. Binding
     * the mesh structure without making its own resources resident returns misses for every ray,
     * which reads as an empty world rather than as an error.
     */
    @Test
    void theMeshSceneMakesItsOwnResourcesResidentRatherThanTheVoxelTiers() throws IOException {
        String pass = read("metalfx/rt/MetalRtShadowPass.java");
        assertTrue(pass.contains("for (long resource : meshScene.resources()) {"),
                "the mesh scene's own handles must be made resident");
        assertTrue(pass.contains("} else {\n                                MetalRtAcceleration.useResources(debugEncoder);"),
                "and the voxel scene's residency must be the other arm, not both");
        assertTrue(read("metalfx/rt/MeshShadowTracer.java").contains("public synchronized void appendResidentResources("),
                "the mesh tracer owns the list of what its structure refers to");
    }

    /**
     * The mesh tier builds its acceleration structures in its own command buffer. Metal starts
     * command buffers on a queue in order but does not hold a later one until an earlier one
     * finishes, so a dispatch that binds those structures without waiting can read them mid-build.
     * The failure is not a crash and not an empty scene: the shapes land, because most of the BVH
     * is already written, and the per-triangle data behind them is garbage, so every surface
     * carries noise instead of its one flat face colour. That is indistinguishable from a decode
     * bug by eye, which is why the wait is pinned rather than left to review.
     */
    @Test
    void theDebugTraceWaitsForTheMeshTiersStructuresBeforeReadingThem() throws IOException {
        String pass = read("metalfx/rt/MetalRtShadowPass.java");
        assertTrue(pass.contains("if (meshScene != null && meshScene.readyEvent() != 0) {"),
                "the mesh scene must be waited on before it is traced");
        assertTrue(pass.contains("meshScene.readyEvent(), meshScene.readyValue()"),
                "and on the value that tier actually signals");

        int wait = pass.indexOf("encodeWaitForEvent:value:\"),\n                                    meshScene.readyEvent()");
        int encoder = pass.indexOf("long debugEncoder = Objc.msgSendId(cb,");
        assertTrue(wait > 0 && wait < encoder,
                "the wait must be encoded into the command buffer before the dispatch that reads");

        String provider = read("metalfx/rt/MeshMetalProvider.java");
        assertTrue(provider.contains("debugEventValue = value + 1;"),
                "the value handed over must be the one the mesh tier's Metal work signals, which "
                        + "is value + 1: value is the Vulkan input and value + 2 the copy-back");
    }

    /**
     * A released Metal object kills the process, not the frame. The mesh tier's scene handles, its
     * structure and the event that says when the structure is ready, describe one frame. A teleport
     * empties the caster set, the tier closes its timeline, and a handle held past that point names
     * freed memory. {@code encodeWaitForEvent} on it dies inside {@code objc_msgSend} with no Java
     * frame to say why.
     */
    @Test
    void theMeshTierForgetsItsSceneHandlesOnEveryFramePathThatCanFreeThem() throws IOException {
        String provider = read("metalfx/rt/MeshMetalProvider.java");
        assertTrue(provider.contains("private void forgetDebugScene() {"),
                "one place clears the handles, so a new teardown path cannot forget half of them");

        // Every path that can destroy the timeline or the structure, plus the top of every frame.
        int cleared = provider.split("forgetDebugScene\\(\\);", -1).length - 1;
        assertTrue(cleared >= 4,
                "the handles must be dropped on frame start, on both mid-trace teardowns and on "
                        + "close; found " + cleared + " call sites");

        int beginFrame = provider.indexOf("public void beginFrame()");
        String beginBody = provider.substring(beginFrame, provider.indexOf("\n    }", beginFrame));
        assertTrue(beginBody.contains("forgetDebugScene()"),
                "a frame must never inherit the previous frame's scene handles");
    }
}
