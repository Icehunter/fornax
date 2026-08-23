package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.option.OptionAnnotation;
import dev.icehunter.fornax.pack.option.PackOption;
import dev.icehunter.fornax.pack.option.PackOptionValues;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Proves a {@code WATER_REFLECTIONS} meta whose top "Beyond" tier requires two conditions at once
 * ({@code SSR_WATER_MODE=4} AND the {@code WORLD_REFLECTIONS} master toggle) parses correctly, and
 * that Zero-Custom holds: every profile plus the DEFAULTS row lands on a NAMED tier rather than
 * "Custom", and none of them selects the manual-only top tier.
 *
 * <p>The {@code [profiles.*]} / {@code [metas.WATER_REFLECTIONS]} text below is a fixture, not a
 * contract. It is shaped like a real pack's option table so the loader is exercised against
 * something realistic, but nothing outside this file depends on its values.
 */
class PackScreensBeyondTierTest {

    private static final String SCREENS_TOML = """
            [profiles.Potato]
            values = { SSR_QUALITY = 0, SSR_WATER_MODE = 0 }

            [profiles.Low]
            values = { SSR_QUALITY = 0, SSR_WATER_MODE = 1 }

            [profiles.Medium]
            values = { SSR_QUALITY = 2, SSR_WATER_MODE = 2, u_SsrTraceQuality = 32.0 }

            [profiles.High]
            values = { SSR_QUALITY = 1, SSR_WATER_MODE = 3 }

            [profiles.Ultra]
            values = { SSR_QUALITY = 1, SSR_WATER_MODE = 3 }

            [metas.WATER_REFLECTIONS]
            label = "Water Reflections"
            description = "Reflections on water, glass, ice and other smooth non-metal surfaces."
            values = ["Off", "Highlights", "Traced", "High", "Beyond"]
            [metas.WATER_REFLECTIONS.assign.Off]
            SSR_WATER_MODE = 0
            [metas.WATER_REFLECTIONS.assign.Highlights]
            SSR_WATER_MODE = 1
            [metas.WATER_REFLECTIONS.assign.Traced]
            SSR_WATER_MODE = 2
            SSR_QUALITY = 2
            u_SsrTraceQuality = 32.0
            [metas.WATER_REFLECTIONS.assign.High]
            SSR_WATER_MODE = 3
            SSR_QUALITY = 1
            u_SsrTraceQuality = 48.0
            [metas.WATER_REFLECTIONS.assign.Beyond]
            SSR_WATER_MODE = 4
            WORLD_REFLECTIONS = 1
            SSR_QUALITY = 1
            u_SsrTraceQuality = 64.0
            """;

    private static ScreensSpec load() {
        return PackTomlLoader.loadScreens(new StringReader(SCREENS_TOML), "screens.toml");
    }

    /** The four options {@code WATER_REFLECTIONS}' assign tables reference, with their real declared
     * defaults (byte-identical to the shipped {@code terrain.fsh}/{@code gbuffer_resolve.fsh}/
     * {@code water_composite.fsh}/{@code ssr_trace.fsh} annotations) -- SSR_WATER_MODE defaults to 3
     * (High), SSR_QUALITY to 1 (Fancy), u_SsrTraceQuality to 48.0, WORLD_REFLECTIONS to 0 (Off,
     * manual-only master). */
    private static Map<String, PackOption> waterReflOptions() {
        Map<String, PackOption> out = new LinkedHashMap<>();
        put(out, "#define SSR_WATER_MODE 3 //[0 1 2 3 4] compile \"Water Reflections\" "
                + "{0=\"Off\" 1=\"Highlights\" 2=\"Traced\" 3=\"High\" 4=\"Beyond\"}");
        put(out, "#define SSR_QUALITY 1 //[0 1 2] compile \"Reflections\" {0=\"Off\" 1=\"Fancy\" 2=\"Fast\"}");
        put(out, "#define WORLD_REFLECTIONS 0 //[0 1] compile \"World Reflections\" {0=\"Off\" 1=\"On\"}");
        put(out, "#define u_SsrTraceQuality 48.0 //[16.0..96.0 step 4.0] runtime \"SSR Trace Quality\"");
        return out;
    }

    private static void put(Map<String, PackOption> out, String annotatedDefine) {
        PackOption option = OptionAnnotation.parseLine(annotatedDefine).orElseThrow();
        out.put(option.name(), option);
    }

    @Test
    void waterReflectionsMetaHasBeyondTierWithMasterToggleAssignment() {
        MetaSpec meta = load().metas().get("WATER_REFLECTIONS");
        assertNotNull(meta, "WATER_REFLECTIONS meta missing");
        assertEquals(List.of("Off", "Highlights", "Traced", "High", "Beyond"), meta.values());

        Map<String, Object> beyond = meta.assign().get("Beyond");
        assertNotNull(beyond, "no assign.Beyond table");
        assertEquals(4L, ((Number) beyond.get("SSR_WATER_MODE")).longValue());
        assertEquals(1L, ((Number) beyond.get("WORLD_REFLECTIONS")).longValue());
        assertEquals(1L, ((Number) beyond.get("SSR_QUALITY")).longValue());
        assertEquals(64.0, ((Number) beyond.get("u_SsrTraceQuality")).doubleValue(), 1e-9);

        // Neither High nor Beyond is a subset of the other (SSR_WATER_MODE 3 vs 4 differ), so
        // MetaMatch.matchingTier's tie-break never needs to arbitrate between them.
        Map<String, Object> high = meta.assign().get("High");
        assertNotEquals(high.get("SSR_WATER_MODE"), beyond.get("SSR_WATER_MODE"));
    }

    @Test
    void everyProfileAndDefaultsLandOnANamedTierNeverCustom() {
        ScreensSpec s = load();
        MetaSpec meta = s.metas().get("WATER_REFLECTIONS");
        Map<String, PackOption> options = waterReflOptions();

        Map<String, String> expectedTier = new LinkedHashMap<>();
        expectedTier.put("Potato", "Off");
        expectedTier.put("Low", "Highlights");
        expectedTier.put("Medium", "Traced");
        expectedTier.put("High", "High");
        expectedTier.put("Ultra", "High");

        for (Map.Entry<String, String> e : expectedTier.entrySet()) {
            ProfileSpec profile = s.profiles().get(e.getKey());
            assertNotNull(profile, "missing profile " + e.getKey());
            Map<String, String> current = currentValues(profile, options);
            assertEquals(e.getValue(), MetaMatch.matchingTier(meta, current, options),
                    e.getKey() + " should land on a named Water Reflections tier, not Custom");
        }

        // DEFAULTS row: no profile applied at all -- every option sitting at its own declared
        // default (the state of a fresh pack load with no saved values file).
        assertEquals("High", MetaMatch.matchingTier(meta, Map.of(), options),
                "fresh pack load (no profile) should land on High, not Custom");
    }

    @Test
    void noProfileEverShipsTheManualOnlyBeyondTier() {
        for (Map.Entry<String, ProfileSpec> e : load().profiles().entrySet()) {
            Object mode = e.getValue().values().get("SSR_WATER_MODE");
            if (mode != null) {
                assertNotEquals(4L, ((Number) mode).longValue(),
                        e.getKey() + " must not ship SSR_WATER_MODE=4 -- Beyond is manual-only");
            }
            assertNull(e.getValue().values().get("WORLD_REFLECTIONS"),
                    e.getKey() + " must not ship WORLD_REFLECTIONS -- Beyond is manual-only");
        }
    }

    private static Map<String, String> currentValues(ProfileSpec profile, Map<String, PackOption> options) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : profile.values().entrySet()) {
            PackOption option = options.get(e.getKey());
            if (option == null) continue; // this focused fixture only scans the water-reflection axis
            out.put(e.getKey(), PackOptionValues.canonicalize(option, e.getValue()));
        }
        return out;
    }
}
