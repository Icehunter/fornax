package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.option.OptionAnnotation;
import dev.icehunter.fornax.pack.option.PackOption;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetaMatchTest {

    private static Map<String, PackOption> options() {
        Map<String, PackOption> out = new LinkedHashMap<>();
        // Sequential put() — never seed a LinkedHashMap from Map.of (hash-salt randomized order).
        PackOption res = OptionAnnotation.parseLine(
                "#define SHADOW_RESOLUTION 2048 //[1024 2048 4096] compile \"Shadow Resolution\"").orElseThrow();
        PackOption soft = OptionAnnotation.parseLine(
                "#define u_ShadowSoftness 1.0 //[0.0..4.0 step 0.25] runtime \"Shadow Softness\"").orElseThrow();
        out.put(res.name(), res);
        out.put(soft.name(), soft);
        return out;
    }

    private static MetaSpec shadowMeta() {
        Map<String, Object> low = new LinkedHashMap<>();
        low.put("SHADOW_RESOLUTION", 1024L);
        low.put("u_ShadowSoftness", 0.5);
        Map<String, Object> high = new LinkedHashMap<>();
        high.put("SHADOW_RESOLUTION", 4096L);
        high.put("u_ShadowSoftness", 2.0);
        Map<String, Map<String, Object>> assign = new LinkedHashMap<>();
        assign.put("Low", low);
        assign.put("High", high);
        return new MetaSpec("Shadow Detail", "", List.of("Low", "High"), assign);
    }

    @Test
    void currentValuesMatchingATierReturnThatTier() {
        Map<String, String> current = new LinkedHashMap<>();
        current.put("SHADOW_RESOLUTION", "4096");
        current.put("u_ShadowSoftness", "2.0");
        assertEquals("High", MetaMatch.matchingTier(shadowMeta(), current, options()));
    }

    @Test
    void partialMatchIsCustom() {
        Map<String, String> current = new LinkedHashMap<>();
        current.put("SHADOW_RESOLUTION", "4096");
        current.put("u_ShadowSoftness", "0.5"); // High res but Low softness — no tier
        assertNull(MetaMatch.matchingTier(shadowMeta(), current, options()));
    }

    @Test
    void runtimeValueComparesNumerically() {
        Map<String, String> current = new LinkedHashMap<>();
        current.put("SHADOW_RESOLUTION", "1024");
        current.put("u_ShadowSoftness", "0.50"); // "0.50" must equal 0.5
        assertEquals("Low", MetaMatch.matchingTier(shadowMeta(), current, options()));
    }

    @Test
    void stagingPlanReturnsCanonicalWidgetStringsForTier() {
        Map<String, String> plan = MetaMatch.stagingPlan(shadowMeta(), "High", options());
        assertEquals("4096", plan.get("SHADOW_RESOLUTION"));
        assertEquals("2.0", plan.get("u_ShadowSoftness"));
        assertEquals(2, plan.size());
    }

    @Test
    void stagingPlanUnknownTierThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> MetaMatch.stagingPlan(shadowMeta(), "Ludicrous", options()));
    }

    @Test
    void metaKeysAbsentFromCurrentValuesFallBackToOptionDefault() {
        // A current-values map missing a meta key uses the option's own default for the compare,
        // exactly like ProfileDiff.countChanged — so a fresh pack shows the default-matching tier.
        Map<String, String> current = new LinkedHashMap<>(); // empty
        // Neither tier matches defaults (res default 2048, soft default 1.0) -> Custom.
        assertNull(MetaMatch.matchingTier(shadowMeta(), current, options()));
    }

    @Test
    void tierWithMissingOrEmptyAssignNeverMatches() {
        // Adjudicated in T1 review: a tier with no (or an empty) assign table is inert — it can
        // never be "in effect" even when currentValues coincidentally satisfy an empty vacuous match.
        Map<String, Map<String, Object>> assign = new LinkedHashMap<>();
        assign.put("Empty", new LinkedHashMap<>()); // present in values, but assign table is empty
        MetaSpec meta = new MetaSpec("Shadow Detail", "", List.of("Empty", "Low", "High"), assign);
        Map<String, String> current = new LinkedHashMap<>();
        current.put("SHADOW_RESOLUTION", "2048"); // arbitrary — doesn't matter, no tier can match
        current.put("u_ShadowSoftness", "1.0");
        assertNull(MetaMatch.matchingTier(meta, current, options()));
    }

    @Test
    void subsetAndSupersetBothMatchingSupersetWins() {
        // T2 review: unlike a profile, a meta's tiers are not required to share a key set. An "Off"
        // tier that only pins resolution is a legal subset of a "Rich" tier that pins resolution AND
        // softness. When current values satisfy both, the MORE SPECIFIC (larger assign map) tier wins
        // — mirroring PackSettingsScreen.matchingProfile's identical tie-break.
        Map<String, Object> off = new LinkedHashMap<>();
        off.put("SHADOW_RESOLUTION", 4096L); // subset: only one key
        Map<String, Object> rich = new LinkedHashMap<>();
        rich.put("SHADOW_RESOLUTION", 4096L);
        rich.put("u_ShadowSoftness", 2.0); // superset: both keys
        Map<String, Map<String, Object>> assign = new LinkedHashMap<>();
        assign.put("Off", off);
        assign.put("Rich", rich);
        MetaSpec meta = new MetaSpec("Shadow Detail", "", List.of("Off", "Rich"), assign);

        Map<String, String> current = new LinkedHashMap<>();
        current.put("SHADOW_RESOLUTION", "4096");
        current.put("u_ShadowSoftness", "2.0"); // satisfies both Off (subset) and Rich (superset)
        assertEquals("Rich", MetaMatch.matchingTier(meta, current, options()));
    }

    @Test
    void equalSpecificityTwoWayMatchFirstDeclaredWins() {
        // T2 review: when two tiers match with EQUAL specificity (same assign-map size), the tie
        // falls back to first-in-values-order, matching profile semantics (strict '>' never replaces
        // on a tie).
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("SHADOW_RESOLUTION", 2048L);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("SHADOW_RESOLUTION", 2048L);
        Map<String, Map<String, Object>> assign = new LinkedHashMap<>();
        assign.put("A", a);
        assign.put("B", b);
        MetaSpec meta = new MetaSpec("Shadow Detail", "", List.of("A", "B"), assign);

        Map<String, String> current = new LinkedHashMap<>();
        current.put("SHADOW_RESOLUTION", "2048");
        current.put("u_ShadowSoftness", "1.0"); // default — unused by either tier
        assertEquals("A", MetaMatch.matchingTier(meta, current, options()));
    }
}
