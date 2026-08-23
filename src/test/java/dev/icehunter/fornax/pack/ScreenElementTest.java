package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScreenElementTest {
    private static final PackOption SSR_QUALITY = new PackOption("SSR_QUALITY", OptionType.COMPILE,
            null, List.of("0", "1", "2"), false, false, "1", "SSR Quality", Map.of());

    private static final PackOption ADVANCED = new PackOption("u_FogAdvanced", OptionType.RUNTIME,
            new dev.icehunter.fornax.pack.option.OptionRange(0.0, 1.0, 1.0), List.of(),
            false, false, "0.0", "Advanced Overrides", Map.of());

    private static final ScreensSpec SCREENS = new ScreensSpec(
            new MainScreenSpec(List.of(), 1),
            Map.of("LIGHTING", new ScreenSpec("Lighting", List.of())),
            Map.of("Ultra", new ProfileSpec(Map.of())),
            List.of());

    @Test
    void profileTokenResolvesToProfileCycler() {
        assertEquals(new ScreenElement.ProfileCycler(),
                ScreenElement.resolve("<profile>", SCREENS, Map.of()));
    }

    @Test
    void emptyTokenResolvesToSpacer() {
        assertEquals(new ScreenElement.Empty(), ScreenElement.resolve("<empty>", SCREENS, Map.of()));
    }

    @Test
    void bracketedTokenResolvesToScreenLink() {
        ScreenElement element = ScreenElement.resolve("[LIGHTING]", SCREENS, Map.of());
        assertEquals(new ScreenElement.ScreenLink("LIGHTING", "Lighting"), element);
    }

    @Test
    void bracketedTokenForUnknownScreenThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> ScreenElement.resolve("[GHOST]", SCREENS, Map.of()));
        assertEquals("screens.toml", e.file());
        assertEquals("[GHOST]", e.key());
    }

    @Test
    void groupTokenResolvesToGroupHeader() {
        assertEquals(new ScreenElement.GroupHeader("Underwater"),
                ScreenElement.resolve("<group:Underwater>", SCREENS, Map.of()));
    }

    @Test
    void groupTokenTitleIsTrimmed() {
        assertEquals(new ScreenElement.GroupHeader("Surface Shimmer"),
                ScreenElement.resolve("<group: Surface Shimmer >", SCREENS, Map.of()));
    }

    @Test
    void groupTokenStartsExpandedByDefault() {
        ScreenElement element = ScreenElement.resolve("<group:Underwater>", SCREENS, Map.of());
        assertFalse(((ScreenElement.GroupHeader) element).collapsed());
    }

    @Test
    void collapsedModifierFoldsTheGroup() {
        assertEquals(new ScreenElement.GroupHeader("Fine Tuning", true),
                ScreenElement.resolve("<group:Fine Tuning|collapsed>", SCREENS, Map.of()));
    }

    @Test
    void collapsedModifierTrimsAroundThePipe() {
        assertEquals(new ScreenElement.GroupHeader("Fine Tuning", true),
                ScreenElement.resolve("<group: Fine Tuning | collapsed >", SCREENS, Map.of()));
    }

    @Test
    void unknownGroupModifierThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> ScreenElement.resolve("<group:Fine Tuning|open>", SCREENS, Map.of()));
        assertEquals("screens.toml", e.file());
        assertTrue(e.reason().contains("unknown group modifier"));
    }

    @Test
    void collapsedModifierWithEmptyTitleThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> ScreenElement.resolve("<group:|collapsed>", SCREENS, Map.of()));
        assertTrue(e.reason().contains("empty title"));
    }

    @Test
    void groupTokenWithEmptyTitleThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> ScreenElement.resolve("<group:>", SCREENS, Map.of()));
        assertEquals("screens.toml", e.file());
        assertEquals("<group:>", e.key());
        assertTrue(e.reason().contains("empty title"));
    }

    @Test
    void groupModifiersCombineInAnyOrder() {
        assertEquals(new ScreenElement.GroupHeader("Fine Tuning", true, "u_FogAdvanced"),
                ScreenElement.resolve("<group:Fine Tuning|requires:u_FogAdvanced|collapsed>",
                        SCREENS, Map.of("u_FogAdvanced", ADVANCED)));
    }

    @Test
    void optionTokenWithRequiresResolvesGated() {
        assertEquals(new ScreenElement.Option(SSR_QUALITY, "u_FogAdvanced"),
                ScreenElement.resolve("SSR_QUALITY|requires:u_FogAdvanced", SCREENS,
                        Map.of("SSR_QUALITY", SSR_QUALITY, "u_FogAdvanced", ADVANCED)));
    }

    @Test
    void requiresUnknownTargetThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> ScreenElement.resolve("SSR_QUALITY|requires:GHOST", SCREENS,
                        Map.of("SSR_QUALITY", SSR_QUALITY)));
        assertTrue(e.reason().contains("requires unknown option"));
    }

    @Test
    void requiresNonTwoStateTargetThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> ScreenElement.resolve("<group:Advanced|requires:SSR_QUALITY>", SCREENS,
                        Map.of("SSR_QUALITY", SSR_QUALITY)));
        assertTrue(e.reason().contains("not a two-state option"));
    }

    @Test
    void plainNameResolvesToOption() {
        ScreenElement element = ScreenElement.resolve("SSR_QUALITY", SCREENS, Map.of("SSR_QUALITY", SSR_QUALITY));
        assertEquals(new ScreenElement.Option(SSR_QUALITY), element);
    }

    @Test
    void unknownOptionNameThrows() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> ScreenElement.resolve("GHOST_OPTION", SCREENS, Map.of()));
        assertEquals("screens.toml", e.file());
        assertEquals("GHOST_OPTION", e.key());
        assertTrue(e.reason().contains("unknown option"));
    }
}
