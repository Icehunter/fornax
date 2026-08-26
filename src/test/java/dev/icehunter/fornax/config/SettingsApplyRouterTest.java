package dev.icehunter.fornax.config;

import dev.icehunter.fornax.config.SettingsApplyRouter.Action;
import dev.icehunter.fornax.pass.ssaa.SsaaPreset;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SettingsApplyRouter#route} is the pure field-diff behind {@code
 * screen.FornaxSettingsScreen}'s YACL save callback -- exercised directly here against hand-built
 * before/after {@link FornaxSettings} pairs, with no YACL, Minecraft, or GraphRunner involved.
 */
class SettingsApplyRouterTest {
    @Test
    void noChangesRoutesNothing() {
        FornaxSettings before = new FornaxSettings();
        FornaxSettings after = new FornaxSettings();

        assertTrue(SettingsApplyRouter.route(before, after).isEmpty());
    }

    @Test
    void shadersEnabledChangeRoutesNothing() {
        // shadersEnabled is owned by screen.FornaxPacksTab (self-applying, outside YACL's save
        // cycle) -- the router must not report it anymore.
        FornaxSettings before = new FornaxSettings();
        before.shadersEnabled = true;
        FornaxSettings after = new FornaxSettings();
        after.shadersEnabled = false;

        assertTrue(SettingsApplyRouter.route(before, after).isEmpty());
    }

    @Test
    void activePackChangeRoutesNothing() {
        // activePack is owned by screen.FornaxPacksTab too -- a bare activePack diff routes nothing.
        FornaxSettings before = new FornaxSettings();
        before.activePack = "PackA";
        FornaxSettings after = new FornaxSettings();
        after.activePack = "PackB";

        assertTrue(SettingsApplyRouter.route(before, after).isEmpty());
    }

    @Test
    void aaMethodChangeRoutesPackReapplyAndSave() {
        FornaxSettings before = new FornaxSettings();
        before.aaMethod = AaMethod.TAA;
        FornaxSettings after = new FornaxSettings();
        after.aaMethod = AaMethod.SSAA;

        assertEquals(Set.of(Action.PACK_REAPPLY, Action.SAVE_ONLY), SettingsApplyRouter.route(before, after));
    }

    @Test
    void ssaaPresetChangeAloneRoutesPackReapplyAndSave() {
        FornaxSettings before = new FornaxSettings();
        before.ssaaPreset = SsaaPreset.X2;
        FornaxSettings after = new FornaxSettings();
        after.ssaaPreset = SsaaPreset.X4;

        assertEquals(Set.of(Action.PACK_REAPPLY, Action.SAVE_ONLY), SettingsApplyRouter.route(before, after));
    }

    @Test
    void taauRatioChangeAloneRoutesPackReapplyAndSave() {
        FornaxSettings before = new FornaxSettings();
        before.taauRatio = TaauRatio.BALANCED;
        FornaxSettings after = new FornaxSettings();
        after.taauRatio = TaauRatio.PERFORMANCE;

        assertEquals(Set.of(Action.PACK_REAPPLY, Action.SAVE_ONLY), SettingsApplyRouter.route(before, after));
    }

    @Test
    void profilerOverlayChangeRoutesSaveOnly() {
        FornaxSettings before = new FornaxSettings();
        before.profilerOverlay = false;
        FornaxSettings after = new FornaxSettings();
        after.profilerOverlay = true;

        assertEquals(Set.of(Action.SAVE_ONLY), SettingsApplyRouter.route(before, after));
    }

    @Test
    void debugViewChangeRoutesSaveOnly() {
        FornaxSettings before = new FornaxSettings();
        before.debugView = GBufferDebugView.OFF;
        FornaxSettings after = new FornaxSettings();
        after.debugView = GBufferDebugView.NORMALS;

        assertEquals(Set.of(Action.SAVE_ONLY), SettingsApplyRouter.route(before, after));
    }

    @Test
    void voxelReachIgnoresRenderDistanceChangeRoutesSaveOnly() {
        FornaxSettings before = new FornaxSettings();
        before.voxelReachIgnoresRenderDistance = false;
        FornaxSettings after = new FornaxSettings();
        after.voxelReachIgnoresRenderDistance = true;

        assertEquals(Set.of(Action.SAVE_ONLY), SettingsApplyRouter.route(before, after));
    }

    @Test
    void sunPathRotationChangeRoutesSaveOnly() {
        FornaxSettings before = new FornaxSettings();
        before.sunPathRotation = -25.0f;
        FornaxSettings after = new FornaxSettings();
        after.sunPathRotation = 40.0f;

        assertEquals(Set.of(Action.SAVE_ONLY), SettingsApplyRouter.route(before, after));
    }

    @Test
    void metalHudEnabledRoutesSaveOnlyAndApply() {
        FornaxSettings before = new FornaxSettings();
        before.metalHud = false;
        FornaxSettings after = new FornaxSettings();
        after.metalHud = true;

        assertEquals(Set.of(Action.SAVE_ONLY, Action.METAL_HUD_APPLY),
                SettingsApplyRouter.route(before, after));
    }

    @Test
    void metalHudDisabledRoutesSaveOnlyAndApply() {
        // Unlike FRAMEGEN_DEACTIVATE (one-way), METAL_HUD_APPLY must fire on BOTH transition
        // directions -- turning the HUD off is just as much a live apply as turning it on.
        FornaxSettings before = new FornaxSettings();
        before.metalHud = true;
        FornaxSettings after = new FornaxSettings();
        after.metalHud = false;

        assertEquals(Set.of(Action.SAVE_ONLY, Action.METAL_HUD_APPLY),
                SettingsApplyRouter.route(before, after));
    }

    @Test
    void frameGenModeToOffRoutesDeactivateAndSave() {
        FornaxSettings before = new FornaxSettings();
        before.frameGenMode = FrameGenMode.AUTO;
        FornaxSettings after = new FornaxSettings();
        after.frameGenMode = FrameGenMode.OFF;

        assertEquals(Set.of(Action.SAVE_ONLY, Action.FRAMEGEN_DEACTIVATE),
                SettingsApplyRouter.route(before, after));
    }

    @Test
    void frameGenModeAutoToAlwaysRoutesSaveOnly() {
        // AUTO<->ALWAYS is a live pacing-policy switch, not a resource transition: both keep
        // FrameGenPass armed, so this must NOT route FRAMEGEN_DEACTIVATE.
        FornaxSettings before = new FornaxSettings();
        before.frameGenMode = FrameGenMode.AUTO;
        FornaxSettings after = new FornaxSettings();
        after.frameGenMode = FrameGenMode.ALWAYS;

        assertEquals(Set.of(Action.SAVE_ONLY), SettingsApplyRouter.route(before, after));
    }

    @Test
    void independentActionsCombineInOneSave() {
        // A single YACL "Save" can carry multiple pending option changes at once -- the router must
        // report every action that applies, not just one.
        FornaxSettings before = new FornaxSettings();
        before.aaMethod = AaMethod.TAA;
        before.debugView = GBufferDebugView.OFF;
        FornaxSettings after = new FornaxSettings();
        after.aaMethod = AaMethod.SSAA;
        after.debugView = GBufferDebugView.NORMALS;

        assertEquals(Set.of(Action.PACK_REAPPLY, Action.SAVE_ONLY),
                SettingsApplyRouter.route(before, after));
    }

    @Test
    void reconstructTuningFieldsAreOutOfScopeAndRouteNothing() {
        // taaBlendFactor/reconstructSharpen aren't exposed by this YACL slice at all -- a diff on
        // them alone must not trigger any action either.
        FornaxSettings before = new FornaxSettings();
        before.taaBlendFactor = 0.9f;
        before.reconstructSharpen = 0.5f;
        FornaxSettings after = new FornaxSettings();
        after.taaBlendFactor = 0.5f;
        after.reconstructSharpen = 0.8f;

        assertTrue(SettingsApplyRouter.route(before, after).isEmpty());
    }
}
