package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.option.PackOption;
import dev.icehunter.fornax.pack.option.PackOptionValues;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure tier-match and staging-plan derivation for one {@link MetaSpec}, reusing the same
 * {@link PackOptionValues} comparison/canonicalization the profile machinery uses (see
 * {@link ProfileDiff} / {@code PackSettingsScreen.matchingProfile}). Unlike a profile, a meta owns
 * ONLY the keys in its assign tables: {@link #matchingTier} compares just those keys, and
 * {@link #stagingPlan} returns just those keys, so meta selection overlays the current state rather
 * than resetting the whole pack. Unit-tested with no screen/session/GPU machinery.
 */
public final class MetaMatch {
    private MetaMatch() {}

    /**
     * The tier whose entire assignment table already matches {@code currentValues} (each key
     * compared in its option's own terms — runtime keys numerically, others as exact strings), or
     * {@code null} ("Custom") when no tier matches. A missing current value falls back to the
     * option's own default, mirroring {@link ProfileDiff#countChanged}. A tier with a missing or
     * empty assign table is inert and can never match (a vacuously-true all-of-nothing would
     * otherwise falsely report as "in effect"). Unlike a profile, a meta's tiers are NOT required
     * to assign the same key set — an "Off" tier assigning fewer keys than a "Rich" tier is legal
     * authoring, so multiple tiers can genuinely zero-diff at once (Off's smaller assign table is a
     * subset of Rich's and both hold). When that happens the tier with the MORE SPECIFIC (larger)
     * assign map wins, mirroring {@code PackSettingsScreen#matchingProfile}'s identical tie-break
     * for the identical reason: the larger match is the more informative confirmation of what's
     * actually configured, and this is the meta-side analogue of a real production bug in the
     * profile machinery where an under-specified subset match reported the wrong preset. Ties at
     * equal specificity fall back to first-in-{@link MetaSpec#values()}-order, unchanged,
     * still-intentional behavior — never a silent arbitrary pick.
     */
    public static @Nullable String matchingTier(MetaSpec meta, Map<String, String> currentValues,
                                                Map<String, PackOption> options) {
        String best = null;
        int bestSpecificity = -1;
        for (String tier : meta.values()) {
            Map<String, Object> assign = meta.assign().get(tier);
            if (assign == null || assign.isEmpty()) {
                continue; // a values entry with no assign table can never be "in effect"
            }
            if (!tierMatches(assign, currentValues, options)) {
                continue;
            }
            int specificity = assign.size();
            if (specificity > bestSpecificity) {
                best = tier;
                bestSpecificity = specificity;
            }
        }
        return best;
    }

    private static boolean tierMatches(Map<String, Object> assign, Map<String, String> currentValues,
                                       Map<String, PackOption> options) {
        for (Map.Entry<String, Object> e : assign.entrySet()) {
            PackOption option = options.get(e.getKey());
            if (option == null) {
                // Fails closed (no match), unlike stagingPlan's skip-and-continue below; both are
                // unreachable post-MetaValidator, so the asymmetry is dead code, not a real behavior split.
                return false; // validated fatal at load, but stay defensive
            }
            String have = currentValues.getOrDefault(option.name(), option.defaultValue());
            if (PackOptionValues.valuesDiffer(option, have, e.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The staging plan for one tier: option-name -> canonical widget-string value, ready for
     * {@code PackEditSession.stageAll}. Only the tier's own keys, in assign-table order.
     *
     * @throws IllegalArgumentException if {@code meta} has no tier named {@code tier}
     */
    public static Map<String, String> stagingPlan(MetaSpec meta, String tier,
                                                  Map<String, PackOption> options) {
        Map<String, Object> assign = meta.assign().get(tier);
        if (assign == null) {
            throw new IllegalArgumentException("meta has no tier '" + tier + "'");
        }
        Map<String, String> plan = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : assign.entrySet()) {
            PackOption option = options.get(e.getKey());
            if (option == null) {
                continue; // validated fatal at load
            }
            plan.put(option.name(), PackOptionValues.canonicalize(option, e.getValue()));
        }
        return plan;
    }
}
