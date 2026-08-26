package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pack.PackModel;
import dev.icehunter.fornax.pack.ProfileDiff;
import dev.icehunter.fornax.pack.ProfileSpec;
import dev.icehunter.fornax.pack.ScreenElement;
import dev.icehunter.fornax.pack.ScreenSpec;
import dev.icehunter.fornax.pack.option.PackOption;
import dev.icehunter.fornax.pack.option.PackOptionValues;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a pack's {@code screens.toml}, restructured after the familiar shaderpack-configure
 * shape:
 * <ul>
 *   <li><b>Main screen</b>: header (pack name), one prominent profile row,
 *       then the category links as a two-column grid of large buttons -- not a flat list.</li>
 *   <li><b>Sub-screens</b>: Back button top-left + centered title, then that screen's options as
 *       full-width translucent rows ({@link PackRow}/{@link PackRowList}).</li>
 * </ul>
 *
 * <p>Editing is fully STAGED: every level shares one {@link PackEditSession}; nothing takes effect
 * until Apply or Done commits the whole session at once (one rebuild/reload at most). Cancel
 * discards every staged edit and leaves the stack. Escape on a nested level goes BACK one level
 * with the session intact -- never closes the whole stack -- and Escape at the root behaves like
 * Done. Every option row additionally carries compact per-row undo (revert this row's staged edit)
 * and reset (stage this row's pack default) affordances; both are staged edits like any other.
 */
public final class PackSettingsScreen extends Screen {
    private static final int HEADER_HEIGHT = 26;
    private static final int FOOTER_HEIGHT = 30;
    private static final int ROW_HEIGHT = 22;
    private static final int BUTTON_WIDTH = 90;
    private static final int BUTTON_GAP = 6;

    private final Screen exitScreen;
    private final PackSettingsScreen parentLevel;
    private final PackEditSession session;
    private final String screenId;

    private PackSettingsScreen(Screen exitScreen, PackSettingsScreen parentLevel, PackEditSession session, String screenId) {
        super(titleFor(session.model(), screenId));
        this.exitScreen = exitScreen;
        this.parentLevel = parentLevel;
        this.session = session;
        this.screenId = screenId;
    }

    /**
     * Opens a fresh editing session at {@code screenId}, falling back to an error screen if
     * screens.toml is malformed.
     *
     * <p><b>Known cross-session gap</b>, distinct from what {@code mixin.yacl.YACLScreenCloseMixin}
     * covers: this constructs a BRAND-NEW {@link PackEditSession} below, independent of whatever
     * {@link PackEditSession} a {@link PackManageScreen}'s YACL categories are sharing -- reached
     * exclusively via the "Shader Options" bridge button. A customization made on a migrated YACL
     * category and a profile pick made here can clobber each other on whichever session applies
     * second, since both do a full-map {@link dev.icehunter.fornax.pack.PackValuesFile#save}. Fixing
     * this requires touching this separate legacy screen's own session lifecycle.
     */
    static void open(Screen parent, PackModel model, String screenId) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            minecraft.gui.setScreen(new PackSettingsScreen(parent, null, new PackEditSession(model), screenId));
        } catch (FornaxPackError e) {
            minecraft.gui.setScreen(new AlertScreen(() -> minecraft.gui.setScreen(parent),
                    Component.literal("Shaderpack Error"), Component.literal(e.getMessage())));
        }
    }

    @Override
    protected void init() {
        if (this.screenId.equals("main")) {
            initMainScreen();
        } else {
            initSubScreen();
        }
        initBottomBar();
    }

    /** Header (name) -> prominent profile row -> two-column category grid -> leftover rows. */
    private void initMainScreen() {
        this.addRenderableWidget(new TitleWidget(0, 9, this.width, 9, this.title));

        int contentWidth = Math.min(PackRowList.contentWidthFor(this.width), this.width - 16);
        int contentLeft = (this.width - contentWidth) / 2;
        int y = HEADER_HEIGHT + 4;

        List<ScreenElement.ScreenLink> links = new ArrayList<>();
        List<PackOption> looseOptions = new ArrayList<>();
        boolean showProfileRow = false;
        for (String token : elements()) {
            ScreenElement element = ScreenElement.resolve(token, this.session.model().screens(), this.session.model().options());
            switch (element) {
                case ScreenElement.ScreenLink link -> links.add(link);
                case ScreenElement.ProfileCycler ignored -> showProfileRow = true;
                case ScreenElement.Option(PackOption option, String ignoredRequires) -> looseOptions.add(option);
                case ScreenElement.Empty ignored -> { }
                // Groups are a YACL-page construct; on this legacy screen the rows render ungrouped.
                case ScreenElement.GroupHeader ignored -> { }
                // Metas render only on YACL-migrated pages (PackManageScreen); on this legacy
                // screen a meta placed on a non-migrated page is skipped -- warn so pack authors
                // can diagnose the layout gap instead of chasing an invisible row.
                case ScreenElement.MetaRef meta -> FornaxMod.LOGGER.warn(
                        "[Fornax] meta option '{}' is on a non-YACL page; add the page to [yacl] pages to render it",
                        meta.metaId());
            }
        }

        if (showProfileRow) {
            PackRow profile = profileRow();
            profile.setPosition(contentLeft, y);
            profile.setWidth(contentWidth);
            this.addRenderableWidget(profile);
            y += ROW_HEIGHT + BUTTON_GAP;
        }

        int columnWidth = (contentWidth - BUTTON_GAP) / 2;
        for (int i = 0; i < links.size(); i++) {
            ScreenElement.ScreenLink link = links.get(i);
            int column = i % 2;
            int x = contentLeft + column * (columnWidth + BUTTON_GAP);
            this.addRenderableWidget(new PackButton(x, y, columnWidth, 24, link.title(),
                    () -> this.minecraft.gui.setScreen(
                            new PackSettingsScreen(this.exitScreen, this, this.session, link.screenId()))));
            if (column == 1 || i == links.size() - 1) {
                y += 24 + BUTTON_GAP;
            }
        }

        // Packs may still put raw options on their main screen -- host them below the grid.
        if (!looseOptions.isEmpty()) {
            PackRowList list = new PackRowList(this.minecraft, this.width,
                    this.height - y - FOOTER_HEIGHT, y, ROW_HEIGHT);
            for (PackOption option : looseOptions) {
                list.addRow(optionRow(option));
            }
            this.addRenderableWidget(list);
        }
    }

    /** Back button + centered title header, then this screen's options as translucent rows --
     * compact (cycle) rows pair up two per line; sliders and links keep the full column width. */
    private void initSubScreen() {
        this.addRenderableWidget(new PackButton(8, 5, 50, 16, "< Back", this::goBack));
        this.addRenderableWidget(new TitleWidget(0, 9, this.width, 9, this.title));

        PackRowList list = new PackRowList(this.minecraft, this.width,
                this.height - HEADER_HEIGHT - FOOTER_HEIGHT, HEADER_HEIGHT, ROW_HEIGHT);
        PackRow pendingCompact = null;
        for (String token : elements()) {
            ScreenElement element = ScreenElement.resolve(token, this.session.model().screens(), this.session.model().options());
            PackRow row = null;
            boolean compact = false;
            switch (element) {
                case ScreenElement.Option(PackOption option, String ignoredRequires) -> {
                    row = optionRow(option);
                    compact = !(row instanceof PackRow.Slider);
                }
                case ScreenElement.ScreenLink(String targetId, String screenTitle) -> row =
                        new PackRow.Link(screenTitle, "Open the " + screenTitle + " settings.",
                                () -> this.minecraft.gui.setScreen(
                                        new PackSettingsScreen(this.exitScreen, this, this.session, targetId)));
                case ScreenElement.ProfileCycler ignored -> row = profileRow();
                case ScreenElement.Empty ignored -> { }
                // Groups are a YACL-page construct; on this legacy screen the rows render ungrouped.
                case ScreenElement.GroupHeader ignored -> { }
                // Metas render only on YACL-migrated pages (PackManageScreen); on this legacy
                // screen a meta placed on a non-migrated page is skipped -- warn so pack authors
                // can diagnose the layout gap instead of chasing an invisible row.
                case ScreenElement.MetaRef meta -> FornaxMod.LOGGER.warn(
                        "[Fornax] meta option '{}' is on a non-YACL page; add the page to [yacl] pages to render it",
                        meta.metaId());
            }
            if (row == null) {
                continue;
            }
            if (compact) {
                if (pendingCompact == null) {
                    pendingCompact = row;
                } else {
                    list.addRowPair(pendingCompact, row);
                    pendingCompact = null;
                }
            } else {
                if (pendingCompact != null) {
                    list.addRow(pendingCompact);
                    pendingCompact = null;
                }
                list.addRow(row);
            }
        }
        if (pendingCompact != null) {
            list.addRow(pendingCompact);
        }
        this.addRenderableWidget(list);
    }

    private void initBottomBar() {
        int barY = this.height - FOOTER_HEIGHT + 4;
        int totalWidth = BUTTON_WIDTH * 3 + BUTTON_GAP * 2;
        int x = (this.width - totalWidth) / 2;
        this.addRenderableWidget(new PackButton(x, barY, BUTTON_WIDTH, 20, "Cancel", this::cancelAll)
                .withTooltip("Discard every staged edit (and revert live previews)."));
        this.addRenderableWidget(new PackButton(x + BUTTON_WIDTH + BUTTON_GAP, barY, BUTTON_WIDTH, 20, "Apply",
                this.session::apply).withTooltip("Save all changes. Shader options rebuild once, here."));
        this.addRenderableWidget(new PackButton(x + (BUTTON_WIDTH + BUTTON_GAP) * 2, barY, BUTTON_WIDTH, 20, "Done", () -> {
            this.session.apply();
            exitAll();
        }));
    }

    private List<String> elements() {
        if (this.screenId.equals("main")) {
            return this.session.model().screens().main().elements();
        }
        ScreenSpec spec = this.session.model().screens().screens().get(this.screenId);
        if (spec == null) {
            throw new FornaxPackError("screens.toml", this.screenId, "no such screen");
        }
        return spec.elements();
    }

    private PackRow optionRow(PackOption option) {
        String current = this.session.get(option.name());
        String tooltip = optionTooltip(option);
        PackRow row;
        if (PackOptionValues.rendersAsSlider(option, this.session.model().screens().sliders())) {
            var range = option.range();
            row = new PackRow.Slider(option.label(), (float) range.min(), (float) range.max(),
                    (float) range.step(), Float.parseFloat(current), PackRow.TWO_DECIMAL, tooltip,
                    v -> this.session.stage(option.name(), String.valueOf(v)));
        } else if (option.isBoolean()) {
            row = new PackRow.Cycle<>(option.label(), List.of(Boolean.TRUE, Boolean.FALSE),
                    PackOptionValues.toBooleanValue(current), v -> v ? "On" : "Off", tooltip,
                    v -> this.session.stage(option.name(), v ? "1" : "0"), "?");
        } else if (option.allowedValues().size() >= 3 && isNumericStepped(option)) {
            // NUMERIC multi-value option (e.g. [4 8 12 16], [1024 2048 4096], [0 5 10 15 20]): a SNAP
            // SLIDER over the allowed values' INDICES rather than a click-through cycler -- drag across
            // the stops, each showing its raw numeric value. The list need not be evenly spaced (e.g.
            // [1024 2048 4096]) because the slider steps over indices, not the values. Bare numbers are
            // meaningless as click-targets ("Sun Glare 10" -- 10 of what?), so a draggable numeric
            // slider is the right widget. WORD-labeled enums (Off/Fancy/Fast, Fast/Balanced/Rich) fall
            // through to the word-preset cycler below -- a word you cycle reads better than a slider,
            // and a numeric slider under a word list would be nonsense. 2-value and boolean options
            // also stay cyclers/toggles -- a 2-stop slider is strictly worse than a toggle.
            List<String> allowed = option.allowedValues();
            int maxIdx = allowed.size() - 1;
            int curIdx = Math.max(0, allowed.indexOf(current));
            row = new PackRow.Slider(option.label(), 0.0f, (float) maxIdx, 1.0f, (float) curIdx,
                    v -> {
                        String val = allowed.get(sliderIndex(v, maxIdx));
                        return option.enumNames().getOrDefault(val, val);
                    }, tooltip,
                    v -> this.session.stage(option.name(), allowed.get(sliderIndex(v, maxIdx))));
        } else {
            row = new PackRow.Cycle<>(option.label(), option.allowedValues(), current,
                    v -> option.enumNames().getOrDefault(v, v), tooltip,
                    v -> this.session.stage(option.name(), v), "?");
        }
        row.dirtySupplier(() -> this.session.isOptionDirty(option.name()));
        row.nonDefaultSupplier(() -> !this.session.isStagedDefault(option.name()));

        // Rightmost cell: reset this row to its pack default. Next to it: undo this row's staged
        // edit. Both stage through the session (Apply/Done persists them) and rebuild the widgets so
        // the row's displayed value reflects the new staged state immediately.
        row.addAffordance(PackRow.AffordanceIcon.RESET_DEFAULT, "Reset to default",
                () -> !this.session.isStagedDefault(option.name()),
                () -> {
                    this.session.stage(option.name(), option.defaultValue());
                    this.rebuildWidgets();
                });
        row.addAffordance(PackRow.AffordanceIcon.UNDO, "Undo staged change",
                () -> this.session.isOptionDirty(option.name()),
                () -> {
                    this.session.stage(option.name(), this.session.appliedValue(option.name()));
                    this.rebuildWidgets();
                });
        return row;
    }

    /** Snaps a discrete-value snap-slider's float track position to a valid index into the option's
     * allowedValues (step-1 track over [0, maxIdx]; round + clamp guards fractional/out-of-range). */
    private static int sliderIndex(float sliderValue, int maxIdx) {
        return Math.max(0, Math.min(maxIdx, Math.round(sliderValue)));
    }

    /** True when a discrete option's values are bare NUMBERS (no distinct word labels) -- these read
     * better as a numeric snap-slider. A label counts as numeric when it's absent or identical to the
     * raw value (e.g. {@code {1024="1024" 2048="2048"}}); a label that differs from its value is a WORD
     * (e.g. {@code {0="Off" 1="Fancy"}} or {@code {4="Fast"}}), which keeps the word-preset cycler. */
    private static boolean isNumericStepped(PackOption option) {
        for (String value : option.allowedValues()) {
            String label = option.enumNames().get(value);
            if (label != null && !label.equals(value)) {
                return false;
            }
        }
        return !option.allowedValues().isEmpty();
    }

    /** Hover tooltip composed from the option's GLSL annotation: label, default, range/choices,
     * and whether it live-previews (runtime) or needs Apply (compile -- shader rebuild). */
    private String optionTooltip(PackOption option) {
        StringBuilder text = new StringBuilder(option.label());
        String defaultLabel = option.enumNames().getOrDefault(option.defaultValue(), option.defaultValue());
        if (option.isBoolean()) {
            defaultLabel = PackOptionValues.toBooleanValue(option.defaultValue()) ? "On" : "Off";
        }
        text.append("\nDefault: ").append(defaultLabel);
        if (option.range() != null) {
            text.append("  (").append(option.range().min()).append(" to ").append(option.range().max()).append(')');
        }
        if (option.type() == dev.icehunter.fornax.pack.option.OptionType.RUNTIME) {
            text.append("\nPreviews live; Apply saves it.");
        } else {
            text.append("\nTakes effect on Apply/Done (rebuilds shaders).");
        }
        return text.toString();
    }

    private PackRow profileRow() {
        Map<String, ProfileSpec> profiles = this.session.model().screens().profiles();
        List<String> names = List.copyOf(profiles.keySet());
        String current = matchingProfile(names, profiles, stagedView(), this.session.model().options());
        return new PackRow.Cycle<>("Profile", names, current, name -> name,
                "Apply a preset combination of options.", this::stageProfile, "Custom");
    }

    private Map<String, String> stagedView() {
        Map<String, String> view = new LinkedHashMap<>();
        for (PackOption option : this.session.model().options().values()) {
            view.put(option.name(), this.session.get(option.name()));
        }
        return view;
    }

    /**
     * Selecting a profile is a full reset-then-apply, not an additive overlay on whatever was staged
     * before: {@link PackEditSession#stageDefaults()} first, THEN this profile's own declared overrides
     * on top. This makes profile selection deterministic and idempotent (selecting the same profile
     * twice always produces the identical resulting state), and is the direct fix for a real production
     * bug where a profile whose declared values are a strict subset of another's (Ultra vs. High) could
     * become permanently unselectable: an additive-only stage left a PREVIOUS profile's more specific
     * override (e.g. High's own SSR trace quality) lingering after switching to a profile that doesn't
     * mention that variable at all, causing {@link #matchingProfile} to keep reporting the OLD profile.
     */
    private void stageProfile(String name) {
        ProfileSpec profile = this.session.model().screens().profiles().get(name);
        if (profile == null) {
            return;
        }
        this.session.stageDefaults();
        Map<String, String> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : profile.values().entrySet()) {
            PackOption option = this.session.model().options().get(entry.getKey());
            if (option == null) {
                continue;
            }
            overrides.put(option.name(), PackOptionValues.canonicalize(option, entry.getValue()));
        }
        this.session.stageAll(overrides);
        // Other rows on this screen display values the profile just re-staged -- rebuild them.
        this.rebuildWidgets();
    }

    /**
     * The profile whose declared values all already match {@code currentValues} (0 changed), i.e. the
     * preset actually in effect right now. When multiple profiles zero-diff simultaneously (a genuinely
     * reachable state -- e.g. a profile whose declared values are a strict subset of another's), the
     * MORE SPECIFIC match wins (the one constraining more variables and still holding exactly), since
     * it's the more informative confirmation of what's actually configured; ties at equal specificity
     * fall back to first-in-{@code names}-order (unchanged, still-intentional behavior). Returns {@code
     * null} -- "Custom" -- when no profile matches at all, never a silent fallback to an arbitrary name.
     */
    static @Nullable String matchingProfile(List<String> names, Map<String, ProfileSpec> profiles,
            Map<String, String> currentValues, Map<String, PackOption> options) {
        String best = null;
        int bestSpecificity = -1;
        for (String name : names) {
            ProfileSpec profile = profiles.get(name);
            if (ProfileDiff.countChanged(profile, currentValues, options) != 0) {
                continue;
            }
            int specificity = profile.values().size();
            if (specificity > bestSpecificity) {
                best = name;
                bestSpecificity = specificity;
            }
        }
        return best;
    }

    private void goBack() {
        this.minecraft.gui.setScreen(this.parentLevel);
    }

    private void cancelAll() {
        this.session.discard();
        exitAll();
    }

    private void exitAll() {
        this.minecraft.gui.setScreen(this.exitScreen);
    }

    @Override
    public void onClose() {
        if (this.parentLevel != null) {
            goBack();
        } else {
            this.session.apply();
            exitAll();
        }
    }

    private static Component titleFor(PackModel model, String screenId) {
        if (screenId.equals("main")) {
            return Component.literal(model.meta().name());
        }
        ScreenSpec spec = model.screens().screens().get(screenId);
        return Component.literal(spec != null ? spec.title() : screenId);
    }
}
