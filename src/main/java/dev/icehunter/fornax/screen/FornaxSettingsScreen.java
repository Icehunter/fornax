package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.config.AaMethod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.config.FornaxSettings;
import dev.icehunter.fornax.config.FrameGenMode;
import dev.icehunter.fornax.config.GBufferDebugView;
import dev.icehunter.fornax.config.SettingsApplyRouter;
import dev.icehunter.fornax.config.SettingsApplyRouter.Action;
import dev.icehunter.fornax.config.SidecarMapResolution;
import dev.icehunter.fornax.config.TaauRatio;
import dev.icehunter.fornax.pack.PackReload;
import dev.icehunter.fornax.pass.ssaa.SsaaPreset;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Factory for Fornax's YACL-hosted settings screen -- NOT a {@code Screen} subclass itself, since
 * YACL owns screen construction/layout entirely (see {@link #create}). Opened from the pause menu
 * via {@code mixin.vanilla.PauseScreenMixin}.
 *
 * <p>Two categories: "Engine" (see {@link #buildEngineCategory}, groups Anti-Aliasing &amp; Scale /
 * Debug, a SKELETON order the user will reorganize later) and "Shader Packs" (see {@link
 * FornaxPacksTab}, the master enabled toggle plus the pack-selection list and links into the
 * pack-settings screen -- entirely self-contained and self-applying, independent of this class's
 * save cycle). Pack-OPTION pages (a pack's own {@code screens.toml} content) stay Sodium/{@code
 * PackSettingsScreen}-hosted; only pack-selection and links to those legacy screens live there.
 *
 * <p><b>Apply semantics (the law-critical part):</b> YACL applies every changed option's binding --
 * writing straight into the live {@link FornaxConfig#get()} instance -- before invoking the
 * top-level save callback ({@code YACLScreen.finishOrSave}, javap-confirmed against the real
 * 3.9.5+26.2 jar). {@link #create} therefore snapshots the settings BEFORE building any option, and
 * the save callback ({@link #applyRoutedChanges}) diffs that snapshot against the now-mutated live
 * settings via {@link SettingsApplyRouter#route}, then dispatches to the SAME apply path {@code
 * SodiumConfigEntry} already uses -- never a fork of that logic: {@code
 * pack.PackReload#reapplyActivePack()} for the AA/scale options (an {@code FX_*} engine-define
 * change). A no-op save (nothing changed) dispatches nothing, per the design doc. The master toggle
 * and active-pack selection are owned by {@link FornaxPacksTab}, which self-applies through {@code
 * pack.ShadersEnabledFlip}/{@code pack.PackSwitch} outside this save cycle entirely.
 */
public final class FornaxSettingsScreen {
    private FornaxSettingsScreen() {
    }

    public static Screen create(Screen parent) {
        // A one-element array, not a plain local, because YACL's Save button does not close this
        // screen -- a single visit can fire this callback several times, and each one must diff
        // against what the PREVIOUS save actually applied, not the value the screen happened to
        // open with. A plain `before` captured once and left unmutated left every action gated on
        // SettingsApplyRouter.route() (PACK_REAPPLY, FRAMEGEN_DEACTIVATE, METAL_HUD_APPLY) comparing
        // against an increasingly stale baseline: e.g. toggle metalHud on and Save (fires apply(true)
        // correctly), then toggle it back off and Save again -- an unmutated `before` would still see
        // the ORIGINAL (already-false) snapshot on both sides, so apply(false) would silently never
        // fire even though the persisted config value did flip, leaving the live OS-level Metal HUD
        // toggle permanently stuck on.
        FornaxSettings[] before = {snapshot(FornaxConfig.get())};

        YetAnotherConfigLib.Builder builder = YetAnotherConfigLib.createBuilder()
                .title(Component.translatable("gui.fornax.settings.title"))
                .category(buildEngineCategory())
                .category(new FornaxPacksTab(parent))
                .save(() -> {
                    applyRoutedChanges(before[0]);
                    before[0] = snapshot(FornaxConfig.get());
                });

        return builder.build().generateScreen(parent);
    }

    /**
     * Diffs the pre-open snapshot against the live (by now YACL-mutated) settings and dispatches the
     * routed engine-option actions. The master toggle and active-pack selection are NOT handled here
     * -- they belong to {@code screen.FornaxPacksTab}, which self-applies through {@code
     * pack.ShadersEnabledFlip}/{@code pack.PackSwitch} outside YACL's save cycle.
     *
     * <p>{@code SAVE_ONLY} persists whatever changed and deliberately runs FIRST, before any other
     * routed action: {@code PackReload.reapplyActivePack()} and {@code MetalHudControl.apply()} both
     * reach outside this class (GPU pipeline rebuild, an Objective-C message send) and can throw. A
     * throw there must never cost the save that already succeeded in memory -- persisting first
     * makes disk state independent of whether any downstream apply action lands, instead of the
     * user's choice surviving only for the current session on the exact configurations where a
     * downstream action fails.
     * {@code PACK_REAPPLY} recompiles the active pack graph for the new {@code FX_*} engine
     * defines (an AA/scale change); {@code FRAMEGEN_DEACTIVATE} releases frame generation's interop
     * resources on the frameGeneration-off or AA-method-switched-away-from-METALFX transition --
     * deliberately routed through this save-time diff rather than the options' own YACL listeners,
     * see {@code SettingsApplyRouter}'s class header for why a click-time listener can't do this
     * correctly.
     */
    private static void applyRoutedChanges(FornaxSettings before) {
        Set<Action> actions = SettingsApplyRouter.route(before, FornaxConfig.get());
        if (actions.contains(Action.SAVE_ONLY)) {
            FornaxConfig.save();
        }
        if (actions.contains(Action.PACK_REAPPLY)) {
            PackReload.reapplyActivePack();
        }
        if (actions.contains(Action.FRAMEGEN_DEACTIVATE)) {
            deactivateFrameGeneration();
        }
        if (actions.contains(Action.RESOURCE_RELOAD)) {
            // Last, deliberately: a reload restarts the atlas builds this change exists to affect,
            // and the other actions are cheap in-place applies that should already have landed.
            net.minecraft.client.Minecraft.getInstance().reloadResourcePacks();
        }
        if (actions.contains(Action.METAL_HUD_APPLY)) {
            dev.icehunter.fornax.metalfx.MetalHudControl.apply(FornaxConfig.get().metalHud);
        }
    }

    /** Value copy of every routed field, taken before YACL mutates the live object. */
    private static FornaxSettings snapshot(FornaxSettings source) {
        FornaxSettings copy = new FornaxSettings();
        copy.aaMethod = source.aaMethod;
        copy.ssaaPreset = source.ssaaPreset;
        copy.taauRatio = source.taauRatio;
        copy.sidecarMapResolution = source.sidecarMapResolution;
        copy.profilerOverlay = source.profilerOverlay;
        copy.debugView = source.debugView;
        copy.frameGenMode = source.frameGenMode;
        copy.metalHud = source.metalHud;
        copy.voxelReachIgnoresRenderDistance = source.voxelReachIgnoresRenderDistance;
        copy.sunPathRotation = source.sunPathRotation;
        return copy;
    }

    private static ConfigCategory buildEngineCategory() {
        ConfigCategory.Builder category = ConfigCategory.createBuilder()
                .name(Component.translatable("gui.fornax.category.engine"));

        // One builder method per group, assembled from this list -- reordering the skeleton later
        // (the user's stated intent) is a one-line change here, nothing structural.
        List<Supplier<OptionGroup>> groups = List.of(
                FornaxSettingsScreen::buildAntiAliasingGroup,
                FornaxSettingsScreen::buildWorldGroup,
                FornaxSettingsScreen::buildDebugGroup);
        groups.forEach(group -> category.group(group.get()));

        return category.build();
    }

    /**
     * Settings that change the WORLD rather than how it is sampled or displayed. Sun path is here
     * and not in the shaderpack's own screen because it is an engine quantity: the shadow map, the
     * pack's lighting and the celestial discs are all built from one direction vector, so the tilt
     * has to be applied where they all read it.
     */
    private static OptionGroup buildWorldGroup() {
        Option<Float> sunPathRotation = Option.<Float>createBuilder()
                .name(Component.translatable("gui.fornax.option.sun_path_rotation"))
                .description(OptionDescription.of(
                        Component.translatable("gui.fornax.option.sun_path_rotation.tooltip")))
                .binding(-25.0f,
                        () -> FornaxConfig.get().sunPathRotation,
                        value -> FornaxConfig.get().sunPathRotation = value)
                .controller(opt -> FloatSliderControllerBuilder.create(opt)
                        .range(-60.0f, 60.0f)
                        .step(5.0f)
                        .formatValue(v -> Component.literal(
                                String.format(java.util.Locale.ROOT, "%.0f\u00b0", v))))
                .build();

        return OptionGroup.createBuilder()
                .name(Component.translatable("gui.fornax.group.world"))
                .option(sunPathRotation)
                .option(buildSidecarMapResolutionOption())
                .build();
    }

    /**
     * How much of a pack's authored labPBR sidecar resolution to keep.
     *
     * <p>Unlike every other row on this screen, saving a change here triggers a RESOURCE RELOAD --
     * the sidecar atlases are built by reload listeners, so nothing less can act on a new tier. See
     * {@code Action.RESOURCE_RELOAD}. Defaults to HALF, which is what the retired byte budget
     * already produced on the large packs this setting exists for.
     */
    private static Option<SidecarMapResolution> buildSidecarMapResolutionOption() {
        return Option.<SidecarMapResolution>createBuilder()
                .name(Component.translatable("gui.fornax.option.sidecar_map_resolution"))
                .description(OptionDescription.of(
                        Component.translatable("gui.fornax.option.sidecar_map_resolution.tooltip")))
                .binding(SidecarMapResolution.HALF,
                        () -> FornaxConfig.get().sidecarMapResolution,
                        v -> FornaxConfig.get().sidecarMapResolution = v)
                .controller(opt -> EnumControllerBuilder.create(opt)
                        .enumClass(SidecarMapResolution.class)
                        .formatValue(FornaxSettingsScreen::sidecarMapResolutionLabel))
                .build();
    }

    private static OptionGroup buildAntiAliasingGroup() {
        Option<SsaaPreset> ssaaPreset = buildSsaaPresetOption();
        Option<TaauRatio> taauRatio = buildTaauRatioOption();
        // Built only where the runtime probe passes -- mirrors METALFX's own exclusion from the
        // aaMethod cycle below (isAvailable()), one level stricter (frame interpolation is a
        // narrower capability than the base upscaler probe): no point offering a toggle that could
        // never arm on this machine.
        Option<FrameGenMode> frameGeneration = dev.icehunter.fornax.metalfx.MetalFxSupport.isFrameInterpolationAvailable()
                ? buildFrameGenerationOption()
                : null;
        Option<AaMethod> aaMethod = buildAaMethodOption(ssaaPreset, taauRatio, frameGeneration);

        // Initial availability mirrors the CURRENT method, same gating compat.SodiumConfigEntry's
        // Engine page applies to this exact pair of rows via its own enabledProvider.
        ssaaPreset.setAvailable(FornaxConfig.get().aaMethod == AaMethod.SSAA);
        taauRatio.setAvailable(FornaxConfig.get().aaMethod == AaMethod.TAAU
                || FornaxConfig.get().aaMethod == AaMethod.METALFX);
        if (frameGeneration != null) {
            frameGeneration.setAvailable(FornaxConfig.get().aaMethod == AaMethod.METALFX);
        }

        OptionGroup.Builder group = OptionGroup.createBuilder()
                .name(Component.translatable("gui.fornax.group.aa_scale"))
                .option(aaMethod)
                .option(ssaaPreset)
                .option(taauRatio);
        if (frameGeneration != null) {
            group.option(frameGeneration);
        }
        return group.build();
    }

    private static Option<AaMethod> buildAaMethodOption(
            Option<SsaaPreset> ssaaPreset, Option<TaauRatio> taauRatio, Option<FrameGenMode> frameGeneration) {
        // METALFX appears in the cycle only where the runtime probe passes (macOS/Apple silicon
        // with MetalFX support) -- the SsaaPreset value-exclusion precedent below, applied to a
        // capability instead of a legacy value. A persisted METALFX config on unsupported hardware
        // still LOADS fine; the seam falls back to the TAAU reconstruct every frame.
        java.util.List<AaMethod> methods = new java.util.ArrayList<>(
                java.util.List.of(AaMethod.OFF, AaMethod.TAA, AaMethod.SSAA, AaMethod.TAAU));
        if (dev.icehunter.fornax.metalfx.MetalFxSupport.isAvailable()) {
            methods.add(AaMethod.METALFX);
        }
        return Option.<AaMethod>createBuilder()
                .name(Component.translatable("gui.fornax.option.aa_method"))
                .description(OptionDescription.of(Component.translatable("gui.fornax.option.aa_method.tooltip")))
                .binding(AaMethod.TAA, () -> FornaxConfig.get().aaMethod, v -> FornaxConfig.get().aaMethod = v)
                // Cycler, deliberately NOT the enum dropdown: YACL 3.9's dropdown is an
                // editable text-filter combobox that renders its suggestion list transparently
                // over neighboring rows on this MC version, unusable. The full value menu lives in
                // the DESCRIPTION instead, so the right pane shows every choice's meaning while
                // cycling. Revisit if a custom list controller lands.
                .controller(opt -> CyclingListControllerBuilder.create(opt)
                        .values(methods)
                        .formatValue(FornaxSettingsScreen::aaMethodLabel))
                // Live UI feedback only (greys the two rows below in/out) -- the actual apply-time
                // relevance gating (including frame-gen resource release) lives in
                // SettingsApplyRouter/PackReload's save-time diff, not here: YACL applies this
                // option's binding before any listener fires, so a listener here would only ever see
                // the pre-save config value -- see SettingsApplyRouter's class header.
                .listener((opt, newValue) -> {
                    ssaaPreset.setAvailable(newValue == AaMethod.SSAA);
                    taauRatio.setAvailable(newValue == AaMethod.TAAU || newValue == AaMethod.METALFX);
                    if (frameGeneration != null) {
                        frameGeneration.setAvailable(newValue == AaMethod.METALFX);
                    }
                })
                .build();
    }

    private static Option<FrameGenMode> buildFrameGenerationOption() {
        return Option.<FrameGenMode>createBuilder()
                .name(Component.translatable("gui.fornax.option.frame_generation"))
                .description(OptionDescription.of(Component.translatable("gui.fornax.option.frame_generation.tooltip")))
                .binding(FrameGenMode.OFF, () -> FornaxConfig.get().frameGenMode, v -> FornaxConfig.get().frameGenMode = v)
                .controller(opt -> EnumControllerBuilder.create(opt)
                        .enumClass(FrameGenMode.class)
                        .formatValue(FornaxSettingsScreen::frameGenModeLabel))
                // No listener: transitioning to OFF must release interop resources, but that has to
                // happen at the save-time apply point (SettingsApplyRouter's FRAMEGEN_DEACTIVATE
                // action), not here: see SettingsApplyRouter's class header for why a click-time
                // listener can't do this correctly (YACL applies this option's binding before any
                // listener fires). Any other transition (including AUTO<->ALWAYS) needs no action
                // anywhere; FrameGenPass.armed()/mode() pick it up live, next frame.
                .build();
    }

    private static Component frameGenModeLabel(FrameGenMode mode) {
        return switch (mode) {
            case OFF -> Component.literal("Off");
            case AUTO -> Component.literal("Auto");
            case ALWAYS -> Component.literal("Always");
        };
    }

    /** Called ONLY from {@link #applyRoutedChanges}, on {@code SettingsApplyRouter.Action
     * #FRAMEGEN_DEACTIVATE}, never from a YACL option listener, see that action's javadoc.
     * {@code GraphRunner.closeCurrent()} calls the same shared {@code FrameGenPresenter.deactivateAll()}
     * on pack teardown. */
    private static void deactivateFrameGeneration() {
        dev.icehunter.fornax.pass.FrameGenPresenter.deactivateAll();
    }

    private static Option<SsaaPreset> buildSsaaPresetOption() {
        return Option.<SsaaPreset>createBuilder()
                .name(Component.translatable("gui.fornax.option.ssaa_preset"))
                .description(OptionDescription.of(Component.translatable("gui.fornax.option.ssaa_preset.tooltip")))
                .binding(SsaaPreset.X2, () -> FornaxConfig.get().ssaaPreset, v -> FornaxConfig.get().ssaaPreset = v)
                // CyclingList, not a dropdown like its siblings: the enum-dropdown controller
                // enumerates EVERY constant and offers no value filter, and this option must
                // exclude a legacy value. CyclingList restricts the cycle to the same allowed set
                // compat.SodiumConfigEntry enforces via setAllowedValues -- OFF is a legacy
                // deserialization value only, never reachable through either UI (on/off lives on
                // aaMethod alone).
                .controller(opt -> CyclingListControllerBuilder.create(opt)
                        .values(SsaaPreset.X1_5, SsaaPreset.X2, SsaaPreset.X4, SsaaPreset.X8, SsaaPreset.X16)
                        .formatValue(FornaxSettingsScreen::ssaaLabel))
                .build();
    }

    private static Option<TaauRatio> buildTaauRatioOption() {
        return Option.<TaauRatio>createBuilder()
                .name(Component.translatable("gui.fornax.option.taau_ratio"))
                .description(OptionDescription.of(Component.translatable("gui.fornax.option.taau_ratio.tooltip")))
                .binding(TaauRatio.BALANCED, () -> FornaxConfig.get().taauRatio, v -> FornaxConfig.get().taauRatio = v)
                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(TaauRatio.class)
                        .formatValue(FornaxSettingsScreen::taauRatioLabel))
                .build();
    }

    private static OptionGroup buildDebugGroup() {
        Option<Boolean> profilerOverlay = Option.<Boolean>createBuilder()
                .name(Component.translatable("gui.fornax.option.profiler_overlay"))
                .description(OptionDescription.of(Component.translatable("gui.fornax.option.profiler_overlay.tooltip")))
                .binding(false, () -> FornaxConfig.get().profilerOverlay, v -> FornaxConfig.get().profilerOverlay = v)
                .controller(opt -> BooleanControllerBuilder.create(opt).coloured(true).onOffFormatter())
                .build();

        Option<GBufferDebugView> debugView = Option.<GBufferDebugView>createBuilder()
                .name(Component.translatable("gui.fornax.option.debug_view"))
                .description(OptionDescription.of(Component.translatable("gui.fornax.option.debug_view.tooltip")))
                .binding(GBufferDebugView.OFF, () -> FornaxConfig.get().debugView, v -> FornaxConfig.get().debugView = v)
                // CyclingListController over an explicitly filtered list rather than EnumController over
                // the whole enum: VOXEL_RAYMARCH must not be selectable (see GBufferDebugView's own note --
                // its presentation path could wedge the GPU and take macOS's WindowServer with it). The
                // constant itself is retained for ordinal stability, so it has to be excluded here at the
                // UI layer instead of by deleting the enum value.
                .controller(opt -> CyclingListControllerBuilder.create(opt)
                        .values(selectableDebugViews())
                        .formatValue(FornaxSettingsScreen::debugViewLabel))
                .build();

        // Built only where the FFM bridge to the Objective-C runtime actually linked (macOS/Apple
        // silicon, mirrors frameGeneration's own capability-gated construction above) -- no point
        // offering a HUD toggle that could never resolve a CAMetalLayer on this machine. Deliberately
        // gated on the bridge itself, NOT MetalFxSupport.isAvailable() (which additionally requires
        // MTLFXTemporalScalerDescriptor.supportsDevice: to pass) or aaMethod == METALFX -- the HUD
        // reads MoltenVK's own CAMetalLayer directly and has nothing to do with which AA/upscale path
        // (if any) is active.
        Option<Boolean> metalHud;
        if (dev.icehunter.fornax.metalfx.objc.Objc.isLoaded()) {
            metalHud = buildMetalHudOption();
        } else if (dev.icehunter.fornax.metalfx.objc.Objc.PLATFORM_SUPPORTED) {
            // Supported hardware (macOS/aarch64) but the bridge still failed to link -- worth
            // surfacing, since the row silently disappearing here is otherwise indistinguishable
            // from "not on a Mac" from the player's side of the screen.
            dev.icehunter.fornax.FornaxMod.LOGGER.warn(
                    "[Fornax] Metal Performance HUD option hidden: Objc bridge failed to load ({})",
                    dev.icehunter.fornax.metalfx.objc.Objc.loadFailure());
            metalHud = null;
        } else {
            metalHud = null; // not macOS/aarch64 -- expected, nothing to log
        }

        Option<Boolean> voxelReachIgnoresRenderDistance = Option.<Boolean>createBuilder()
                .name(Component.translatable("gui.fornax.option.voxel_reach_ignores_render_distance"))
                .description(OptionDescription.of(
                        Component.translatable("gui.fornax.option.voxel_reach_ignores_render_distance.tooltip")))
                .binding(false, () -> FornaxConfig.get().voxelReachIgnoresRenderDistance,
                        v -> FornaxConfig.get().voxelReachIgnoresRenderDistance = v)
                .controller(opt -> BooleanControllerBuilder.create(opt).coloured(true).onOffFormatter())
                .build();

        OptionGroup.Builder group = OptionGroup.createBuilder()
                .name(Component.translatable("gui.fornax.group.debug"))
                .option(profilerOverlay)
                .option(debugView)
                .option(voxelReachIgnoresRenderDistance);
        if (metalHud != null) {
            group.option(metalHud);
        }
        return group.build();
    }

    private static Option<Boolean> buildMetalHudOption() {
        return Option.<Boolean>createBuilder()
                .name(Component.translatable("gui.fornax.option.metal_hud"))
                .description(OptionDescription.of(Component.translatable("gui.fornax.option.metal_hud.tooltip")))
                .binding(false, () -> FornaxConfig.get().metalHud, v -> FornaxConfig.get().metalHud = v)
                .controller(opt -> BooleanControllerBuilder.create(opt).coloured(true).onOffFormatter())
                // No listener: the live apply (resolving the CAMetalLayer and setting/clearing its
                // developerHUDProperties) happens at the save-time apply point (SettingsApplyRouter's
                // METAL_HUD_APPLY action), not here -- same reasoning as frameGeneration's own comment
                // above (YACL applies this option's binding before any listener fires, so a listener
                // here would only ever see the pre-save config value).
                .build();
    }

    // Enum-value label formatters, deliberately literal (not translatable) -- mirrors
    // compat.SodiumConfigEntry's own convention for this exact set of enums, which never localized
    // per-value labels either. Kept as separate small switches here rather than reusing
    // SodiumConfigEntry's private methods: cosmetic display formatting carries none of the
    // render-state-latch risk the APPLY paths do, so a second small switch per enum is a much
    // lower-stakes duplication than forking apply logic would be.
    private static Component aaMethodLabel(AaMethod method) {
        return switch (method) {
            case OFF -> Component.literal("Off");
            case TAA -> Component.literal("TAA");
            case SSAA -> Component.literal("SSAA (supersample)");
            case TAAU -> Component.literal("TAAU (temporal upscale)");
            case METALFX -> Component.literal("MetalFX (ML upscale)");
        };
    }

    private static Component ssaaLabel(SsaaPreset preset) {
        return switch (preset) {
            case OFF -> Component.literal("Off"); // unreachable -- see buildSsaaPresetOption's allowed-values restriction
            case X1_5 -> Component.literal("1.5x");
            case X2 -> Component.literal("2x");
            case X4 -> Component.literal("4x");
            case X8 -> Component.literal("8x");
            case X16 -> Component.literal("16x");
        };
    }

    private static Component sidecarMapResolutionLabel(SidecarMapResolution resolution) {
        return switch (resolution) {
            case FULL -> Component.literal("Full");
            case HALF -> Component.literal("Half");
            case QUARTER -> Component.literal("Quarter");
        };
    }

    private static Component taauRatioLabel(TaauRatio ratio) {
        return switch (ratio) {
            case QUALITY -> Component.literal("Quality");
            case BALANCED -> Component.literal("Balanced");
            case PERFORMANCE -> Component.literal("Performance");
        };
    }

    /** Every debug view a player may select, i.e. all of them except the ones excluded by {@link
     * GBufferDebugView#isSelectable()}. Kept as a list (not the raw enum) so the dropdown can omit a
     * value without deleting the constant and shifting the ordinals the shaders branch on. */
    private static List<GBufferDebugView> selectableDebugViews() {
        return Arrays.stream(GBufferDebugView.values())
                .filter(GBufferDebugView::isSelectable)
                .toList();
    }

    private static Component debugViewLabel(GBufferDebugView view) {
        return Component.literal(view.label());
    }
}
