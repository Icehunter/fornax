package dev.icehunter.fornax.screen;

import java.util.List;

/**
 * Pure staged/live model for the shader-packs tab ({@code screen.FornaxPacksTab}): the master
 * shaders toggle and the active-pack selection, each held as a STAGED value (what Apply will commit)
 * alongside the LIVE value (what config currently holds). No Minecraft, YACL, GraphRunner, or config
 * references -- the tab is a thin widget shell over this, and this is unit-tested headless.
 *
 * <p>{@link #applyPlan()} returns the ORDERED action list the tab executes, preserving the legacy
 * both-changed precedence exactly: pack change supersedes the toggle-only path, and when both change
 * the staged {@code shadersEnabled} is written FIRST ({@link Action#WRITE_ENABLED}) so
 * {@code pack.PackSwitch.apply} reads the new value live, then the pack switch runs ({@link
 * Action#PACK_SWITCH}) -- never also the dedicated toggle flip ({@link Action#SHADERS_FLIP}). This
 * mirrors {@code ShaderPacksScreen.applyChanges}'s original {@code packChanged} branch (which wrote
 * {@code shadersEnabled} then ran only the pack-selection logic) and
 * {@code FornaxSettingsScreen.applyRoutedChanges}'s PACK_SWITCH-supersedes-SHADERS_TOGGLE dispatch.
 */
public final class PackListState {
    /** One step in the ordered apply plan. */
    public enum Action {
        /** Write staged {@code shadersEnabled} into config before the pack switch (both-changed case only). */
        WRITE_ENABLED,
        /** {@code pack.PackSwitch.apply(stagedPack, livePack, freshParent)}. */
        PACK_SWITCH,
        /** {@code pack.ShadersEnabledFlip.apply(stagedEnabled)} (enabled-only case). */
        SHADERS_FLIP
    }

    private boolean liveEnabled;
    private String livePack;
    private boolean stagedEnabled;
    private String stagedPack;

    /** @param pack the active-pack name; empty string means "None" (no pack, pure vanilla Sodium). */
    public PackListState(boolean enabled, String pack) {
        this.liveEnabled = enabled;
        this.livePack = pack;
        this.stagedEnabled = enabled;
        this.stagedPack = pack;
    }

    public boolean stagedEnabled() {
        return this.stagedEnabled;
    }

    public String stagedPack() {
        return this.stagedPack;
    }

    public boolean liveEnabled() {
        return this.liveEnabled;
    }

    public String livePack() {
        return this.livePack;
    }

    public void stageEnabled(boolean enabled) {
        this.stagedEnabled = enabled;
    }

    public void stagePack(String pack) {
        this.stagedPack = pack;
    }

    public boolean isEnabledDirty() {
        return this.stagedEnabled != this.liveEnabled;
    }

    public boolean isPackDirty() {
        return !this.stagedPack.equals(this.livePack);
    }

    public boolean isDirty() {
        return isEnabledDirty() || isPackDirty();
    }

    /** The ordered steps Apply must run; empty when nothing is staged. */
    public List<Action> applyPlan() {
        if (isPackDirty()) {
            return isEnabledDirty()
                    ? List.of(Action.WRITE_ENABLED, Action.PACK_SWITCH)
                    : List.of(Action.PACK_SWITCH);
        }
        if (isEnabledDirty()) {
            return List.of(Action.SHADERS_FLIP);
        }
        return List.of();
    }

    /** Reseeds live AND staged from the post-apply config, converging the tab's dirty state. */
    public void refresh(boolean enabled, String pack) {
        this.liveEnabled = enabled;
        this.livePack = pack;
        this.stagedEnabled = enabled;
        this.stagedPack = pack;
    }
}
