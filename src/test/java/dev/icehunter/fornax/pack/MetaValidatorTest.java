package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.option.OptionAnnotation;
import dev.icehunter.fornax.pack.option.PackOption;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetaValidatorTest {
    private static ScreensSpec load(String toml) {
        return PackTomlLoader.loadScreens(new StringReader(toml), "screens.toml");
    }

    private static Map<String, PackOption> options(String... defineLines) {
        Map<String, PackOption> out = new LinkedHashMap<>();
        for (String line : defineLines) {
            PackOption o = OptionAnnotation.parseLine(line).orElseThrow();
            out.put(o.name(), o);
        }
        return out;
    }

    @Test
    void validPassesSilently() {
        ScreensSpec s = load("""
                [screens.quality]
                title = "Quality"
                elements = ["<meta:AO>"]
                [metas.AO]
                values = ["Off", "On"]
                [metas.AO.assign.Off]
                SSAO_TAPS = 4
                [metas.AO.assign.On]
                SSAO_TAPS = 16
                [yacl]
                pages = ["quality"]
                """);
        assertDoesNotThrow(() -> MetaValidator.validate(s,
                options("#define SSAO_TAPS 16 //[4 8 16] compile \"AO Samples\"")));
    }

    @Test
    void metaAssignsUnknownOptionFailsLoad() {
        ScreensSpec s = load("""
                [metas.AO]
                values = ["On"]
                [metas.AO.assign.On]
                NOPE = 1
                """);
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> MetaValidator.validate(s, options("#define SSAO_TAPS 16 //[4 8 16] compile \"AO Samples\"")));
        assertTrue(e.reason().contains("NOPE"));
    }

    @Test
    void pageReferencesUnknownMetaFailsLoad() {
        ScreensSpec s = load("""
                [screens.quality]
                title = "Quality"
                elements = ["<meta:GHOST>"]
                """);
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> MetaValidator.validate(s, options()));
        assertTrue(e.reason().contains("GHOST"));
    }

    @Test
    void yaclPageNamingUnknownScreenFailsLoad() {
        ScreensSpec s = load("""
                [yacl]
                pages = ["ghost_page"]
                """);
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> MetaValidator.validate(s, options()));
        assertTrue(e.reason().contains("ghost_page"));
    }
}
