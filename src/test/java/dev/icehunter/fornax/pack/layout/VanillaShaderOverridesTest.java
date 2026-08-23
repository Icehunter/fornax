package dev.icehunter.fornax.pack.layout;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class VanillaShaderOverridesTest {
    private static final String LIGHTMAP_BODY = """
            #version 330
            #define LIGHTMAP_CURVES //[] compile "Lightmap Curves"
            out vec4 fragColor;
            void main() { fragColor = vec4(1.0); }
            """;

    @Test
    void overrideFileMapsToVanillaAssetPath() {
        Map<String, String> out = VanillaShaderOverrides.extract(
                Map.of("shaders/vanilla/lightmap.fsh", LIGHTMAP_BODY),
                Map.of("LIGHTMAP_CURVES", 1));
        assertEquals(Map.of("shaders/core/lightmap.fsh", LIGHTMAP_BODY), out);
    }

    @Test
    void gateOptionOffSuppressesTheOverride() {
        Map<String, String> out = VanillaShaderOverrides.extract(
                Map.of("shaders/vanilla/lightmap.fsh", LIGHTMAP_BODY),
                Map.of("LIGHTMAP_CURVES", 0));
        assertTrue(out.isEmpty(), "toggle OFF must serve untouched vanilla (no override entry)");
    }

    @Test
    void absentGateOptionSuppressesTheOverride() {
        // A gate option missing from compileValues entirely (never just resolved to 0) must default
        // to vanilla passthrough too -- the safer failure mode for a caller that forgot to resolve
        // (or carry) the pack's full compile-value map, not an accidental activation.
        Map<String, String> out = VanillaShaderOverrides.extract(
                Map.of("shaders/vanilla/lightmap.fsh", LIGHTMAP_BODY),
                Map.of());
        assertTrue(out.isEmpty(), "absent gate option must default to OFF (vanilla passthrough)");
    }

    @Test
    void missingOverrideFileProducesNoEntries() {
        Map<String, String> out = VanillaShaderOverrides.extract(
                Map.of("shaders/post/gbuffer_resolve.fsh", "// not an override"),
                Map.of());
        assertTrue(out.isEmpty());
    }

    @Test
    void unknownVanillaOverrideNameFails() {
        // Only registered override points are legal -- a typo like sky.fsh must fail loudly,
        // not silently serve nothing.
        assertThrows(dev.icehunter.fornax.pack.FornaxPackError.class, () ->
                VanillaShaderOverrides.extract(
                        Map.of("shaders/vanilla/skybox.fsh", "#version 330"),
                        Map.of()));
    }
}
