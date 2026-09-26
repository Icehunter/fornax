package dev.icehunter.fornax.pass.mirror;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-text contract for a recycling rule that matters because skipping it caused a MoltenVK
 * device-loss leak in {@code PlayerShadowCaster}: {@code endFrame()} on this class's private
 * {@code RenderBuffers} must be called from inside a {@code finally} block, so a throwing {@code
 * renderAllFeatures} still recycles its staged vertex memory instead of holding onto it forever.
 *
 * <p>No live-GPU test exists for this anywhere in the tree, nor one for {@code PlayerShadowCaster}
 * itself, so a source-text check is what is available headless. This is the same approach {@code
 * GBufferFormatLockTest} and {@code PlayerMirrorTargetsTest} use for a fact a headless test cannot
 * exercise by calling the code.
 */
class PlayerMirrorCasterEndFrameContractTest {

    @Test
    void endFrameIsCalledInsideAFinallyBlock() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pass/mirror/PlayerMirrorCaster.java"));

        int endFrameCall = source.indexOf("mirrorBuffers.endFrame();");
        assertTrue(endFrameCall >= 0, "PlayerMirrorCaster must call mirrorBuffers.endFrame()");

        String before = source.substring(0, endFrameCall);
        int lastFinally = before.lastIndexOf("finally {");
        int lastTry = before.lastIndexOf("try {");
        assertTrue(lastFinally >= 0 && lastFinally > lastTry,
                "endFrame() must be called from inside a finally block, not after the try: a"
                        + " throwing draw must still recycle the staged vertex memory or it leaks"
                        + " every frame it throws on");
    }

    @Test
    void phaseFlagIsAlsoClearedInsideThatSameFinally() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pass/mirror/PlayerMirrorCaster.java"));

        // The phase flag is an active-slot marker, cleared with null rather than false; see
        // DeferredGeometryPipelines.setActiveMirrorSlot's comment.
        int setFalse = source.indexOf("setActiveMirrorSlot(null)");
        assertTrue(setFalse >= 0, "the active-slot marker must be lowered somewhere in this class");

        int endFrameCall = source.indexOf("mirrorBuffers.endFrame();");
        assertTrue(setFalse < endFrameCall,
                "the active-slot marker must be lowered before endFrame() recycles the buffers, so no"
                        + " later draw in the same frame is misrouted while the buffers are being"
                        + " reclaimed");
    }

    /**
     * Each slot must get a distinct {@code PlayerMirrorCaster} instance, never a shared one:
     * sharing one instance across slots would share the underlying {@code RenderBuffers} and
     * {@code FeatureRenderDispatcher} too, the staging-ring reuse hazard this class's javadoc
     * names. This is not a live-GPU claim (nothing here calls {@code cast()}); it only proves the
     * registry hands out distinct, memoized instances per slot, which is the part a headless test
     * can exercise.
     */
    @Test
    void everySlotGetsItsOwnDistinctCasterInstance() {
        var floor = dev.icehunter.fornax.pass.mirror.PlayerMirrorCaster.forSlot(
                dev.icehunter.fornax.pack.GeometrySlot.PLAYER_MIRROR);
        var x = dev.icehunter.fornax.pass.mirror.PlayerMirrorCaster.forSlot(
                dev.icehunter.fornax.pack.GeometrySlot.PLAYER_MIRROR_X);
        var z = dev.icehunter.fornax.pass.mirror.PlayerMirrorCaster.forSlot(
                dev.icehunter.fornax.pack.GeometrySlot.PLAYER_MIRROR_Z);
        var floorAgain = dev.icehunter.fornax.pass.mirror.PlayerMirrorCaster.forSlot(
                dev.icehunter.fornax.pack.GeometrySlot.PLAYER_MIRROR);

        assertNotSame(floor, x, "the floor and X-wall casters must be distinct instances");
        assertNotSame(floor, z, "the floor and Z-wall casters must be distinct instances");
        assertNotSame(x, z, "the two wall casters must be distinct instances");
        assertSame(floor, floorAgain, "the same slot must always return the SAME memoized instance");
    }
}
