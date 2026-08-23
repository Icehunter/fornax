package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the shader-id resolution contract between a pack's on-disk layout and what blaze3d
 * actually requests at pipeline-compile time, against a snapshot of a real pack
 * ({@code packs/sample_pack}).
 *
 * <p>The contract (javap-verified against the 26.2 client jar): {@code ShaderType.idConverter()} is
 * {@code FileToIdConverter("shaders", ".vsh"/".fsh")}, so a pipeline referencing
 * {@code fornax_runtime:blocks/terrain} looks up the resource path
 * {@code "shaders/blocks/terrain.vsh"} -- which {@code RuntimeShaderPack} serves straight out of
 * {@code PackDiscovery.loadShaderSources}' key set. A key-prefix drift here is invisible to every
 * other test but renders the world black at runtime (live-caught once: sources were keyed
 * shaders-dir-relative, every terrain pipeline failed to compile).
 */
class PackShaderIdResolutionTest {
    private static final Pattern MOJ_IMPORT = Pattern.compile("#moj_import\\s*<([a-z0-9_]+):([^>]+)>");

    @Test
    void shaderSourceKeysCarryTheShadersPrefix() {
        Map<String, String> sources = PackDiscovery.loadShaderSources(fixtureRoot());
        assertFalse(sources.isEmpty(), "fixture pack has no shader sources");
        for (String key : sources.keySet()) {
            assertTrue(key.startsWith("shaders/"),
                    "source key '" + key + "' is not the resource path blaze3d requests (must start with shaders/)");
        }
    }

    @Test
    void terrainGeometryProgramResolvesForBothStages() {
        Path root = fixtureRoot();
        Map<String, String> sources = PackDiscovery.loadShaderSources(root);
        GraphSpec graph = PackDiscovery.loadFrom(root, 1920, 1080).graph();

        PassSpec geometry = graph.passes().stream()
                .filter(p -> p.type() == PassType.GEOMETRY).findFirst().orElseThrow();
        String program = geometry.program();
        assertNotNull(program, "geometry pass declares no program");

        // The terrain shader-location mixin requests fornax_runtime:blocks/terrain for VERTEX and
        // FRAGMENT -- FileToIdConverter("shaders", ext) maps those to program + ".vsh"/".fsh".
        assertTrue(sources.containsKey(program + ".vsh"),
                "vertex stage unresolvable: no source at " + program + ".vsh");
        assertTrue(sources.containsKey(program + ".fsh"),
                "fragment stage unresolvable: no source at " + program + ".fsh");
    }

    @Test
    void everyDeclaredPassShaderResolves() {
        Path root = fixtureRoot();
        Map<String, String> sources = PackDiscovery.loadShaderSources(root);
        GraphSpec graph = PackDiscovery.loadFrom(root, 1920, 1080).graph();

        for (PassSpec pass : graph.passes()) {
            if (pass.shader() == null) {
                continue;
            }
            // PassSpec.shader() ("shaders/post/x.fsh") becomes runtime id fornax_runtime:post/x,
            // which blaze3d converts straight back to this same resource path.
            assertTrue(sources.containsKey(pass.shader()),
                    "pass '" + pass.name() + "' shader unresolvable: no source at " + pass.shader());
        }
    }

    @Test
    void mojImportsResolveAgainstPackOrEngine() {
        // Every include id any pack shader can request via #moj_import must resolve against the
        // served key set -- through the SAME validator the loader runs (see ShaderImports), so the
        // fixture exercises the exact code path that guards a real pack at load/apply time.
        Map<String, String> sources = PackDiscovery.loadShaderSources(fixtureRoot());
        assertTrue(sources.values().stream().anyMatch(src -> MOJ_IMPORT.matcher(src).find()),
                "fixture pack declares no #moj_import lines -- this test would be vacuous");
        assertDoesNotThrow(() -> ShaderImports.validate(sources));
    }

    @Test
    void samplePackMirrorLoadsAndValidates() {
        assertDoesNotThrow(() -> PackDiscovery.loadFrom(fixtureRoot(), 1920, 1080));
    }

    private static Path fixtureRoot() {
        var url = PackShaderIdResolutionTest.class.getResource("/packs/sample_pack");
        assertNotNull(url, "missing test fixture: packs/sample_pack");
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
