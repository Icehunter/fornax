package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileValidatorTest {
    private static final Map<String, PackOption> OPTIONS = Map.of(
            "KNOWN", new PackOption("KNOWN", OptionType.COMPILE, null, List.of(), true, false, "0", "Known", Map.of()));

    @Test
    void returnsUnknownProfileKeysQualifiedByProfileName() {
        ProfileSpec profile = new ProfileSpec(Map.of("KNOWN", 1L, "BOGUS", 2L));
        ScreensSpec screens = new ScreensSpec(new MainScreenSpec(List.of(), 1), Map.of(),
                Map.of("Ultra", profile), List.of());

        assertEquals(List.of("Ultra.BOGUS"), ProfileValidator.unknownProfileKeys(screens, OPTIONS));
    }

    @Test
    void returnsEmptyListWhenAllProfileKeysAreKnown() {
        ProfileSpec profile = new ProfileSpec(Map.of("KNOWN", 1L));
        ScreensSpec screens = new ScreensSpec(new MainScreenSpec(List.of(), 1), Map.of(),
                Map.of("Ultra", profile), List.of());

        assertTrue(ProfileValidator.unknownProfileKeys(screens, OPTIONS).isEmpty());
    }
}
