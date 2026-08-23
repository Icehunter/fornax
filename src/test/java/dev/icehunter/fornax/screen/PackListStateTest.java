package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.screen.PackListState.Action;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PackListState} is the pure staged/live model behind {@code screen.FornaxPacksTab} -- no
 * Minecraft, YACL, or GraphRunner involved, so it is exercised directly here. The apply-plan
 * precedence pinned below mirrors the legacy both-changed ordering ({@code shadersEnabled} written
 * first, then ONLY the pack switch) that {@code ShaderPacksScreen.applyChanges} and
 * {@code FornaxSettingsScreen.applyRoutedChanges} both enforced.
 */
class PackListStateTest {
    @Test
    void freshStateIsNotDirty() {
        PackListState state = new PackListState(true, "PackA");
        assertFalse(state.isDirty());
        assertFalse(state.isEnabledDirty());
        assertFalse(state.isPackDirty());
        assertEquals(List.of(), state.applyPlan());
    }

    @Test
    void stagingSameValuesKeepsClean() {
        PackListState state = new PackListState(true, "PackA");
        state.stageEnabled(true);
        state.stagePack("PackA");
        assertFalse(state.isDirty());
        assertEquals(List.of(), state.applyPlan());
    }

    @Test
    void stagingDifferentPackIsDirty() {
        PackListState state = new PackListState(true, "PackA");
        state.stagePack("PackB");
        assertTrue(state.isPackDirty());
        assertFalse(state.isEnabledDirty());
        assertTrue(state.isDirty());
    }

    @Test
    void togglingEnabledIsDirty() {
        PackListState state = new PackListState(true, "PackA");
        state.stageEnabled(false);
        assertTrue(state.isEnabledDirty());
        assertFalse(state.isPackDirty());
        assertTrue(state.isDirty());
    }

    @Test
    void applyPlanEmptyWhenNotDirty() {
        PackListState state = new PackListState(false, "");
        assertEquals(List.of(), state.applyPlan());
    }

    @Test
    void applyPlanShadersFlipWhenOnlyEnabledChanged() {
        PackListState state = new PackListState(true, "PackA");
        state.stageEnabled(false);
        assertEquals(List.of(Action.SHADERS_FLIP), state.applyPlan());
    }

    @Test
    void applyPlanPackSwitchWhenOnlyPackChanged() {
        PackListState state = new PackListState(true, "PackA");
        state.stagePack("PackB");
        assertEquals(List.of(Action.PACK_SWITCH), state.applyPlan());
    }

    @Test
    void applyPlanWritesEnabledBeforePackSwitchWhenBothChanged() {
        PackListState state = new PackListState(true, "PackA");
        state.stageEnabled(false);
        state.stagePack("PackB");
        // Ordered: WRITE_ENABLED first (so PackSwitch reads the new shadersEnabled live), then
        // PACK_SWITCH -- and never SHADERS_FLIP. Pack change supersedes the toggle-only path.
        assertEquals(List.of(Action.WRITE_ENABLED, Action.PACK_SWITCH), state.applyPlan());
    }

    @Test
    void applyPlanNoneRoundTripStagesThenClears() {
        PackListState state = new PackListState(true, "PackA");
        state.stagePack(""); // stage "None"
        assertEquals(List.of(Action.PACK_SWITCH), state.applyPlan());
        state.stagePack("PackA"); // back to the live pack
        assertFalse(state.isDirty());
        assertEquals(List.of(), state.applyPlan());
    }

    @Test
    void enabledRoundTripStagesThenClears() {
        PackListState state = new PackListState(true, "PackA");
        state.stageEnabled(false);
        assertTrue(state.isDirty());
        assertEquals(List.of(Action.SHADERS_FLIP), state.applyPlan());
        state.stageEnabled(true); // back to the live value
        assertFalse(state.isDirty());
        assertEquals(List.of(), state.applyPlan());
    }

    @Test
    void revertingEnabledWhilePackDirtyDropsWriteEnabled() {
        PackListState state = new PackListState(true, "PackA");
        state.stageEnabled(false);
        state.stagePack("PackB");
        assertEquals(List.of(Action.WRITE_ENABLED, Action.PACK_SWITCH), state.applyPlan());
        state.stageEnabled(true); // back to the live value; pack is still dirty
        // Precedence is re-derived from current staged-vs-live state, never latched: reverting
        // enabled drops WRITE_ENABLED even though it was staged dirty a moment ago.
        assertEquals(List.of(Action.PACK_SWITCH), state.applyPlan());
    }

    @Test
    void applyPlanIsPureAndRepeatable() {
        PackListState state = new PackListState(true, "PackA");
        state.stageEnabled(false);
        state.stagePack("PackB");
        // applyPlan is a read, not a consume -- the tab queries dirty state after planning.
        assertEquals(state.applyPlan(), state.applyPlan());
        assertTrue(state.isDirty());
        assertTrue(state.isEnabledDirty());
        assertTrue(state.isPackDirty());
    }

    @Test
    void refreshReseedsStagedAndLive() {
        PackListState state = new PackListState(true, "PackA");
        state.stageEnabled(false);
        state.stagePack("PackB");
        assertTrue(state.isDirty());
        // Post-apply convergence: live becomes the applied values, staged snaps to match.
        state.refresh(false, "PackB");
        assertFalse(state.isDirty());
        assertEquals(false, state.stagedEnabled());
        assertEquals("PackB", state.stagedPack());
        assertEquals(false, state.liveEnabled());
        assertEquals("PackB", state.livePack());
    }
}
