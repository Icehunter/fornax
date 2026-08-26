package dev.icehunter.fornax.config;

import java.util.EnumSet;
import java.util.Set;

/**
 * Pure change-detection between a pre-open {@link FornaxSettings} snapshot and the settings object
 * as it stands once a settings UI's save step has applied every pending option value (YACL applies
 * each changed option's binding -- writing straight into the live {@link FornaxConfig#get()}
 * instance -- before invoking its top-level save callback; {@code
 * YACLScreen.finishOrSave} javap-confirmed against the real 3.9.5+26.2 jar: {@code
 * OptionUtils.forEachOptions(applyValue)} runs, THEN {@code saveFunction.run()}). {@link
 * #route(FornaxSettings, FornaxSettings)} is field-by-field, no side effects, and decides which
 * apply action(s) fire -- the caller (currently {@code screen.FornaxSettingsScreen}'s save
 * callback) is responsible for actually running them via the shared engine paths (see {@code
 * pack.PackReload#reapplyActivePack}), never a fork of that logic.
 *
 * <p>Field groupings, per the design doc's law-critical apply semantics:
 * <ul>
 *   <li>{@code aaMethod}/{@code ssaaPreset}/{@code taauRatio} changed -&gt; {@link
 *       Action#PACK_REAPPLY}: these change which {@code FX_*} engine defines the active pack graph
 *       compiles with, so the pack needs recompiling ({@code PackReload.reapplyActivePack()}) --
 *       never a renderer reload, since the terrain pipeline shape itself is unaffected.</li>
 *   <li>ANY field changing -&gt; {@link Action#SAVE_ONLY}: a config write is always needed to
 *       persist whatever changed. This is NOT mutually exclusive with {@link Action#PACK_REAPPLY}
 *       -- {@code PackReload.reapplyActivePack()} deliberately never calls {@code
 *       FornaxConfig.save()} itself (see its own doc comment: it only recompiles the pack graph),
 *       so a {@link Action#PACK_REAPPLY}-triggering change still needs {@link Action#SAVE_ONLY} to
 *       actually reach disk.</li>
 *   <li>{@code frameGenMode} any-&gt;{@code OFF}, OR {@code aaMethod} METALFX-&gt;anything else
 *       -&gt; {@link Action#FRAMEGEN_DEACTIVATE}: frame generation's interop resources need
 *       releasing. {@code AUTO}&lt;-&gt;{@code ALWAYS} does NOT trigger this: both policies keep
 *       the same resources armed, only {@code FrameGenPacer}'s per-frame decision differs.
 *       Deliberately NOT done from the YACL option listeners, which would call this
 *       directly on click -- YACL applies every option's binding (including this exact field)
 *       BEFORE the save callback runs (see the apply-semantics note above), so a listener firing on
 *       click sees the OLD `armed()` truth (config still true) for every frame rendered while the
 *       settings screen stays open, and by the time save/close actually flips the config the
 *       listener has already fired and won't fire again -- resources released neither at the click
 *       (undone by the next armed frame rebuilding everything) nor at the true config transition
 *       (nothing left to call it). Routing through the same before/after diff this class already
 *       does for every other field fixes both halves: the action only fires once the field has
 *       ACTUALLY changed, at the one point (this save callback) where {@code after} is the real
 *       final value.</li>
 *   <li>{@code metalHud} changed, either direction -&gt; {@link Action#METAL_HUD_APPLY}: {@code
 *       MetalHudControl.apply(after.metalHud)} re-resolves the game window's {@code CAMetalLayer}
 *       and sets/clears its {@code developerHUDProperties} every time, so both on and off route
 *       through the same action (unlike {@link Action#FRAMEGEN_DEACTIVATE}'s one-way teardown) --
 *       routed at save time for the identical reason: a click-time YACL listener would only ever
 *       see the pre-save value.</li>
 * </ul>
 *
 * <p>The master toggle and active-pack selection are owned by {@code screen.FornaxPacksTab}, which
 * self-applies through {@code pack.ShadersEnabledFlip}/{@code pack.PackSwitch} outside YACL's save
 * cycle, so this router never sees them.
 *
 * <p>A completely unchanged snapshot pair yields an empty set: no action at all, per the design
 * doc's "no-op saves do nothing" rule.
 */
public final class SettingsApplyRouter {
    /** One routed apply action. Independent actions may fire together in the same save. */
    public enum Action {
        /** Plain {@code FornaxConfig.save()} -- covers fields that are live-read every frame. */
        SAVE_ONLY,
        /** {@code PackReload.reapplyActivePack()} -- an {@code FX_*} engine-define change. */
        PACK_REAPPLY,
        /**
         * {@code FrameGenPass.deactivate()} + {@code UiLayerCapture.deactivate()} + {@code
         * FrameGenPresenter.deactivate()}. Fires on the {@code frameGenMode} any-&gt;{@code OFF} or
         * {@code aaMethod} METALFX-&gt;other transition; see the class header for why this can't
         * live in the YACL listeners.
         */
        FRAMEGEN_DEACTIVATE,
        /**
         * {@code MetalHudControl.apply(after.metalHud)} -- fires on ANY {@code metalHud} change,
         * either direction (unlike {@link #FRAMEGEN_DEACTIVATE}, which only fires one way): turning
         * the HUD on and off are symmetric live actions, not a one-way resource teardown, so both
         * transitions need the same save-time dispatch (a click-time YACL listener would suffer the
         * exact same stale-value problem {@link #FRAMEGEN_DEACTIVATE}'s javadoc describes).
         */
        METAL_HUD_APPLY,
        /**
         * {@code Minecraft.reloadResourcePacks()} -- fires on a {@code sidecarMapResolution} change.
         *
         * <p>The ONLY action here that rebuilds an atlas, and it needs to be: the sidecar atlases
         * are built by resource-reload listeners, so nothing short of a reload can act on a new
         * resolution tier. Without this the settings row would appear to do nothing until the user
         * happened to press F3+T, which is worse than having no row at all.
         */
        RESOURCE_RELOAD
    }

    private SettingsApplyRouter() {
    }

    /**
     * @param before a snapshot taken before the settings UI applied any pending values (a value
     *               copy -- comparing against the live, since-mutated {@link FornaxConfig#get()}
     *               object would always read as unchanged)
     * @param after  the settings object once every pending option value has been applied (in
     *               practice, {@code FornaxConfig.get()} itself, read from the save callback)
     * @return the set of apply actions this change requires; empty when nothing changed
     */
    public static Set<Action> route(FornaxSettings before, FornaxSettings after) {
        boolean packReapplyNeeded = before.aaMethod != after.aaMethod
                || before.ssaaPreset != after.ssaaPreset
                || before.taauRatio != after.taauRatio;
        boolean plainSaveNeeded = before.profilerOverlay != after.profilerOverlay
                || before.debugView != after.debugView
                || before.frameGenMode != after.frameGenMode
                || before.metalHud != after.metalHud
                || before.voxelReachIgnoresRenderDistance != after.voxelReachIgnoresRenderDistance
                || before.sunPathRotation != after.sunPathRotation;
        boolean resourceReloadNeeded = before.sidecarMapResolution != after.sidecarMapResolution;
        boolean anythingChanged = packReapplyNeeded || plainSaveNeeded || resourceReloadNeeded;
        // AUTO<->ALWAYS deliberately does NOT deactivate: FrameGenPacer reads the mode live every
        // frame, so tearing down interop resources for a policy switch would be a pointless rebuild.
        boolean framegenDeactivateNeeded = (before.frameGenMode != FrameGenMode.OFF && after.frameGenMode == FrameGenMode.OFF)
                || (before.aaMethod == AaMethod.METALFX && after.aaMethod != AaMethod.METALFX);
        boolean metalHudApplyNeeded = before.metalHud != after.metalHud;

        Set<Action> actions = EnumSet.noneOf(Action.class);
        if (packReapplyNeeded) {
            actions.add(Action.PACK_REAPPLY);
        }
        if (resourceReloadNeeded) {
            actions.add(Action.RESOURCE_RELOAD);
        }
        if (anythingChanged) {
            actions.add(Action.SAVE_ONLY);
        }
        if (framegenDeactivateNeeded) {
            actions.add(Action.FRAMEGEN_DEACTIVATE);
        }
        if (metalHudApplyNeeded) {
            actions.add(Action.METAL_HUD_APPLY);
        }
        return actions;
    }
}
