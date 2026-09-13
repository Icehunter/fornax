package dev.icehunter.fornax.screen;

import com.google.gson.JsonParser;
import dev.icehunter.fornax.config.RayTracingMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The screen requires a running client; source wiring complements the pure menu-value tests. */
class RayTracingBackendUiTest {
    @Test
    void backendControlIsInTheEngineWorldGroupOnEveryPlatform() throws IOException {
        String source = Files.readString(Path.of("src/main/java/dev/icehunter/fornax/screen/FornaxSettingsScreen.java"));
        String world = source.substring(source.indexOf("private static OptionGroup buildWorldGroup()"),
                source.indexOf("private static Option<SidecarMapResolution>"));
        assertTrue(world.contains(".option(buildRayTracingBackendOption())"));
        assertTrue(source.contains("RayTracingMode.availableBackends(metalSupported)"));
        assertTrue(source.contains("MetalRtSupport.isSupported()"));
        assertFalse(source.contains("buildRayTracingOption()"));
    }

    @Test
    void unsupportedPersistedMetalSelectionHasAValidMenuValueWithoutChangingConfig() {
        assertEquals(RayTracingMode.AUTO, FornaxSettingsScreen.displayedRayTracingBackend(RayTracingMode.FORCE, false));
        assertEquals(RayTracingMode.FORCE, FornaxSettingsScreen.displayedRayTracingBackend(RayTracingMode.FORCE, true));
        assertEquals(RayTracingMode.OFF, FornaxSettingsScreen.displayedRayTracingBackend(RayTracingMode.OFF, false));
    }

    @Test
    void wordingDescribesBackendAndPackSubscriptionWithoutAPerFeatureSwitch() throws IOException {
        var language = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/fornax/lang/en_us.json")))
                .getAsJsonObject();
        assertEquals("Ray Tracing Backend", language.get("gui.fornax.option.ray_tracing").getAsString());
        String tooltip = language.get("gui.fornax.option.ray_tracing.tooltip").getAsString();
        assertTrue(tooltip.contains("Automatic (default)"));
        assertTrue(tooltip.contains("None"));
        assertTrue(tooltip.contains("Metal RT"));
        assertTrue(tooltip.contains("pack subscribes"));
        assertFalse(tooltip.contains("Apple9"));
        assertFalse(tooltip.contains("Force:"));
    }
}
