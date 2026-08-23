package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.option.OptionRange;
import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfileDiffTest {
    private static final Map<String, PackOption> OPTIONS = Map.of(
            "SSAO_RADIUS", new PackOption("SSAO_RADIUS", OptionType.RUNTIME,
                    new OptionRange(0.0, 4.0, 0.1), List.of(), false, false, "0.5", "SSAO Radius", Map.of()),
            "SSAO_ENABLED", new PackOption("SSAO_ENABLED", OptionType.COMPILE,
                    null, List.of(), true, false, "0", "SSAO", Map.of()),
            "SSR_QUALITY", new PackOption("SSR_QUALITY", OptionType.COMPILE,
                    null, List.of("0", "1", "2"), false, false, "1", "SSR Quality",
                    Map.of("0", "Off", "1", "Fast", "2", "Fancy")));

    @Test
    void countsZeroWhenEverythingMatches() {
        ProfileSpec profile = new ProfileSpec(Map.of("SSAO_RADIUS", 1.0, "SSAO_ENABLED", true, "SSR_QUALITY", 1L));
        Map<String, String> current = Map.of("SSAO_RADIUS", "1.0", "SSAO_ENABLED", "1", "SSR_QUALITY", "1");

        assertEquals(0, ProfileDiff.countChanged(profile, current, OPTIONS));
    }

    @Test
    void countsEachDifferingOption() {
        ProfileSpec profile = new ProfileSpec(Map.of("SSAO_RADIUS", 2.0, "SSAO_ENABLED", true, "SSR_QUALITY", 2L));
        Map<String, String> current = Map.of("SSAO_RADIUS", "0.5", "SSAO_ENABLED", "1", "SSR_QUALITY", "0");

        // SSAO_RADIUS differs (2.0 vs 0.5), SSAO_ENABLED matches (true vs "1"), SSR_QUALITY differs (2 vs 0).
        assertEquals(2, ProfileDiff.countChanged(profile, current, OPTIONS));
    }

    @Test
    void missingCurrentValueFallsBackToOptionDefault() {
        ProfileSpec profile = new ProfileSpec(Map.of("SSAO_ENABLED", true));

        assertEquals(1, ProfileDiff.countChanged(profile, Map.of(), OPTIONS));
    }

    @Test
    void unknownOptionInProfileIsIgnored() {
        ProfileSpec profile = new ProfileSpec(Map.of("GHOST_OPTION", 5L));

        assertEquals(0, ProfileDiff.countChanged(profile, Map.of(), OPTIONS));
    }

    @Test
    void numericRangeUsesToleranceNotExactStringMatch() {
        ProfileSpec profile = new ProfileSpec(Map.of("SSAO_RADIUS", 1.5));
        Map<String, String> current = Map.of("SSAO_RADIUS", "1.5000000");

        assertEquals(0, ProfileDiff.countChanged(profile, current, OPTIONS));
    }
}
