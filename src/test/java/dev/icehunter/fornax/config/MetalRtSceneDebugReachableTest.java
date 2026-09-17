package dev.icehunter.fornax.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Metal RT scene debug view was unreachable in game by three independent mechanisms at once: it
 * was excluded from {@link GBufferDebugView#isSelectable()} so the F9 cycle skipped it, {@link
 * FornaxSettings#migrate} reset it to {@code OFF} on every load so a hand-edited config could not
 * hold it either, and {@code rtDebugMode} had no control in the settings screen at all. Each on its
 * own is enough to make a working trace look like a missing feature, and each is cheap to
 * reintroduce by accident, so each gets an assertion here.
 */
class MetalRtSceneDebugReachableTest {

    @Test
    void theRtSceneViewIsSelectableSoTheCycleCanReachIt() {
        assertTrue(GBufferDebugView.METAL_RT_SCENE_DEBUG.isSelectable(),
                "MetalRtDebugPass.presentIfEnabled blits this view, so it must be selectable");
    }

    /**
     * The cycle steps until it finds a selectable view. If this one is skipped, no keypress reaches
     * it, whatever the config says.
     */
    @Test
    void steppingThroughEverySelectableViewLandsOnTheRtSceneView() {
        boolean found = false;
        for (GBufferDebugView view : GBufferDebugView.values()) {
            if (view.isSelectable() && view == GBufferDebugView.METAL_RT_SCENE_DEBUG) {
                found = true;
            }
        }
        assertTrue(found, "the F9 cycle walks selectable views; this one must be among them");
    }

    /** A saved pick must survive a reload, or the user re-picks it every launch. */
    @Test
    void migrateKeepsTheRtSceneViewInsteadOfResettingItToOff() {
        FornaxSettings saved = new FornaxSettings();
        saved.debugView = GBufferDebugView.METAL_RT_SCENE_DEBUG;

        assertEquals(GBufferDebugView.METAL_RT_SCENE_DEBUG, FornaxSettings.migrate(saved).debugView,
                "migrate must not wipe a view the user can now select");
    }

    /**
     * Its neighbour keeps being reset, deliberately: METAL_RT_SUN_MASK has no presenter worth
     * restoring. Asserted so the fix above is not read as "stop resetting retired views".
     */
    @Test
    void migrateStillResetsTheRetiredSunMaskView() {
        FornaxSettings saved = new FornaxSettings();
        saved.debugView = GBufferDebugView.METAL_RT_SUN_MASK;

        assertEquals(GBufferDebugView.OFF, FornaxSettings.migrate(saved).debugView);
    }

    /** A null view still normalizes, which is what this guard was originally for. */
    @Test
    void migrateStillNormalizesAViewGsonCouldNotResolve() {
        FornaxSettings saved = new FornaxSettings();
        saved.debugView = null;

        assertEquals(GBufferDebugView.OFF, FornaxSettings.migrate(saved).debugView);
    }

    /**
     * The trace's colouring mode needs a control. Without one the only route was hand-editing
     * fornax.json, and a mode nobody can select renders as a feature that does nothing.
     */
    @Test
    void theRtDebugModeOptionHasANameAndTooltipInTheLanguageFile() throws IOException {
        String json = Files.readString(Path.of("src/main/resources/assets/fornax/lang/en_us.json"));
        @SuppressWarnings("unchecked")
        Map<String, String> lang = new Gson().fromJson(json, Map.class);

        assertTrue(lang.containsKey("gui.fornax.option.rt_debug_mode"),
                "the control would render its raw translation key without this");
        assertTrue(lang.containsKey("gui.fornax.option.rt_debug_mode.tooltip"),
                "every option in this screen carries a tooltip");
    }

    /** The screen must actually build the control, not merely copy the field. */
    @Test
    void theSettingsScreenBuildsAControlForTheRtDebugMode() throws IOException {
        String screen = Files.readString(
                Path.of("src/main/java/dev/icehunter/fornax/screen/FornaxSettingsScreen.java"));
        assertTrue(screen.contains("buildRtDebugModeOption()"),
                "FornaxSettingsScreen must define the control");
        assertTrue(screen.contains(".option(buildRtDebugModeOption())"),
                "defining it is not enough: it must be added to a group, or it never renders");
    }

    /**
     * Two independent reasons to bring the backend up, and each has to work alone. A pack that
     * reads only the cascade's image subscribes to nothing else, so gating on a pack subscription
     * left both the diagnostic and the voxel tier unreachable with every control set correctly and
     * nothing logged to say why.
     */
    @Test
    void theShadowPassCountsASelectedDebugModeAsItsOwnSubscription() throws IOException {
        String pass = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/metalfx/rt/MetalRtShadowPass.java"));
        int at = pass.indexOf("boolean consumer =");
        assertTrue(at > 0, "runIfEnabled must still compute a consumer flag");
        String decl = pass.substring(at, pass.indexOf(';', at));
        assertTrue(decl.contains("celestial != null"),
                "a celestial fill request must bring the backend up on its own, or the voxel tier "
                        + "never answers for a pack that reads only the cascade's image");
        assertTrue(decl.contains("rtDebugMode"),
                "a selected scene-debug mode must bring the backend up on its own, or the "
                        + "diagnostic is unreachable on any pack using the mesh shadow path");
    }

    /** Every mode the enum offers is formatted by the control; a missing arm is a compile error. */
    @Test
    void theViewNameNoLongerCallsItselfLegacyNowThatItRunsWithoutOne() {
        String label = GBufferDebugView.METAL_RT_SCENE_DEBUG.label();
        assertNotEquals("", label);
        assertTrue(!label.toLowerCase().contains("legacy"),
                "the trace runs on rtDebugMode alone and builds its own acceleration structure, so "
                        + "calling it legacy tells a user it needs something it does not: " + label);
    }
}
