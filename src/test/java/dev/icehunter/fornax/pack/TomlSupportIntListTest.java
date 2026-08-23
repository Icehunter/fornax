package dev.icehunter.fornax.pack;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.InMemoryFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TomlSupportIntListTest {
    private static Config configWith(String key, Object value) {
        Config c = InMemoryFormat.defaultInstance().createConfig();
        c.set(key, value);
        return c;
    }

    @Test
    void missingKeyReturnsEmptyList() {
        Config c = InMemoryFormat.defaultInstance().createConfig();
        assertEquals(List.of(), TomlSupport.getIntList(c, "dispatch", "graph.toml"));
    }

    @Test
    void parsesAListOfIntegers() {
        Config c = configWith("dispatch", List.of(8, 8, 1));
        assertEquals(List.of(8, 8, 1), TomlSupport.getIntList(c, "dispatch", "graph.toml"));
    }

    @Test
    void rejectsNonListValue() {
        Config c = configWith("dispatch", "not-a-list");
        assertThrows(FornaxPackError.class, () -> TomlSupport.getIntList(c, "dispatch", "graph.toml"));
    }

    @Test
    void rejectsListWithNonIntegerElement() {
        Config c = configWith("dispatch", List.of(8, "x", 1));
        assertThrows(FornaxPackError.class, () -> TomlSupport.getIntList(c, "dispatch", "graph.toml"));
    }

    @Test
    void rejectsOutOfRangeLongElement() {
        Config c = configWith("dispatch", List.of(8L, 99999999999L, 1L));
        assertThrows(FornaxPackError.class, () -> TomlSupport.getIntList(c, "dispatch", "graph.toml"));
    }
}
