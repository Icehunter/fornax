package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.pack.ProfileSpec;
import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link PackSettingsScreen#matchingProfile} is the pure selection logic behind the {@code
 * <profile>} cycler's initial widget value -- exercised directly here since the surrounding
 * screen needs a live Minecraft/GraphRunner to construct.
 */
class PackSettingsScreenTest {
    private static final PackOption SSR_QUALITY = new PackOption("SSR_QUALITY", OptionType.COMPILE,
            null, List.of("0", "1", "2"), false, false, "1", "SSR Quality", Map.of());

    private static final Map<String, PackOption> OPTIONS = Map.of("SSR_QUALITY", SSR_QUALITY);

    @Test
    void selectsProfileThatAlreadyMatchesCurrentValues() {
        List<String> names = List.of("Low", "Ultra");
        Map<String, ProfileSpec> profiles = Map.of(
                "Low", new ProfileSpec(Map.of("SSR_QUALITY", 0)),
                "Ultra", new ProfileSpec(Map.of("SSR_QUALITY", 2)));
        Map<String, String> currentValues = Map.of("SSR_QUALITY", "2");

        assertEquals("Ultra", PackSettingsScreen.matchingProfile(names, profiles, currentValues, OPTIONS));
    }

    @Test
    void returnsNullWhenNoProfileMatchesExactly() {
        List<String> names = List.of("Low", "Ultra");
        Map<String, ProfileSpec> profiles = Map.of(
                "Low", new ProfileSpec(Map.of("SSR_QUALITY", 0)),
                "Ultra", new ProfileSpec(Map.of("SSR_QUALITY", 2)));
        Map<String, String> currentValues = Map.of("SSR_QUALITY", "1"); // matches neither preset

        assertNull(PackSettingsScreen.matchingProfile(names, profiles, currentValues, OPTIONS),
                "no exact match -> Custom (null), not a silent first-profile fallback");
    }

    @Test
    void picksEarlierMatchingNameWhenMultiplePresetsHappenToAgreeAtEqualSpecificity() {
        List<String> names = List.of("A", "B");
        Map<String, ProfileSpec> profiles = Map.of(
                "A", new ProfileSpec(Map.of("SSR_QUALITY", 2)),
                "B", new ProfileSpec(Map.of("SSR_QUALITY", 2)));
        Map<String, String> currentValues = Map.of("SSR_QUALITY", "2");

        assertEquals("A", PackSettingsScreen.matchingProfile(names, profiles, currentValues, OPTIONS));
    }

    @Test
    void prefersTheMoreSpecificProfileWhenALessSpecificProfileWouldAlsoZeroDiff() {
        // Reproduces the real Ultra/High production bug: Ultra's declared values are a strict subset
        // of High's, so when the extra variable High constrains (u_SsrTraceQuality) happens to also
        // sit at High's exact value, BOTH profiles zero-diff simultaneously. The more specific match
        // (High, which constrains more variables and still holds) must win over the less specific one
        // (Ultra, which says nothing about the third variable) -- not whichever is first in list order.
        PackOption ssaoEnabled = new PackOption("SSAO_ENABLED", OptionType.COMPILE,
                null, List.of(), true, true, "1", "Ambient Occlusion", Map.of());
        PackOption traceQuality = new PackOption("u_SsrTraceQuality", OptionType.RUNTIME,
                new dev.icehunter.fornax.pack.option.OptionRange(16.0, 96.0, 4.0), List.of(), false, false,
                "48.0", "SSR Trace Quality", Map.of());
        Map<String, PackOption> options = Map.of(
                "SSR_QUALITY", SSR_QUALITY, "SSAO_ENABLED", ssaoEnabled, "u_SsrTraceQuality", traceQuality);

        List<String> names = List.of("High", "Ultra"); // deliberately High-before-Ultra: the fix must
                                                          // not depend on list order to get this right
        Map<String, ProfileSpec> profiles = Map.of(
                "High", new ProfileSpec(Map.of("SSR_QUALITY", 1, "SSAO_ENABLED", true, "u_SsrTraceQuality", 32.0)),
                "Ultra", new ProfileSpec(Map.of("SSR_QUALITY", 1, "SSAO_ENABLED", true)));
        Map<String, String> currentValues = Map.of(
                "SSR_QUALITY", "1", "SSAO_ENABLED", "1", "u_SsrTraceQuality", "32.0");

        assertEquals("High", PackSettingsScreen.matchingProfile(names, profiles, currentValues, options),
                "High constrains 3 variables and still matches exactly -- more specific than Ultra's 2, must win");
    }

    @Test
    void ultraIsSelectableOnceItsOwnValuesAreTheOnlyZeroDiffMatch() {
        // The converse case: once state is NOT also High-compatible (e.g. after Task 2's
        // reset-then-apply stageProfile fix runs), Ultra's own looser match is correctly the ONLY
        // zero-diff profile and must be selectable.
        //
        // u_SsrTraceQuality must be a registered option here (not just the class-level SSR_QUALITY-only
        // OPTIONS) -- ProfileDiff.countChanged silently skips profile keys with no matching PackOption
        // (drift tolerance), so without registering it, High's u_SsrTraceQuality=32.0 vs current 48.0
        // would never actually be diffed and High would spuriously zero-diff too.
        PackOption traceQuality = new PackOption("u_SsrTraceQuality", OptionType.RUNTIME,
                new dev.icehunter.fornax.pack.option.OptionRange(16.0, 96.0, 4.0), List.of(), false, false,
                "48.0", "SSR Trace Quality", Map.of());
        Map<String, PackOption> options = Map.of("SSR_QUALITY", SSR_QUALITY, "u_SsrTraceQuality", traceQuality);

        List<String> names = List.of("High", "Ultra");
        Map<String, ProfileSpec> profiles = Map.of(
                "High", new ProfileSpec(Map.of("SSR_QUALITY", 1, "u_SsrTraceQuality", 32.0)),
                "Ultra", new ProfileSpec(Map.of("SSR_QUALITY", 1)));
        Map<String, String> currentValues = Map.of("SSR_QUALITY", "1", "u_SsrTraceQuality", "48.0");

        assertEquals("Ultra", PackSettingsScreen.matchingProfile(names, profiles, currentValues, options));
    }
}
