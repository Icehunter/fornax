package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.pack.MetaSpec;
import dev.icehunter.fornax.pack.ScreenElement;
import dev.icehunter.fornax.pack.ScreenSpec;
import dev.icehunter.fornax.pack.ScreensSpec;
import dev.icehunter.fornax.pack.option.OptionRange;
import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import dev.icehunter.fornax.pack.option.PackOptionValues;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders one migrated {@code [screens.X]} page as a YACL {@link ConfigCategory}, each
 * {@link ScreenElement} becoming a native YACL row bound into the shared {@link PackEditSession}.
 * Cyclers, never dropdowns (the dropdown controller renders transparently over neighbouring rows on
 * this MC version -- the same law {@code FornaxSettingsScreen} follows). Runtime rows live-preview via
 * {@code .listener} (one option, ring-safe); the binding setter records quietly ({@code stageQuiet});
 * the page's Save fires the single {@code session.apply()}. The binding GETTER reads {@link
 * PackEditSession#getApplied}, not {@link PackEditSession#get}: YACL's {@code changed()} compares the
 * getter against the listener's own just-staged pending value, so a getter that read staged state
 * would go "synced" the instant the listener ran, permanently disabling Save (see {@link
 * PackEditSession#getApplied}'s doc).
 */
public final class YaclPackRows {
    private YaclPackRows() {}

    public static ConfigCategory category(PackEditSession session, ScreenSpec page, ScreensSpec screens) {
        ConfigCategory.Builder category = ConfigCategory.createBuilder()
                .name(Component.literal(page.title()));
        // Rows accumulate into the current group; a <group:Title> token closes it and opens a named
        // one. Named groups start OPEN -- a section heading should show its rows, not hide them --
        // and a page that wants a busy section folded says so with <group:Title|collapsed>. The
        // leading page-title group is only emitted if any rows precede the first header, so a page
        // that opens with <group:...> doesn't render an empty header row.
        OptionGroup.Builder group = OptionGroup.createBuilder()
                .name(Component.literal(page.title()));
        int rowsInGroup = 0;
        int groupsEmitted = 0;
        // The `requires` gates. A row gated by a two-state option (its own `|requires:` modifier,
        // or its group's) is collected against that governor; after the page is built, each
        // governor's row gets a listener that greys its dependents live, and every dependent
        // starts in the state the session already holds. A governor that is not itself a row on
        // this page still gates -- statically, from the stored value.
        String groupRequires = null;
        Map<String, List<Option<?>>> dependents = new LinkedHashMap<>();
        Map<String, Option<?>> governorRows = new HashMap<>();
        for (String token : page.elements()) {
            ScreenElement element = ScreenElement.resolve(token, screens, session.model().options());
            if (element instanceof ScreenElement.GroupHeader(String title, boolean collapsed,
                                                             String requires)) {
                if (rowsInGroup > 0) {
                    category.group(group.build());
                    groupsEmitted++;
                }
                group = OptionGroup.createBuilder()
                        .name(Component.literal(title))
                        .collapsed(collapsed);
                groupRequires = requires;
                rowsInGroup = 0;
                continue;
            }
            Option<?> row = row(session, element, screens);
            if (row != null) {
                group.option(row);
                rowsInGroup++;
                String gate = element instanceof ScreenElement.Option(PackOption o, String req)
                        && req != null ? req : groupRequires;
                if (element instanceof ScreenElement.Option opt) {
                    // Tracked by two-stateness, not render shape: a governor gates its dependents
                    // live whether it rendered as a tick box or a word-labeled cycler (the
                    // hasWordLabels carve-out in rendersAsToggle below). ScreenElement.requireTwoState
                    // already refused to compile any `requires:` target that failed this same check,
                    // so every name actually looked up in dependents below is guaranteed to be one.
                    if (isTwoState(opt.option())) {
                        governorRows.put(opt.option().name(), row);
                    }
                    // A governor never gates itself, whatever group it sits in.
                    if (gate != null && gate.equals(opt.option().name())) {
                        gate = null;
                    }
                }
                if (gate != null) {
                    dependents.computeIfAbsent(gate, k -> new ArrayList<>()).add(row);
                }
            }
        }
        // The trailing group, plus the degenerate no-rows page: a category must carry at least one
        // group (the pre-group builder always emitted exactly one, rows or not).
        if (rowsInGroup > 0 || groupsEmitted == 0) {
            category.group(group.build());
        }
        for (Map.Entry<String, List<Option<?>>> entry : dependents.entrySet()) {
            boolean on = PackOptionValues.toBooleanValue(session.getApplied(entry.getKey()));
            List<Option<?>> gated = entry.getValue();
            gated.forEach(dep -> dep.setAvailable(on));
            Option<?> governor = governorRows.get(entry.getKey());
            if (governor != null) {
                governor.addListener((opt, value) ->
                        gated.forEach(dep -> dep.setAvailable(twoStateOn(value))));
            }
        }
        return category.build();
    }

    /**
     * Translates a two-state governor's live listener value into on/off -- a {@link Boolean} for a
     * tick box governor, or the staged "0"/"1" {@link String} {@link PackOptionValues#toBooleanValue}
     * already knows how to read for a word-labeled cycler governor (the {@link #hasWordLabels}
     * carve-out in {@link #rendersAsToggle}). Whichever shape rendered the row, {@link #isTwoState}
     * guaranteed there are only ever these two value shapes to translate.
     */
    private static boolean twoStateOn(Object value) {
        if (value instanceof Boolean on) {
            return on;
        }
        return PackOptionValues.toBooleanValue(String.valueOf(value));
    }

    private static Option<?> row(PackEditSession session, ScreenElement element, ScreensSpec screens) {
        if (element instanceof ScreenElement.Option opt) {
            return optionRow(session, opt.option(), screens.sliders());
        }
        if (element instanceof ScreenElement.MetaRef meta) {
            return metaRow(session, meta.metaId(), meta.meta());
        }
        // ScreenLink / ProfileCycler / Empty are not rendered as YACL rows on a migrated page in v1.
        return null;
    }

    /**
     * Whether an option should render as a tick box rather than a cycle button.
     *
     * <p>{@code PackOption.isBoolean()} is true only for the bracket-less {@code #define FOO //[]}
     * form, which is one of three ways a pack can spell an on/off switch. The other two --
     * {@code //[0 1]} and a runtime {@code //[0.0..1.0 step 1.0]} -- are equally two-state and equally
     * obviously toggles to anyone reading the settings screen, but were rendering as cycle buttons
     * that a user has to click through to discover the value. That is the same option presented three
     * different ways depending on a detail of how it happened to be declared.
     *
     * <p>Judged on the values an option can take, not on its declaration syntax: exactly two states,
     * being zero and one, is a toggle whatever produced it.
     *
     * <p>Unless the pack NAMED those states -- see {@link #hasWordLabels}.
     */
    static boolean rendersAsToggle(PackOption option) {
        // A pack that named its two values did not author a toggle; it authored a two-armed CHOICE.
        // Collapsing it to a tick box discards the names and shows "On/Off", which says nothing
        // about what either arm does. Live report on a pack's two-armed lighting model, whose arms
        // each name a distinct model: "what does on/off mean? they seem the same".
        //
        // Checked FIRST, ahead of isBoolean(), because every spelling below can carry enumNames --
        // the names are the authored intent, whatever syntax declared the values.
        //
        // EXCEPT literal Off/On. Those names carry exactly the information a tick box already
        // shows, so an option labeled {0="Off" 1="On"} is a toggle that happened to spell its
        // states out, not a choice -- and it renders as the tick box the user asked every
        // true/false setting to be.
        if (hasWordLabels(option) && !labelsAreJustOffOn(option)) {
            return false;
        }
        return isTwoState(option);
    }

    /**
     * Whether an option has exactly two reachable states, zero and one -- true whatever render shape
     * ends up choosing for it. {@link #rendersAsToggle} narrows this further by render intent (a
     * word-labeled two-state option renders as a cycler, not a tick box); this predicate stays
     * broader because the live dependency-greying listener wired in {@link #category} has to find a
     * governor's row whichever shape it rendered as. {@code ScreenElement.requireTwoState} already
     * refuses to compile a {@code requires:} target that fails this same check, so every governor
     * this method says yes to is one {@link #category} may actually need to gate on live.
     */
    static boolean isTwoState(PackOption option) {
        if (option.isBoolean()) {
            return true;
        }
        List<String> allowed = option.allowedValues();
        if (allowed.size() == 2 && allowed.contains("0") && allowed.contains("1")) {
            return true;
        }
        OptionRange range = option.range();
        return range != null && range.min() == 0.0f && range.max() == 1.0f && range.step() == 1.0f;
    }

    /**
     * Whether the pack gave any of this option's values a WORD label rather than leaving it a bare
     * number -- the signal that the two arms mean different things and the screen must say which.
     *
     * <p>Blank names do not count: an author who wrote an empty label supplied no information, and
     * cycling between "" and "" is strictly worse than a tick box. A label equal to its own value
     * ({@code "1" -> "1"}) does not count either -- {@link #enumLabel} would render it identically
     * with or without the entry, so it carries no intent.
     */
    static boolean hasWordLabels(PackOption option) {
        return option.enumNames().entrySet().stream()
                .anyMatch(e -> !e.getValue().isBlank() && !e.getValue().equals(e.getKey()));
    }

    /** Whether every authored value name is literally "Off" or "On" (any case) -- names that add
     * nothing a tick box does not already say. */
    static boolean labelsAreJustOffOn(PackOption option) {
        return !option.enumNames().isEmpty() && option.enumNames().values().stream()
                .allMatch(v -> v.equalsIgnoreCase("Off") || v.equalsIgnoreCase("On"));
    }

    private static Option<?> optionRow(PackEditSession session, PackOption option, List<String> sliders) {
        String authored = session.model().screens().descriptions().getOrDefault(option.name(), "");
        if (rendersAsToggle(option)) {
            return Option.<Boolean>createBuilder()
                    .name(Component.literal(option.label()))
                    .description(OptionDescription.of(Component.literal(tooltip(authored, option))))
                    .binding(PackOptionValues.toBooleanValue(option.defaultValue()),
                            () -> PackOptionValues.toBooleanValue(session.getApplied(option.name())),
                            v -> session.stageQuiet(option.name(), v ? "1" : "0"))
                    // A tick box, not an On/Off button: the user's stated preference for every
                    // true/false setting ("it's a type of button with a check").
                    .controller(TickBoxControllerBuilder::create)
                    .listener((o, v) -> session.stage(option.name(), v ? "1" : "0"))
                    .build();
        }
        if (PackOptionValues.rendersAsSlider(option, sliders)) {
            OptionRange range = option.range();
            return Option.<Float>createBuilder()
                    .name(Component.literal(option.label()))
                    .description(OptionDescription.of(Component.literal(tooltip(authored, option))))
                    .binding(Float.parseFloat(option.defaultValue()),
                            () -> Float.parseFloat(session.getApplied(option.name())),
                            v -> session.stageQuiet(option.name(), formatFloat(v)))
                    .controller(o -> FloatSliderControllerBuilder.create(o)
                            .range((float) range.min(), (float) range.max())
                            .step((float) range.step())
                            // YACL's default float formatter prints ONE decimal, which silently
                            // misreports any option whose step is finer than 0.1: POM Depth steps
                            // 0.01, so a stored 0.18 rendered as "0.2" and two adjacent steps were
                            // indistinguishable. The value was always correct -- only the label was
                            // wrong -- which is worse than a wrong value, because it makes the
                            // author distrust a slider that is working. The precision a slider can
                            // express is a property of its STEP, so derive it rather than pick a
                            // constant that is wrong for some other option.
                            .formatValue(v -> Component.literal(
                                    String.format("%." + decimalsForStep(range.step()) + "f", v))))
                    .listener((o, v) -> session.stage(option.name(), formatFloat(v)))
                    .build();
        }
        // BARE-NUMBER multi-value option ([0 1 2 ... 12], [4 8 12 16], [1024 2048 4096]) -> a SNAP
        // SLIDER over the values' INDICES, not a cycler. PackSettingsScreen has carried this branch
        // since 2026-07-19, but THIS class is what actually renders, and it never had one -- so
        // every numeric value-list option shipped as a click-through cycler regardless of what the
        // other screen would have done. Live report: "why is it still a clicker for 13 values".
        // Thirteen clicks to cross a range is not a control, and a bare number is meaningless as a
        // click target ("Water Distance Fog 7" -- 7 of what, and how many more are there?).
        //
        // Slides over INDICES rather than values so a non-uniform list ([1024 2048 4096]) behaves
        // the same as a contiguous one. Word-labeled enums fall through to the cycler below, where
        // a word you cycle reads better than a slider -- that is the distinction isBareNumeric draws.
        List<String> allowed = option.allowedValues();
        if (allowed.size() >= 3 && isBareNumeric(option)) {
            int maxIdx = allowed.size() - 1;
            int defIdx = Math.max(0, allowed.indexOf(option.defaultValue()));
            return Option.<Integer>createBuilder()
                    .name(Component.literal(option.label()))
                    .description(OptionDescription.of(Component.literal(tooltip(authored, option))))
                    .binding(defIdx,
                            () -> Math.max(0, allowed.indexOf(session.getApplied(option.name()))),
                            v -> session.stageQuiet(option.name(), allowed.get(clampIdx(v, maxIdx))))
                    .controller(o -> IntegerSliderControllerBuilder.create(o)
                            .range(0, maxIdx)
                            .step(1)
                            // Show the VALUE, never the index -- the index is an implementation
                            // detail of sliding over a possibly non-uniform list.
                            .formatValue(v -> Component.literal(allowed.get(clampIdx(v, maxIdx)))))
                    .listener((o, v) -> session.stage(option.name(), allowed.get(clampIdx(v, maxIdx))))
                    .build();
        }

        // Enum / labeled numeric set -> cycler over the declared values.
        List<String> values = option.allowedValues();
        return Option.<String>createBuilder()
                .name(Component.literal(option.label()))
                .description(OptionDescription.of(Component.literal(tooltip(authored, option))))
                .binding(option.defaultValue(), () -> session.getApplied(option.name()),
                        v -> session.stageQuiet(option.name(), v))
                .controller(o -> CyclingListControllerBuilder.create(o)
                        .values(values)
                        .formatValue(v -> Component.literal(enumLabel(option, v))))
                .listener((o, v) -> session.stage(option.name(), v))
                .build();
    }

    /**
     * Ring law for a meta row -- the same setter/listener split as {@link #optionRow}, and here it
     * IS the law, not a style choice: YACL's {@code finishOrSave} runs ONE synchronous apply-value
     * loop over every changed option before the save callback fires, so the binding SETTER (fired
     * once per changed option, inside that one loop) must never itself rotate the runtime ring --
     * it routes through {@link MetaBinding#selectQuiet}, so N changed meta rows in one Save burst
     * costs zero ring rotations, not N. The LISTENER (fired once per user click, one option at a
     * time) is the only path allowed to live-preview, so it stays on {@link MetaBinding#select} --
     * one option, one rotation, ring-safe. Wiring the setter to {@code select} instead is the exact
     * mechanism that wraps the 3-slot ring and throws {@code IllegalStateException: Cannot wait on a
     * fence for the current submit} once a Save carries 3+ changed meta rows.
     */
    private static Option<?> metaRow(PackEditSession session, String metaId, MetaSpec meta) {
        List<String> selectable = MetaBinding.selectableValues(meta);
        // Default value: MetaBinding.defaultTier -- the tier matching the pack's DECLARED DEFAULTS
        // (never MetaBinding.CUSTOM; see that method's doc for why CUSTOM as a Reset target would
        // restage the exact #2 mismatch bug through the reset button instead of the cycler). This is
        // never meta.values().get(0) directly: an unconditional "first authored tier" silently forced
        // every Reset click to the pack's LOWEST tier whenever tier 0 wasn't also the declared
        // default -- the "Reset turns everything off" bug. defaultTier() still falls back to
        // values().get(0) if a meta's tiers genuinely never cover the declared defaults, but only as
        // a logged pack-authoring-gap fallback, not the normal path.
        String defaultTier = MetaBinding.defaultTier(session, metaId, meta);
        Option<String> row = Option.<String>createBuilder()
                .name(Component.literal(meta.label()))
                .description(OptionDescription.of(Component.literal(metaDescription(session, meta))))
                .binding(defaultTier, () -> MetaBinding.current(session, meta),
                        v -> MetaBinding.selectQuiet(session, meta, v))
                .controller(o -> CyclingListControllerBuilder.create(o)
                        .values(selectable)
                        .formatValue(Component::literal))
                .listener((o, v) -> MetaBinding.select(session, meta, v))
                .build();
        // Build-time-only availability: a dormant row (e.g. LIGHT_REACH while EMITTER_LIGHTS is off)
        // must LOOK dormant, not read as a live control that quietly does nothing. The gating option
        // always lives on a different page than its dependent meta row, so it cannot change while
        // this page is open -- see MetaBinding.dependencyMet's doc for why build-time is sufficient
        // (mirrors FornaxSettingsScreen's aaMethod -> ssaaPreset/taauRatio setAvailable precedent).
        if (!MetaBinding.dependencyMet(session, meta)) {
            row.setAvailable(false);
        }
        return row;
    }

    /**
     * A meta row's description text with the same "Applies live."/"Applies on Save (recompiles)."
     * suffix {@link #tooltip} already appends to every flat option row -- the meta-row equivalent of
     * that apply-class hint (see {@link MetaBinding#recompilesOnSave}'s doc: there is no "restart"
     * class for pack options, only this live-vs-recompile split).
     */
    private static String metaDescription(PackEditSession session, MetaSpec meta) {
        return meta.description() + (MetaBinding.recompilesOnSave(session, meta)
                ? "\nApplies on Save (recompiles)." : "\nApplies live.");
    }

    private static String enumLabel(PackOption option, String value) {
        String label = option.enumNames().get(value);
        return label != null ? label : value;
    }

    private static String tooltip(String authored, PackOption option) {
        StringBuilder sb = new StringBuilder();
        // Pack-authored prose first, then the mechanical facts. The author's explanation of what an
        // option DOES is what a player is looking for; "Default: 0.9" is only useful once they have
        // already decided to touch it.
        if (!authored.isBlank()) {
            sb.append(authored).append("\n\n");
        }
        sb.append("Default: ").append(option.defaultValue());
        if (option.range() != null) {
            sb.append("\nRange: ").append(option.range().min()).append(" – ").append(option.range().max());
        }
        sb.append(option.type() == OptionType.RUNTIME
                ? "\nApplies live." : "\nApplies on Save (recompiles).");
        return sb.toString();
    }

    /**
     * How many decimal places a slider needs to show every value its step can reach.
     *
     * <p>Derived from the step rather than fixed, because this screen renders every pack's options:
     * a step of 8.0 wants none, 0.05 wants two, and any constant is wrong for one of them. Counted
     * by scaling until the step is a whole number rather than by string inspection, so a step
     * arriving as 0.0500001 from a float parse still answers 2. Capped at 4 -- past that the label
     * is wider than the information in it -- and 0.0 (a step-less range) answers 2, which reads
     * every value a float slider realistically produces.
     */
    static int decimalsForStep(double step) {
        double s = Math.abs(step);
        if (s <= 0.0 || !Double.isFinite(s)) {
            return 2;
        }
        for (int d = 0; d <= 4; d++) {
            double scaled = s * Math.pow(10, d);
            if (Math.abs(scaled - Math.rint(scaled)) < 1e-6) {
                return d;
            }
        }
        return 4;
    }

    private static String formatFloat(float v) {
        // Match the slider's own String form to the annotation literal style (no trailing zeros
        // beyond one decimal), so isOptionDirty's numeric compare stays clean.
        if (v == Math.rint(v)) {
            return String.valueOf((double) v);
        }
        return Float.toString(v);
    }

    /** Clamps a slider index into the allowed-values list. */
    private static int clampIdx(int v, int maxIdx) {
        return Math.max(0, Math.min(maxIdx, v));
    }

    /** True when a discrete option's values are bare NUMBERS with no distinct word labels -- these
     * read as a numeric snap-slider. A label counts as bare when it is absent or identical to the
     * raw value; a label that differs is a WORD (0="Off", 4="Fast") and keeps the cycler. Mirrors
     * PackSettingsScreen.isNumericStepped, which had this branch while this class did not. */
    private static boolean isBareNumeric(PackOption option) {
        for (String value : option.allowedValues()) {
            String label = option.enumNames().get(value);
            if (label != null && !label.equals(value)) {
                return false;
            }
        }
        return !option.allowedValues().isEmpty();
    }
}
