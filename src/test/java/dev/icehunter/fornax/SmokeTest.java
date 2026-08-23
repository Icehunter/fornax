package dev.icehunter.fornax;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlFormat;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmokeTest {
    @Test
    void nightConfigParsesToml() {
        Config c = TomlFormat.instance().createParser().parse(new StringReader("[pack]\nname = \"x\"\n"));
        assertEquals("x", c.get("pack.name"));
    }
}
