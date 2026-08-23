package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.MetaMatch;
import dev.icehunter.fornax.pack.MetaSpec;
import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import dev.icehunter.fornax.pack.option.PackOptionValues;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The String-valued binding shim behind a meta-option's YACL cycler. The cycler's value is a tier
 * name, or the {@link #CUSTOM} sentinel when the last-APPLIED internal values match no tier (a
 * hand-edit on a granular row, applied, flips it here -- see {@link #current}). Selecting CUSTOM is
 * idempotent; selecting a real tier stages that tier's whole assignment through {@link
 * PackEditSession#stageAll} in one batched, ring-safe call.
 */
public final class MetaBinding {
    private MetaBinding() {}

    public static final String CUSTOM = "Custom";

    /**
     * The cycler's value list: the meta's real tiers ONLY -- {@link #CUSTOM} is display-only (see
     * {@link #current}) and must never appear here. YACL's {@code CyclingListController} renders
     * whatever the binding getter returns via {@code formatValue} regardless of list membership (no
     * {@code indexOf}/{@code contains} check on the render path -- verified in YACL 3.9.5's
     * decompiled {@code CyclingListController.formatValue()}), so a getter value of CUSTOM still
     * displays correctly even though it's absent from this list. What this list DOES gate is what a
     * click/scroll/keyboard cycle can land on: {@code CyclingControllerElement.cycleValue} indexes
     * into it (wrapping arithmetic on {@code values.indexOf(pendingValue)}), so leaving CUSTOM out
     * makes it impossible to ever select by cycling -- the fix for the "cycle onto Custom" bug.
     */
    public static List<String> selectableValues(MetaSpec meta) {
        return List.copyOf(meta.values());
    }

    private static final Set<String> DEFAULT_TIER_MISMATCH_WARNED = ConcurrentHashMap.newKeySet();

    /**
     * The tier a fresh install shows, and what YACL's Reset-to-default button ({@code
     * OptionListWidget}'s "&#8635;", {@code StateManager.resetToDefault()}) writes straight into this
     * row's pendingValue -- {@link MetaMatch#matchingTier} evaluated over the pack's DECLARED
     * DEFAULTS (each option's own {@link dev.icehunter.fornax.pack.option.PackOption#defaultValue()}),
     * NOT the session's current applied/staged state. Passing an EMPTY map as {@code currentValues}
     * gets exactly that for free: {@code matchingTier}'s {@code tierMatches} already falls back to
     * {@code option.defaultValue()} for any key absent from {@code currentValues} (see that method's
     * doc), so an empty map makes every comparison run against the declared default.
     *
     * <p>This is the fix for the "Reset turns everything off" bug: the previous binding default was
     * unconditionally {@code meta.values().get(0)} -- the FIRST authored tier, regardless of which
     * tier the pack's declared defaults actually describe. When tier 0 was named "Off"/"Low" (as it
     * usually is, so a stray default never lands on the {@link #CUSTOM} sentinel -- see below), every
     * Reset click forced every meta row to its lowest tier, and Save then persisted that state as
     * fact. Resolving the default THROUGH {@code matchingTier} instead means Reset lands on whatever
     * tier the pack author's own defaults describe, same as a fresh install.
     *
     * <p>After the pack's defaults-alignment pass every meta's tiers are authored so their assign
     * tables partition the option defaults into one real named tier, so {@code matchingTier} should
     * always resolve here -- {@code null} only if a newly-authored meta's tiers never cover the
     * declared defaults (a pack-authoring gap, not a runtime state). In that case, fall back to the
     * first tier -- the same fallback the previous unconditional code used -- and log ONCE per
     * {@code metaId} (YACL fires every visible meta row's binding default resolution once per screen
     * build, so this must not spam on every open) so the misalignment stays diagnosable.
     */
    public static String defaultTier(PackEditSession session, String metaId, MetaSpec meta) {
        String tier = MetaMatch.matchingTier(meta, Map.of(), session.model().options());
        if (tier != null) {
            return tier;
        }
        if (DEFAULT_TIER_MISMATCH_WARNED.add(metaId)) {
            FornaxMod.LOGGER.warn(
                    "[Fornax] meta '{}' has no tier matching the pack's declared option defaults; "
                            + "Reset will fall back to its first tier '{}' instead of a real default -- "
                            + "check the meta's tiers cover the options' defaultValue()s",
                    metaId, meta.values().get(0));
        }
        return meta.values().get(0);
    }

    /**
     * The tier currently APPLIED (last-saved), or {@link #CUSTOM}, read live off the session -- no
     * full-option-table copy. This is the YACL binding GETTER for every meta row: {@code current} is
     * re-fired every frame for every VISIBLE meta row, and YACL's {@code changed()} compares this
     * getter's return value against the listener's own just-staged pending tier -- so this MUST read
     * {@link PackEditSession#getApplied}, never {@link PackEditSession#get} (staged), or a cycler
     * click's own listener would flip {@code changed()} back to false the instant it ran, permanently
     * disabling Save (see {@link PackEditSession#getApplied}'s doc for the full mechanism). {@link
     * MetaMatch#matchingTier}'s {@code tierMatches} only ever calls {@code getOrDefault} on its
     * {@code currentValues} argument, so {@link SessionLookup} adapts {@link PackEditSession#getApplied}
     * directly -- each lookup is the session's own O(1) map read, nothing materialized.
     */
    public static String current(PackEditSession session, MetaSpec meta) {
        String tier = MetaMatch.matchingTier(meta, new SessionLookup(session), session.model().options());
        return tier == null ? CUSTOM : tier;
    }

    /**
     * Stage a tier's whole assignment plan (no-op for CUSTOM), live-previewing it in one batched
     * {@link PackEditSession#stageAll} call. Ring-safe for exactly ONE call (the row's own
     * {@code .listener} -- a single user click). NEVER call this from a YACL binding SETTER: see
     * {@link #selectQuiet}.
     *
     * <p>Also a no-op (zero work, not just zero live-preview writes) when {@code tier} is already
     * the tier in effect AND its plan's values all already match the session's current effective
     * values -- {@link PackEditSession#stageAll} alone already fires zero ring rotations for an
     * unchanged plan, so this composes to zero rotations even without this check, but it's added as
     * defense in depth: YACL's {@code OptionImpl} constructor fires every meta row's {@code
     * .listener} once at screen-build time with {@link #current}'s own return value, so
     * {@code PackManageScreen.create}'s six synchronous meta rows must cost nothing here, not just
     * avoid the GPU write.
     */
    public static void select(PackEditSession session, MetaSpec meta, String tier) {
        if (CUSTOM.equals(tier)) {
            return;
        }
        Map<String, String> plan = MetaMatch.stagingPlan(meta, tier, session.model().options());
        if (tier.equals(current(session, meta)) && session.allStagedMatch(plan)) {
            return;
        }
        session.stageAll(plan);
    }

    /**
     * Save-burst-safe counterpart to {@link #select}: same tier resolution/validation (CUSTOM is a
     * no-op; an unknown tier throws via {@link MetaMatch#stagingPlan}), but stages the plan through
     * {@link PackEditSession#stageAllQuiet} instead of {@link PackEditSession#stageAll} -- no
     * live-preview write, so no runtime-ring rotation. This is the ONLY binding-setter-safe path: YACL's
     * {@code finishOrSave} runs one synchronous apply-value loop over every changed option in a Save,
     * so N changed meta rows means N setter calls in that one loop, and {@link #select} would cost N
     * ring rotations there (3-slot ring; N>=3 wraps it mid-frame). {@link PackEditSession#apply()}
     * still performs the one combined runtime resync at commit time.
     */
    public static void selectQuiet(PackEditSession session, MetaSpec meta, String tier) {
        if (CUSTOM.equals(tier)) {
            return;
        }
        Map<String, String> plan = MetaMatch.stagingPlan(meta, tier, session.model().options());
        session.stageAllQuiet(plan);
    }

    /**
     * A read-only {@link Map} view over {@link PackEditSession#getApplied} used ONLY as {@link
     * MetaMatch#matchingTier}'s {@code currentValues} argument -- the APPLIED view, deliberately, so
     * {@link #current} (this view's only caller) stays the YACL binding getter's source of truth
     * rather than the listener's staged scratch state (see {@link #current}'s doc). {@code
     * tierMatches} calls nothing but {@link #getOrDefault}, so only that method (and the {@link #get}
     * it delegates to) needs to be real; {@link #entrySet()} is intentionally unsupported since
     * nothing in the matching path ever iterates this view.
     */
    /**
     * Whether {@code meta}'s optional {@link MetaSpec#dependsOn} gating option is currently enabled
     * in {@code session} -- {@code true} unconditionally when the meta declares no dependency. Reads
     * {@link PackEditSession#getApplied}, the same APPLIED (never staged) view {@link #current} uses,
     * so this only ever reflects the row's last-committed state, matching the build-time-only
     * {@code setAvailable} wiring in {@code YaclPackRows.metaRow} (the gating option always lives on a
     * DIFFERENT page, so it cannot change while this row's page is open -- returning to the page
     * rebuilds the screen and re-resolves this).
     *
     * <p>Fails CLOSED (unmet) whenever the depended-on option's applied value is absent from the
     * session -- a pack-authoring gap (a {@code dependsOn} naming an option the pack never declares)
     * must never silently read as "enabled" and leave a placebo row looking live.
     */
    public static boolean dependencyMet(PackEditSession session, MetaSpec meta) {
        String dependsOn = meta.dependsOn();
        if (dependsOn == null) {
            return true;
        }
        String appliedValue = session.getApplied(dependsOn);
        return appliedValue != null && PackOptionValues.toBooleanValue(appliedValue);
    }

    /**
     * Whether selecting a tier of this meta touches at least one COMPILE-type option -- i.e. Save
     * runs {@link PackEditSession#apply()}'s {@code compileDirty()} branch ({@code
     * GraphRunner.rebuild} + a renderer reload -- a live shader recompile) rather than the plain
     * {@code GraphRunner.updateRuntimeValues} resync a runtime-only meta gets. There is no "restart"
     * apply class on the pack side (performance-screen plan Task 9 / Spec conflicts §6) -- only this
     * live-recompile-vs-resync split, the same one {@link YaclPackRows}'s flat-option tooltip already
     * surfaces per-row; this is the meta-row equivalent so the six Quality-page tier rows carry the
     * same honest hint (today only {@code LIGHT_REACH} is runtime-only; every other meta assigns at
     * least one compile key).
     *
     * <p>Scans EVERY tier's assign table, not just the currently-selected one: a meta's assigned
     * option SET is stable across its own tiers by pack-authoring convention (only the per-tier
     * VALUES differ), but checking every tier is the assumption-free reading rather than trusting
     * that convention. An assign key absent from the model (a pack-authoring gap) is treated as
     * not-compile -- unlike {@link #dependencyMet}'s fail-closed posture (which gates a row's
     * usability), mislabeling a broken meta's hint text here has no functional consequence: Save-time
     * behavior is driven by each option's own real declared type, never this display-only read.
     */
    public static boolean recompilesOnSave(PackEditSession session, MetaSpec meta) {
        for (Map<String, Object> tierAssign : meta.assign().values()) {
            for (String optionName : tierAssign.keySet()) {
                PackOption option = session.model().options().get(optionName);
                if (option != null && option.type() == OptionType.COMPILE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class SessionLookup extends AbstractMap<String, String> {
        private final PackEditSession session;

        SessionLookup(PackEditSession session) {
            this.session = session;
        }

        @Override
        public String get(Object key) {
            return key instanceof String name ? this.session.getApplied(name) : null;
        }

        @Override
        public String getOrDefault(Object key, String defaultValue) {
            String value = get(key);
            return value != null ? value : defaultValue;
        }

        @Override
        public Set<Entry<String, String>> entrySet() {
            throw new UnsupportedOperationException("SessionLookup only supports get/getOrDefault");
        }
    }
}
