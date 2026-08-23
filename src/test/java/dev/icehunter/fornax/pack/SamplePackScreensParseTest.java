package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the Quality-page fixture ({@code packs/sample_pack/screens.toml}'s
 * {@code [metas.*]}/{@code [yacl]}/{@code [screens.quality]} block, transcribed verbatim from a
 * real shipped pack) against the fornax loader end to end -- the same fixture
 * {@link PackShaderIdResolutionTest}/{@link SamplePackMaterialsTest} exercise for the render graph
 * and blocks.toml, here also carrying the pack's Quality page. {@link
 * ScreensMetaLoaderTest}/{@link MetaValidatorTest} unit-test the parser/validator against small
 * synthetic snippets in isolation; this test proves a real, full-size metas+yacl+quality-page
 * pack loads clean through {@link PackDiscovery#loadFrom}, including the
 * {@code MetaValidator.validate} call that runs as part of that load.
 */
class SamplePackScreensParseTest {

    @Test
    void samplePackLoadsWithTheQualityPageAndItsMetas() {
        PackModel pack = PackDiscovery.loadFrom(fixtureRoot(), 1920, 1080);
        ScreensSpec screens = pack.screens();

        assertEquals(
                // Only what this pack implements. The shadow, cloud and coloured-light metas
                // were removed because the ENGINE acts on those options -- cancelling vanilla's
                // cloud pass, allocating a shadow map, sizing the voxel window -- so advertising
                // them without rendering them made the engine work for nothing, and lost clouds.
                Set.of("WATER_REFLECTIONS", "SURFACE_REFLECTIONS", "AMBIENT_SHADING"),
                screens.metas().keySet());
        assertEquals(List.of("quality", "LIGHTING", "REFLECTIONS"), screens.yaclPages());
        assertTrue(screens.screens().containsKey("quality"), "no [screens.quality] page parsed");
        assertEquals("Quality", screens.screens().get("quality").title());
    }

    @Test
    void qualityPageElementsResolveToTheirMetaRefs() {
        PackModel pack = PackDiscovery.loadFrom(fixtureRoot(), 1920, 1080);
        ScreensSpec screens = pack.screens();
        ScreenSpec quality = screens.screens().get("quality");

        // Every <meta:NAME> token on the page resolves to a MetaRef rather than being taken for an
        // option of that name. A token that resolved to a plain Option would render as a broken row
        // rather than failing, which is why this is asserted rather than left to the loader.
        List<String> metaTokens = List.of("<meta:WATER_REFLECTIONS>",
                "<meta:SURFACE_REFLECTIONS>", "<meta:AMBIENT_SHADING>");
        for (String token : metaTokens) {
            ScreenElement resolved = ScreenElement.resolve(token, screens, pack.options());
            assertInstanceOf(ScreenElement.MetaRef.class, resolved, token + " did not resolve to a MetaRef");
        }
        assertEquals(metaTokens, quality.elements(),
                "the Quality page should hold exactly its meta rows, in declaration order");

        // A plain option name on a page is still an Option, not a MetaRef -- the two share the
        // element list and only the <meta:...> wrapper distinguishes them.
        ScreenElement plain = ScreenElement.resolve("u_SsaoStrength", screens, pack.options());
        assertInstanceOf(ScreenElement.Option.class, plain);
    }

    @Test
    void metaValidatorAcceptsTheRealFixturePack() {
        // PackDiscovery.loadFrom already calls MetaValidator.validate internally (part of the fatal
        // load-time validation chain) -- this loads cleanly with no thrown FornaxPackError.
        assertDoesNotThrow(() -> PackDiscovery.loadFrom(fixtureRoot(), 1920, 1080));
    }

    @Test
    void ambientShadingMetaResolvesItsSpacedTierName() {
        PackModel pack = PackDiscovery.loadFrom(fixtureRoot(), 1920, 1080);
        MetaSpec meta = pack.screens().metas().get("AMBIENT_SHADING");

        assertEquals(List.of("Off", "Fast", "Balanced", "Very Rich"), meta.values());
        assertTrue(meta.assign().containsKey("Very Rich"),
                "night-config's parsed tier key for [metas.AMBIENT_SHADING.assign.\"Very Rich\"] "
                        + "should be plain 'Very Rich' (quotes stripped)");

        Map<String, Object> veryRich = meta.assign().get("Very Rich");
        assertEquals(16, ((Number) veryRich.get("SSAO_TAPS")).intValue());
        assertEquals(Boolean.TRUE, veryRich.get("SSAO_ENABLED"));

        // End to end through MetaMatch, the same lookup MetaBinding.current uses: staging the
        // "Very Rich" tier's own assignment resolves back to "Very Rich", not Custom. A tier name
        // that survives parsing but not matching would show as Custom the moment a player picked it.
        Map<String, String> plan = MetaMatch.stagingPlan(meta, "Very Rich", pack.options());
        assertEquals("Very Rich", MetaMatch.matchingTier(meta, plan, pack.options()));
    }

    @Test
    void everyMetaAssignedOptionExistsInTheMergedOptionTable() {
        PackModel pack = PackDiscovery.loadFrom(fixtureRoot(), 1920, 1080);
        for (Map.Entry<String, MetaSpec> meta : pack.screens().metas().entrySet()) {
            for (Map.Entry<String, Map<String, Object>> tier : meta.getValue().assign().entrySet()) {
                for (String optionName : tier.getValue().keySet()) {
                    assertTrue(pack.options().containsKey(optionName),
                            "metas." + meta.getKey() + ".assign." + tier.getKey()
                                    + " assigns undeclared option '" + optionName + "'");
                }
            }
        }
    }

    private static Path fixtureRoot() {
        var url = SamplePackScreensParseTest.class.getResource("/packs/sample_pack");
        assertNotNull(url, "missing test fixture: packs/sample_pack");
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
