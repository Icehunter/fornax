package dev.icehunter.fornax.pack;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlFormat;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TomlSupportTest {
    private static Config parse(String s) {
        return TomlFormat.instance().createParser().parse(new StringReader(s));
    }

    @Test
    void requireStringReturnsValue() {
        Config c = parse("name = \"Sample Pack\"\n");
        assertEquals("Sample Pack", TomlSupport.requireString(c, "name", "pack.toml"));
    }

    @Test
    void missingKeyThrowsWithFileAndKey() {
        Config c = parse("other = 1\n");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> TomlSupport.requireString(c, "name", "pack.toml"));
        assertEquals("pack.toml", e.file());
        assertEquals("name", e.key());
        assertTrue(e.reason().toLowerCase().contains("missing"));
    }

    @Test
    void wrongTypeThrows() {
        Config c = parse("format = \"one\"\n");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> TomlSupport.requireInt(c, "format", "pack.toml"));
        assertEquals("format", e.key());
    }

    @Test
    void unknownKeysRejected() {
        Config c = parse("name = \"x\"\nbogus = 3\n");
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> TomlSupport.rejectUnknownKeys(c, Set.of("name"), "pack.toml"));
        assertTrue(e.reason().contains("bogus"));
    }
}
